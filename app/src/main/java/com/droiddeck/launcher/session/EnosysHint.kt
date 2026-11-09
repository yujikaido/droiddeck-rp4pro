package com.droiddeck.launcher.session

import com.droiddeck.launcher.R

/**
 * The end-screen hint for a log full of "Function not implemented". It suggests "Run proot without seccomp" only while
 * that switch is off; with it already on the errors come from that mode, so the hint says to turn it off.
 */
internal object EnosysHint {
    const val MIN_LINES = 8

    fun plural(prootNoSeccomp: Boolean): Int =
        if (prootNoSeccomp) R.plurals.session_hint_enosys_no_seccomp else R.plurals.session_hint_enosys
}
