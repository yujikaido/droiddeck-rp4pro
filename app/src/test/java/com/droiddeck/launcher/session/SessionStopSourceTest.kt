package com.droiddeck.launcher.session

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Ledger rows 44 and 47 (5a195dd21): events.jsonl says why a session ended. Some phones (vivo) keep the app's
 * logcat out of app.log, so the stop reason in events.jsonl is the only record. SessionService.stop carries the
 * source of the stop to the service in the intent; the notification's Stop sends none and is named by default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionStopSourceTest {
    private var wasRunning = false
    private var hadStopRequested = false

    @Before fun remember() {
        wasRunning = SessionState.running
        hadStopRequested = SessionState.stopRequested
        SessionState.running = true
        SessionState.stopRequested = false
    }

    @After fun restore() {
        SessionState.running = wasRunning
        SessionState.stopRequested = hadStopRequested
    }

    private fun stopIntent(block: (android.app.Application) -> Unit): Intent {
        val app = RuntimeEnvironment.getApplication()
        block(app)
        return shadowOf(app).nextStartedService
    }

    @Test fun theMenusStopNamesItselfInTheIntent() {
        val intent = stopIntent { SessionService.stop(it, SessionService.STOP_FROM_MENU) }
        assertEquals(SessionService.ACTION_STOP, intent.action)
        assertEquals("Stop in the session menu", intent.getStringExtra(SessionService.EXTRA_STOP_SOURCE))
    }

    @Test fun theAgentsStopNamesItself() {
        val intent = stopIntent { SessionService.stop(it, "the agent's stop command") }
        assertEquals("the agent's stop command", intent.getStringExtra(SessionService.EXTRA_STOP_SOURCE))
    }

    @Test fun aStopWithNoSourceIsNamedAsTheApp() {
        val intent = stopIntent { SessionService.stop(it) }
        assertEquals("the app", intent.getStringExtra(SessionService.EXTRA_STOP_SOURCE))
    }

    @Test fun theNotificationsStopHasItsOwnName() {
        assertEquals("the notification's Stop", SessionService.STOP_NOTIFICATION)
    }

    @Test fun aStopBeforeTheSessionRunsIsRememberedNotSent() {
        SessionState.running = false
        val app = RuntimeEnvironment.getApplication()
        SessionService.stop(app, "starting another session")
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(SessionState.stopRequested)
    }
}
