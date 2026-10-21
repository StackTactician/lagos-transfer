package com.lagostransfer.data

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        TransferNotify.ensureChannel(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EkoAza::keep").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, "transfers")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Eko Aza active")
            .setContentText("Keeping transfers alive in background")
            .setOngoing(true)
            .build()
        try { startForeground(1, n) } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        TransferNotify.clearProgress(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
