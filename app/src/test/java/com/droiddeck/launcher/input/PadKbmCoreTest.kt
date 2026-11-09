package com.droiddeck.launcher.input

import com.droiddeck.launcher.input.PadKbmCore.Control
import com.droiddeck.launcher.input.PadKbmCore.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadKbmCoreTest {
    private val events = mutableListOf<String>()
    private val moves = mutableListOf<Pair<Float, Float>>()
    private val core = PadKbmCore(object : PadKbmCore.Output {
        override fun key(code: Int, down: Boolean) { events += "key $code ${if (down) "down" else "up"}" }
        override fun button(code: Int, down: Boolean) { events += "button $code ${if (down) "down" else "up"}" }
        override fun wheel(steps: Int) { events += "wheel $steps" }
        override fun move(dx: Float, dy: Float) { moves += dx to dy }
    })

    private fun left(device: Int, x: Float, y: Float) = core.axes(device, x, y, 0f, 0f, 0f, 0f, triggers = true)

    @Test fun leftStickIsWasdWithHysteresis() {
        left(1, 0f, -0.6f)
        assertEquals(listOf("key ${PadKbmCore.KEY_W} down"), events)
        left(1, 0f, -0.4f) // between release (0.35) and press (0.5): still held
        assertEquals(1, events.size)
        left(1, 0f, -0.3f)
        assertEquals("key ${PadKbmCore.KEY_W} up", events.last())
        events.clear()
        left(1, 0.7f, 0.7f)
        assertEquals(setOf("key ${PadKbmCore.KEY_D} down", "key ${PadKbmCore.KEY_S} down"), events.toSet())
    }

    @Test fun triggersClickAndADigitalTriggerJoinsTheAnalogOne() {
        core.axes(1, 0f, 0f, 0f, 0f, 0f, 0.8f, triggers = true)
        assertEquals(listOf("button ${PointerGestures.BTN_LEFT} down"), events)
        core.control(1, Kind.KEYS, Control.RT, true)
        core.axes(1, 0f, 0f, 0f, 0f, 0f, 0f, triggers = true)
        assertEquals(1, events.size) // the R2 key still holds it
        core.control(1, Kind.KEYS, Control.RT, false)
        assertEquals("button ${PointerGestures.BTN_LEFT} up", events.last())
        events.clear()
        core.axes(1, 0f, 0f, 0f, 0f, 0.9f, 0f, triggers = true)
        assertEquals(listOf("button ${PointerGestures.BTN_RIGHT} down"), events)
    }

    @Test fun withoutAnalogTriggersTheAxesLeaveTriggersAlone() {
        core.control(1, Kind.KEYS, Control.LT, true)
        core.axes(1, 0f, 0f, 0f, 0f, 0f, 0f, triggers = false)
        assertEquals(listOf("button ${PointerGestures.BTN_RIGHT} down"), events)
    }

    @Test fun twoControllersHoldingOneButtonPressItOnce() {
        core.control(1, Kind.KEYS, Control.A, true)
        core.control(2, Kind.KEYS, Control.A, true)
        core.control(1, Kind.KEYS, Control.A, false)
        assertEquals(listOf("key ${PadKbmCore.KEY_SPACE} down"), events)
        core.control(2, Kind.KEYS, Control.A, false)
        assertEquals("key ${PadKbmCore.KEY_SPACE} up", events.last())
    }

    @Test fun rightStickMovesThePointerOnlyPastTheDeadZone() {
        core.axes(1, 0f, 0f, 0.1f, 0f, 0f, 0f, triggers = true)
        assertFalse(core.needsFrames())
        core.step(0.1f)
        assertTrue(moves.isEmpty())
        core.axes(1, 0f, 0f, 1f, 0f, 0f, 0f, triggers = true)
        assertTrue(core.needsFrames())
        core.step(0.1f)
        assertEquals(PadKbmCore.POINTER_WIDTHS_PER_S * 0.1f, moves.single().first, 1e-5f)
        assertEquals(0f, moves.single().second, 1e-6f)
        // A half tilt is slower than half speed (square curve), for fine aim.
        moves.clear()
        core.axes(1, 0f, 0f, 0f, 0.5f, 0f, 0f, triggers = true)
        core.step(0.1f)
        assertTrue(moves.single().second in 0f..(PadKbmCore.POINTER_WIDTHS_PER_S * 0.05f * 0.5f))
        assertTrue(events.isEmpty())
    }

    @Test fun wheelButtonsStepOnceThenRepeatWhileHeld() {
        core.control(1, Kind.KEYS, Control.LB, true)
        assertEquals(listOf("wheel -1"), events)
        assertTrue(core.needsFrames())
        core.step(0.3f)
        assertEquals(1, events.size)
        core.step(0.06f) // 0.36 s: first repeat
        assertEquals(2, events.size)
        core.step(0.31f) // two more at 0.15 s each
        assertEquals(4, events.size)
        core.control(1, Kind.KEYS, Control.LB, false)
        core.step(1f)
        assertEquals(4, events.size)
        assertFalse(core.needsFrames())
        core.control(1, Kind.KEYS, Control.RB, true)
        assertEquals("wheel 1", events.last())
    }

    @Test fun resetLetsGoOfEverythingOnce() {
        left(1, 0f, -1f)
        core.control(1, Kind.KEYS, Control.L3, true)
        core.axes(2, 0f, 0f, 0f, 0f, 0f, 1f, triggers = true)
        events.clear()
        core.reset()
        assertEquals(
            setOf("key ${PadKbmCore.KEY_W} up", "key ${PadKbmCore.KEY_LEFTSHIFT} up", "button ${PointerGestures.BTN_LEFT} up"),
            events.toSet(),
        )
        assertEquals(3, events.size)
        events.clear()
        core.reset()
        assertTrue(events.isEmpty())
    }

    @Test fun aControllerThatGoesAwayLetsGoOfWhatOnlyItHeld() {
        core.control(1, Kind.KEYS, Control.X, true)
        core.control(1, Kind.KEYS, Control.Y, true)
        core.control(2, Kind.KEYS, Control.Y, true)
        events.clear()
        core.forget(1)
        assertEquals(listOf("key ${PadKbmCore.KEY_E} up"), events)
    }

    @Test fun hatAndDpadKeysAreTheArrowKeys() {
        core.hat(1, 0f, -1f)
        core.control(1, Kind.KEYS, Control.UP, true)
        core.hat(1, 0f, 0f)
        assertEquals(listOf("key ${PadKbmCore.KEY_UP} down"), events)
        core.control(1, Kind.KEYS, Control.UP, false)
        assertEquals("key ${PadKbmCore.KEY_UP} up", events.last())
    }

    @Test fun theMapUsesEveryControlOnceAndLeavesGuideOut() {
        assertEquals(Control.entries.toSet(), PadKbmCore.MAP.keys)
        val keys = PadKbmCore.MAP.values.filterIsInstance<PadKbmCore.Bind.Key>().map { it.code }
        assertEquals(keys.size, keys.toSet().size)
    }
}
