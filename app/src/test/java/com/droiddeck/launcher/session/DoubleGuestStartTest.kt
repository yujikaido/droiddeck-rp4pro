package com.droiddeck.launcher.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tab S11, 0.5.5.3-test12: two Linux guests ran in one app process (app.log:211 "session pid 31068"
 * for folder 09, app.log:239 "session pid 31244" for folder 06, no stop between). The folder-06 start thread
 * belonged to a SessionService instance that had stopped and been destroyed (stopSelf after a stop); Android
 * made a new instance for the next start. The start threads check `gen == sessionGen && SessionState.running`
 * (runSession's stillStarting and the checks before the guest starts). sessionGen was a field of each instance,
 * so the new instance's count restarted and the old thread compared its gen with its own dead instance's
 * unchanged count, while SessionState.running (one flag per process) was true again for the new session.
 *
 * The test reads the service's real generation field (an instance field before the fix, one per process after)
 * and steps it exactly like onStartCommand's `++sessionGen`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DoubleGuestStartTest {
    private val field = SessionService::class.java.getDeclaredField("sessionGen").apply { isAccessible = true }
    private var wasRunning = false
    private var savedGen = 0

    @Before fun remember() {
        wasRunning = SessionState.running
        SessionState.running = false
        savedGen = field.getInt(Robolectric.buildService(SessionService::class.java).get())
    }

    @After fun restore() {
        SessionState.running = wasRunning
    }

    /** What onStartCommand does when it starts a session: the next generation, and the running flag. */
    private fun start(service: SessionService): Int {
        val gen = field.getInt(service) + 1
        field.setInt(service, gen)
        SessionState.running = true
        return gen
    }

    /** runSession's own test before it starts parts and the guest (SessionService.kt stillStarting). */
    private fun stillStarting(service: SessionService, gen: Int) = gen == field.getInt(service) && SessionState.running

    @Test fun aStartThreadOfADestroyedServiceIsNotCurrentOnceANewInstanceStarts() {
        val first = Robolectric.buildService(SessionService::class.java).create()
        val stale = start(first.get())
        SessionState.running = false // the player's stop; the start thread is still copying the SD library
        first.destroy() // finishSessionStop -> stopSelf

        val second = Robolectric.buildService(SessionService::class.java).create()
        val current = start(second.get())

        assertTrue("the new session's own start goes ahead", stillStarting(second.get(), current))
        assertFalse("the destroyed instance's start thread must not start a second guest", stillStarting(first.get(), stale))
        SessionState.running = false
        second.destroy()
    }

    @Test fun aNewerStartInTheSameInstanceStillSupersedesTheOlderOne() {
        val service = Robolectric.buildService(SessionService::class.java).create()
        val older = start(service.get())
        SessionState.running = false
        val newer = start(service.get())
        assertFalse(stillStarting(service.get(), older))
        assertTrue(stillStarting(service.get(), newer))
        SessionState.running = false
        service.destroy()
    }

    @Test fun aSingleStartStaysCurrentUntilItsStop() {
        val service = Robolectric.buildService(SessionService::class.java).create()
        val gen = start(service.get())
        assertTrue(stillStarting(service.get(), gen))
        SessionState.running = false
        assertFalse(stillStarting(service.get(), gen))
        service.destroy()
    }

    @Test fun anEarlierStartThatGivesUpLeavesTheCurrentSessionRunning() {
        val first = Robolectric.buildService(SessionService::class.java).create()
        val stale = start(first.get())
        SessionState.running = false
        first.destroy()
        val second = Robolectric.buildService(SessionService::class.java).create()
        start(second.get())

        // e.g. "proot is missing" reached late by the old thread: before, its stopSession ended the new session
        SessionService::class.java.getDeclaredMethod("stopIfCurrent", Int::class.java, Int::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(first.get(), stale, -1, "proot is missing")
        assertTrue(SessionState.running)
        assertEquals(stale + 1, field.getInt(second.get()))
        SessionState.running = false
        second.destroy()
    }
}
