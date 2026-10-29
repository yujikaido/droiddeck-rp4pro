package com.droiddeck.launcher.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R

/* Keeps a Send logs upload alive while the player is away from the app. Without it Android 16 cuts
 * the app's network about 5 s after it goes to the background (netpolicy APP_BACKGROUND), and the
 * upload fails with no code; the same happens once the session is stopped, since SessionService is
 * then no longer a foreground service. A short dataSync foreground service from the moment Send is
 * pressed until the upload ends, with a "Sending logs… N s" notification. At the end the notification
 * is detached and LogUpload turns it into the code notification (or removes it when the code shows on
 * the screen it was sent from). The upload itself still runs in LogUpload; this only holds the process in the foreground. */
class LogUploadService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        // startForeground first in every case: a service started with startForegroundService must call it.
        runCatching {
            val n = progress(this, LogUpload.sendingSeconds)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(LogUpload.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(LogUpload.NOTIFICATION_ID, n)
        }.onFailure { Log.w(TAG, "could not go to the foreground", it) }
        if (LogUpload.sendingSince == null) {
            // The upload ended before the service got here.
            stopNow(remove = true)
            return START_NOT_STICKY
        }
        if (wake == null) wake = runCatching {
            getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidDeck:send-logs")
                ?.apply { setReferenceCounted(false); acquire(WAKE_MS) }
        }.getOrNull()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWake()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun releaseWake() {
        wake?.let { runCatching { if (it.isHeld) it.release() } }
        wake = null
    }

    /** [remove] false keeps the notification on screen so LogUpload can replace it with the code. */
    internal fun stopNow(remove: Boolean) {
        releaseWake()
        runCatching { stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH) }
        stopSelf()
        if (instance === this) instance = null
    }

    companion object {
        private const val TAG = "LogUploadService"
        internal const val CHANNEL = "logsend_progress"
        private const val WAKE_MS = 5 * 60_000L
        @Volatile internal var instance: LogUploadService? = null
            private set

        /** From Send (a screen is in front, so starting a foreground service is allowed). */
        fun start(context: Context) {
            val app = context.applicationContext
            runCatching {
                val intent = Intent(app, LogUploadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
            }.onFailure { Log.w(TAG, "could not start; the upload runs without it", it) }
        }

        /** Once a second while sending. */
        fun tick(context: Context, seconds: Int) {
            if (instance == null) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching { nm.notify(LogUpload.NOTIFICATION_ID, progress(context, seconds)) }
        }

        /** When the upload ended. The notification stays until LogUpload replaces or cancels it. If the
         * service hasn't started yet, onStartCommand sees no upload running and stops by itself. */
        fun stop() {
            instance?.stopNow(remove = false)
        }

        internal fun progress(context: Context, seconds: Int): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.logsend_progress_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) })
            val open = PendingIntent.getActivity(context, LogUpload.NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(context.getString(R.string.logsend_title))
                .setContentText(context.getString(R.string.logsend_sending_elapsed, seconds))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }
}
