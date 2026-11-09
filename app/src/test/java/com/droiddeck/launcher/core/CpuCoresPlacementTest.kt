package com.droiddeck.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The automatic placement (droiddeck-cpu-placement) stays off once the player picked cores himself. */
class CpuCoresPlacementTest {
    @Test fun aSavedGameCoreListOrTheClientOverrideIsThePlayersPick() {
        assertFalse(CpuCores.playerPicked("", clientPinned = false))
        assertFalse(CpuCores.playerPicked("  ", clientPinned = false))
        assertTrue(CpuCores.playerPicked("4,5,6,7", clientPinned = false))
        // Every core ticked is still a choice: the automatic split would take the small ones away.
        assertTrue(CpuCores.playerPicked("0,1,2,3,4,5,6,7", clientPinned = false))
        assertTrue(CpuCores.playerPicked("", clientPinned = true))
    }
}
