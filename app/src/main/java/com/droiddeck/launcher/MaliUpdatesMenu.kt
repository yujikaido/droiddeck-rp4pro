package com.droiddeck.launcher

import android.util.Log
import com.droiddeck.launcher.core.NetworkError
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.MaliUpdateActions
import com.droiddeck.launcher.ui.MaliUpdateState
import com.droiddeck.launcher.update.MaliReleases
import com.droiddeck.launcher.update.MaliUpdater
import com.droiddeck.launcher.update.SelfInstaller
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The Updates tab of the MaliBridge build: checks MaliBridge's newest GitHub release on start (at
 * most every few hours), downloads a newer APK in the background (on Wi-Fi only, if so set),
 * verifies it, then offers it. Installing always goes through Android's own confirmation.
 */
internal class MaliUpdatesMenu(private val activity: Activity, private val ui: Handler) {
    private var enabled by mutableStateOf(true)
    private var wifiOnly by mutableStateOf(true)
    private var checking by mutableStateOf(false)
    private var checkedAt by mutableLongStateOf(0L)
    private var release by mutableStateOf<MaliReleases.Release?>(null)
    private var standing by mutableStateOf<MaliReleases.Standing?>(null)
    private var phase by mutableStateOf(MaliUpdateState.Phase.IDLE)
    private var percent by mutableIntStateOf(-1)
    private var error by mutableStateOf<String?>(null)
    private var askPermission by mutableStateOf(false)
    private var bannerDismissed by mutableStateOf(false)
    private var readyFile: File? = null
    private var pendingInstall = false
    @Volatile private var stopDownload = false
    private var request = 0

    fun state() = MaliUpdateState(
        MaliUpdater.installedTag, enabled, wifiOnly, checking, checkedAt, release, standing,
        phase, percent, error, askPermission, bannerDismissed,
    )

    fun actions() = MaliUpdateActions(
        onCheck = { check(manual = true) },
        onEnabled = { on ->
            MaliUpdater.setEnabled(activity, on); enabled = on
            if (on) check(manual = false)
        },
        onWifiOnly = { on ->
            MaliUpdater.setWifiOnly(activity, on); wifiOnly = on
            if (phase == MaliUpdateState.Phase.WAITING_WIFI && !on) download()
        },
        onDownload = ::download,
        onInstall = ::install,
        onAllowInstalls = {
            askPermission = false
            runCatching { activity.startActivity(SelfInstaller.permissionIntent(activity)) }
                .onFailure { error = activity.getString(R.string.upd_unknown_apps_failed); pendingInstall = false }
        },
        onDismissPermission = { askPermission = false; pendingInstall = false },
        onDismissBanner = { bannerDismissed = true },
    )

    fun start() {
        enabled = MaliUpdater.enabled(activity)
        wifiOnly = MaliUpdater.wifiOnly(activity)
        checkedAt = MaliUpdater.lastCheck(activity)
        val id = ++request
        Thread({
            val cached = MaliUpdater.cached(activity)
            val s = cached?.let { MaliUpdater.standing(activity, it) }
            val newer = s == MaliReleases.Standing.NEWER
            if (!newer) MaliUpdater.cleanup(activity, null)
            val file = if (newer) MaliUpdater.ready(activity, cached!!) else null
            ui.post {
                if (id != request) return@post
                release = cached; standing = s
                if (file != null) { readyFile = file; phase = MaliUpdateState.Phase.READY }
            }
        }, "mali-updates-start").start()
    }

    fun onResume() {
        if (pendingInstall && SelfInstaller.canInstall(activity)) { pendingInstall = false; install() }
        if (SessionState.running) return
        if (MaliUpdater.autoCheckDue(activity)) check(manual = false)
        else if (phase == MaliUpdateState.Phase.WAITING_WIFI && MaliUpdater.onWifi(activity)) download()
    }

