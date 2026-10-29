package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProcessLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aProcessThatDiesAtOnceLeavesItsErrorAndStatus() {
        val file = File(tmp.root, "proot.log")
        val log = ProcessLog(file)
        val done = CountDownLatch(1)
        // The pid comes back -1 on a desktop JVM (its Process hides the field); the process still runs.
        HostProcess.start("/bin/sh -c echo\\ boom\\ >&2;\\ exit\\ 1", null, tmp.root, { status ->
            log.close("== exited with status $status")
            done.countDown()
        }, log)
        assertTrue(done.await(10, TimeUnit.SECONDS))
        val text = file.readText()
        assertTrue(text, text.contains("boom"))
        assertTrue(text, text.contains("== exited with status 1"))
        assertEquals(listOf("boom"), log.recent())
    }

    @Test fun pastTheCapTheHeadAndTailStayAndTheMiddleIsCounted() {
        val file = File(tmp.root, "proot.log")
        val log = ProcessLog(file, headBytes = 100, tailBytes = 100)
        for (i in 0 until 1000) log.accept("line %04d".format(i))
        log.close("end")
        val lines = file.readLines()
        assertEquals("line 0000", lines.first())
        assertEquals("end", lines.last())
        assertEquals("line 0999", lines[lines.size - 2])
        assertTrue(lines.any { it.matches(Regex("\\[\\d+ lines left out]")) })
        assertFalse(lines.contains("line 0500"))
        assertTrue(file.length() < 400)
        assertEquals(ProcessLog.RECENT_LINES, log.recent().size)
    }
}
