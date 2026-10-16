package com.lagostransfer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Account(val username: String, val password: String, val pin: String? = null)
data class Balance(val money: Long, val canSend: Long?)
data class Contact(val id: String, val username: String)

class ApiException(val code: Int, message: String) : IOException(message)

class LagosClient(
    private val username: String,
    private val password: String,
    private val pin: String?,
    private val base: String = "https://lagoslife.app"
) {
    private val jar = mutableMapOf<String, String>()
    private val client = shared
    companion object {
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
    }

    var onAuthLost: (() -> Unit)? = null
    private var loggedOut = false

    private fun cookieHeader(): String = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun restoreCookies(map: Map<String, String>) { synchronized(jar) { jar.clear(); jar.putAll(map) } }
    fun exportCookies(): Map<String, String> = synchronized(jar) { jar.toMap() }
    fun hasSession(): Boolean = synchronized(jar) { jar.isNotEmpty() }

    suspend fun login(): Boolean = withContext(Dispatchers.IO) {
        loggedOut = false
        val body = JSONObject(mapOf("username" to username, "password" to password)).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/api/auth/login").post(body)
            .header("Accept","application/json")
            .header("User-Agent","Mozilla/5.0").build()
        try {
            val res = client.newCall(req).execute()
            res.headers("set-cookie").forEach { h ->
                val kv = h.split(";")[0].split("=", limit=2)
                if (kv.size==2) jar[kv[0].trim()] = kv[1].trim()
            }
            res.code == 200
        } catch (_: Exception) { false }
    }

    private fun exec(builder: () -> Request): Response {
        val res = client.newCall(builder()).execute()
        if (res.code == 401) {
            onAuthLost?.invoke()
            throw ApiException(401, "Session expired")
        }
        return res
    }

    suspend fun getBalance(): Balance = withContext(Dispatchers.IO) {
        val res = exec { Request.Builder().url("$base/api/save").get()
            .header("Accept","application/json").header("Cookie", cookieHeader()).build() }
        val j = JSONObject(res.body!!.string())
        val game = j.getJSONObject("game")
        val canSend = if (j.has("canSend") && !j.isNull("canSend")) j.getLong("canSend") else null
        Balance(money = game.getLong("money"), canSend = canSend)
    }

    suspend fun getContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val res = exec { Request.Builder().url("$base/api/messages").get()
            .header("Accept","application/json").header("Cookie", cookieHeader()).build() }
        val j = JSONObject(res.body!!.string())
        val arr = j.optJSONArray("threads") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val id = o.optString("id", null) ?: return@mapNotNull null
            val u = o.optString("username", null) ?: return@mapNotNull null
            Contact(id, u)
        }
    }

    suspend fun resolveUsername(username: String): String? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(username.trim(), "UTF-8")
        val res = exec { Request.Builder().url("$base/api/players?q=$q").get()
            .header("Accept","application/json").header("Cookie", cookieHeader()).build() }
        if (res.code != 200) return@withContext null
        val j = JSONObject(res.body!!.string())
        val arr = j.optJSONArray("players") ?: return@withContext null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("username", "").equals(username.trim(), ignoreCase = true)) {
                return@withContext o.optString("id", null)?.takeIf { it.isNotBlank() }
            }
        }
        null
    }

    suspend fun sendMoney(toId: String, amount: Long, note: String?): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("to", toId); put("amount", amount)
            if (!note.isNullOrBlank()) put("note", note)
            if (!pin.isNullOrBlank()) put("pin", pin)
        }
        val res = exec {
            Request.Builder().url("$base/api/send")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("Accept","application/json").header("Cookie", cookieHeader()).build()
        }
        val text = res.body?.string() ?: "{}"
        val j = JSONObject(text.ifBlank { "{}" })
        j.put("_http", res.code)
        j
    }
}
