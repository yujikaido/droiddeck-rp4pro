package com.droiddeck.launcher.session

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TolerantPrefsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val raw = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        SessionState.mode = SessionService.MODE_STEAM
        raw.edit().clear().commit()
    }

    @Test
    fun theHudSettingStoredAsTextIsReadAndStoredAgainAsOnOff() {
        SessionState.mode = SessionService.MODE_STEAM
        raw.edit().putString("hud", "true").commit()
        assertTrue(SessionPrefs.hudEnabled(context))
        assertEquals(true, raw.all["hud"])
        raw.edit().putString("hud", "false").commit()
        assertFalse(SessionPrefs.hudEnabled(context))
        assertEquals(false, raw.all["hud"])
    }

    @Test
    fun textThatIsNotOnOrOffFallsBackToTheDefaultAndIsRemoved() {
        SessionState.mode = SessionService.MODE_STEAM
        raw.edit().putString("hud", "maybe").commit()
        // The HUD is off by default.
        assertFalse(SessionPrefs.hudEnabled(context))
        assertFalse(raw.contains("hud"))
    }

    @Test
    fun numbersAndTextAreTakenWhereTheOtherIsKept() {
        raw.edit().putString("upscaleSharpness", "40").putInt("theme", 3).putFloat("orientation", 1.5f).commit()
        assertEquals(40, SessionPrefs.upscaleSharpness(context))
        assertEquals(40, raw.all["upscaleSharpness"])
        assertEquals("3", SessionPrefs.theme(context))
        assertEquals("3", raw.all["theme"])
        // A fraction is no orientation: the default, and the value is dropped.
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, SessionPrefs.orientation(context))
        assertFalse(raw.contains("orientation"))
    }

    @Test
    fun rightlyTypedValuesReadAsBefore() {
        SessionPrefs.setSyncFallback(context, false)
        assertFalse(SessionPrefs.syncFallback(context))
        SessionPrefs.setUpscaleSharpness(context, 10)
        assertEquals(10, SessionPrefs.upscaleSharpness(context))
    }
}
