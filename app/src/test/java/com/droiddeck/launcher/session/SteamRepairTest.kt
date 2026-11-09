package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamRepairTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before @After fun idle() {
        SessionState.running = false
        SessionState.steamClientArch = null
    }

    private fun marker(home: String) = File(LinuxRuntime.rootDir(app), "root/$home/package/droiddeck-unconfirmed").apply {
        parentFile?.mkdirs()
        writeText("tracked\nstart\nstart\n")
    }

    @Test fun aShownClientClearsOnlyItsOwnUnconfirmedStarts() {
        val arm = marker(".local/share/Steam")
        val x64 = marker(".droiddeck-x64/.local/share/Steam")
        SteamRepair.clientShown(app)
        assertFalse(arm.exists())
        assertTrue(x64.exists())

        marker(".local/share/Steam")
        SessionPrefs.setSteamClientArch(app, "x86_64")
        SteamRepair.clientShown(app)
        assertTrue(arm.exists())
        assertFalse(x64.exists())
    }

    private val installing = "[1] Startup - a\n[2] uninstalled manifest found in p (1).\n"

    @Test fun aStartEndedDuringSteamsUpdateInstallIsNotCounted() {
        assertFalse(SteamRepair.countsAsUnconfirmed(installing = true, playerStopped = false, sinceClientStartMs = 10 * 60_000L))
        assertFalse(SteamRepair.countsAsUnconfirmed(installing = true, playerStopped = true, sinceClientStartMs = 10 * 60_000L))
    }

    @Test fun aPlayersEarlyStopIsNotCountedButALateOneAndACrashAre() {
        assertFalse(SteamRepair.countsAsUnconfirmed(false, playerStopped = true, sinceClientStartMs = 60_000L))
        assertTrue(SteamRepair.countsAsUnconfirmed(false, playerStopped = true, sinceClientStartMs = SteamRepair.PLAYER_STOP_COUNTS_AFTER_MS))
        assertTrue(SteamRepair.countsAsUnconfirmed(false, playerStopped = false, sinceClientStartMs = 5_000L))
    }

    @Test fun forgettingAStartRemovesOnlyTheLastOne() {
        val file = marker(".local/share/Steam")
        assertTrue(SteamRepair.forgetLastStart(file))
        assertEquals("tracked\nstart\n", file.readText())
        assertTrue(SteamRepair.forgetLastStart(file))
        assertEquals("tracked\n", file.readText())
        assertFalse(SteamRepair.forgetLastStart(file))
        file.writeText("start\n")
        assertTrue(SteamRepair.forgetLastStart(file))
        assertFalse(file.exists())
        assertFalse(SteamRepair.forgetLastStart(file))
    }

    @Test fun theInstallStateComesFromTheBootstrapLogAndIsNotStale() {
        val log = File(app.cacheDir, "bootstrap_log.txt").apply { writeText(installing) }
        assertTrue(SteamRepair.updateInstalling(log))
        log.writeText(installing + "[3] Shutdown\n")
        assertFalse(SteamRepair.updateInstalling(log))
        log.writeText(installing)
        assertFalse(SteamRepair.updateInstalling(log, now = log.lastModified() + SteamRepair.INSTALL_LOG_MAX_AGE_MS + 1))
        assertFalse(SteamRepair.updateInstalling(null))
        assertFalse(SteamRepair.updateInstalling(File(app.cacheDir, "missing.txt")))
    }

    @Test fun sessionEndedUncountsAnInstallingStartButNotAnOrdinaryOne() {
        val arm = marker(".local/share/Steam")
        val log = File(arm.parentFile, "../logs/bootstrap_log.txt").apply { parentFile?.mkdirs(); writeText(installing) }
        SteamRepair.sessionEnded(app, "arm64", playerStopped = false, sinceClientStartMs = 120_000L)
        assertEquals("tracked\nstart\n", arm.readText())
        log.writeText(installing + "[3] Shutdown\n")
        SteamRepair.sessionEnded(app, "arm64", playerStopped = false, sinceClientStartMs = 120_000L)
        assertEquals("tracked\nstart\n", arm.readText())
        SteamRepair.sessionEnded(app, "arm64", playerStopped = true, sinceClientStartMs = 20_000L)
        assertEquals("tracked\n", arm.readText())
    }

    @Test fun theLoadingScreenSaysSteamIsInstallingItsUpdate() {
        val arm = marker(".local/share/Steam")
        File(arm.parentFile, "../logs/bootstrap_log.txt").apply { parentFile?.mkdirs(); writeText(installing) }
        val loading = LoadingState(app, steam = true)
        loading.update(app, null)
        assertTrue(loading.step, loading.step.startsWith("Steam is installing its update. This can take a few minutes."))
        assertTrue(loading.step, loading.step.endsWith("0:00"))
        assertEquals(-1, loading.percent)
        File(arm.parentFile, "../logs/bootstrap_log.txt").writeText(installing + "[3] Shutdown\n")
        val session = File(app.cacheDir, "session.log").apply { writeText("== STEP 12:00:00 starting the Steam client\n") }
        loading.update(app, session)
        assertEquals("starting the Steam client", loading.step)
    }
}
