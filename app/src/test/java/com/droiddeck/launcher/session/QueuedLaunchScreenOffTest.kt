package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screen-off loop (Tab S11 Ultra, 11-03): a game launch queued while the screen was off kept waking the
 * paused Steam session every 250 ms flush tick, and each wake re-armed the 4 s native sleep, so the session
 * paused and resumed every ~5 s. The model below replays that loop with the service's own decision.
 */
class QueuedLaunchScreenOffTest {
    private class Model {
        var suspended = false
        var surfaceVisible = true
        var pauses = 0
        var resumes = 0
        private var sleepMs = -1L

        /** Hidden: the session pauses once its 4 s native sleep ran out. Visible: it resumes once. */
        private fun policy() {
            if (!surfaceVisible && !suspended && sleepMs < 0) sleepMs = 4000
            if (surfaceVisible && suspended) { suspended = false; resumes++ }
            if (surfaceVisible) sleepMs = -1
        }

        /** A pending launch that never got a frame stays queued; the flush runs every 250 ms. */
        fun run(ms: Long, launchQueued: Boolean) {
            var t = 0L
            while (t < ms) {
                t += 250
                if (sleepMs >= 0) {
                    sleepMs -= 250
                    if (sleepMs < 0) { suspended = true; pauses++ }
                }
                if (launchQueued && SessionService.queuedLaunchWakesSession(suspended, surfaceVisible)) {
                    suspended = false
                    resumes++
                    sleepMs = -1
                }
                policy()
            }
        }
    }

    @Test fun screenOffWithAQueuedLaunchPausesOnceAndNeverResumes() {
        val m = Model()
        m.surfaceVisible = false
        m.run(60_000, launchQueued = true)
        assertEquals(1, m.pauses)
        assertEquals(0, m.resumes)
        assertTrue(m.suspended)
    }

    @Test fun screenOffWithoutAQueuedLaunchPausesOnce() {
        val m = Model()
        m.surfaceVisible = false
        m.run(60_000, launchQueued = false)
        assertEquals(1, m.pauses)
        assertEquals(0, m.resumes)
    }

    @Test fun comingBackResumesExactlyOnce() {
        val m = Model()
        m.surfaceVisible = false
        m.run(60_000, launchQueued = true)
        m.surfaceVisible = true
        m.run(10_000, launchQueued = true)
        assertEquals(1, m.pauses)
        assertEquals(1, m.resumes)
        assertFalse(m.suspended)
    }

    @Test fun aLaunchFromTheVisibleAppStillWakesAPausedSession() {
        assertTrue(SessionService.queuedLaunchWakesSession(suspended = true, surfaceVisible = true))
        assertFalse(SessionService.queuedLaunchWakesSession(suspended = false, surfaceVisible = true))
        assertFalse(SessionService.queuedLaunchWakesSession(suspended = false, surfaceVisible = false))
    }

    @Test fun aHiddenPausedSessionIsNotWoken() {
        assertFalse(SessionService.queuedLaunchWakesSession(suspended = true, surfaceVisible = false))
    }
}
