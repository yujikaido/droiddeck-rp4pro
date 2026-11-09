package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MouseKeysTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun clicksPressAndReleaseTheirButton() {
        assertEquals(MouseKeys.Action.Button(PointerGestures.BTN_LEFT, true), MouseKeys.route(MouseKeys.LEFT, true))
        assertEquals(MouseKeys.Action.Button(PointerGestures.BTN_LEFT, false), MouseKeys.route(MouseKeys.LEFT, false))
        assertEquals(MouseKeys.Action.Button(PointerGestures.BTN_RIGHT, true), MouseKeys.route(MouseKeys.RIGHT, true))
        assertEquals(MouseKeys.Action.Button(PointerGestures.BTN_MIDDLE, false), MouseKeys.route(MouseKeys.MIDDLE, false))
    }

    @Test fun wheelKeysTurnOneStepOnThePressOnly() {
        // sendSceneWheel takes negative steps for up, as onMouse sends a wheel turned away.
        assertEquals(MouseKeys.Action.Wheel(-1), MouseKeys.route(MouseKeys.WHEEL_UP, true))
        assertEquals(MouseKeys.Action.Wheel(1), MouseKeys.route(MouseKeys.WHEEL_DOWN, true))
        assertEquals(MouseKeys.Action.None, MouseKeys.route(MouseKeys.WHEEL_UP, false))
        assertEquals(MouseKeys.Action.None, MouseKeys.route(MouseKeys.WHEEL_DOWN, false))
    }

    @Test fun keyboardKeysStillType() {
        assertEquals(MouseKeys.Action.Key(17, true), MouseKeys.route(17, true))
        assertEquals(MouseKeys.Action.Key(29, false), MouseKeys.route(29, false))
        PC_KEYS.keys.forEach { assertFalse("key $it taken as a mouse key", MouseKeys.isMouse(it)) }
    }

    @Test fun mouseKeysHaveTheirOwnLabels() {
        assertEquals("Left click", screenKeyLabel(context, listOf(MouseKeys.LEFT)))
        assertEquals("Wheel down", screenKeyLabel(context, listOf(MouseKeys.WHEEL_DOWN)))
        assertEquals("Ctrl+Right click", screenKeyLabel(context, listOf(29, MouseKeys.RIGHT)))
    }

    @Test fun theBuiltInLayoutHoldsTheFiveMouseKeysOnScreen() {
        val keys = MouseKeys.builtInLayout()
        assertEquals(setOf(MouseKeys.LEFT, MouseKeys.RIGHT, MouseKeys.MIDDLE, MouseKeys.WHEEL_UP, MouseKeys.WHEEL_DOWN), keys.flatMap { it.codes }.toSet())
        keys.forEach { assertTrue(it.x in 0f..1f && it.y in 0f..1f) }
        assertEquals(keys, ControllerPrefs.decodeScreenKeys(ControllerPrefs.encodeScreenKeys(keys)))
    }

    @Test fun exportLeavesMouseKeysOutLikeOtherUnknownCodes() {
        val text = exportScreenLayout(listOf(ScreenKey(listOf(17), 0.2f, 0.5f, 44)) + MouseKeys.builtInLayout(), 1000, 2f)
        val elements = org.json.JSONObject(text).getJSONArray("elements")
        assertEquals(1, elements.length())
        assertEquals("KEY_W", elements.getJSONObject(0).getJSONArray("bindings").getString(0))
    }

    @Test fun theEnvSwitchCanHideThem() {
        assertTrue(MouseKeys.enabled(null))
        assertFalse(MouseKeys.enabled("DD_SCREEN_MOUSE_KEYS=off"))
        assertTrue(MouseKeys.enabled("DD_SCREEN_MOUSE_KEYS=on"))
    }
}
