package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPauseReturnTest {
    @Test fun aPauseThatTookWaitsForResume() {
        assertTrue(SessionService.manualPauseOnReturn(requested = true, suspended = true))
    }

    @Test fun aPauseHeldOffWhileSteamUpdatedIsDroppedOnReturn() {
        // the player left during the client update and came back 3 s later; the held pause then froze the update.
        assertFalse(SessionService.manualPauseOnReturn(requested = true, suspended = false))
        assertFalse(SessionService.manualPauseOnReturn(requested = false, suspended = false))
    }
}
