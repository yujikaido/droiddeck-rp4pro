package com.droiddeck.launcher.session

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.DeviceSupport
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/* "Send logs to MaliBridge": the same scrubbed zip Share logs makes, uploaded to the MaliBridge log
 * service (a Cloudflare Worker; the zip is kept 90 days and the developer gets a note in a private
 * channel). The player gets a short code (ML-7F3K) to paste into a GitHub issue or on Discord
 * instead of attaching the file. Asked once before the first upload; a note is optional.
 *
 * The upload and its result live in the process, not in the screen that started it: an upload can
 * take most of a minute on a slow link, and the player may stop the session, rotate or switch apps
 * meanwhile. The code is saved before anything is shown; the screen in front (attach/detach from
 * onResume/onPause) shows it. The notification has it too whenever the upload outlived the screen it
 * was sent from (no screen open, or the session was stopped and the main screen is in front), or when
 * the screen goes while it still shows the code (Stop pressed just as the upload ended), until the
 * player closes or copies it; a code the player hasn't closed yet comes back on the next screen. The last code also stays under Send logs.
 * LogUploadService holds the process in the foreground meanwhile: without it Android 16 cuts the app's
 * network ~5 s after it leaves the screen, and the upload failed with no code. */
object LogUpload {
    private const val ENDPOINT = "https://malibridge-logs.stratintos.workers.dev/v1/logs"
    private const val NOTE_MAX = 500
    private const val CHANNEL = "logsend"
    internal const val NOTIFICATION_ID = 0x4C4F47
    internal const val EXTRA_CODE = "code"
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(context: Context) = context.getSharedPreferences("log_upload", Context.MODE_PRIVATE)

    /** A random id made once, so the service can limit uploads per phone. Nothing else is in it. */
    private fun installId(context: Context): String {
        val p = prefs(context)
        return p.getString("install", null) ?: UUID.randomUUID().toString().also { p.edit().putString("install", it).apply() }
    }

    /** The last code the service gave this phone, kept until the next upload. [seen] turns true once
     * the player closed or copied it from the code dialog. */
    internal object LastCode {
        private const val CODE = "last_code"
        private const val AT = "last_code_at"
        private const val SEEN = "last_code_seen"

        fun save(context: Context, code: String, at: Long = System.currentTimeMillis()) {
            // commit, not apply: the process may be gone soon after (the player stopped the session).
            prefs(context).edit().putString(CODE, code).putLong(AT, at).putBoolean(SEEN, false).commit()
        }

        fun read(context: Context): String? = prefs(context).getString(CODE, null)
        fun at(context: Context): Long = prefs(context).getLong(AT, 0L)
        fun unseen(context: Context): Boolean = read(context) != null && !prefs(context).getBoolean(SEEN, true)
        fun markSeen(context: Context) { prefs(context).edit().putBoolean(SEEN, true).apply() }
    }

    /** When the current upload started (elapsedRealtime), or null when none is running. */
    var sendingSince by mutableStateOf<Long?>(null)
        private set

    /** Whole seconds since [sendingSince], ticking once a second while an upload runs. */
    var sendingSeconds by mutableStateOf(0)
        private set

    private var codeState by mutableStateOf<String?>(null)
    private var codeLoaded = false

    /** The last code, for the Send logs rows (Compose reads it as state). */
    fun lastCode(context: Context): String? {
        if (!codeLoaded) { codeLoaded = true; codeState = LastCode.read(context) }
        return codeState
    }

    private var host: WeakReference<Activity>? = null
    /** The screen the running upload was sent from. An upload that outlives it (the session was
     * stopped, the player left or rotated) also puts its result in the notification. */
    private var origin: WeakReference<Activity>? = null
    private var appContext: Context? = null
    private var progressDialog: AlertDialog? = null
    private var codeDialog: AlertDialog? = null
    private var progressHidden = false

    private val tick = object : Runnable {
        override fun run() {
            val since = sendingSince ?: return
            sendingSeconds = ((SystemClock.elapsedRealtime() - since) / 1000).toInt()
            progressDialog?.let { it.setMessage(progressText(it.context)) }
            appContext?.let { LogUploadService.tick(it, sendingSeconds) }
            main.postDelayed(this, 1000)
        }
    }

    private fun liveHost(): Activity? = host?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** From onResume: this screen shows the upload's progress or a code the player hasn't closed yet. */
    fun attach(activity: Activity) {
        host = WeakReference(activity)
        if (sendingSince != null) { if (!progressHidden) showProgress(activity) }
        else if (LastCode.unseen(activity)) LastCode.read(activity)?.let { showCode(activity, it) }
    }

