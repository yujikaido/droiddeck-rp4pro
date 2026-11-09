package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.SessionPrefs.STEAM_MENUS_AUTO
import com.droiddeck.launcher.session.SessionPrefs.STEAM_MENUS_GPU
import com.droiddeck.launcher.session.SessionPrefs.STEAM_MENUS_SOFTWARE
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamMenusTest {
    @Test
    fun automaticDrawsSteamsMenusInSoftwareOnlyOnOlderMaliGpus() {
        assertEquals(false, SessionPrefs.steamMenusOnGpu(STEAM_MENUS_AUTO, "Mali-G57 MC2"))
        assertEquals(false, SessionPrefs.steamMenusOnGpu(STEAM_MENUS_AUTO, "Mali-G610 MC6"))
        assertEquals(false, SessionPrefs.steamMenusOnGpu(STEAM_MENUS_AUTO, "Mali-G68 MC4"))
        assertEquals(null, SessionPrefs.steamMenusOnGpu(STEAM_MENUS_AUTO, "Mali-G710 MC10"))
        assertEquals(null, SessionPrefs.steamMenusOnGpu(STEAM_MENUS_AUTO, "Mali-G615 MC6"))
        assertEquals(null, SessionPrefs.steamMenusOnGpu(STEAM_MENUS_AUTO, "Mali-G720-Immortalis MC12"))
        assertEquals(null, SessionPrefs.steamMenusOnGpu(STEAM_MENUS_AUTO, ""))
    }

    @Test
    fun aChoiceInSetupWinsOverTheGpuModel() {
        assertEquals(true, SessionPrefs.steamMenusOnGpu(STEAM_MENUS_GPU, "Mali-G68 MC4"))
        assertEquals(false, SessionPrefs.steamMenusOnGpu(STEAM_MENUS_SOFTWARE, "Mali-G720-Immortalis MC12"))
    }
}
