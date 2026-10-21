package com.lagostransfer.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class LicenceState(
    val deviceId: String,
    val trialVolume: Long,
    val licensed: Boolean,
    val tampered: Boolean
) {
    val trialExhausted: Boolean get() = trialVolume >= Config.TRIAL_LIMIT
    val locked: Boolean get() = !licensed && (trialExhausted || tampered)
    val trialLeft: Long get() = (Config.TRIAL_LIMIT - trialVolume).coerceAtLeast(0L)
}

/**
 * Trial and licence state for this app on this phone.
 *
 * The trial counter is cumulative over every account of the app and lives only
 * on the phone. It is written to several places so that clearing the app data
 * or uninstalling the app does not hand the user a fresh trial:
 *  - app preferences
 *  - the app external files folder (survives "clear data")
 *  - a hidden file in the public Downloads collection (survives uninstall)
 *  - a system setting (survives uninstall, needs the modify-settings grant)
 *
 * Every copy is sealed with a key derived from a secret inside the app and the
 * device id, so editing a file by hand is detected.
 */
object License {
    private const val PREFS = "eko_state"
    private const val K_TRIAL = "trial_blob"
    private const val K_DEVICE = "device_id"
    private const val K_LICENCED = "licensed"
    private const val K_RECEIPT_URI = "receipt_uri"
    private const val RECEIPT_NAME = ".eko_receipt"
    private const val SYS_KEY = "eko_trial_receipt"

    // split so it is not one readable string in the binary
    private val secretA = "eko9f3"
    private val secretB = "7c21trial"

    private val lock = Mutex()
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    var state: LicenceState = LicenceState("", 0L, false, false)
        private set

    private fun androidId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "no-android-id"

    private fun key(ctx: Context): SecretKeySpec {
        val raw = MessageDigest.getInstance("SHA-256")
            .digest((secretA + secretB + androidId(ctx)).toByteArray())
        return SecretKeySpec(raw, "AES")
    }

