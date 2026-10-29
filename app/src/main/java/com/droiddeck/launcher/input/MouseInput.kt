package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** How a physical mouse's buttons and wheel reach the guest. */
object MouseInput {
    const val BTN_SIDE = 0x113
    const val BTN_EXTRA = 0x114

    /**
     * The evdev button for a mouse button, or 0 when it stays with Android. The back and forward
     * side buttons go to the game only while it holds the pointer lock; otherwise Android turns
     * them into its Back key, which opens the session menu as before.
     */
    fun guestButton(actionButton: Int, pointerLocked: Boolean): Int = when (actionButton) {
        MotionEvent.BUTTON_SECONDARY -> PointerGestures.BTN_RIGHT
        MotionEvent.BUTTON_TERTIARY -> PointerGestures.BTN_MIDDLE
        MotionEvent.BUTTON_BACK -> if (pointerLocked) BTN_SIDE else 0
        MotionEvent.BUTTON_FORWARD -> if (pointerLocked) BTN_EXTRA else 0
        else -> PointerGestures.BTN_LEFT
    }

    /** Side buttons the game was sent a press for, so their release reaches it after the lock ends. */
    class SideButtons {
        private var held = 0

        fun evdev(actionButton: Int, pressed: Boolean, pointerLocked: Boolean): Int {
            val bit = when (actionButton) {
                MotionEvent.BUTTON_BACK -> 1
                MotionEvent.BUTTON_FORWARD -> 2
                else -> return guestButton(actionButton, pointerLocked)
            }
            val code = if (bit == 1) BTN_SIDE else BTN_EXTRA
            if (pressed) {
                if (!pointerLocked) return 0
                held = held or bit
                return code
            }
            if (held and bit == 0) return 0
            held = held and bit.inv()
            return code
        }
    }

    /**
     * Android adds a Back or Forward key event of its own when a mouse's side button is pressed.
     * While the game holds the pointer lock the button already went to the game, so that key is
     * dropped instead of opening the session menu.
     */
    fun dropsSideButtonKey(keyCode: Int, source: Int, pointerLocked: Boolean): Boolean {
        if (!pointerLocked) return false
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_FORWARD) return false
        return isFrom(source, InputDevice.SOURCE_MOUSE) || isFrom(source, InputDevice.SOURCE_MOUSE_RELATIVE)
    }

    private fun isFrom(source: Int, wanted: Int) = source and wanted == wanted

    /**
     * Wheel steps from AXIS_VSCROLL values. High-resolution wheels and touchpads report parts of a
     * step; they add up here instead of each rounding to nothing.
     */
    class Wheel {
        private var pending = 0f

        fun steps(value: Float): Int {
            if (value == 0f || value.isNaN()) return 0
            if (pending != 0f && (pending > 0f) != (value > 0f)) pending = 0f
            pending += value
            val whole = pending.toInt()
            pending -= whole
            return whole
        }

        fun reset() { pending = 0f }
    }
}
