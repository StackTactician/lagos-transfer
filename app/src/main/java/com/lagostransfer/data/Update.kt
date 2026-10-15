package com.lagostransfer.data

import android.app.DownloadManager
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
    data class Info(val tag: String, val url: String, val name: String, val updatedAt: String?)

    suspend fun latest(): Info? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/StackTactician/lagos-transfer/releases/tags/latest")
            .header("Accept","application/vnd.github+json").build()
        val res = client.newCall(req).execute()
        if (res.code != 200) return@withContext null
        val j = JSONObject(res.body!!.string())
        val tag = j.optString("tag_name", "")
        val updatedAt = j.optString("published_at", null) ?: j.optString("updated_at", null)
        val assets = j.optJSONArray("assets") ?: return@withContext null
        if (assets.length() == 0) return@withContext null
        val a0 = assets.getJSONObject(0)
        val assetUpdated = a0.optString("updated_at", null)
        Info(tag, a0.getString("browser_download_url"), a0.getString("name"), assetUpdated ?: updatedAt)
    }

    fun isUpToDate(ctx: Context, remoteUpdatedAt: String?): Boolean {
        if (remoteUpdatedAt == null) return false
        return try {
            val pm = ctx.packageManager
            val pkg = pm.getPackageInfo(ctx.packageName, 0)
            val installTime = pkg.lastUpdateTime
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            val remoteTime = try { sdf.parse(remoteUpdatedAt)?.time ?: 0L } catch (_: Exception) { java.time.Instant.parse(remoteUpdatedAt).toEpochMilli() }
            installTime >= remoteTime - 60000
        } catch (_: Exception) { false }
    }

    fun enqueue(ctx: Context, url: String): Long {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("Eko Aza update")
            setDescription("Downloading latest")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(ctx, null, "update.apk")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        return dm.enqueue(req)
    }

    fun queryProgress(ctx: Context, id: Long): Pair<Int, Int> {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(id)
        dm.query(q).use { c ->
            if (c.moveToFirst()) {
                val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).toInt()
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).toInt()
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                return Pair(if (total > 0) (bytes * 100 / total) else 0, status)
            }
        }
        return Pair(0, -1)
    }

    fun fileForId(ctx: Context, id: Long): File? {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(id)
        dm.query(q).use { c ->
            if (c.moveToFirst()) {
                val uriStr = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                return if (uriStr != null) File(Uri.parse(uriStr).path ?: "") else File(ctx.getExternalFilesDir(null), "update.apk")
            }
        }
        return null
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }

    // legacy direct download kept for fallback
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
}