    private fun seal(ctx: Context, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(ctx))
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return iv + body
    }

    private fun open(ctx: Context, data: ByteArray): ByteArray? = try {
        if (data.size <= 12) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(ctx), GCMParameterSpec(128, data, 0, 12))
            cipher.doFinal(data, 12, data.size - 12)
        }
    } catch (_: Exception) {
        null
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(K_DEVICE, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(K_DEVICE, id).apply()
        return id
    }

    // ---- where the counter is kept -------------------------------------------------

    private fun writePrefs(ctx: Context, blob: ByteArray) {
        prefs(ctx).edit().putString(K_TRIAL, android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)).apply()
    }

    private fun readPrefs(ctx: Context): ByteArray? {
        val raw = prefs(ctx).getString(K_TRIAL, null) ?: return null
        return try {
            android.util.Base64.decode(raw, android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun externalFile(ctx: Context) = ctx.getExternalFilesDir(null)?.let { java.io.File(it, "eko_trial.bin") }

    private fun writeExternal(ctx: Context, blob: ByteArray) {
        try {
            val f = externalFile(ctx) ?: return
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(blob)
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    private fun readExternal(ctx: Context): ByteArray? = try {
        externalFile(ctx)?.takeIf { it.exists() }?.readBytes()
    } catch (_: Exception) {
        null
    }

    /** Hidden file in the public Downloads collection - survives uninstall. */
    private fun writeReceipt(ctx: Context, blob: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            // overwrite the receipt we already have, do not fill the folder with copies
            val known = prefs(ctx).getString(K_RECEIPT_URI, null)
            if (known != null) {
                val uri = android.net.Uri.parse(known)
                val wrote = ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(blob) }
                if (wrote != null) return
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, RECEIPT_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(blob) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            prefs(ctx).edit().putString(K_RECEIPT_URI, uri.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun readReceipt(ctx: Context): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val stored = prefs(ctx).getString(K_RECEIPT_URI, null)
        if (stored != null) {
            try {
                val uri = android.net.Uri.parse(stored)
                ctx.contentResolver.openInputStream(uri)?.use { return it.readBytes() }
            } catch (_: Exception) {
            }
        }
        return try {
            val projection = arrayOf(MediaStore.Downloads._ID)
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(RECEIPT_NAME), null
            )?.use { c ->
                if (!c.moveToFirst()) null else {
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** System setting - survives uninstall, needs the modify-settings grant. */
    private fun writeSys(ctx: Context, blob: ByteArray) {
        try {
            Settings.System.putString(
                ctx.contentResolver, SYS_KEY,
                android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)
            )
        } catch (_: Exception) {
        }
    }

    private fun readSys(ctx: Context): ByteArray? {
        val raw = try {
            Settings.System.getString(ctx.contentResolver, SYS_KEY)
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            android.util.Base64.decode(raw, android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    // ---- the counter ---------------------------------------------------------------

    private fun volumeFrom(ctx: Context, blob: ByteArray?): Long? {
        if (blob == null) return null
        val plain = open(ctx, blob) ?: return null
        return try {
            JSONObject(String(plain)).optLong("v", -1L).takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun persist(ctx: Context, volume: Long) {
        val blob = seal(ctx, JSONObject().put("v", volume).put("d", deviceId(ctx)).toString().toByteArray())
        writePrefs(ctx, blob)
        writeExternal(ctx, blob)
        writeReceipt(ctx, blob)
        writeSys(ctx, blob)
    }

    /** Reads every copy and keeps the highest value that still verifies. */
    private fun readState(ctx: Context): LicenceState {
        val id = deviceId(ctx)
        val candidates = listOf(readPrefs(ctx), readExternal(ctx), readReceipt(ctx), readSys(ctx))
        var volume = 0L
        var tampered = false
        var sawReceipt = false
        candidates.forEachIndexed { index, blob ->
            if (blob == null) return@forEachIndexed
            val v = volumeFrom(ctx, blob)
            if (v == null) {
                // the cross uninstall copies must never be broken, that means editing
                if (index >= 2) tampered = true
                return@forEachIndexed
            }
            if (index >= 2) sawReceipt = true
            if (v > volume) volume = v
        }
        val licensed = prefs(ctx).getBoolean(K_LICENCED, false)
        val out = LicenceState(id, volume, licensed, tampered)
        if (!sawReceipt && volume > 0L) persist(ctx, volume) // put the receipt back in place
        return out
    }

    suspend fun load(ctx: Context): LicenceState = withContext(Dispatchers.IO) {
        lock.withLock {
            state = readState(ctx)
            state
        }
    }

    /** Counts a successful transfer into the trial. */
    suspend fun addSent(ctx: Context, amount: Long): LicenceState = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = if (state.deviceId.isEmpty()) readState(ctx) else state
            val next = current.trialVolume + amount.coerceAtLeast(0L)
            persist(ctx, next)
            state = current.copy(trialVolume = next)
            state
        }
    }

    // ---- the licence ---------------------------------------------------------------

    private fun serverUrl(path: String): String = Config.LICENCE_SERVER.trimEnd('/') + path

    /** Asks the server whether this phone already owns a licence. */
    suspend fun refresh(ctx: Context): LicenceState = withContext(Dispatchers.IO) {
        val id = deviceId(ctx)
        val licensed = try {
            val req = Request.Builder()
                .url(serverUrl("/api/licence?device_id=" + java.net.URLEncoder.encode(id, "UTF-8")))
                .get().header("Accept", "application/json").build()
            http.newCall(req).execute().use { res ->
                if (res.code != 200) false else JSONObject(res.body?.string() ?: "{}").optBoolean("licensed", false)
            }
        } catch (_: Exception) {
            null
        }
        if (licensed != null) {
            prefs(ctx).edit().putBoolean(K_LICENCED, licensed).apply()
            lock.withLock {
                state = state.copy(licensed = licensed)
            }
        }
        state
    }

    /** Redeems a code. Returns an empty string on success, otherwise the reason. */
    suspend fun redeem(ctx: Context, code: String): String = withContext(Dispatchers.IO) {
        val id = deviceId(ctx)
        val body = JSONObject().put("code", code.trim()).put("device_id", id).toString()
            .toRequestBody("application/json".toMediaType())
        try {
            val req = Request.Builder().url(serverUrl("/api/redeem")).post(body)
                .header("Accept", "application/json").build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string() ?: "{}"
                if (res.code == 200 && JSONObject(text).optBoolean("ok", false)) {
                    prefs(ctx).edit().putBoolean(K_LICENCED, true).apply()
                    lock.withLock { state = state.copy(licensed = true) }
                    ""
                } else {
                    JSONObject(text).optString("error", "Could not redeem the code (${res.code})")
                }
            }
        } catch (e: Exception) {
            "Could not reach the licence server - ${e.message ?: "no network"}"
        }
    }

    /** True when the app may send. */
    fun allowsSending(): Boolean = !state.locked
}
