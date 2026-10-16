package com.lagostransfer.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object TransferNotify {
    private const val CHANNEL_ID = "transfers"
    private const val GROUP = "lagos-transfers"
    private const val SUMMARY_ID = 0

    @Volatile private var channelReady = false
    fun ensureChannel(ctx: Context) {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Transfer progress"
            }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        channelReady = true
    }

    private fun idFor(username: String) = 1000 + (username.hashCode() and 0x7fffffff) % 9000

    fun clearProgress(ctx: Context) {
        ensureChannel(ctx)
        try { NotificationManagerCompat.from(ctx).cancel(SUMMARY_ID) } catch (_: SecurityException) {}
    }

    fun clearAll(ctx: Context, usernames: List<String>) {
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        try { usernames.forEach { nm.cancel(idFor(it)) }; nm.cancel(SUMMARY_ID) } catch (_: SecurityException) {}
    }

    fun showProgress(ctx: Context, username: String, current: Int, total: Int, amount: Long, status: String) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("@$username — $current/$total")
            .setContentText("$status · ₦$amount · next in 32s")
            .setProgress(total, current, false)
            .setOngoing(true)
            .setGroup(GROUP)
            .setOnlyAlertOnce(true)
            .build()
        val summary = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Eko Aza")
            .setContentText("$current/$total active")
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setOngoing(true)
            .build()
        val nm = NotificationManagerCompat.from(ctx)
        try {
            nm.notify(idFor(username), n)
            nm.notify(SUMMARY_ID, summary)
        } catch (_: SecurityException) {}
    }

    fun showDone(ctx: Context, username: String, succeeded: Int, total: Int) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("@$username — done $succeeded/$total")
            .setContentText(if (succeeded == total) "All sent" else "$succeeded succeeded")
            .setAutoCancel(true)
            .setGroup(GROUP)
            .build()
        try {
            val nm = NotificationManagerCompat.from(ctx)
            nm.cancel(idFor(username))
            nm.notify(idFor(username) + 5, n)
        } catch (_: SecurityException) {}
    }

    fun showError(ctx: Context, username: String, msg: String) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("@$username — error")
            .setContentText(msg.take(80))
            .setGroup(GROUP)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(idFor(username) + 1, n) } catch (_: SecurityException) {}
    }
}
