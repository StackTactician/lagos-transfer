package com.lagostransfer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Account(val username: String, val password: String, val pin: String? = null)

data class Balance(val money: Long, val canSend: Long?, val hasGame: Boolean = true)

data class Contact(val id: String, val username: String)

/** One row of the server transfer ledger: POST /api/family {"action":"history"}. */
data class Transfer(
    val id: String,
    val who: String,
    val amount: Long,
    val sent: Boolean,
    val received: Boolean,
    val status: String,
    val at: Long,
    val claimedAt: Long?,
    val note: String?
) {
    /** Money parked in escrow waiting for the other side to claim it. */
    val waiting: Boolean get() = !sent && !received && status == "waiting"

    /** Money that landed (claimed) on this side. */
    val claimed: Boolean get() = received || status == "delivered"
}

class ApiException(val code: Int, message: String, val errorCode: String? = null) : IOException(message)

/** Outcome of one send attempt. ambiguous = server may or may not have applied it. */
data class SendResult(
    val ok: Boolean,
    val id: String? = null,
    val amount: Long = 0L,
    val fee: Long = 0L,
    val http: Int = 0,
    val error: String? = null,
    val errorCode: String? = null,
    val ambiguous: Boolean = false
)

sealed class Lookup {
    data class Found(val id: String) : Lookup()
    object NotFound : Lookup()
    data class Failed(val reason: String) : Lookup()
}

/** Contract the transfer runner needs - keeps the send logic free of Android types. */
interface TransferApi {
    suspend fun resolveUsername(username: String): Lookup
    suspend fun getBalance(): Balance
    suspend fun sendMoney(toId: String, amount: Long, note: String?): SendResult
    suspend fun ledger(): List<Transfer>
    suspend fun claimIncoming(): List<Transfer>

    /** Milliseconds epoch of our last accepted send - the server cooldown runs from it. */
    fun lastSendAtMillis(): Long

    /** Clock the runner reads - overridable so tests can run without real waiting. */
    fun now(): Long = System.currentTimeMillis()
}

