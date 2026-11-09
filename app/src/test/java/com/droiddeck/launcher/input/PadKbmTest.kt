package com.droiddeck.launcher.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PadKbmTest {
    private val events = mutableListOf<String>()
    private val pad = PadKbm(object : PadKbmCore.Output {
        override fun key(code: Int, down: Boolean) { events += "key $code ${if (down) "down" else "up"}" }
        override fun button(code: Int, down: Boolean) { events += "button $code ${if (down) "down" else "up"}" }
        override fun wheel(steps: Int) { events += "wheel $steps" }
        override fun move(dx: Float, dy: Float) {}
    })

    private fun key(action: Int, code: Int, repeat: Int = 0) =
        KeyEvent(0L, 0L, action, code, repeat, 0, 7, 0, 0, android.view.InputDevice.SOURCE_GAMEPAD)

    @Test fun mappedButtonsAreTakenAndTyped() {
        assertTrue(pad.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
        assertTrue(pad.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, repeat = 1)))
        assertTrue(pad.onKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)))
        assertEquals(listOf("key ${PadKbmCore.KEY_SPACE} down", "key ${PadKbmCore.KEY_SPACE} up"), events)
    }

    @Test fun guideAndUnknownButtonsGoOnToTheRestOfTheApp() {
        assertFalse(pad.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_MODE)))
        assertFalse(pad.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_C)))
        assertFalse(pad.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertTrue(events.isEmpty())
    }

    @Test fun resetReleasesHeldKeys() {
        pad.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START))
        pad.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R2))
        events.clear()
        pad.reset()
        assertEquals(setOf("key ${PadKbmCore.KEY_ESC} up", "button ${PointerGestures.BTN_LEFT} up"), events.toSet())
    }

    @Test fun deviceRemovalReleasesThatDevicesKeys() {
        pad.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT))
        events.clear()
        pad.onDeviceRemoved(7)
        assertEquals(listOf("key ${PadKbmCore.KEY_TAB} up"), events)
    }

    @Test fun theEnvSwitchTurnsItOff() {
        assertTrue(PadKbm.enabled(null))
        assertFalse(PadKbm.enabled("DD_PAD_KBM=off\n"))
        assertTrue(PadKbm.enabled("DD_PAD_KBM=on\n"))
    }
}
