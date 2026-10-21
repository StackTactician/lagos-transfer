package com.lagostransfer.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap

object TransferNotify {
    private const val CHANNEL_ID = "transfers"
    private const val GROUP = "lagos-transfers"
    private const val SUMMARY_ID = 0

    @Volatile private var channelReady = false

    /** What each account is doing right now - the summary is built from this. */
    private data class Progress(val current: Int, val total: Int, val amount: Long, val status: String)
    private val active = ConcurrentHashMap<String, Progress>()

    // One stable id per account - a hash can collide and would mix two notifications.
    private val ids = ConcurrentHashMap<String, Int>()
    private val nextId = java.util.concurrent.atomic.AtomicInteger(1000)

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

    private fun idFor(username: String): Int =
        ids.getOrPut(username) { nextId.incrementAndGet() * 10 }

    fun clearProgress(ctx: Context) {
        ensureChannel(ctx)
        active.clear()
        try { NotificationManagerCompat.from(ctx).cancel(SUMMARY_ID) } catch (_: SecurityException) {}
    }

    /** Removes only this account's notifications - other accounts keep theirs. */
    fun clearAll(ctx: Context, usernames: List<String>) {
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        usernames.forEach {
            active.remove(it)
            try { nm.cancel(idFor(it)) } catch (_: SecurityException) {}
            try { nm.cancel(idFor(it) + 1) } catch (_: SecurityException) {}
        }
        updateSummary(ctx)
    }

    private fun updateSummary(ctx: Context) {
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        val count = active.size
        val summary = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Eko Aza")
            .setContentText(if (count == 0) "No active transfers" else "$count account(s) sending")
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setOngoing(count > 0)
            .build()
        try {
            if (count == 0) nm.cancel(SUMMARY_ID) else nm.notify(SUMMARY_ID, summary)
        } catch (_: SecurityException) {}
    }

    fun showProgress(ctx: Context, username: String, current: Int, total: Int, amount: Long, status: String) {
        ensureChannel(ctx)
        active[username] = Progress(current, total, amount, status)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("@$username — $current/$total")
            .setContentText("$status · ₦$amount")
            .setProgress(total, current, false)
            .setOngoing(true)
            .setGroup(GROUP)
            .setOnlyAlertOnce(true)
            .build()
        val nm = NotificationManagerCompat.from(ctx)
        try {
            nm.notify(idFor(username), n)
        } catch (_: SecurityException) {}
        updateSummary(ctx)
    }

    fun showDone(ctx: Context, username: String, succeeded: Int, total: Int) {
        ensureChannel(ctx)
        active.remove(username)
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
            nm.notify(idFor(username) + 1, n)
        } catch (_: SecurityException) {}
        updateSummary(ctx)
    }

    fun showError(ctx: Context, username: String, msg: String) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("@$username — error")
            .setContentText(msg.take(80))
            .setGroup(GROUP)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(idFor(username) + 2, n) } catch (_: SecurityException) {}
    }
}
