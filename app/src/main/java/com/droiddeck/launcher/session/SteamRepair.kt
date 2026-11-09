package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

object SteamRepair {
    private fun home(context: Context) = File(LinuxRuntime.rootDir(context), "root")
    private fun request(context: Context) = File(home(context), ".bl-steam-repair")
    private fun unconfirmed(context: Context) = File(home(context), ".local/share/Steam/package/droiddeck-unconfirmed")
    private fun x64Unconfirmed(context: Context) = File(home(context), ".droiddeck-x64/.local/share/Steam/package/droiddeck-unconfirmed")

    fun queued(context: Context): Boolean = request(context).exists()

    fun queue(context: Context): Boolean = runCatching {
        request(context).apply { parentFile?.mkdirs() }.writeText("1\n")
    }.isSuccess

    /** Steam's own update install takes minutes with a blank window, so a start that ends during it is not a
     *  failed start; neither is the player's own Stop within the first minutes. Two uncounted-for starts never
     *  trigger the repair (droiddeck-steam-install), which would download 362 MB again. */
    const val PLAYER_STOP_COUNTS_AFTER_MS = 5L * 60L * 1000L
    const val INSTALL_LOG_MAX_AGE_MS = 15L * 60L * 1000L

    internal fun bootstrapLog(context: Context, arch: String?): File =
        File(home(context), (if (arch == "x86_64") ".droiddeck-x64/" else "") + ".local/share/Steam/logs/bootstrap_log.txt")

    /** The updater has begun unpacking a client update and has not shut down to start it (an untranslated log line). */
    internal fun updateInstalling(log: File?, now: Long = System.currentTimeMillis()): Boolean =
        log != null && log.isFile && now - log.lastModified() <= INSTALL_LOG_MAX_AGE_MS &&
            SteamDownloadMonitor.installingUpdate(LoadingState.tail(log).orEmpty())

    internal fun countsAsUnconfirmed(installing: Boolean, playerStopped: Boolean, sinceClientStartMs: Long): Boolean =
        !installing && !(playerStopped && sinceClientStartMs < PLAYER_STOP_COUNTS_AFTER_MS)

    /** Takes the last "start" line out of the unconfirmed-starts file; false when there was none. */
    internal fun forgetLastStart(file: File): Boolean {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return false
        val at = lines.lastIndexOf("start")
        if (at < 0) return false
        val kept = lines.filterIndexed { i, _ -> i != at }
        return runCatching { if (kept.isEmpty()) file.delete() else file.writeText(kept.joinToString("\n", postfix = "\n")) }.isSuccess
    }

    /** At the end of a Steam session that never showed a frame. Call before the client is torn down: its
     *  shutdown writes "Shutdown" to the bootstrap log, which ends the "installing" state. */
    fun sessionEnded(context: Context, arch: String?, playerStopped: Boolean, sinceClientStartMs: Long) {
        arch ?: return
        val file = if (arch == "x86_64") x64Unconfirmed(context) else unconfirmed(context)
        if (!file.isFile) return
        if (!countsAsUnconfirmed(updateInstalling(bootstrapLog(context, arch)), playerStopped, sinceClientStartMs)) forgetLastStart(file)
    }

    fun clientShown(context: Context) {
        (if (SessionPrefs.activeSteamClientArch(context) == "x86_64") x64Unconfirmed(context) else unconfirmed(context)).delete()
    }
}
