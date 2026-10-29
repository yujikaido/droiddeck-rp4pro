package com.droiddeck.launcher.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEndScanTest {
    private fun scan(text: String) = SessionEndScan.scan(text.lineSequence())

    @Test fun steamDownloadRefusedEveryTryIsReported() {
        // Behind a VPN: every try refused within 11 ms, then the install gives up.
        val log = "== STEP 11:20:41 checking the Steam client (first run downloads it, ~1-2 min with nothing on screen)\n" +
            "curl: (7) Failed to connect to client-update.fastly.steamstatic.com port 443 after 11 ms: Could not connect to server\n" +
            "== droiddeck-steam-install: steam_client_steamdeck_publicbeta_linuxarm64 failed (try 1 of 5), trying again\n" +
            "curl: (7) Failed to connect to client-update.fastly.steamstatic.com port 443 after 5 ms: Could not connect to server\n" +
            "== droiddeck-steam-install: steam_client_steamdeck_publicbeta_linuxarm64 failed (try 4 of 5), trying again\n" +
            "curl: (7) Failed to connect to client-update.fastly.steamstatic.com port 443 after 6 ms: Could not connect to server\n" +
            "droiddeck-steam-install: could not reach Steam's download servers (steam_client_steamdeck_publicbeta_linuxarm64, curl exit 7 after 5 tries)\n"
        val s = scan(log)
        assertTrue(s.steamUnreachable)
        assertFalse(s.fexMissing)
    }

    @Test fun aRetryThatWorksIsNotReported() {
        val log = "== droiddeck-steam-install: steam_client_steamdeck_publicbeta_linuxarm64 failed (try 1 of 5), trying again\n" +
            "== STEP 11:20:50 downloading Steam: client_linuxarm64_linuxarm64 (1/3)\n" +
            "== STEP 11:21:40 Steam client ready\n"
        assertFalse(scan(log).steamUnreachable)
    }

    @Test fun aMissingFileOnTheServerIsNotCalledANetworkProblem() {
        val log = "droiddeck-steam-install: bins_linuxarm64.zip: the server has no such file (HTTP error)\n"
        assertFalse(scan(log).steamUnreachable)
    }

    @Test fun theOtherHintsStillCount() {
        val s = scan("droiddeck-fex: x86 Linux programs need FEX\n" + "open: Function not implemented\n".repeat(3))
        assertTrue(s.fexMissing)
        assertEquals(3, s.enosys)
        assertFalse(s.steamUnreachable)
    }

    @Test fun steamUnreachableShowsItsScreenEvenWithStatus0() {
        // rc9 item 16 (S10+, network off, session 2026-10-27-63): the install gave up, gamescope's
        // child ended, and the session ended with status 0. The ended screen must stay with the hint.
        val log = "== STEP 13:24:12 checking the Steam client (first run downloads it, ~1-2 min with nothing on screen)\n" +
            "curl: (6) Could not resolve host: client-update.fastly.steamstatic.com\n" +
            "== droiddeck-steam-install: steam_client_steamdeck_publicbeta_linuxarm64 failed (try 4 of 5), trying again\n" +
            "curl: (6) Could not resolve host: client-update.fastly.steamstatic.com\n" +
            "droiddeck-steam-install: could not reach Steam's download servers (steam_client_steamdeck_publicbeta_linuxarm64, curl exit 6 after 5 tries)\n" +
            "[gamescope] [\u001b[0;34mInfo\u001b[0m]  \u001b[0;37mlaunch:\u001b[0m Primary child shut down!\n" +
            "(EE) failed to read Wayland events: Broken pipe\n"
        val s = scan(log)
        assertTrue(s.keepsEndedScreen(0))
        assertEquals(SessionEndScan.Hint.STEAM_UNREACHABLE, s.hint)
        assertTrue(s.keepsEndedScreen(1))
    }

    @Test fun anOrdinaryEndWithStatus0StillClosesTheScreen() {
        // Steam installed (or installed after a retry), the player quit: no hint, no ended screen.
        val ok = scan("== droiddeck-steam-install: steam_client_steamdeck_publicbeta_linuxarm64 failed (try 1 of 5), trying again\n" +
            "== STEP 11:21:40 Steam client ready\n" +
            "[gamescope] [Info]  launch: Primary child shut down!\n")
        assertFalse(ok.keepsEndedScreen(0))
        assertEquals(null, ok.hint)
        assertTrue(ok.keepsEndedScreen(137))
        // The other hints keep their old rule: shown only for a status other than 0.
        val fex = scan("droiddeck-fex: x86 Linux programs need FEX\n")
        assertFalse(fex.keepsEndedScreen(0))
        assertEquals(SessionEndScan.Hint.FEX_MISSING, fex.hint)
        val enosys = scan("open: Function not implemented\n".repeat(SessionEndScan.ENOSYS_HINT))
        assertFalse(enosys.keepsEndedScreen(0))
        assertEquals(SessionEndScan.Hint.ENOSYS, enosys.hint)
        assertEquals(null, scan("open: Function not implemented\n".repeat(SessionEndScan.ENOSYS_HINT - 1)).hint)
        // A missing file on the server is not the network message.
        assertFalse(scan("droiddeck-steam-install: bins_linuxarm64.zip: the server has no such file (HTTP error)\n").keepsEndedScreen(0))
    }

    @Test fun theUnreachableHintComesFirst() {
        val s = scan("droiddeck-fex: x86 Linux programs need FEX\n" + "open: Function not implemented\n".repeat(9) +
            SessionEndScan.STEAM_UNREACHABLE + " (x, curl exit 7 after 5 tries)\n")
        assertEquals(SessionEndScan.Hint.STEAM_UNREACHABLE, s.hint)
    }

    @Test fun theScriptWritesTheLineTheAppLooksFor() {
        val script = File("../tools/linuxfs/overlay/usr/local/bin/droiddeck-steam-install").readText()
        assertTrue(script.contains("UNREACHABLE=\"${SessionEndScan.STEAM_UNREACHABLE}\""))
    }
}
