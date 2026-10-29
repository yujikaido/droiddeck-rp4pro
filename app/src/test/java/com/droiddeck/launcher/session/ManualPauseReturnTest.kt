package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPauseReturnTest {
    @Test fun aPauseThatTookWaitsForResume() {
        assertTrue(SessionService.manualPauseOnReturn(requested = true, suspended = true))
    }

    @Test fun aPauseHeldOffWhileSteamUpdatedIsDroppedOnReturn() {
        // The player leaves during the client update and comes back 3 s later.
        assertFalse(SessionService.manualPauseOnReturn(requested = true, suspended = false))
        assertFalse(SessionService.manualPauseOnReturn(requested = false, suspended = false))
    }
}