    fun check(manual: Boolean) {
        if (checking || phase.busy) return
        val id = ++request
        checking = true
        if (manual) error = null
        Thread({
            val result = MaliUpdater.check(activity)
            // Without an answer (offline, rate limited) the last known release stays on show.
            val shown = (result as? MaliUpdater.Check.Done)?.release ?: MaliUpdater.cached(activity)
            val s = shown?.let { MaliUpdater.standing(activity, it) }
            val file = if (s == MaliReleases.Standing.NEWER) MaliUpdater.ready(activity, shown!!) else null
            if (result is MaliUpdater.Check.Done && s != MaliReleases.Standing.NEWER) MaliUpdater.cleanup(activity, null)
            ui.post {
                if (id != request) return@post
                checking = false
                checkedAt = MaliUpdater.lastCheck(activity)
                release = shown; standing = s
                when {
                    file != null -> { readyFile = file; phase = MaliUpdateState.Phase.READY }
                    s != MaliReleases.Standing.NEWER -> { readyFile = null; phase = MaliUpdateState.Phase.IDLE }
                    result is MaliUpdater.Check.Done && (enabled || manual) -> autoDownload()
                }
                when (result) {
                    is MaliUpdater.Check.Done -> error = null
                    is MaliUpdater.Check.Limited -> if (manual) error = activity.getString(
                        R.string.mali_upd_rate_limited, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(result.until)),
                    )
                    is MaliUpdater.Check.Failed -> {
                        // The raw text stays in the log; the screen gets the player's-language line when there is one.
                        Log.w("MaliUpdates", "check failed: ${result.message}")
                        if (manual) error = activity.getString(R.string.upd_couldnt_check, result.cause?.let(::failureText) ?: result.message)
                    }
                }
            }
        }, "mali-updates-check").start()
    }

    private fun autoDownload() {
        if (wifiOnly && !MaliUpdater.onWifi(activity)) { phase = MaliUpdateState.Phase.WAITING_WIFI; return }
        download()
    }

    private fun download() {
        val r = release ?: return
        if (phase.busy || r.apk == null) return
        error = null
        phase = MaliUpdateState.Phase.DOWNLOADING
        percent = 0
        stopDownload = false
        Thread({
            val result = runCatching {
                MaliUpdater.prepare(activity, r, { stopDownload }) { pct -> ui.post { percent = pct } }
            }
            ui.post {
                result.onSuccess {
                    readyFile = it; phase = MaliUpdateState.Phase.READY; bannerDismissed = false
                }.onFailure { e ->
                    phase = MaliUpdateState.Phase.IDLE
                    error = describe(e)
                }
            }
        }, "mali-updates-download").start()
    }

    private fun install() {
        val r = release ?: return
        val file = readyFile ?: return
        if (phase != MaliUpdateState.Phase.READY) return
        if (SessionState.running) { error = activity.getString(R.string.upd_stop_first); return }
        if (!SelfInstaller.canInstall(activity)) { pendingInstall = true; askPermission = true; return }
        error = null
        phase = MaliUpdateState.Phase.INSTALLING
        Thread({
            val result = runCatching {
                MaliUpdater.verifyReady(activity, r, file)
                SelfInstaller.install(activity, file)
            }
            result.onFailure { e ->
                ui.post {
                    if (e is MaliUpdater.Refused) { readyFile = null; phase = MaliUpdateState.Phase.IDLE }
                    else phase = MaliUpdateState.Phase.READY
                    error = describe(e)
                }
            }
        }, "mali-updates-install").start()
    }

    /** PackageInstaller's answer, as UpdatesMenu's receiver hears it. */
    fun onInstallStatus(intent: Intent) {
        if (phase != MaliUpdateState.Phase.INSTALLING && phase != MaliUpdateState.Phase.CONFIRM) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) { phase = MaliUpdateState.Phase.READY; error = activity.getString(R.string.upd_not_offered); return }
                phase = MaliUpdateState.Phase.CONFIRM
                activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> phase = MaliUpdateState.Phase.IDLE
            else -> {
                phase = MaliUpdateState.Phase.READY
                error = SelfInstaller.describeFailure(activity, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
    }

    fun stop() { stopDownload = true }

    private fun describe(e: Throwable): String = when ((e as? MaliUpdater.Refused)?.problem) {
        MaliReleases.Problem.CHECKSUM -> activity.getString(R.string.mali_upd_problem_checksum)
        MaliReleases.Problem.NO_CHECKSUM -> activity.getString(R.string.mali_upd_problem_no_checksum)
        MaliReleases.Problem.WRONG_PACKAGE -> activity.getString(R.string.mali_upd_problem_package)
        MaliReleases.Problem.OLDER_VERSION_CODE -> activity.getString(R.string.mali_upd_problem_version)
        MaliReleases.Problem.NO_SIGNER, MaliReleases.Problem.WRONG_SIGNER -> activity.getString(R.string.mali_upd_problem_signer)
        MaliReleases.Problem.UNREADABLE -> activity.getString(R.string.mali_upd_problem_unreadable)
        null -> activity.getString(R.string.mali_upd_failed, failureText(e) ?: e.message ?: e.javaClass.simpleName)
    }

    /** The player's-language text for the updater's own failures; null for anything else (its message is shown). */
    private fun failureText(e: Throwable): String? {
        val f = e as? MaliReleases.Failure ?: return NetworkError.describe(activity, e)
        return when (f.reason) {
            MaliReleases.Reason.NOT_STABLE -> activity.getString(R.string.mali_upd_err_not_stable)
            MaliReleases.Reason.BAD_TAG -> activity.getString(R.string.mali_upd_err_bad_tag, f.arg)
            MaliReleases.Reason.CHECKSUM_UNREADABLE -> activity.getString(R.string.mali_upd_err_checksum_unreadable, f.arg)
            MaliReleases.Reason.CHECKSUM_UNLISTED -> activity.getString(R.string.mali_upd_err_checksum_unlisted, f.arg)
            MaliReleases.Reason.CHECKSUM_DISAGREE -> activity.getString(R.string.mali_upd_err_checksum_disagree, f.arg)
            MaliReleases.Reason.HTTP -> activity.getString(R.string.mali_upd_err_http, f.arg)
            MaliReleases.Reason.STOPPED -> activity.getString(R.string.mali_upd_err_stopped)
            MaliReleases.Reason.TOO_LARGE -> activity.getString(R.string.mali_upd_err_too_large)
            MaliReleases.Reason.CUT_SHORT -> activity.getString(R.string.mali_upd_err_cut_short)
            MaliReleases.Reason.NO_CONTINUE -> activity.getString(R.string.mali_upd_err_no_continue)
            MaliReleases.Reason.NOT_MOVED -> activity.getString(R.string.mali_upd_err_not_moved)
            MaliReleases.Reason.ANSWER_TOO_LARGE -> activity.getString(R.string.mali_upd_err_answer_too_large)
            MaliReleases.Reason.NO_APK -> activity.getString(R.string.mali_upd_err_no_apk)
        }
    }
}
