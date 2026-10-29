package com.droiddeck.launcher.session

/**
 * What the end of a session's log says about why it stopped, for the ended screen.
 * Reads the lines the guest's own tools write in English, whatever the app's or Steam's language.
 */
class SessionEndScan {
    /** "Function not implemented" lines: syscalls the sandbox refuses. */
    var enosys = 0
        private set

    /** A Linux x86 program asked for FEX, which isn't installed yet. */
    var fexMissing = false
        private set

    /**
     * droiddeck-steam-install gave up on the first Steam download without ever getting an answer from
     * Steam's servers (a VPN/accelerator app refused every try in a few ms).
     */
    var steamUnreachable = false
        private set

    /** What the ended screen says, most specific first; null: the plain "... has stopped". */
    enum class Hint { STEAM_UNREACHABLE, FEX_MISSING, ENOSYS }

    val hint: Hint?
        get() = when {
            steamUnreachable -> Hint.STEAM_UNREACHABLE
            fexMissing -> Hint.FEX_MISSING
            enosys >= ENOSYS_HINT -> Hint.ENOSYS
            else -> null
        }

    /**
     * Whether a session that ended with [status] keeps its ended screen up. Any other status than 0
     * does. A 0 does only when Steam's first download never reached the servers: the session script
     * runs under gamescope, which ends 0 whatever its child did, so that 0 is not a success - Steam
     * never started (rc9 item 16). An ordinary end with status 0 still just closes the screen.
     */
    fun keepsEndedScreen(status: Int): Boolean = status != 0 || steamUnreachable

    fun line(line: String) {
        if (line.contains("Function not implemented")) enosys++
        if (line.contains(FEX_MISSING)) fexMissing = true
        if (line.contains(STEAM_UNREACHABLE)) steamUnreachable = true
    }

    companion object {
        /** This many refused syscalls name the sandbox switches on the ended screen. */
        const val ENOSYS_HINT = 8
        const val FEX_MISSING = "droiddeck-fex: x86 Linux programs need"
        const val STEAM_UNREACHABLE = "droiddeck-steam-install: could not reach Steam's download servers"

        fun scan(lines: Sequence<String>): SessionEndScan = SessionEndScan().also { s -> lines.forEach(s::line) }
    }
}
