package com.droiddeck.launcher.input

/**
 * When the pointer MaliBridge draws over the session shows.
 *
 * In a Steam session touch drives the pointer by default (touch mode Auto = Direct), and with the Cursor toggle off
 * a finger moves the pointer without drawing it. That rule used to apply to every move, so a physical mouse's pointer
 * was never drawn either (players saw no pointer unless gamescope drew it). The rule now applies to finger moves only.
 *
 * Switch: DD_MOUSE_POINTER=off in droiddeck-env brings back the old rule (for a test round).
 */
object MousePointer {
    const val ENV_KEY = "DD_MOUSE_POINTER"
    const val IDLE_HIDE_MS = 2500L

    /** Off only when droiddeck-env says DD_MOUSE_POINTER=off (or 0/false/old). */
    fun enabled(envFile: String?): Boolean {
        val value = envFile.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("$ENV_KEY=") }
            .lastOrNull()?.substringAfter('=')?.trim()?.lowercase()
            ?: return true
        return value !in setOf("off", "0", "false", "old")
    }

    /** Whether a pointer move leaves the pointer undrawn because touch drives it and the Cursor toggle is off. */
    fun hiddenForTouch(fromMouse: Boolean, on: Boolean, directCursor: Boolean, touchDrivesPointer: Boolean): Boolean =
        !(on && fromMouse) && !directCursor && touchDrivesPointer

    /** Whether the drawn pointer keeps following the guest's pointer (warps by the game) after this move. */
    fun followsGuest(fromMouse: Boolean, on: Boolean, touchDrivesPointer: Boolean): Boolean =
        (on && fromMouse) || !touchDrivesPointer

    /**
     * How long after the last move the pointer hides, or null for never. Steam's interface and games (not Steam's
     * desktop UI) hide it after [IDLE_HIDE_MS]; a mouse's pointer stays when the player asked to keep it.
     */
    fun idleHideMs(steamSession: Boolean, steamDesktopUi: Boolean, fromMouse: Boolean, keep: Boolean): Long? = when {
        !steamSession || steamDesktopUi -> null
        fromMouse && keep -> null
        else -> IDLE_HIDE_MS
    }
}
