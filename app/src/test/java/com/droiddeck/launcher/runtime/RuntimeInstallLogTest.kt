package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RuntimeInstallLogTest {
    @Test fun aFailedTryAndAFailedInstallReachTheSessionEvents() {
        val dir = Files.createTempDirectory("install-diag").toFile()
        SessionState.logDirectory = dir
        SessionState.eventsFile = File(dir, "events.jsonl")
        try {
            RuntimeInstallLog.begin()
            RuntimeInstallLog.stage("runtime-archive upstream")
            val a = Downloader.Attempt("https://github.com/x/rt.tar.zst", "https://github.com/x/rt.tar.zst")
            a.httpStatus = 503; a.bytes = 100; a.total = 1000; a.outcome = "RETRY"
            Downloader.attemptSink!!.onAttempt(a)
            RuntimeInstallLog.failure("install", IOException("Cannot download proot"))
        } finally {
            RuntimeInstallLog.end()
            SessionState.logDirectory = null
            SessionState.eventsFile = null
        }
        val text = File(dir, "events.jsonl").readText()
        assertTrue(text, text.contains("runtime.install.diag") && text.contains("host github.com") && text.contains("HTTP 503"))
        assertTrue(text, text.contains("received 100 of 1000 bytes") && text.contains("runtime-archive upstream"))
        assertTrue(text, text.contains("IOException: Cannot download proot") && text.contains("free storage"))
        dir.deleteRecursively()
    }
}
