package com.droiddeck.launcher.input

import kotlin.math.hypot
import kotlin.math.pow

/**
 * A controller as mouse and keyboard, for games that read only those (3JOE-YXO: Penumbra, started without Steam,
 * where Steam Input's templates are not there). Pure logic: controls in, keys, buttons, wheel steps and pointer
 * motion out, so it is tested without a device.
 *
 * One fixed map, close to the usual first-person layout: left stick W/A/S/D, right stick the mouse, RT left click,
 * LT right click, LB/RB wheel up/down, A Space, B Ctrl, X E, Y F, L3 Shift, R3 middle click, Start Esc, Back Tab,
 * D-pad arrows. The Guide/Home button is not taken, so the session menu stays on the controller.
 *
 * Several sources can hold one control (two controllers, a digital and an analog trigger, D-pad keys and hat): the
 * output is pressed while any of them holds it and released once, when the last lets go.
 */
class PadKbmCore(private val out: Output) {
    interface Output {
        fun key(code: Int, down: Boolean)
        fun button(code: Int, down: Boolean)
        /** Negative turns the wheel up (away), as a mouse wheel's notch reaches sendSceneWheel. */
        fun wheel(steps: Int)
        /** Pointer motion as fractions of the scene's width (both axes, so motion is round). */
        fun move(dx: Float, dy: Float)
    }

    enum class Control { A, B, X, Y, LB, RB, L3, R3, START, BACK, UP, DOWN, LEFT, RIGHT, LT, RT, LS_UP, LS_DOWN, LS_LEFT, LS_RIGHT }

    sealed interface Bind {
        data class Key(val code: Int) : Bind
        data class Button(val code: Int) : Bind
        data class Wheel(val steps: Int) : Bind
    }

    /** Where a control's state comes from, per device: keys, the hat, the sticks, the trigger axes. */
    enum class Kind { KEYS, HAT, STICK, TRIGGER }

    private class Axes {
        var rx = 0f
        var ry = 0f
    }

    private val holders = HashMap<Control, MutableSet<Long>>()
    private val axes = HashMap<Int, Axes>()
    private val wheelHeld = LinkedHashMap<Control, Float>()

    /** A digital control from [device] ([kind] tells keys from the hat, so one releasing does not release the other). */
    fun control(device: Int, kind: Kind, control: Control, down: Boolean) {
        val source = (device.toLong() shl 8) or kind.ordinal.toLong()
        val set = holders.getOrPut(control) { HashSet() }
        val was = set.isNotEmpty()
        if (down) set.add(source) else set.remove(source)
        val now = set.isNotEmpty()
        if (was != now) emit(control, now)
    }

    /**
     * One controller's analog state. Sticks are -1..1 (down and right positive), triggers 0..1; [triggers] false
     * when the controller has no analog triggers (its L2/R2 keys then come through [control]).
     */
    fun axes(device: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, triggers: Boolean) {
        threshold(device, Kind.STICK, Control.LS_LEFT, -lx)
        threshold(device, Kind.STICK, Control.LS_RIGHT, lx)
        threshold(device, Kind.STICK, Control.LS_UP, -ly)
        threshold(device, Kind.STICK, Control.LS_DOWN, ly)
        if (triggers) {
            threshold(device, Kind.TRIGGER, Control.LT, lt)
            threshold(device, Kind.TRIGGER, Control.RT, rt)
        }
        val a = axes.getOrPut(device) { Axes() }
        a.rx = rx
        a.ry = ry
    }

    fun hat(device: Int, x: Float, y: Float) {
        control(device, Kind.HAT, Control.LEFT, x < -HAT)
        control(device, Kind.HAT, Control.RIGHT, x > HAT)
        control(device, Kind.HAT, Control.UP, y < -HAT)
        control(device, Kind.HAT, Control.DOWN, y > HAT)
    }

    /** Advance [dt] seconds: pointer motion from the right sticks and wheel repeats for held wheel buttons. */
    fun step(dt: Float) {
        if (dt <= 0f) return
        var vx = 0f
        var vy = 0f
        for (a in axes.values) {
            val (x, y) = curve(a.rx, a.ry)
            vx += x
            vy += y
        }
        if (vx != 0f || vy != 0f) out.move(vx.coerceIn(-1f, 1f) * POINTER_WIDTHS_PER_S * dt, vy.coerceIn(-1f, 1f) * POINTER_WIDTHS_PER_S * dt)
        for (entry in wheelHeld.entries) {
            var left = entry.value - dt
            while (left <= 0f) {
                (MAP[entry.key] as? Bind.Wheel)?.let { out.wheel(it.steps) }
                left += WHEEL_REPEAT_S
            }
            entry.setValue(left)
        }
    }

