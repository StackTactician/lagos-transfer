package com.lagostransfer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Account(val username: String, val password: String, val pin: String? = null)
data class Balance(val money: Long, val canSend: Long?)
data class Contact(val id: String, val username: String)

class LagosClient(private val base: String = "https://lagoslife.app") {
    private val jar = mutableMapOf<String, String>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun cookieHeader(): String = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

    suspend fun login(acc: Account): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject(mapOf("username" to acc.username, "password" to acc.password)).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/api/auth/login").post(body).header("Accept","application/json").build()
        val res = client.newCall(req).execute()
        res.headers("set-cookie").forEach { h ->
            val kv = h.split(";")[0].split("=", limit=2)
            if (kv.size==2) jar[kv[0].trim()] = kv[1].trim()
        }
        res.code == 200
    }

    suspend fun getBalance(): Balance = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/save").get()
            .header("Accept","application/json")
            .header("Cookie", cookieHeader()).build()
        val res = client.newCall(req).execute()
        val j = JSONObject(res.body!!.string())
        val game = j.getJSONObject("game")
        val canSend = if (j.has("canSend") && !j.isNull("canSend")) j.getLong("canSend") else null
        Balance(money = game.getLong("money"), canSend = canSend)
    }

    suspend fun getContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/messages").get()
            .header("Accept","application/json")
            .header("Cookie", cookieHeader()).build()
        val res = client.newCall(req).execute()
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
        val req = Request.Builder().url("$base/api/players?q=$q").get()
            .header("Accept","application/json")
            .header("Cookie", cookieHeader()).build()
        val res = client.newCall(req).execute()
        if (res.code != 200) return@withContext null
        val j = JSONObject(res.body!!.string())
        val arr = j.optJSONArray("players") ?: return@withContext null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val u = o.optString("username", "")
            if (u.equals(username.trim(), ignoreCase = true)) {
                return@withContext o.optString("id", null)?.takeIf { it.isNotBlank() }
            }
        }
        // fallback: if only one result, return it
        if (arr.length() == 1) {
            return@withContext arr.getJSONObject(0).optString("id", null)
        }
        null
    }

    suspend fun sendMoney(toId: String, amount: Long, note: String?, pin: String?): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("to", toId); put("amount", amount)
            if (!note.isNullOrBlank()) put("note", note)
            if (!pin.isNullOrBlank()) put("pin", pin)
        }
        val req = Request.Builder().url("$base/api/send").post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept","application/json").header("Cookie", cookieHeader()).build()
        val res = client.newCall(req).execute()
        JSONObject(res.body!!.string()).apply { put("_http", res.code) }
    }
}
