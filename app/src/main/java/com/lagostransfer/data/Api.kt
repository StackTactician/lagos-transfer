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
    private val base: String = "https://lagoslife.app"
) : TransferApi {
    private val jar = mutableMapOf<String, String>()
    private val client = shared
    private val loginLock = Mutex()

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
        const val MAX_SEND = 10_000_000_000L
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

    suspend fun login(): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject(mapOf("username" to username, "password" to password)).toString()
                .toRequestBody(JSON)
            val req = Request.Builder().url("$base/api/auth/login").post(body)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT).build()
            val res = client.newCall(req).execute()
            capture(res)
            res.close()
            res.code == 200
        } catch (_: Exception) {
            false
        }
    }

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
        (0 until arr.length()).mapNotNull { i ->
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
        (0 until arr.length()).mapNotNull { i ->
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
}
