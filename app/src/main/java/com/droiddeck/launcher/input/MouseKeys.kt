package com.droiddeck.launcher.input

import com.droiddeck.launcher.R

/**
 * On-screen keys that click or turn the wheel instead of typing (wmr112: "a layout that only includes mouse keys").
 * The buttons use their evdev codes; the wheel has no evdev key, so it gets codes far above every key code.
 * A screen key holds plain ints, so saved layouts with these codes load in older builds too (the key just does nothing there).
 *
 * Switch: DD_SCREEN_MOUSE_KEYS=off in droiddeck-env hides the picker row and the built-in layout.
 */
object MouseKeys {
    const val ENV_KEY = "DD_SCREEN_MOUSE_KEYS"
    const val LEFT = PointerGestures.BTN_LEFT
    const val RIGHT = PointerGestures.BTN_RIGHT
    const val MIDDLE = PointerGestures.BTN_MIDDLE
    const val WHEEL_UP = 0x10001
    const val WHEEL_DOWN = 0x10002

    sealed interface Action {
        data class Button(val code: Int, val down: Boolean) : Action
        /** Wheel steps as sendSceneWheel takes them: negative scrolls up (as a mouse wheel turned away). */
        data class Wheel(val steps: Int) : Action
        data class Key(val code: Int, val down: Boolean) : Action
        data object None : Action
    }

    val ROW: List<PcKey> = listOf(
        k(R.string.screen_key_mouse_left, LEFT, 1.5f),
        k(R.string.screen_key_mouse_middle, MIDDLE, 1.5f),
        k(R.string.screen_key_mouse_right, RIGHT, 1.5f),
        k(R.string.screen_key_wheel_up, WHEEL_UP, 1.5f),
        k(R.string.screen_key_wheel_down, WHEEL_DOWN, 1.5f),
    )

    val BY_CODE: Map<Int, PcKey> = ROW.associateBy { it.code }

    fun isMouse(code: Int): Boolean = code in BY_CODE

    /** What a screen key's press or release does. A wheel key turns one step on its press. */
    fun route(code: Int, down: Boolean): Action = when (code) {
        LEFT, RIGHT, MIDDLE -> Action.Button(code, down)
        WHEEL_UP -> if (down) Action.Wheel(-1) else Action.None
        WHEEL_DOWN -> if (down) Action.Wheel(1) else Action.None
        else -> Action.Key(code, down)
    }

    fun enabled(envFile: String?): Boolean {
        val value = envFile.orEmpty().lineSequence().map { it.trim() }
            .filter { it.startsWith("$ENV_KEY=") }.lastOrNull()?.substringAfter('=')?.trim()?.lowercase() ?: return true
        return value !in setOf("off", "0", "false")
    }

    /**
     * The built-in "Mouse keys" layout: the five keys in a column on the right edge, clicks lower where a thumb rests.
     * x and y are the key centres as fractions of the screen, as ScreenKeys stores them.
     */
    fun builtInLayout(): List<ScreenKey> = listOf(
        ScreenKey(listOf(WHEEL_UP), 0.93f, 0.22f, 44),
        ScreenKey(listOf(WHEEL_DOWN), 0.93f, 0.37f, 44),
        ScreenKey(listOf(MIDDLE), 0.93f, 0.52f, 44),
        ScreenKey(listOf(RIGHT), 0.93f, 0.67f, 52, shape = SCREEN_KEY_ROUND),
        ScreenKey(listOf(LEFT), 0.83f, 0.80f, 60, shape = SCREEN_KEY_ROUND),
    )
}
