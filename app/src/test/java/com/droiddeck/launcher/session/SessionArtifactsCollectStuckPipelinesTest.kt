package com.droiddeck.launcher.session

import com.droiddeck.launcher.gpu.VkBridgeComponent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ledger row 24 (66c7c220): Share logs / Send logs carry the bridge's list of pipelines its compile watchdog
 * stopped waiting for (vkbridge-stuck-pipelines.txt), which says whose shaders it gave up on. This is the
 * normal end of a session (collect); SessionArtifactsAbandonedTest covers a session Android killed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionArtifactsCollectStuckPipelinesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()

    private fun list() = File(VkBridgeComponent.cacheDir(app), "stuck-pipelines")

    // A wayland.log already in the folder keeps collect() from reading the native compositor's log file.
    private fun session(name: String) = tmp.newFolder(name).also { File(it, "wayland.log").writeText("test compositor log\n") }

    @Test fun theListGoesIntoTheCollectedSessionFolder() {
        val folder = session("2026-10-29-01-steam")
        list().writeText("v3\n0123456789abcdef m\n")
        SessionArtifacts.collect(app, folder, "test")
        assertEquals("v3\n0123456789abcdef m\n", File(folder, "vkbridge-stuck-pipelines.txt").readText())
    }

    @Test fun aListOverSixtyFourKilobytesIsOnlyNamed() {
        val folder = session("2026-10-29-02-steam")
        list().writeText("x".repeat(65 * 1024))
        SessionArtifacts.collect(app, folder, "test")
        val text = File(folder, "vkbridge-stuck-pipelines.txt").readText()
        assertTrue(text, text.contains("not copied"))
        assertTrue(text.length < 200)
    }

    @Test fun noListMeansNoFile() {
        val folder = session("2026-10-29-03-steam")
        list().delete()
        SessionArtifacts.collect(app, folder, "test")
        assertFalse(File(folder, "vkbridge-stuck-pipelines.txt").exists())
    }
}
