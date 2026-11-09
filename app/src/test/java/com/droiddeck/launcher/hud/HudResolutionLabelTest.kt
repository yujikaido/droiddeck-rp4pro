package com.droiddeck.launcher.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class HudResolutionLabelTest {
    private fun label(w: Int, h: Int, mhz: Int, session: Pair<Int, Int>?) = DroidDeckHudHost.resolutionLabel(w, h, mhz, session)

    @Test fun gamescopesOwnSizeAndRefreshWin() {
        assertEquals("1280x800 @ 60Hz", label(1280, 800, 59_940, Pair(2800, 1752)))
        assertEquals("1280x800", label(1280, 800, 0, Pair(2800, 1752)))
    }

    @Test fun withoutGamescopesFeedTheSessionSizeIsShown() {
        // Before: an empty resolution line on sessions where gamescope reports no output size.
        assertEquals("1920x1200", label(0, 0, 0, Pair(1920, 1200)))
    }

    @Test fun nothingKnownIsAnEmptyLine() {
        assertEquals("", label(0, 0, 0, null))
        assertEquals("", label(0, 0, 0, Pair(0, 0)))
    }
}
