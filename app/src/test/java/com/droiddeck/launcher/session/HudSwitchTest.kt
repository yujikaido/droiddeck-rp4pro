package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One performance-HUD switch: the pre-launch switch, the side menu and the HUD itself all read and write "hud". */
@RunWith(RobolectricTestRunner::class)
class HudSwitchTest {
    private lateinit var context: Context
    private val raw get() = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        raw.edit().clear().commit()
    }

    @After fun tearDown() {
        SessionState.mode = SessionService.MODE_STEAM
        raw.edit().clear().commit()
    }

    @Test fun bothKeysAbsentMeansOffAndNothingIsWritten() {
        assertFalse(SessionPrefs.hudEnabled(context))
        assertFalse(raw.contains("hud"))
        assertFalse(raw.contains("hudDeck"))
    }

    @Test fun theOldSteamDeckKeyOnBecomesTheNewKeyOn() {
        raw.edit().putBoolean("hudDeck", true).commit()
        assertTrue(SessionPrefs.hudEnabled(context))
        assertEquals(true, raw.all["hud"])
        assertFalse(raw.contains("hudDeck"))
    }

    @Test fun theOldSteamDeckKeyOffLeavesTheNewKeyAsItWas() {
        raw.edit().putBoolean("hudDeck", false).putBoolean("hud", true).commit()
        assertTrue(SessionPrefs.hudEnabled(context))
        raw.edit().putBoolean("hudDeck", false).putBoolean("hud", false).commit()
        assertFalse(SessionPrefs.hudEnabled(context))
        assertFalse(raw.contains("hudDeck"))
    }

    @Test fun theOldGlobalKeyOnStaysOnInEverySessionMode() {
        raw.edit().putBoolean("hud", true).commit()
        for (mode in listOf(SessionService.MODE_STEAM, "desktop")) {
            SessionState.mode = mode
            assertTrue(mode, SessionPrefs.hudEnabled(context))
        }
    }

    @Test fun writingMigratesFirstSoTheLegacyKeyCannotComeBack() {
        raw.edit().putBoolean("hudDeck", true).commit()
        SessionPrefs.setHudEnabled(context, false)
        assertFalse(SessionPrefs.hudEnabled(context))
        assertFalse(raw.contains("hudDeck"))
    }

    @Test fun thePreLaunchSwitchWritesWhatTheHudReadsInSteamDeckAndOtherSessions() {
        for (mode in listOf(SessionService.MODE_STEAM, "desktop")) {
            SessionState.mode = mode
            SessionPrefs.setHudEnabled(context, true) // what the pre-launch switch's onHud calls
            assertEquals(true, raw.all["hud"])
            assertTrue(SessionPrefs.hudEnabled(context)) // what DroidDeckHudHost and the side menu read
            SessionPrefs.setHudEnabled(context, false)
            assertFalse(SessionPrefs.hudEnabled(context))
        }
    }

    @Test fun aSteamDeckSessionReadsTheUnifiedKeyWhateverWasSetInAnotherMode() {
        SessionState.mode = "desktop"
        SessionPrefs.setHudEnabled(context, true)
        SessionState.mode = SessionService.MODE_STEAM
        assertTrue(SessionPrefs.hudEnabled(context))
    }

    @Test fun thePreLaunchScreenIsWiredToTheSamePrefTheSideMenuUses() {
        val src = File("src/main/java/com/droiddeck/launcher")
        val front = File(src, "FrontEndActivity.kt").readText()
        val session = File(src, "SessionActivity.kt").readText()
        assertTrue(front.contains("onHud = { on -> SessionPrefs.setHudEnabled(this, on)"))
        assertTrue(session.contains("SessionPrefs.setHudEnabled(this@SessionActivity, on)"))
        assertTrue(File(src, "ui/ModeSettingsDialog.kt").readText().contains("onChange = a.onHud"))
    }
}
