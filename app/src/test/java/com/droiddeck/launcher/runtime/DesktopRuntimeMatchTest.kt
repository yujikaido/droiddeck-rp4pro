package com.droiddeck.launcher.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopRuntimeMatchTest {
    @Test fun r3NeedsTheUbuntuRuntime() {
        assertTrue(DesktopRuntimeMatch.needsUbuntuRuntime("desktop-kde-r3"))
        assertTrue(DesktopRuntimeMatch.needsUbuntuRuntime("desktop-kde-r12"))
        assertTrue(DesktopRuntimeMatch.needsUbuntuRuntime("desktop-kde-r3-rc1"))
    }

    @Test fun olderOrUnknownPackagesAreNotBlocked() {
        assertFalse(DesktopRuntimeMatch.needsUbuntuRuntime("desktop-kde-r2"))
        assertFalse(DesktopRuntimeMatch.needsUbuntuRuntime("desktop-kde-r1"))
        assertFalse(DesktopRuntimeMatch.needsUbuntuRuntime("r1"))
        assertFalse(DesktopRuntimeMatch.needsUbuntuRuntime(""))
        assertFalse(DesktopRuntimeMatch.needsUbuntuRuntime(null))
    }

    @Test fun r3OnTheArchRuntimeR9DoesNotMatch() {
        assertFalse(DesktopRuntimeMatch.matches("desktop-kde-r3", "r9"))
        assertFalse(DesktopRuntimeMatch.matches("desktop-kde-r3", "r9\n"))
    }

    @Test fun r3OnTheUbuntuRuntimeMatches() {
        assertTrue(DesktopRuntimeMatch.matches("desktop-kde-r3", "ubuntu-r1-rc4"))
        assertTrue(DesktopRuntimeMatch.matches("desktop-kde-r3", "ubuntu-r1"))
    }

    @Test fun r2RunsOnEither() {
        assertTrue(DesktopRuntimeMatch.matches("desktop-kde-r2", "r9"))
        assertTrue(DesktopRuntimeMatch.matches("desktop-kde-r2", "ubuntu-r1-rc4"))
    }

    @Test fun noRuntimeInstalledIsLeftToTheRuntimeInstall() {
        assertTrue(DesktopRuntimeMatch.matches("desktop-kde-r3", null))
        assertTrue(DesktopRuntimeMatch.matches("desktop-kde-r3", " "))
    }
}
