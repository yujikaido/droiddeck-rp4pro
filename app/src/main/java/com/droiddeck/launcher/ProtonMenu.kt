package com.droiddeck.launcher

import android.os.Handler
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.ui.ProtonRow
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.frontend.AutoComponents
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller

internal class ProtonMenu(
    private val activity: android.app.Activity,
    private val ui: Handler,
    private val idle: () -> Boolean = { true },
) {
    // Install workers and their results outlive the activity that starts them.
    private object Operation {
        val rows = mutableStateOf<List<ProtonRow>>(emptyList())
        val busyId = mutableStateOf<String?>(null)
        val stage = mutableStateOf<String?>(null)
        val percent = mutableIntStateOf(-1)
        val problem = mutableStateOf<String?>(null)
    }
    var protonRows by Operation.rows
    var protonBusyId by Operation.busyId
    var protonStage by Operation.stage
    var protonPercent by Operation.percent
    var protonProblem by Operation.problem
    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)
    /** (tool, the tag asked for or null, progress) */
    @VisibleForTesting internal var installer: (ProtonExtras.Tool, String?, (String, Int) -> Unit) -> String? =
        { tool, want, progress -> ProtonExtras.install(activity, tool, progress, want) }

    /** [want]: the tag to install; null = the update the Proton list offers for it (the player tapped it), else the default. */
    fun installProton(id: String, automatic: Boolean = false, want: String? = null) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        val tag = want ?: runCatching { ProtonExtras.update(activity, tool) }.getOrNull()
        if (protonBusyId != null || ProtonExtras.installInProgress || SessionState.running) return
        ProtonExtras.unqueue(activity, tool)
        protonBusyId = id
        protonProblem = null
        protonStage = activity.getString(R.string.store_starting)
        protonPercent = -1
        Thread({
            val problem = runCatching {
                installer(tool, tag) { label, value ->
                    ui.post { protonStage = label; protonPercent = value }
                }
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            if (problem == null && !automatic && tool.id == ProtonExtras.OWN) ProtonExtras.setDeclined(activity, false)
            ui.post {
                protonProblem = problem
                protonBusyId = null
                protonStage = null
                protonPercent = -1
                refreshProtons()
                if (problem != null) {
                    if (automatic) Log.w("ProtonMenu", "auto-update ${tool.id}: $problem")
                    else android.widget.Toast.makeText(activity, problem, android.widget.Toast.LENGTH_LONG).show()
                }
                if (automatic) ensureAutoUpdates()
            }
        }, "install-proton-$id").start()
    }

    fun removeProton(id: String, dir: String? = null, older: Boolean = false) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        protonBusyId = id
        protonProblem = null
        protonStage = activity.getString(R.string.main_removing_named, dir ?: tool.name)
        protonPercent = -1
        Thread({
            if (older) ProtonExtras.removeOlder(activity, tool) else ProtonExtras.remove(activity, tool, dir)
            if (tool.id == ProtonExtras.OWN && ProtonExtras.installedAll(activity, tool).isEmpty()) ProtonExtras.setDeclined(activity, true)
            ui.post {
                protonBusyId = null
                protonStage = null
                refreshProtons()
            }
        }, "remove-proton-$id").start()
    }

    fun refreshProtons() {
        protonRows = ProtonExtras.tools.map {
            val all = ProtonExtras.installedAll(activity, it)
            ProtonRow(it.id, it.name, all.lastOrNull(), ProtonExtras.queued(activity, it), all, ProtonExtras.update(activity, it))
        }
    }

    fun checkUpdates(force: Boolean = false) {
        if (!checking.compareAndSet(false, true)) return
        Thread({
            val asked = try { ProtonExtras.checkLatest(activity, force) } catch (_: Throwable) { false } finally { checking.set(false) }
            ui.post {
                if (asked) refreshProtons()
                ensureAutoUpdates()
            }
        }, "proton-updates").start()
    }

    internal fun ensureAutoUpdates(now: Long = System.currentTimeMillis(), tidied: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed || !idle() || protonBusyId != null ||
            ProtonExtras.installInProgress || SessionState.running || LinuxRuntimeInstaller.isBusy() ||
            SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED) ||
            !LinuxRuntime.isInstalled(activity) || !AutoComponents.online(activity) || AutoComponents.unfavourable(activity)) return
        // Only DroidDeck-Proton builds that nothing uses go (ProtonExtras.removable): a build a game is on stays installed.
        if (!tidied && ProtonExtras.tidyNeeded(activity)) {
            protonBusyId = ProtonExtras.OWN
            protonProblem = null
            Thread({
                runCatching { ProtonExtras.tidy(activity) }.onFailure { Log.w("ProtonMenu", "tidy", it) }
                ui.post {
                    protonBusyId = null
                    refreshProtons()
                    ensureAutoUpdates(tidied = true)
                }
            }, "proton-tidy").start()
            return
        }
        val attempts = activity.getSharedPreferences("proton-auto-updates", android.content.Context.MODE_PRIVATE)
        val (tool, update) = ProtonExtras.tools.firstNotNullOfOrNull { tool ->
            val update = ProtonExtras.autoUpdate(activity, tool) ?: ProtonExtras.missing(activity, tool) ?: return@firstNotNullOfOrNull null
            val previous = attempts.getLong("${tool.id}.at", 0L)
            if (attempts.getString("${tool.id}.tag", null) != update || now - previous !in 0 until 15L * 60 * 1000) tool to update else null
        } ?: return
        attempts.edit().putString("${tool.id}.tag", update).putLong("${tool.id}.at", now).apply()
        installProton(tool.id, automatic = true, want = update)
    }
}