class LagosClient(
    private val username: String,
    private val password: String,
    private val pin: String?,
    totpSecret: String? = null,
    private val base: String = "https://lagoslife.app"
) : TransferApi {
    /** Only ever the owner's own secret, set at enrolment or restored from the Keystore store. */
    @Volatile private var totpSecret: String? = totpSecret
    private val jar = mutableMapOf<String, String>()
    private val client = shared
    private val loginLock = Mutex()

    fun adoptTotpSecret(secret: String?) { totpSecret = secret }
    fun hasTotpSecret(): Boolean = !totpSecret.isNullOrBlank()

    /** Last code we handed the server, so we never replay one inside the same 30s window. */
    private var lastTotpCode: String? = null

    /** Set when the server wants a code we cannot make - the user answers this one. */
    @Volatile private var pendingChallenge: String? = null
    @Volatile private var pendingMethod: String? = null

    val needsSecondFactor: Boolean get() = pendingChallenge != null
    fun secondFactorMethod(): String? = pendingMethod

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) EkoAza/1.0"

        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .build()
        }

        private val JSON = "application/json".toMediaType()

        const val MIN_SEND = 100L
        const val MAX_SEND = 100_000_000_000L
        const val MIN_NOTE_LEN = 3

        /** Server refuses a second send on the same account inside 30s. */
        const val SERVER_COOLDOWN_MS = 30_000L

        /** Gap between two sends of the same account (server floor is 30s). */
        const val SEND_GAP_MS = 32_000L

        /** What we wait after a 429 before trying the very same send again. */
        const val RATE_LIMIT_WAIT_MS = 65_000L

        /** Server fee tiers, mirrored so the app can show the real total before sending. */
        fun feeFor(amount: Long): Long = when {
            amount <= 5_000L -> 10L
            amount <= 50_000L -> 25L
            else -> 50L
        }
    }

    var onAuthLost: (() -> Unit)? = null

    override fun lastSendAtMillis(): Long = lastSendAt

    /** Timestamp of our last accepted send - drives the local cooldown. */
    @Volatile var lastSendAt: Long = 0L
        private set

    private fun cookieHeader(): String = synchronized(jar) { jar.entries.joinToString("; ") { "${it.key}=${it.value}" } }

    fun restoreCookies(map: Map<String, String>) { synchronized(jar) { jar.clear(); jar.putAll(map) } }
    fun exportCookies(): Map<String, String> = synchronized(jar) { jar.toMap() }
    fun hasSession(): Boolean = synchronized(jar) { jar.isNotEmpty() }

    /** Keeps rotated session cookies in the jar - the server rewrites them on some calls. */
    private fun capture(res: Response) {
        val cookies = res.headers("set-cookie")
        if (cookies.isEmpty()) return
        synchronized(jar) {
            cookies.forEach { h ->
                val kv = h.split(";")[0].split("=", limit = 2)
                if (kv.size == 2) {
                    val name = kv[0].trim()
                    val value = kv[1].trim()
                    if (value.isEmpty()) jar.remove(name) else jar[name] = value
                }
            }
        }
    }

    /**
     * Logs in, answering an app-method second factor when we hold the owner's
     * own secret. Never guesses, never brute-forces - a wrong code burns the
     * server's attempt budget and can pause codes on the account.
     */
    suspend fun login(): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject(mapOf("username" to username, "password" to password)).toString()
                .toRequestBody(JSON)
            val req = Request.Builder().url("$base/api/auth/login").post(body)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT).build()
            val res = client.newCall(req).execute()
            capture(res)
            val text = res.body?.string() ?: ""
            val code = res.code
            res.close()
            if (code == 200) return@withContext true

            val j = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            // Password is right, but the account wants its second factor.
            if (j.optString("code") != "two_factor") return@withContext false
            pendingChallenge = j.optString("challenge")
            pendingMethod = j.optString("method", "app")
            // No secret on this device: the owner has to give us the code.
            val secret = totpSecret ?: return@withContext false
            if (pendingMethod != "app") return@withContext false

            val c = nextTotpCode() ?: return@withContext false
            val payload = JSONObject()
                .put("action", "login")
                .put("challenge", j.optString("challenge"))
                .put("code", c)
            post2fa(payload).code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Finish a login the owner answered by hand. Accepts a 6-digit authenticator
     * code or one of the account's backup codes.
     */
    suspend fun loginWithCode(code: String): Boolean = withContext(Dispatchers.IO) {
        val ch = pendingChallenge ?: return@withContext false
        val c = code.trim().replace(" ", "")
        if (c.length !in 6..10) return@withContext false
        val r = post2fa(JSONObject().put("action", "login").put("challenge", ch).put("code", c))
        if (r.code in 200..299) {
            pendingChallenge = null
            pendingMethod = null
            true
        } else {
            false
        }
    }

    /** Forget a challenge that went stale, so a retry starts clean. */
    fun clearSecondFactor() {
        pendingChallenge = null
        pendingMethod = null
    }

    /** A code the server has not already seen in this window. */
    private suspend fun nextTotpCode(): String? {
        val secret = totpSecret ?: return null
        if (!Totp.isPlausibleSecret(secret)) return null
        var c = Totp.code(secret)
        if (c == lastTotpCode) {
            kotlinx.coroutines.delay(Totp.millisLeft() + 1_000L)
            c = Totp.code(secret)
        }
        lastTotpCode = c
        return c
    }

    private data class TwoFactorRaw(val code: Int, val body: String)

    private fun post2fa(payload: JSONObject): TwoFactorRaw {
        val req = Request.Builder().url("$base/api/auth/2fa")
            .post(payload.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Cookie", cookieHeader())
            .build()
        val res = client.newCall(req).execute()
        capture(res)
        val text = res.body?.string() ?: ""
        val code = res.code
        res.close()
        return TwoFactorRaw(code, text)
    }

    private fun errorCodeOf(body: String): String = try { JSONObject(body).optString("code") } catch (_: Exception) { "" }
    private fun challengeOf(body: String): String = try { JSONObject(body).optString("challenge") } catch (_: Exception) { "" }
    private fun errorOf(body: String): String = try { JSONObject(body).optString("error") } catch (_: Exception) { "" }

    private data class HttpResult(val code: Int, val body: String)

    /** True when a 401 is about the transaction PIN, not about the session. */
    private fun isPinProblem(body: String): Boolean {
        val code = try { JSONObject(body).optString("code") } catch (_: Exception) { "" }
        return code == "pin" || code == "pin_wrong"
    }

    /**
     * Runs a request and, when the session expired, logs in again and repeats it once.
     * PIN problems are never retried - a wrong PIN burns lockout attempts.
     */
    private suspend fun request(build: () -> Request): HttpResult = withContext(Dispatchers.IO) {
        var res = client.newCall(build()).execute()
        capture(res)
        var code = res.code
        var text = res.body?.string() ?: ""
        res.close()

        // The action itself wants a second factor (usually a large send).
        val kind = if (code in 400..499) errorCodeOf(text) else ""
        if (kind == "two_factor" || kind == "two_factor_step") {
            val c = nextTotpCode()
            if (c != null) {
                val payload = if (kind == "two_factor") {
                    JSONObject().put("action", "login")
                        .put("challenge", challengeOf(text))
                        .put("code", c)
                } else {
                    JSONObject().put("action", "step").put("code", c)
                }
                val solved = post2fa(payload)
                if (solved.code in 200..299) {
                    res = client.newCall(build()).execute()
                    capture(res)
                    code = res.code
                    text = res.body?.string() ?: ""
                    res.close()
                }
            }
        }

        if (code == 401 && !isPinProblem(text)) {
            val back = loginLock.withLock { login() }
            if (back) {
                res = client.newCall(build()).execute()
                capture(res)
                code = res.code
                text = res.body?.string() ?: ""
                res.close()
            } else {
                onAuthLost?.invoke()
            }
        }
        HttpResult(code, text)
    }

    override suspend fun getBalance(): Balance {
        val res = request {
            Request.Builder().url("$base/api/save").get()
                .header("Accept", "application/json")
                .header("Cookie", cookieHeader()).build()
        }
        if (res.code != 200) throw ApiException(res.code, "Balance check failed (${res.code})")
        val j = JSONObject(res.body.ifBlank { "{}" })
        val game = j.optJSONObject("game")
        val money = game?.optLong("money", 0L) ?: 0L
        val canSend = if (j.has("canSend") && !j.isNull("canSend")) j.optLong("canSend") else null
        return Balance(money = money, canSend = canSend, hasGame = game != null)
    }

    override suspend fun resolveUsername(username: String): Lookup {
        val target = username.trim()
        if (target.isEmpty()) return Lookup.NotFound
        val q = URLEncoder.encode(target, "UTF-8")
        val res = request {
            Request.Builder().url("$base/api/players?q=$q").get()
                .header("Accept", "application/json")
                .header("Cookie", cookieHeader()).build()
        }
        if (res.code != 200) return Lookup.Failed("Lookup failed (${res.code})")
        val j = JSONObject(res.body.ifBlank { "{}" })
        val arr = j.optJSONArray("players")
        // An empty list is not proof of anything - the server answers empty under load.
        if (arr == null || arr.length() == 0) return Lookup.Failed("Lookup returned no results")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("username", "").equals(target, ignoreCase = true)) {
                val id = o.optString("id", "")
                if (id.isNotBlank()) return Lookup.Found(id)
            }
        }
        return Lookup.NotFound
    }

    override suspend fun ledger(): List<Transfer> {
        val res = request {
            Request.Builder().url("$base/api/family").post(
                JSONObject().put("action", "history").toString().toRequestBody(JSON))
                .header("Accept", "application/json")
                .header("Cookie", cookieHeader()).build()
        }
        if (res.code != 200) throw ApiException(res.code, "History check failed (${res.code})")
        val j = JSONObject(res.body.ifBlank { "{}" })
        val arr = j.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Transfer(
                id = o.optString("id", ""),
                who = o.optString("who", ""),
                amount = o.optLong("amount", 0L),
                sent = o.optBoolean("sent", false),
                received = o.optBoolean("received", false),
                status = o.optString("status", ""),
                at = o.optLong("at", 0L),
                claimedAt = if (o.has("claimedAt") && !o.isNull("claimedAt")) o.optLong("claimedAt", 0L) else null,
                note = if (o.has("note") && !o.isNull("note")) o.optString("note", null) else null
            )
        }
    }

    override suspend fun claimIncoming(): List<Transfer> {
        val res = request {
            Request.Builder().url("$base/api/family").post(
                JSONObject().put("action", "claim").toString().toRequestBody(JSON))
                .header("Accept", "application/json")
                .header("Cookie", cookieHeader()).build()
        }
        if (res.code != 200) throw ApiException(res.code, "Claim failed (${res.code})")
        val j = JSONObject(res.body.ifBlank { "{}" })
        val arr = j.optJSONArray("received") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Transfer(
                id = o.optString("id", ""),
                who = o.optString("from", ""),
                amount = o.optLong("amount", 0L),
                sent = false,
                received = true,
                status = "delivered",
                at = o.optLong("at", 0L),
                claimedAt = null,
                note = if (o.has("note") && !o.isNull("note")) o.optString("note", null) else null
            )
        }
    }

    override suspend fun sendMoney(toId: String, amount: Long, note: String?): SendResult {
        val payload = JSONObject().apply {
            put("to", toId)
            put("amount", amount)
            if (!note.isNullOrBlank()) put("note", note)
            if (!pin.isNullOrBlank()) put("pin", pin)
        }
        val res = try {
            request {
                Request.Builder().url("$base/api/send").post(payload.toString().toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .header("Cookie", cookieHeader()).build()
            }
        } catch (e: Exception) {
            // Connection died - we cannot tell whether the server applied the send.
            return SendResult(ok = false, http = 0, error = e.message ?: "Network error", ambiguous = true)
        }

        val j = try { JSONObject(res.body.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        if (res.code == 200 && j.optBoolean("ok", false)) {
            lastSendAt = System.currentTimeMillis()
            return SendResult(
                ok = true,
                id = j.optString("id", "").takeIf { it.isNotBlank() },
                amount = j.optLong("amount", amount),
                fee = j.optLong("fee", 0L),
                http = 200
            )
        }
        val errCode = j.optString("code", "").takeIf { it.isNotBlank() }
        val errMsg = j.optString("error", "").ifBlank { "HTTP ${res.code}" }
        return SendResult(
            ok = false,
            http = res.code,
            error = errMsg,
            errorCode = errCode,
            ambiguous = res.code >= 500
        )
    }

    // ---- App-method two-step enrolment (opt-in, per account) ---------------

    /**
     * Step 1: ask the server for a fresh authenticator secret for THIS account.
     * The caller must show the user the secret/URI before anything is confirmed.
     */
    suspend fun startAppTwoStep(password: String): AppTwoStepStart = withContext(Dispatchers.IO) {
        val r = post2fa(JSONObject().put("action", "setup_app").put("password", password))
        if (r.code !in 200..299) throw ApiException(r.code, errorOf(r.body).ifBlank { "Couldn't start setup (${r.code})" }, errorCodeOf(r.body))
        val j = JSONObject(r.body.ifBlank { "{}" })
        val secret = j.optString("secret")
        if (secret.isBlank()) throw ApiException(r.code, "Server sent no secret")
        AppTwoStepStart(secret = secret, uri = j.optString("uri"))
    }

    /**
     * Step 2: confirm with a code from that secret and collect the backup codes.
     * Takes the secret explicitly - the account's own stored secret is not it yet.
     */
    suspend fun confirmAppTwoStep(secret: String, atMillis: Long = System.currentTimeMillis()): AppTwoStepDone = withContext(Dispatchers.IO) {
        val code = Totp.code(secret, atMillis)
        val r = post2fa(JSONObject().put("action", "confirm").put("method", "app").put("code", code))
        if (r.code !in 200..299) throw ApiException(r.code, errorOf(r.body).ifBlank { "That code was not accepted" }, errorCodeOf(r.body))
        val j = JSONObject(r.body.ifBlank { "{}" })
        val arr = j.optJSONArray("backup")
        val codes = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
        AppTwoStepDone(backupCodes = codes.filter { it.isNotBlank() })
    }

    /** What the account currently has switched on, so we never overwrite an existing factor. */
    suspend fun twoStepState(): TwoStepState = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/auth/2fa").get()
            .header("Accept", "application/json")
            .header("Cookie", cookieHeader())
            .header("User-Agent", USER_AGENT).build()
        val res = client.newCall(req).execute()
        capture(res)
        val text = res.body?.string() ?: ""
        val code = res.code
        res.close()
        if (code != 200) return@withContext TwoStepState(unknown = true)
        val j = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
        TwoStepState(
            on = j.optBoolean("on", false),
            method = j.optString("method").ifBlank { null },
            backupLeft = if (j.has("backupLeft")) j.optInt("backupLeft") else null
        )
    }
}

/** A secret the server just minted. Show it before confirming - it is the only copy. */
data class AppTwoStepStart(val secret: String, val uri: String)

data class AppTwoStepDone(val backupCodes: List<String>)

data class TwoStepState(val on: Boolean = false, val method: String? = null, val backupLeft: Int? = null, val unknown: Boolean = false)
