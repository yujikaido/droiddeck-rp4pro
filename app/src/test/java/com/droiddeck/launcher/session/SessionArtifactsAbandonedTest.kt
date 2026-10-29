package com.droiddeck.launcher.session

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import com.droiddeck.launcher.gpu.VkBridgeComponent
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionArtifactsAbandonedTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun theNewestAbandonedSessionGetsTheStuckPipelinesAndHowAndroidEndedTheApp() {
        val logs = LinuxRuntime.logDir(app)
        val older = File(logs, "2026-10-25-01-steam").apply { mkdirs() }
        val newest = File(logs, "2026-10-25-02-steam").apply { mkdirs() }
        File(VkBridgeComponent.cacheDir(app), "stuck-pipelines").writeText("v3\n0123456789abcdef m\n")
        shadowOf(app.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder().setPid(4242).setProcessName(app.packageName)
                .setReason(ApplicationExitInfo.REASON_LOW_MEMORY).setStatus(9)
                .setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
                .setPss(2L * 1024 * 1024).setRss(3L * 1024 * 1024).setTimestamp(1_761_400_000_000L).build())

        SessionArtifacts.finishAbandoned(app)

        assertEquals("v3\n0123456789abcdef m\n", File(newest, "vkbridge-stuck-pipelines.txt").readText())
        assertFalse(File(older, "vkbridge-stuck-pipelines.txt").exists())
        val ended = File(newest, "ended-without-teardown.txt").readText()
        assertTrue(ended, ended.contains("pid 4242: low memory (Android's low-memory killer), signal 9, in the background (cached), pss 2048 MB, rss 3072 MB"))
        assertFalse(File(older, "ended-without-teardown.txt").readText().contains("pid 4242"))
        assertTrue(File(newest, "events.jsonl").readText().contains("low memory"))
    }

    @Test fun anOversizedListIsOnlyNamed() {
        val newest = File(LinuxRuntime.logDir(app), "2026-10-25-03-steam").apply { mkdirs() }
        File(VkBridgeComponent.cacheDir(app), "stuck-pipelines").writeText("x".repeat(65 * 1024))
        SessionArtifacts.finishAbandoned(app)
        assertTrue(File(newest, "vkbridge-stuck-pipelines.txt").readText().contains("not copied"))
    }
}
