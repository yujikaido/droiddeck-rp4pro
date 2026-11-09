package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ledger row 98 (baa4ae3f0): drawing each game frame the moment it arrives (renderOnCommit) cost Balatro 9 fps
 * on the Galaxy Tab S11 Ultra (96 instead of 105, p90 17.0 instead of 14.7 ms) with no shorter time to the
 * screen, so the compositor draws on the screen tick by default. The hidden pref renderOnCommit=true turns it back on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RenderOnCommitDefaultTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE)

    @Test fun theCompositorDrawsOnTheScreenTickByDefault() {
        prefs.edit().clear().commit()
        assertFalse(SessionPrefs.renderOnCommit(RuntimeEnvironment.getApplication()))
    }

    @Test fun theHiddenPrefTurnsDrawingOnArrivalBackOn() {
        prefs.edit().clear().putBoolean("renderOnCommit", true).commit()
        assertTrue(SessionPrefs.renderOnCommit(RuntimeEnvironment.getApplication()))
        prefs.edit().putBoolean("renderOnCommit", false).commit()
        assertFalse(SessionPrefs.renderOnCommit(RuntimeEnvironment.getApplication()))
    }

    @Test fun aWrongTypedValueFallsBackToTheDefault() {
        prefs.edit().clear().putString("renderOnCommit", "banana").commit()
        assertFalse(SessionPrefs.renderOnCommit(RuntimeEnvironment.getApplication()))
    }
}
