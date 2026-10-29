package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuSupportTextTest {
    private fun gpu(model: Int, family: GpuInfo.Family) = GpuInfo("GPU", model, family, "", false)

    @Test
    fun aMaliWithTheBridgeOnIsNotToldItNeedsAnAdreno() {
        val mali = gpu(0, GpuInfo.Family.NOT_ADRENO)
        assertFalse(mali.supportText(bridge = true).contains("needs an Adreno"))
        assertTrue(mali.supportText(bridge = true).contains("Mali bridge"))
        assertTrue(mali.supportText(bridge = false).contains("Mali bridge off"))
    }

    @Test
    fun anAdrenoReadsAsBeforeWhateverTheBridgeSwitch() {
        val adreno = gpu(740, GpuInfo.Family.A7XX)
        assertEquals(adreno.supportText, adreno.supportText(bridge = true))
        assertEquals(adreno.supportText, adreno.supportText(bridge = false))
    }
}
