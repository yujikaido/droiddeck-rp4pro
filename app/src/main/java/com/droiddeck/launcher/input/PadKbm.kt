package com.droiddeck.launcher.input

import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Android side of [PadKbmCore]: reads a controller's KeyEvents and MotionEvents, and runs the core's pointer/wheel
 * steps on the display's frames only while a stick or a wheel button is held.
 * Events it takes are not passed on to PadBridge, so the game never sees the same press as a pad button too.
 *
 * Switch: DD_PAD_KBM=off in droiddeck-env turns it off everywhere; otherwise it is a per-game toggle in the session
 * menu (games started without Steam; Steam sessions have Steam Input's templates for this).
 */
class PadKbm(private val output: PadKbmCore.Output) {
    private val core = PadKbmCore(output)
    private val choreographer by lazy { Choreographer.getInstance() }
    private var running = false
    private var frameAt = 0L
    private val analogTriggers = HashMap<Int, Boolean>()
    private val wired = HashMap<Int, Boolean>()

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (frameAt == 0L) 1f / 60f else ((frameTimeNanos - frameAt) / 1e9f).coerceIn(0f, 0.05f)
            frameAt = frameTimeNanos
            core.step(dt)
            if (core.needsFrames()) choreographer.postFrameCallback(this) else stop()
        }
    }

    /** True when the key is one of the mapped controls (and was used). Guide/Home and unknown buttons go on as before. */
    fun onKey(event: KeyEvent): Boolean {
        val control = CONTROLS[event.keyCode] ?: return false
        if (event.repeatCount > 0) return true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> core.control(event.deviceId, PadKbmCore.Kind.KEYS, control, true)
            KeyEvent.ACTION_UP -> core.control(event.deviceId, PadKbmCore.Kind.KEYS, control, false)
        }
        wake()
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return true
        val id = event.deviceId
        val w = wired.getOrPut(id) { PadBridge.wiredXboxLayout(event) }
        val triggers = analogTriggers.getOrPut(id) { hasAnalogTriggers(event.device) || w }
        core.axes(
            id,
            event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y),
            PadBridge.rightX(event, w), PadBridge.rightY(event, w),
            if (triggers) PadBridge.leftTrigger(event, w) else 0f,
            if (triggers) PadBridge.rightTrigger(event, w) else 0f,
            triggers,
        )
        core.hat(id, event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        wake()
        return true
    }

    fun onDeviceRemoved(deviceId: Int) {
        core.forget(deviceId)
        analogTriggers.remove(deviceId)
        wired.remove(deviceId)
    }

    fun reset() {
        stop()
        core.reset()
        analogTriggers.clear()
        wired.clear()
    }

    private fun wake() {
        if (running || !core.needsFrames()) return
        running = true
        frameAt = 0L
        choreographer.postFrameCallback(frame)
    }

    private fun stop() {
        if (!running) return
        running = false
        frameAt = 0L
        choreographer.removeFrameCallback(frame)
    }

    private fun hasAnalogTriggers(device: InputDevice?): Boolean = device == null ||
        device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null || device.getMotionRange(MotionEvent.AXIS_BRAKE) != null

    companion object {
        const val ENV_KEY = "DD_PAD_KBM"

        fun enabled(envFile: String?): Boolean {
            val value = envFile.orEmpty().lineSequence().map { it.trim() }
                .filter { it.startsWith("$ENV_KEY=") }.lastOrNull()?.substringAfter('=')?.trim()?.lowercase() ?: return true
            return value !in setOf("off", "0", "false")
        }

        val CONTROLS: Map<Int, PadKbmCore.Control> = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to PadKbmCore.Control.A,
            KeyEvent.KEYCODE_BUTTON_B to PadKbmCore.Control.B,
            KeyEvent.KEYCODE_BUTTON_X to PadKbmCore.Control.X,
            KeyEvent.KEYCODE_BUTTON_Y to PadKbmCore.Control.Y,
            KeyEvent.KEYCODE_BUTTON_L1 to PadKbmCore.Control.LB,
            KeyEvent.KEYCODE_BUTTON_R1 to PadKbmCore.Control.RB,
            KeyEvent.KEYCODE_BUTTON_L2 to PadKbmCore.Control.LT,
            KeyEvent.KEYCODE_BUTTON_R2 to PadKbmCore.Control.RT,
            KeyEvent.KEYCODE_BUTTON_THUMBL to PadKbmCore.Control.L3,
            KeyEvent.KEYCODE_BUTTON_THUMBR to PadKbmCore.Control.R3,
            KeyEvent.KEYCODE_BUTTON_START to PadKbmCore.Control.START,
            KeyEvent.KEYCODE_BUTTON_SELECT to PadKbmCore.Control.BACK,
            KeyEvent.KEYCODE_DPAD_UP to PadKbmCore.Control.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to PadKbmCore.Control.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to PadKbmCore.Control.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to PadKbmCore.Control.RIGHT,
        )
    }
}
