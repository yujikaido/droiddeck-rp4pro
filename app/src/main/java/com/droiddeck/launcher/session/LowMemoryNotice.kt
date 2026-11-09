package com.droiddeck.launcher.session

import android.app.ApplicationExitInfo
import android.content.Context

/**
 * One notice at the next start when Android's low-memory killer ended the app while a game session
 * ran, so the player does not take it for a crash. Recorded where an abandoned session folder is
 * finished (SessionArtifacts.finishAbandoned: whoever finishes it first, the agent provider or the
 * launcher), shown by the launcher once per kill and only within a day of it. An app killed while
 * idle in the background leaves no session folder and gets no notice.
 */
object LowMemoryNotice {
    private const val MAX_AGE_MS = 24L * 3600 * 1000

    /** Time of the app's newest main-process exit if Android ended it for low memory, else null.
     *  Records come newest first; helper processes (":agent", ":xboxcloud", ...) are skipped. */
    internal fun killTime(exits: List<SessionArtifacts.Exit>): Long? {
        val main = exits.firstOrNull { !it.process.contains(':') } ?: return null
        return main.time.takeIf { main.reason == ApplicationExitInfo.REASON_LOW_MEMORY }
    }

    internal fun shouldShow(pending: Long, lastShown: Long, now: Long): Boolean =
        pending > 0 && pending > lastShown && now - pending in -MAX_AGE_MS..MAX_AGE_MS

    /** For the newest abandoned session's exit records: remembers a low-memory kill to announce. */
    internal fun record(context: Context, exits: List<SessionArtifacts.Exit>) {
        val time = killTime(exits) ?: return
        if (time <= SessionPrefs.lowMemoryNoticeShown(context)) return
        SessionPrefs.setLowMemoryNoticePending(context, time)
    }

    /** True once for a recorded kill (it is marked shown); false when there is nothing to show. */
    @Synchronized
    fun take(context: Context): Boolean {
        val pending = SessionPrefs.lowMemoryNoticePending(context)
        if (!shouldShow(pending, SessionPrefs.lowMemoryNoticeShown(context), System.currentTimeMillis())) return false
        SessionPrefs.setLowMemoryNoticeShown(context, pending)
        return true
    }
}
