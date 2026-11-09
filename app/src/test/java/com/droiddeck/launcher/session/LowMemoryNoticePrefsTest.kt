package com.droiddeck.launcher.session

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/* The whole path: finishing an abandoned session records the kill, the launcher shows it once. An
 * idle app killed in the background (no session folder) records nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LowMemoryNoticePrefsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun exit(reason: Int, time: Long) =
        SessionArtifacts.Exit(time, 4242, "com.droiddeck.launcher", reason, 9, RunningAppProcessInfo.IMPORTANCE_FOREGROUND, 0L, 0L, null)

    @Test fun aRecordedKillIsShownOnce() {
        val now = System.currentTimeMillis()
        LowMemoryNotice.record(context, listOf(exit(ApplicationExitInfo.REASON_LOW_MEMORY, now - 1000)))
        assertTrue(LowMemoryNotice.take(context))
        assertFalse(LowMemoryNotice.take(context))
        LowMemoryNotice.record(context, listOf(exit(ApplicationExitInfo.REASON_LOW_MEMORY, now - 1000)))
        assertFalse("the same kill again", LowMemoryNotice.take(context))
    }

    @Test fun nothingRecordedNothingShown() {
        LowMemoryNotice.record(context, listOf(exit(ApplicationExitInfo.REASON_CRASH_NATIVE, System.currentTimeMillis())))
        assertFalse(LowMemoryNotice.take(context))
    }
}
