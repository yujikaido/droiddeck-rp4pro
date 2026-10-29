package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseInputTest {
    @Test
    fun mainButtonsKeepTheirEvdevCodes() {
        for (locked in listOf(false, true)) {
            assertEquals(PointerGestures.BTN_LEFT, MouseInput.guestButton(MotionEvent.BUTTON_PRIMARY, locked))
            assertEquals(PointerGestures.BTN_RIGHT, MouseInput.guestButton(MotionEvent.BUTTON_SECONDARY, locked))
            assertEquals(PointerGestures.BTN_MIDDLE, MouseInput.guestButton(MotionEvent.BUTTON_TERTIARY, locked))
        }
    }

    @Test
    fun sideButtonsNeverClickLeft() {
        assertEquals(0, MouseInput.guestButton(MotionEvent.BUTTON_BACK, pointerLocked = false))
        assertEquals(0, MouseInput.guestButton(MotionEvent.BUTTON_FORWARD, pointerLocked = false))
        assertEquals(MouseInput.BTN_SIDE, MouseInput.guestButton(MotionEvent.BUTTON_BACK, pointerLocked = true))
        assertEquals(MouseInput.BTN_EXTRA, MouseInput.guestButton(MotionEvent.BUTTON_FORWARD, pointerLocked = true))
    }

    @Test
    fun sideButtonReleaseFollowsItsPress() {
        val side = MouseInput.SideButtons()
        assertEquals(MouseInput.BTN_SIDE, side.evdev(MotionEvent.BUTTON_BACK, pressed = true, pointerLocked = true))
        assertEquals(MouseInput.BTN_SIDE, side.evdev(MotionEvent.BUTTON_BACK, pressed = false, pointerLocked = false))
        assertEquals(0, side.evdev(MotionEvent.BUTTON_FORWARD, pressed = true, pointerLocked = false))
        assertEquals(0, side.evdev(MotionEvent.BUTTON_FORWARD, pressed = false, pointerLocked = true))
        assertEquals(PointerGestures.BTN_RIGHT, side.evdev(MotionEvent.BUTTON_SECONDARY, pressed = true, pointerLocked = false))
    }

    @Test
    fun sideButtonBackKeyDroppedOnlyWhileLocked() {
        assertTrue(MouseInput.dropsSideButtonKey(KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_MOUSE, true))
        assertTrue(MouseInput.dropsSideButtonKey(KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_MOUSE_RELATIVE, true))
        assertTrue(MouseInput.dropsSideButtonKey(KeyEvent.KEYCODE_FORWARD, InputDevice.SOURCE_MOUSE, true))
        assertFalse(MouseInput.dropsSideButtonKey(KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_MOUSE, false))
    }

    @Test
    fun otherBackKeysStillReachTheMenu() {
        assertFalse(MouseInput.dropsSideButtonKey(KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_KEYBOARD, true))
        assertFalse(MouseInput.dropsSideButtonKey(KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_TOUCHSCREEN, true))
        assertFalse(MouseInput.dropsSideButtonKey(KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_GAMEPAD, true))
        assertFalse(MouseInput.dropsSideButtonKey(KeyEvent.KEYCODE_ESCAPE, InputDevice.SOURCE_MOUSE, true))
    }

    @Test
    fun wholeWheelStepsPassStraightThrough() {
        val wheel = MouseInput.Wheel()
        assertEquals(1, wheel.steps(1f))
        assertEquals(-1, wheel.steps(-1f))
        assertEquals(3, wheel.steps(3f))
        assertEquals(0, wheel.steps(0f))
    }

    @Test
    fun partialStepsAddUp() {
        val wheel = MouseInput.Wheel()
        val got = (1..10).sumOf { wheel.steps(0.25f) }
        assertEquals(2, got)
        val down = (1..8).sumOf { wheel.steps(-0.125f) }
        assertEquals(-1, down)
    }

    @Test
    fun turningBackDropsTheLeftover() {
        val wheel = MouseInput.Wheel()
        assertEquals(0, wheel.steps(0.9f))
        assertEquals(0, wheel.steps(-0.5f))
        assertEquals(-1, wheel.steps(-0.5f))
    }
}
