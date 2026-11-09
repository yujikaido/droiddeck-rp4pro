package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MousePointerTest {
    @Test fun aMouseShowsItsPointerWhileTouchDrivesThePointer() {
        // Steam session defaults: touch Auto (= Direct), Cursor toggle off. : "physical mouse cursor invisible".
        assertFalse(MousePointer.hiddenForTouch(fromMouse = true, on = true, directCursor = false, touchDrivesPointer = true))
    }

    @Test fun aFingerStillLeavesThePointerUndrawnWithTheCursorToggleOff() {
        assertTrue(MousePointer.hiddenForTouch(fromMouse = false, on = true, directCursor = false, touchDrivesPointer = true))
        assertFalse(MousePointer.hiddenForTouch(fromMouse = false, on = true, directCursor = true, touchDrivesPointer = true))
        assertFalse(MousePointer.hiddenForTouch(fromMouse = false, on = true, directCursor = false, touchDrivesPointer = false))
    }

    @Test fun switchedOffTheOldRuleHidesTheMouseToo() {
        assertTrue(MousePointer.hiddenForTouch(fromMouse = true, on = false, directCursor = false, touchDrivesPointer = true))
        assertFalse(MousePointer.followsGuest(fromMouse = true, on = false, touchDrivesPointer = true))
    }

    @Test fun theMousePointerFollowsGuestWarpsInDirectMode() {
        assertTrue(MousePointer.followsGuest(fromMouse = true, on = true, touchDrivesPointer = true))
        assertFalse(MousePointer.followsGuest(fromMouse = false, on = true, touchDrivesPointer = true))
        assertTrue(MousePointer.followsGuest(fromMouse = false, on = true, touchDrivesPointer = false))
    }

    @Test fun idleHideFollowsTheSessionAndTheKeepToggle() {
        assertEquals(MousePointer.IDLE_HIDE_MS, MousePointer.idleHideMs(steamSession = true, steamDesktopUi = false, fromMouse = true, keep = false))
        assertNull(MousePointer.idleHideMs(steamSession = true, steamDesktopUi = false, fromMouse = true, keep = true))
        // Keep is about a mouse's pointer; a finger's pointer still hides.
        assertEquals(MousePointer.IDLE_HIDE_MS, MousePointer.idleHideMs(steamSession = true, steamDesktopUi = false, fromMouse = false, keep = true))
        assertNull(MousePointer.idleHideMs(steamSession = true, steamDesktopUi = true, fromMouse = true, keep = false))
        assertNull(MousePointer.idleHideMs(steamSession = false, steamDesktopUi = false, fromMouse = false, keep = false))
    }

    @Test fun theEnvSwitchReadsTheLastLine() {
        assertTrue(MousePointer.enabled(null))
        assertTrue(MousePointer.enabled("DD_OTHER=off\n"))
        assertFalse(MousePointer.enabled("DD_MOUSE_POINTER=off\n"))
        assertFalse(MousePointer.enabled("  DD_MOUSE_POINTER=0  "))
        assertTrue(MousePointer.enabled("DD_MOUSE_POINTER=off\nDD_MOUSE_POINTER=on\n"))
    }
}
