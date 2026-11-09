package com.droiddeck.launcher.core

import com.droiddeck.launcher.gpu.GpuInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerInfoTest {
    @Test fun driverRevisionReadsTheMaliRelease() {
        assertEquals("r44p1", DeviceCard.driverRevision("v1.r44p1-01eac0.abc123", "44.1.0"))
        assertEquals("r26p0", DeviceCard.driverRevision("Mali-G715 r26p0-00eac0", null))
    }

    @Test fun driverRevisionFallsBackToTheVersionThenToNothing() {
        assertEquals("512.530.0", DeviceCard.driverRevision("", "512.530.0"))
        assertEquals("", DeviceCard.driverRevision(null, null))
    }

    @Test fun verdictFollowsTheSupportAndTheBridge() {
        assertEquals(DeviceCard.Verdict.SUPPORTED, DeviceCard.verdict(GpuInfo.Support.TESTED, bridge = false, gpuKnown = true))
        assertEquals(DeviceCard.Verdict.SUPPORTED, DeviceCard.verdict(GpuInfo.Support.UNSUPPORTED, bridge = true, gpuKnown = true))
        assertEquals(DeviceCard.Verdict.LIMITED, DeviceCard.verdict(GpuInfo.Support.UNSUPPORTED, bridge = false, gpuKnown = true))
        assertEquals(DeviceCard.Verdict.LIMITED, DeviceCard.verdict(GpuInfo.Support.UNTESTED, bridge = false, gpuKnown = true))
        assertEquals(DeviceCard.Verdict.UNKNOWN, DeviceCard.verdict(GpuInfo.Support.UNSUPPORTED, bridge = true, gpuKnown = false))
    }

    @Test fun cardLineForAnR44p1Device() {
        val line = DeviceCard.line(DeviceCard.Info("Dimensity 9400 (mt6991)", "Mali-G925-Immortalis MC12", "r44p1", "1.3.289", "15"), "Supported")
        assertEquals("Dimensity 9400 (mt6991) · Mali-G925-Immortalis MC12 · driver r44p1 · Vulkan 1.3.289 · Android 15 · Supported", line)
    }

    @Test fun cardLineForAnR26p0Device() {
        val line = DeviceCard.line(DeviceCard.Info("Exynos 2200", "Mali-G715", "r26p0", "1.1.0", "14"), "Limited")
        assertTrue(line, line.contains("driver r26p0") && line.endsWith("Limited"))
    }

    @Test fun cardLineSkipsWhatIsUnknown() {
        val line = DeviceCard.line(DeviceCard.Info("", "", "", "", "13"), "Unknown")
        assertEquals("Android 13 · Unknown", line)
    }

    @Test fun settingsSummaryHasEachFieldAndSkipsEmptyOnes() {
        val text = SettingsSummary.text(listOf("Device" to "Samsung SM-X926B", "MaliBridge" to "0.5.5.4", "Proton" to null, "FEX" to ""))
        assertEquals("Device: Samsung SM-X926B\nMaliBridge: 0.5.5.4\n", text)
    }

    @Test fun settingsSummaryHidesTokensAndLinksWithQueries() {
        val text = SettingsSummary.text(listOf(
            "Note" to "abcdefghijklmnopqrstuvwxyz0123456789ABCD",
            "Link" to "https://example.com/a?key=secret",
        ))
        assertFalse(text, text.contains("abcdefghijklmnop"))
        assertFalse(text, text.contains("secret"))
    }

    @Test fun reportLinkCarriesDeviceAndVersionEncoded() {
        val url = ReportLink.url("Samsung SM-X926B", "Mali-G720", "0.5.5.4")
        assertEquals("https://malibridge.com/submit?device=Samsung%20SM-X926B&gpu=Mali-G720&version=0.5.5.4", url)
    }

    @Test fun reportLinkDropsEmptyParts() {
        assertEquals("https://malibridge.com/submit?version=1", ReportLink.url("", " ", "1"))
        assertEquals("https://malibridge.com/submit", ReportLink.url("", "", ""))
    }
}
