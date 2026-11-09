package com.droiddeck.launcher.core

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReportLinkIntentTest {
    @Test fun theReportOpensTheBrowserOnTheSubmitPage() {
        val intent = ReportLink.intent(RuntimeEnvironment.getApplication())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https", intent.data?.scheme)
        assertEquals("malibridge.com", intent.data?.host)
        assertEquals("/submit", intent.data?.path)
        assertTrue(intent.data?.getQueryParameter("version")?.isNotEmpty() == true)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }
}
