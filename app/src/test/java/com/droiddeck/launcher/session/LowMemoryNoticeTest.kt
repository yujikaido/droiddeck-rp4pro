package com.droiddeck.launcher.session

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/* RIVALS RV-8 / ROADMAP low-memory: players whose game ended because Android's low-memory killer
 * took the app (a player log SkyrimSE "LMK in ended-without-teardown") saw nothing at the next start and
 * reported a crash. The notice comes only for a kill that ended a running session (an abandoned
 * session folder), once per kill, and not days later. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LowMemoryNoticeTest {
    private val t = 1_761_400_000_000L
    private fun exit(reason: Int, time: Long = t, process: String = "com.droiddeck.launcher") =
        SessionArtifacts.Exit(time, 4242, process, reason, 9, RunningAppProcessInfo.IMPORTANCE_FOREGROUND, 0L, 0L, null)

    @Test fun theMainProcessKilledForMemoryGivesItsTime() {
        assertEquals(t, LowMemoryNotice.killTime(listOf(exit(ApplicationExitInfo.REASON_LOW_MEMORY))))
    }

    @Test fun helperProcessesAreSkippedForTheMainOne() {
        val exits = listOf(
            exit(ApplicationExitInfo.REASON_LOW_MEMORY, time = t + 5, process = "com.droiddeck.launcher:xboxcloud"),
            exit(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(t, LowMemoryNotice.killTime(exits))
        val helperOnly = listOf(
            exit(ApplicationExitInfo.REASON_LOW_MEMORY, process = "com.droiddeck.launcher:agent"),
            exit(ApplicationExitInfo.REASON_CRASH_NATIVE, time = t - 5))
        assertNull(LowMemoryNotice.killTime(helperOnly))
    }

    @Test fun otherEndingsGiveNoNotice() {
        for (r in listOf(ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_USER_REQUESTED,
                         ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_EXIT_SELF)) {
            assertNull("reason $r", LowMemoryNotice.killTime(listOf(exit(r))))
        }
        assertNull(LowMemoryNotice.killTime(emptyList()))
    }

    @Test fun shownOnceAndOnlyWhileRecent() {
        val day = 24L * 3600 * 1000
        assertTrue(LowMemoryNotice.shouldShow(pending = t, lastShown = 0L, now = t + 60_000))
        assertFalse("already shown", LowMemoryNotice.shouldShow(pending = t, lastShown = t, now = t + 60_000))
        assertFalse("nothing pending", LowMemoryNotice.shouldShow(pending = 0L, lastShown = 0L, now = t))
        assertFalse("a day old", LowMemoryNotice.shouldShow(pending = t, lastShown = 0L, now = t + day + 1))
        assertFalse("clock went back", LowMemoryNotice.shouldShow(pending = t, lastShown = 0L, now = t - day - 1))
    }
}
