package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionArtifactsPruneTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun folder(name: String, complete: Boolean = true): File =
        File(LinuxRuntime.logDir(app), name).apply {
            mkdirs()
            File(this, "session.log").writeText("x\n")
            if (complete) File(this, SessionArtifacts.COMPLETE_MARKER).writeText("collected\n")
        }

    @Test fun aSessionEndKeepsTheNewestFoldersAndOnesStillBeingWritten() {
        val yesterday = (1..10).map { folder("2026-10-25-%02d-steam".format(it)) }
        val today = (1..40).map { folder("2026-10-26-%02d-steam".format(it)) }
        val unfinished = folder("2026-10-25-11-desktop", complete = false)

        SessionArtifacts.pruneQuietly(app)

        yesterday.forEach { assertFalse(it.name, it.exists()) }
        today.take(10).forEach { assertFalse(it.name, it.exists()) }
        today.drop(10).forEach { assertTrue(it.name, it.exists()) }
        assertTrue(unfinished.exists())
        assertEquals(SessionPaths.KEEP_SESSIONS + 1, SessionPaths.sessionFolders(app).size)
    }

    @Test fun nothingGoesWhileThereAreFewerThanTheLimit() {
        val all = (1..5).map { folder("2026-10-26-%02d-steam".format(it)) }
        SessionArtifacts.pruneQuietly(app)
        all.forEach { assertTrue(it.name, it.exists()) }
    }
}
