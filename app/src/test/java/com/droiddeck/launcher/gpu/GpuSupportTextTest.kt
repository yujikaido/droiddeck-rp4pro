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
        assertFalse(mali.supportText(bridge = true, mali = true).contains("needs an Adreno"))
        assertTrue(mali.supportText(bridge = true, mali = true).contains("Mali bridge"))
        assertTrue(mali.supportText(bridge = false, mali = true).contains("Mali bridge off"))
    }

    @Test
    fun aGpuThatIsNeitherAnAdrenoNorAMaliReadsAsBefore() {
        val other = gpu(0, GpuInfo.Family.NOT_ADRENO)
        assertEquals(other.supportText, other.supportText(bridge = false, mali = false))
        assertFalse(other.supportText(bridge = false, mali = false).contains("Mali"))
    }

    @Test
    fun anAdrenoReadsAsBeforeWhateverTheBridgeSwitch() {
        val adreno = gpu(740, GpuInfo.Family.A7XX)
        assertEquals(adreno.supportText, adreno.supportText(bridge = true, mali = false))
        assertEquals(adreno.supportText, adreno.supportText(bridge = false, mali = false))
    }
}
