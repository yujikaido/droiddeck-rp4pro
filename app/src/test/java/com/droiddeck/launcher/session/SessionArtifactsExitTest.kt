package com.droiddeck.launcher.session

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionArtifactsExitTest {
    private fun exit(reason: Int, status: Int = 9, importance: Int = RunningAppProcessInfo.IMPORTANCE_CACHED,
                     process: String = "com.droiddeck.launcher", description: String? = null) =
        SessionArtifacts.Exit(1_761_400_000_000L, 4242, process, reason, status, importance, 3_145_728L, 4_194_304L, description)

    @Test fun noRecordsAddNothing() {
        assertEquals("", SessionArtifacts.exitText(emptyList()))
    }

    @Test fun aLowMemoryKillSaysSoWithItsSizes() {
        val text = SessionArtifacts.exitText(listOf(exit(ApplicationExitInfo.REASON_LOW_MEMORY, description = "lmk")))
        assertTrue(text, text.contains("pid 4242: low memory (Android's low-memory killer), signal 9, in the background (cached)"))
        assertTrue(text, text.contains("pss 3072 MB, rss 4096 MB - lmk"))
    }

    @Test fun anExitGivesItsStatusAndASwipeIsTheUsers() {
        val text = SessionArtifacts.exitText(listOf(
            exit(ApplicationExitInfo.REASON_EXIT_SELF, status = 0, importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND),
            exit(ApplicationExitInfo.REASON_USER_REQUESTED, status = 0, process = "com.droiddeck.launcher:agent")))
        assertTrue(text, text.contains("exited itself, exit status 0, in the foreground"))
        assertTrue(text, text.contains("pid 4242 (agent): stopped by the user"))
        assertFalse(text, text.contains("com.droiddeck"))
        assertEquals(2, text.lines().count { it.startsWith("  ") })
    }

    @Test fun unknownReasonsShowTheirNumber() {
        assertEquals("reason 99", SessionArtifacts.exitReason(99))
    }
}
