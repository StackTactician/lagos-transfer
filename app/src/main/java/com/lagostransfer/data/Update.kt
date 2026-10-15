package com.lagostransfer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateChecker {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    data class Info(val tag: String, val url: String, val name: String)

    suspend fun latest(): Info? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/StackTactician/lagos-transfer/releases/tags/latest")
            .header("Accept","application/vnd.github+json").build()
        val res = client.newCall(req).execute()
        if (res.code != 200) return@withContext null
        val j = JSONObject(res.body!!.string())
        val tag = j.optString("tag_name", "")
        val assets = j.optJSONArray("assets") ?: return@withContext null
        if (assets.length() == 0) return@withContext null
        val a0 = assets.getJSONObject(0)
        Info(tag, a0.getString("browser_download_url"), a0.getString("name"))
    }

    suspend fun download(ctx: Context, url: String, onProgress: (Int)->Unit = {}): File? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        val res = client.newCall(req).execute()
        if (res.code != 200) return@withContext null
        val file = File(ctx.cacheDir, "update.apk")
        val body = res.body ?: return@withContext null
        val total = body.contentLength()
        body.byteStream().use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(8192)
                var read: Int
                var done = 0L
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    done += read
                    if (total > 0) onProgress(((done * 100 / total).toInt()))
                }
            }
        }
        file
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }
}
