package com.droiddeck.launcher.core

import android.content.Context
import com.droiddeck.launcher.R

/** Turns the last download that gave up on this thread into a line the player can act on. */
object DownloadProblem {
    /** [message], plus how many tries were made, on which hosts, and the last error, when a download gave up. */
    fun explain(context: Context, message: String): String {
        val f = Downloader.lastFailure() ?: return message
        return message + "\n" + context.getString(R.string.download_gave_up, f.tries, f.hosts.joinToString(", "), f.last)
    }

    /** Forgets an earlier failure so it is not reported with a later, unrelated error. */
    fun forget() = Downloader.forgetFailure()
}
