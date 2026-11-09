package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.SessionPrefs.BRIDGE_COMPOSITE_ALWAYS
import com.droiddeck.launcher.session.SessionPrefs.BRIDGE_COMPOSITE_AUTO
import com.droiddeck.launcher.session.SessionPrefs.BRIDGE_COMPOSITE_OFF
import com.droiddeck.launcher.session.SessionPrefs.BRIDGE_COMPOSITE_UI
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeCompositeTest {
    @Test
    fun automaticIsMenusOnlyOnJobManagerMalisAndOffOnCsfMalis() {
        for (gpu in listOf("Mali-G76 MC4", "Mali-G68 MC4", "Mali-G57 MC2", "Mali-G78 MP14", "Samsung Xclipse 940", ""))
            assertEquals(gpu, BRIDGE_COMPOSITE_UI, SessionPrefs.bridgeCompositeFor(BRIDGE_COMPOSITE_AUTO, gpu))
        for (gpu in listOf("Mali-G720-Immortalis MC12", "Mali-G925-Immortalis MC12", "Mali-G615 MC6", "Mali-G610 MC6", "Mali-G710 MC10", "Mali-G1-Ultra MC12"))
            assertEquals(gpu, BRIDGE_COMPOSITE_OFF, SessionPrefs.bridgeCompositeFor(BRIDGE_COMPOSITE_AUTO, gpu))
    }

    @Test
    fun aChoiceWinsOverTheGpu() {
        assertEquals(BRIDGE_COMPOSITE_ALWAYS, SessionPrefs.bridgeCompositeFor(BRIDGE_COMPOSITE_ALWAYS, "Mali-G720-Immortalis MC12"))
        assertEquals(BRIDGE_COMPOSITE_UI, SessionPrefs.bridgeCompositeFor(BRIDGE_COMPOSITE_UI, "Mali-G720-Immortalis MC12"))
        assertEquals(BRIDGE_COMPOSITE_OFF, SessionPrefs.bridgeCompositeFor(BRIDGE_COMPOSITE_OFF, "Mali-G76 MC4"))
        assertEquals(BRIDGE_COMPOSITE_UI, SessionPrefs.bridgeCompositeFor("something else", "Mali-G76 MC4"))
    }
}