    /** Whether [step] still has work: a right stick out of its dead zone or a wheel button held. */
    fun needsFrames(): Boolean = wheelHeld.isNotEmpty() || axes.values.any { curve(it.rx, it.ry).let { (x, y) -> x != 0f || y != 0f } }

    /** Let go of everything that is held (menu opened, focus lost, controller gone, switched off). */
    fun reset() {
        val held = holders.filterValues { it.isNotEmpty() }.keys.toList()
        holders.clear()
        axes.clear()
        held.forEach { emit(it, false) }
        wheelHeld.clear()
    }

    /** A controller went away: what only it held is let go. */
    fun forget(device: Int) {
        val prefix = device.toLong() shl 8
        holders.forEach { (control, set) ->
            val was = set.isNotEmpty()
            set.removeAll { it and 0xffL.inv() == prefix }
            if (was && set.isEmpty()) emit(control, false)
        }
        axes.remove(device)
    }

    private fun threshold(device: Int, kind: Kind, control: Control, value: Float) {
        val source = (device.toLong() shl 8) or kind.ordinal.toLong()
        val held = holders[control]?.contains(source) == true
        val down = if (held) value > RELEASE else value > PRESS
        if (down != held) control(device, kind, control, down)
    }

    private fun emit(control: Control, down: Boolean) {
        when (val bind = MAP[control] ?: return) {
            is Bind.Key -> out.key(bind.code, down)
            is Bind.Button -> out.button(bind.code, down)
            is Bind.Wheel -> if (down) {
                out.wheel(bind.steps)
                wheelHeld[control] = WHEEL_FIRST_REPEAT_S
            } else {
                wheelHeld.remove(control)
            }
        }
    }

    companion object {
        const val PRESS = 0.5f
        const val RELEASE = 0.35f
        const val HAT = 0.5f
        const val DEAD_ZONE = 0.12f
        /** Full right-stick tilt moves the pointer this many scene widths a second. */
        const val POINTER_WIDTHS_PER_S = 0.9f
        const val WHEEL_FIRST_REPEAT_S = 0.35f
        const val WHEEL_REPEAT_S = 0.15f

        const val KEY_ESC = 1
        const val KEY_TAB = 15
        const val KEY_W = 17
        const val KEY_E = 18
        const val KEY_LEFTCTRL = 29
        const val KEY_A = 30
        const val KEY_S = 31
        const val KEY_D = 32
        const val KEY_F = 33
        const val KEY_LEFTSHIFT = 42
        const val KEY_SPACE = 57
        const val KEY_UP = 103
        const val KEY_LEFT = 105
        const val KEY_RIGHT = 106
        const val KEY_DOWN = 108

        val MAP: Map<Control, Bind> = mapOf(
            Control.LS_UP to Bind.Key(KEY_W), Control.LS_LEFT to Bind.Key(KEY_A),
            Control.LS_DOWN to Bind.Key(KEY_S), Control.LS_RIGHT to Bind.Key(KEY_D),
            Control.RT to Bind.Button(PointerGestures.BTN_LEFT), Control.LT to Bind.Button(PointerGestures.BTN_RIGHT),
            Control.R3 to Bind.Button(PointerGestures.BTN_MIDDLE),
            Control.LB to Bind.Wheel(-1), Control.RB to Bind.Wheel(1),
            Control.A to Bind.Key(KEY_SPACE), Control.B to Bind.Key(KEY_LEFTCTRL),
            Control.X to Bind.Key(KEY_E), Control.Y to Bind.Key(KEY_F),
            Control.L3 to Bind.Key(KEY_LEFTSHIFT),
            Control.START to Bind.Key(KEY_ESC), Control.BACK to Bind.Key(KEY_TAB),
            Control.UP to Bind.Key(KEY_UP), Control.DOWN to Bind.Key(KEY_DOWN),
            Control.LEFT to Bind.Key(KEY_LEFT), Control.RIGHT to Bind.Key(KEY_RIGHT),
        )

        /** Radial dead zone, then a square curve so small tilts aim finely and a full tilt turns fast. */
        fun curve(x: Float, y: Float): Pair<Float, Float> {
            val m = hypot(x, y)
            if (m <= DEAD_ZONE) return 0f to 0f
            val scaled = ((m.coerceAtMost(1f) - DEAD_ZONE) / (1f - DEAD_ZONE)).pow(2)
            return x / m * scaled to y / m * scaled
        }
    }
}
