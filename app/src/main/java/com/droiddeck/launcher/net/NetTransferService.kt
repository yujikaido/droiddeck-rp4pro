package com.droiddeck.launcher.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.Formatter
import android.util.Log
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R

/* Holds the app up while the Network page copies: a dataSync foreground service with a progress notification,
 * a CPU wake lock and a Wi-Fi lock (a wired adapter needs none, but holding it costs nothing). Without it Android
 * cuts a background app's network within seconds and the copy stalls until the page is opened again. */
class NetTransferService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        runCatching {
            val n = notification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, n)
        }.onFailure { Log.w(TAG, "could not go to the foreground", it) }
        if (!NetTransfers.busy) {
            stopNow()
            return START_NOT_STICKY
        }
        if (wake == null) wake = runCatching {
            getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidDeck:net-transfer")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        if (wifi == null) wifi = runCatching {
            @Suppress("DEPRECATION")
            applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DroidDeck:net-transfer")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun release() {
        wake?.let { runCatching { if (it.isHeld) it.release() } }
        wake = null
        wifi?.let { runCatching { if (it.isHeld) it.release() } }
        wifi = null
    }

    internal fun stopNow() {
        release()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        if (instance === this) instance = null
    }

    companion object {
        private const val TAG = "NetTransferService"
        private const val CHANNEL = "net_transfers"
        private const val NOTIFICATION_ID = 0x4E54
        @Volatile private var instance: NetTransferService? = null
        @Volatile private var lastNotify = 0L

        /** When a copy is added (the Network page is in front, so starting a foreground service is allowed). */
        fun start(context: Context) {
            if (instance != null) return
            runCatching {
                val intent = Intent(context, NetTransferService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { Log.w(TAG, "could not start; copies run only while DroidDeck is in front", it) }
        }

        /** From the copy loop; the notification is redrawn at most once a second. */
        fun progress() {
            val svc = instance ?: return
            val now = System.currentTimeMillis()
            if (now - lastNotify < 1000L) return
            lastNotify = now
            runCatching { svc.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(svc)) }
        }

        fun stop() {
            instance?.stopNow()
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.net_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) })
            val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val job = NetTransfers.jobs.firstOrNull { it.active }
            val b = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_session)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            if (job == null) return b.setContentTitle(context.getString(R.string.net_channel)).setProgress(0, 0, true).build()
            val left = NetTransfers.jobs.count { it.active } - 1
            b.setContentTitle(context.getString(R.string.net_notify_title, job.label) + if (left > 0) " (+$left)" else "")
            b.setContentText(
                if (job.status == NetTransfers.Status.RETRYING) context.getString(R.string.net_status_retrying)
                else "${Formatter.formatShortFileSize(context, job.bytesDone)} / ${Formatter.formatShortFileSize(context, job.bytesTotal)}" +
                    if (job.rate > 0) " · ${Formatter.formatShortFileSize(context, job.rate)}/s" else "",
            )
            if (job.bytesTotal > 0) b.setProgress(1000, (job.fraction * 1000).toInt(), false) else b.setProgress(0, 0, true)
            return b.build()
        }
    }
}
