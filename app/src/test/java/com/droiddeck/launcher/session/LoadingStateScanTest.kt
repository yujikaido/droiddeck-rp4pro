package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingStateScanTest {
    private fun shown(text: String): Int? {
        val s = LoadingState.scan(text)
        return if (s.downloadAt > s.stepAt && s.downloadPercent >= 0) s.downloadPercent else null
    }

    private val start = "== STEP 08:02:14 starting the Steam client\n"

    @Test fun chineseClientUpdateShowsItsPercentage() {
        // Steam in Simplified Chinese, a 60 MB client update.
        val log = start +
            "[2026-10-26 16:02:15] Set percent complete: 0\n" +
            "[2026-10-26 16:02:15] Set percent complete: -1\n" +
            "[2026-10-26 16:02:16] 正在下载更新 (已下载 988，共 60708 KB)...\n" +
            "[2026-10-26 16:02:16] Set status message: 正在下载更新 (已下载 988，共 60708 KB)...\n" +
            "[2026-10-26 16:02:17] Set percent complete: 1\n" +
            "[2026-10-26 16:02:17] 正在下载更新 (已下载 27212，共 60708 KB)...\n" +
            "[2026-10-26 16:02:17] Set percent complete: 44\n"
        assertEquals(44, shown(log))
    }

    @Test fun percentageStaysWhileTheUpdateUnpacks() {
        val log = start +
            "[2026-10-26 16:02:30] Set percent complete: 100\n" +
            "[2026-10-26 16:02:30] Set status message: 下载完成。\n" +
            "[2026-10-26 16:02:30] Set percent complete: -1\n" +
            "[2026-10-26 16:02:30] Set status message: 正在展开安装包...\n"
        assertEquals(100, shown(log))
    }

    @Test fun ordinaryStartWithoutAnUpdateShowsNoDownload() {
        val log = start +
            "[2026-10-26 06:59:25] Set percent complete: 0\n" +
            "[2026-10-26 06:59:26] Set percent complete: -1\n" +
            "[2026-10-26 06:59:26] Set percent complete: -201\n"
        assertEquals(null, shown(log))
        assertEquals("starting the Steam client", LoadingState.scan(log).stepText)
    }

    @Test fun englishLineStillCounts() {
        val log = start + "[2026-10-26 06:58:20] Downloading update (988 of 60708 KB)...\n"
        assertEquals(1, shown(log))
    }

    @Test fun aNewStepEndsTheUpdatesProgress() {
        val log = start +
            "[2026-10-26 16:02:17] Set percent complete: 44\n" +
            "== STEP 08:02:59 starting the Steam client\n" +
            "[2026-10-26 16:02:59] Set percent complete: 0\n"
        assertEquals(null, shown(log))
    }

    @Test fun clientInstallStepIsStillRead() {
        val s = LoadingState.scan("== STEP 05:00:00 downloading Steam: bins_linuxarm64 (3/12)\n")
        assertEquals("bins_linuxarm64", s.clientDownload)
        assertEquals(25, s.clientPercent)
        assertTrue(s.clientDownloadAt == s.stepAt)
    }

    // rc6: the Steam notification said "Steam is downloading its update" for ~100 s on every start.

    @Test fun theNotificationsUpdateTextNeedsTheUpdatesProgress() {
        val ordinary = start +
            "[2026-10-26 06:59:25] Set percent complete: 0\n" +
            "[2026-10-26 06:59:26] Set percent complete: -1\n" +
            "[2026-10-26 06:59:26] Set percent complete: -201\n"
        assertFalse(LoadingState.updateDownloadSeen(ordinary))
        assertFalse(LoadingState.updateDownloadSeen(start))
        assertTrue(LoadingState.updateDownloadSeen(start + "[2026-10-26 16:02:17] Set percent complete: 44\n"))
        assertTrue(LoadingState.updateDownloadSeen(start + "[2026-10-26 06:58:20] Downloading update (988 of 60708 KB)...\n"))
        assertFalse(SteamDownloadMonitor.clientUpdateShown(updating = true, active = true, progressSeen = false))
        assertTrue(SteamDownloadMonitor.clientUpdateShown(updating = true, active = true, progressSeen = true))
        assertFalse(SteamDownloadMonitor.clientUpdateShown(updating = true, active = false, progressSeen = true))
        assertFalse(SteamDownloadMonitor.clientUpdateShown(updating = false, active = true, progressSeen = true))
    }

    @Test fun protonInstallStepIsFoundForTheTranslatedLine() {
        val step = LoadingState.scan("== STEP 05:00:00 installing Proton 'proton-10-arm64' (large download, several minutes)\n").stepText!!
        assertEquals("proton-10-arm64", LoadingState.INSTALLING_PROTON.find(step)?.groupValues?.get(1))
        assertEquals(null, LoadingState.INSTALLING_PROTON.find("installed Proton 'proton-10-arm64'"))
    }

    // The first download: the install step counts the script's own packages, then the client's updater counts its own.

    @Test fun theUpdateShowsMegabytesInEveryLanguage() {
        val english = start + "[2026-11-03 10:00:00] Downloading update (518727 of 670832 KB)...\n"
        assertEquals("518 / 670 MB", LoadingState.scan(english).downloadMb)
        val chinese = start + "[2026-10-26 16:02:17] 正在下载更新 (已下载 27212，共 60708 KB)...\n"
        assertEquals("27 / 60 MB", LoadingState.scan(chinese).downloadMb)
        assertEquals(44, shown(chinese))
        val grouped = start + "[2026-11-03 10:00:00] Downloading update (518,727 of 670,832 KB)...\n"
        assertEquals("518 / 670 MB", LoadingState.scan(grouped).downloadMb)
        assertEquals(77, shown(grouped))
    }

    @Test fun theMegabytesStayWhileItsPercentLinesComeBetween() {
        val log = start +
            "[2026-11-03 10:00:00] Downloading update (100000 of 670832 KB)...\n" +
            "[2026-11-03 10:00:00] Set percent complete: 14\n"
        assertEquals("100 / 670 MB", LoadingState.scan(log).downloadMb)
        assertEquals(14, shown(log))
    }

    @Test fun aFinishedDownloadShowsOnlyItsHundredPercent() {
        val log = start +
            "[2026-11-03 10:00:00] Downloading update (670000 of 670832 KB)...\n" +
            "[2026-11-03 10:00:01] Set percent complete: 100\n"
        assertEquals(null, LoadingState.scan(log).downloadMb)
        assertEquals(100, shown(log))
    }

    @Test fun otherBracketedLinesAreNotTheUpdate() {
        assertEquals(null, LoadingState.updateKilobytes("[2026-11-03 10:00:00] Package file x.zip.vz.abc_123 missing (size 4 KB)"))
        assertEquals(null, LoadingState.updateKilobytes("[2026-11-03 10:00:00] Set status message: Installing (3/5)"))
        assertEquals(null, LoadingState.updateKilobytes("== STEP 08:02:14 downloading Steam: a_all (1/2), 13 of 240 MB"))
    }

    @Test fun anUpdateWithoutKilobytesStillShowsItsPercent() {
        val log = start + "[2026-10-26 16:02:17] Set percent complete: 1\n[2026-10-26 16:02:18] Set percent complete: 44\n"
        assertEquals(null, LoadingState.scan(log).downloadMb)
        assertEquals(44, shown(log))
    }

}