    /** From onPause: the dialogs go with the screen; the upload and the code don't. A code still open
     * on the screen as it goes (Stop or Home pressed just as the upload ended) also goes to the
     * notification, as if the upload had outlived the screen. A rotation brings the dialog back
     * on the new screen instead. */
    fun detach(activity: Activity) {
        if (host?.get() !== activity) return
        host = null
        progressDialog?.let { runCatching { it.dismiss() } }
        progressDialog = null
        val openCode = codeDialog?.takeIf { it.isShowing }?.let { LastCode.read(activity) }
        codeDialog?.let { runCatching { it.dismiss() } }
        codeDialog = null
        if (openCode != null && !activity.isChangingConfigurations && LastCode.unseen(activity))
            notifyCode(activity.applicationContext, openCode)
    }

    /** From a button: asks (once), takes a note, packages [folder]'s logs and sends them. */
    fun start(activity: Activity, folder: () -> File?) {
        host = WeakReference(activity)
        if (sendingSince != null) { progressHidden = false; showProgress(activity); return }
        if (SessionLogShare.progress != null) return
        if (prefs(activity).getBoolean("consented", false)) askNote(activity, folder)
        else AlertDialog.Builder(activity)
            .setTitle(R.string.logsend_consent_title)
            .setMessage(R.string.logsend_consent_body)
            .setPositiveButton(R.string.logsend_consent_ok) { _, _ ->
                prefs(activity).edit().putBoolean("consented", true).apply()
                askNote(activity, folder)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askNote(activity: Activity, folder: () -> File?) {
        val note = EditText(activity).apply {
            hint = activity.getString(R.string.logsend_note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(note) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.logsend_title)
            .setView(box)
            .setPositiveButton(R.string.logsend_send) { _, _ -> send(activity, folder, note.text.toString().trim().take(NOTE_MAX)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun send(activity: Activity, folder: () -> File?, note: String) {
        val app = activity.applicationContext
        // prepare() does nothing while Share logs is packing, and then the upload would never end.
        if (SessionLogShare.progress != null || !begin()) return
        appContext = app
        // Keeps the process (and its network) alive if the player leaves the app or stops the session.
        LogUploadService.start(app)
        liveHost()?.let { showProgress(it) }
        SessionLogShare.prepare(app, folder) { zip ->
            if (zip == null) {
                finished(app, Result.failure(Archive()))
                return@prepare
            }
            Thread({
                val result = runCatching { upload(app, zip, note) }
                    .onFailure { Log.w("LogUpload", "upload failed", it) }
                // Saved here, before the main thread hears of it: the code survives the screen and the process.
                result.onSuccess { LastCode.save(app, it) }
                main.post { finished(app, result) }
            }, "send-logs").start()
        }
    }

    /** Marks an upload as running. False when one already is. */
    internal fun begin(now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (sendingSince != null) return false
        sendingSince = now
        sendingSeconds = 0
        progressHidden = false
        origin = host?.get()?.let { WeakReference(it) }
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000)
        return true
    }

    /** On the main thread when the upload ends; [result] is the code or why it failed. */
    internal fun finished(context: Context, result: Result<String>) {
        val app = context.applicationContext
        sendingSince = null
        main.removeCallbacks(tick)
        // The service leaves its "Sending logs" notification behind; it becomes the result's, or goes.
        LogUploadService.stop()
        progressDialog?.let { runCatching { it.dismiss() } }
        progressDialog = null
        val screen = liveHost()
        // Still on the screen it was sent from: the dialog (or toast) there is enough. Otherwise
        // (after a session stop the main screen is in front) the notification gets it too.
        val away = screen == null || screen !== origin?.get()
        origin = null
        result.fold({ code ->
            if (LastCode.read(app) != code) LastCode.save(app, code)
            codeLoaded = true
            codeState = code
            if (!(away && notifyCode(app, code))) cancelNotification(app)
            if (screen != null) showCode(screen, code)
        }, { e ->
            if (!(away && notifyFailed(app, e))) cancelNotification(app)
            failed(screen ?: app, e)
        })
    }

    private fun cancelNotification(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    private class Refused(val status: Int) : Exception("HTTP $status")
    private class Archive : Exception("could not package the logs")

    /** The code the service gave this upload. */
    private fun upload(context: Context, zip: File, note: String): String {
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.setFixedLengthStreamingMode(zip.length())
            c.setRequestProperty("Content-Type", "application/zip")
            c.setRequestProperty("X-MB-Install", installId(context))
            c.setRequestProperty("X-MB-Device", enc("${Build.MANUFACTURER} ${Build.MODEL}"))
            c.setRequestProperty("X-MB-Gpu", enc(DeviceSupport.gpuName(context)))
            c.setRequestProperty("X-MB-Version", enc(BuildConfig.MALIBRIDGE_VERSION.ifEmpty { BuildConfig.VERSION_NAME }))
            if (note.isNotEmpty()) c.setRequestProperty("X-MB-Note", enc(note))
            c.outputStream.use { o -> zip.inputStream().use { it.copyTo(o) } }
            val status = c.responseCode
            if (status != 201) throw Refused(status)
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return codeIn(body) ?: throw Refused(status)
        } finally {
            c.disconnect()
        }
    }

    internal fun codeIn(body: String): String? = CODE.find(body)?.value

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun progressText(context: Context) =
        context.getString(R.string.logsend_sending_elapsed, sendingSeconds) + "\n\n" + context.getString(R.string.logsend_sending_note)

    private fun showProgress(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        progressDialog?.let { if (it.isShowing && it.ownerActivity === activity) return; runCatching { it.dismiss() } }
        progressDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.logsend_title)
            .setMessage(progressText(activity))
            .setCancelable(false)
            .setPositiveButton(R.string.logsend_hide) { _, _ -> progressHidden = true; progressDialog = null }
            .create().apply {
                setCanceledOnTouchOutside(false)
                setOwnerActivity(activity)
                show()
            }
    }

    private fun showCode(activity: Activity, code: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        codeDialog?.let { runCatching { it.dismiss() } }
        val density = activity.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val big = TextView(activity).apply {
            text = code
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        val body = TextView(activity).apply {
            setText(R.string.logsend_done_body)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(big); addView(body)
        }
        codeDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.logsend_done_title)
            .setView(box)
            .setPositiveButton(R.string.logsend_copy) { _, _ -> seen(activity); copy(activity, code) }
            .setNegativeButton(R.string.logsend_close) { _, _ -> seen(activity) }
            .setOnCancelListener { seen(activity) }
            .create().apply {
                setCanceledOnTouchOutside(false)
                setOnDismissListener { if (codeDialog === this) codeDialog = null }
                show()
            }
    }

    /** The player closed or copied the code: it doesn't come back, and its notification goes. */
    private fun seen(context: Context) {
        LastCode.markSeen(context)
        cancelNotification(context)
    }

    /** Puts [code] on the clipboard. */
    fun copy(context: Context, code: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("MaliBridge log code", code))
        Toast.makeText(context, context.getString(R.string.logsend_copied, code), Toast.LENGTH_SHORT).show()
    }

    /** The result's notification channel, or null when the app may not post notifications. */
    private fun resultChannel(context: Context): NotificationManager? {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return null
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.logsend_channel), NotificationManager.IMPORTANCE_DEFAULT))
        return nm
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(context, NOTIFICATION_ID,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** No screen open when the upload failed: says so in the notification, since a toast may go unseen. */
    internal fun notifyFailed(context: Context, e: Throwable): Boolean {
        val nm = resultChannel(context) ?: return false
        val text = context.getString(failureText(e))
        val n = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(context.getString(R.string.logsend_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        return runCatching { nm.notify(NOTIFICATION_ID, n) }.onFailure { Log.w("LogUpload", "failure notification failed", it) }.isSuccess
    }

    /** No screen open when the code came: a notification with the code and a Copy button. False when none could be posted. */
    internal fun notifyCode(context: Context, code: String): Boolean {
        val nm = resultChannel(context) ?: return false
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = openApp(context)
        val copy = PendingIntent.getBroadcast(context, NOTIFICATION_ID + 1,
            Intent(context, CopyReceiver::class.java).putExtra(EXTRA_CODE, code), flags)
        val text = context.getString(R.string.logsend_notify_text, code)
        val n = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(context.getString(R.string.logsend_done_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.logsend_copy), copy).build())
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
        return runCatching { nm.notify(NOTIFICATION_ID, n) }.onFailure { Log.w("LogUpload", "code notification failed", it) }.isSuccess
    }

    /** The notification's Copy button. */
    class CopyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.getStringExtra(EXTRA_CODE) ?: LastCode.read(context) ?: return
            copy(context, code)
            LastCode.markSeen(context)
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private fun failureText(e: Throwable) = when {
        e is Archive -> R.string.session_logs_archive_failed
        (e as? Refused)?.status == 429 -> R.string.logsend_too_many
        (e as? Refused)?.status == 413 -> R.string.logsend_too_big
        else -> R.string.logsend_failed
    }

    private fun failed(context: Context, e: Throwable) {
        Toast.makeText(context, failureText(e), Toast.LENGTH_LONG).show()
    }

    private val CODE = Regex("ML-[0-9A-Z]{4}")
}
