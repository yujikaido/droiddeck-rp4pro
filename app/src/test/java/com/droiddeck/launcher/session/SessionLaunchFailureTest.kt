package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionLaunchFailureTest {
    private fun find(vararg lines: String) = SessionLaunchFailure.find(lines.asSequence())

    @Test fun installedSteamThatExitsWithSigillHasACpuFailureHint() {
        assertEquals(SessionLaunchFailure.STEAM_ILLEGAL_INSTRUCTION, find(
            "== STEP 06:35:36.987 Steam client ready",
            "== STEP 06:35:41.058 starting the Steam client",
            "/usr/local/bin/droiddeck-session: line 309: 1192 Illegal instruction steam",
            "== steam exited rc=132",
            "(EE) failed to read Wayland events: Broken pipe",
        ))
    }

    @Test fun gamescopeWithoutAPhysicalDeviceHasAGraphicsFailureHint() {
        assertEquals(SessionLaunchFailure.VULKAN_DEVICE_MISSING, find(
            "[gamescope] [\u001b[0;31mError\u001b[0m] \u001b[0;37mvulkan:\u001b[0m failed to find physical device",
            "SDL_Vulkan_CreateSurface failed: VK_KHR_wayland_surface extension is not enabled in the Vulkan instance.Failed to create backend.",
        ))
    }

    @Test fun unrelatedIllegalInstructionsAndExitCodesAreNotBlamedOnSteam() {
        assertNull(find("game: Illegal instruction", "== steam exited rc=132"))
        assertNull(find("== STEP 12:00:00 starting the Steam client", "== steam exited rc=139"))
        assertNull(find("== STEP 12:00:00 starting the Steam client", "== steam exited rc=1320"))
        assertNull(find("== STEP 12:00:00 starting the Steam client", "== steam exited rc=0"))
    }

    @Test fun onlyTheLatestClientAttemptCounts() {
        assertNull(find(
            "== STEP 12:00:00 starting the Steam client", "== steam exited rc=132",
            "== STEP 12:01:00 starting the Steam client", "== steam exited rc=0",
        ))
    }

    @Test fun aLaterSuccessfulDeviceSelectionClearsTheGraphicsFailure() {
        assertNull(find(
            "[gamescope] [Error] vulkan: failed to find physical device",
            "[gamescope] [Info] vulkan: selecting physical device 'Turnip Adreno (TM) 610': queue family 0",
        ))
    }

    @Test fun aNewSessionDoesNotReuseAnOldFailure() {
        assertNull(find(
            "== STEP 12:00:00 starting the Steam client", "== steam exited rc=132",
            "== droiddeck-session Fri Oct 23 12:01:00 mode=steam",
        ))
    }

    @Test fun desktopOnTheWrongRuntimeHasARuntimeHint() {
        assertEquals(SessionLaunchFailure.DESKTOP_RUNTIME_MISMATCH, find(
            "== droiddeck-desktop 2026-11-02 06:30:58 UTC 1280x720",
            "kwriteconfig6: /usr/lib/libQt6Core.so.6: version `Qt_6.12' not found (required by kwriteconfig6)",
            "startplasma-wayland: /usr/lib/libQt6Core.so.6: version `Qt_6.12' not found (required by startplasma-wayland)",
        ))
    }

    @Test fun otherLoaderLinesAreNotBlamedOnTheRuntime() {
        assertNull(find("foo: /usr/lib/libc.so.6: version `GLIBC_2.43' not found (required by foo)"))
        assertNull(find("Qt_6.12 is a version string"))
    }

    @Test fun aNewerSessionStartClearsAnOldRuntimeMismatch() {
        assertNull(find(
            "foo: version `Qt_6.12' not found",
            "== droiddeck-session start",
        ))
    }

    @Test fun aSteamSessionEndedForAMissingBridgeServerSaysSo() {
        // droiddeck-session's own lines when the Mali bridge server never opened its socket.
        assertEquals(SessionLaunchFailure.BRIDGE_NOT_USABLE, find(
            "== droiddeck-session Wed Nov  4 21:00:00 UTC 2026 mode=steam",
            "== vkbridge: no server socket after 10000 ms at /data/x/server.sock (gave up at 10000 ms)",
            "== vulkan driver: Mali bridge requested but NOT usable (icd /usr/local/share/vkbridge/droidbridge_icd.json, socket /data/x/server.sock; missing: socket)",
            "== vulkan driver: the Steam session cannot start without the Mali bridge; ending it",
        ))
        // The desktop goes on without the bridge: no hint from the "NOT usable" line alone.
        assertNull(find(
            "== droiddeck-session Wed Nov  4 21:00:00 UTC 2026 mode=lxqt",
            "== vulkan driver: Mali bridge requested but NOT usable (icd x, socket y; missing: socket)",
        ))
        assertNull(find(
            "== vulkan driver: the Steam session cannot start without the Mali bridge; ending it",
            "== droiddeck-session Wed Nov  4 21:01:00 UTC 2026 mode=steam",
        ))
    }
}
