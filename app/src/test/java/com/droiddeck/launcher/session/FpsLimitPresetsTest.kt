package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsLimitPresetsTest {
    @Test fun theChipsAre30And60() {
        assertEquals(listOf(30, 60), SessionPrefs.FPS_LIMIT_PRESETS)
    }

    @Test fun eachChipStoresWhatTheSliderStoresAtThatStop() {
        for (preset in SessionPrefs.FPS_LIMIT_PRESETS) {
            assertTrue(preset in SessionPrefs.FPS_LIMIT_OFF..SessionPrefs.FPS_LIMIT_MAX)
            assertEquals(0, (preset - SessionPrefs.FPS_LIMIT_OFF) % 5)
            assertEquals(preset, SessionPrefs.fpsLimitFromSlider(preset))
        }
    }

    @Test fun theSliderStopOffStillMeansNoLimit() {
        assertEquals(0, SessionPrefs.fpsLimitFromSlider(SessionPrefs.FPS_LIMIT_OFF))
    }
}
