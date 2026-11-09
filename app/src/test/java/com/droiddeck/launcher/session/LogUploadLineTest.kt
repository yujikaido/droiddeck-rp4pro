package com.droiddeck.launcher.session

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class LogUploadLineTest {
    @Test fun successLineHasStatusAndCode() {
        assertEquals("send logs: HTTP 201, code ML-7F3K", LogUpload.successLine(201, "ML-7F3K"))
    }

    @Test fun successWithoutACodeSaysSo() {
        assertEquals("send logs: HTTP 201, code none in the reply", LogUpload.successLine(201, null))
    }

    @Test fun refusedShowsTheStatus() {
        assertEquals("send logs: HTTP 413", LogUpload.failureLine(LogUpload.Refused(413)))
    }

    @Test fun otherFailuresShowTheClassAndMessage() {
        assertEquals("send logs: error IOException: timeout", LogUpload.failureLine(IOException("timeout")))
    }

    @Test fun theCodeFormatMatchesWhatTheLineReports() {
        assertEquals("ML-7F3K", LogUpload.codeIn("""{"code":"ML-7F3K"}"""))
    }
}
