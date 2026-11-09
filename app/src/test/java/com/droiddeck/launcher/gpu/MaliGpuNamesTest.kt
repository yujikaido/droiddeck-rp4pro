package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaliGpuNamesTest {
    private val file = "device=Mali-G720-Immortalis MC12\napi=1.3.0\ndriver_api=1.3.0\npromotion=off\n"

    @Test fun theBridgesVulkanNameWinsWithItsCoreCount() {
        assertEquals("Mali-G720-Immortalis MC12", MaliGpuNames.choose(file, "Mali-G720", "mt6989", "mt6989"))
    }

    @Test fun theVulkanNameIsUsedWhenThereIsNoFileYet() {
        assertEquals("Mali-G925-Immortalis MC12", MaliGpuNames.choose(null, "Mali-G925-Immortalis MC12", "mt6991", "mt6991"))
        assertEquals("Mali-G610 MC6", MaliGpuNames.choose("api=1.3.0\n", " Mali-G610 MC6 ", "mt6895", "mt6895"))
    }

    @Test fun theTableNamesTheFamilyWhenNothingElseKnows() {
        assertEquals("Mali-G720-Immortalis", MaliGpuNames.choose(null, null, "mt6989", ""))
        assertEquals("Mali-G1-Ultra", MaliGpuNames.choose(null, null, "MT6993", ""))
        assertEquals("Xclipse 540", MaliGpuNames.choose(null, null, "s5e8855", "s5e8855"))
        assertEquals("Mali-G715", MaliGpuNames.choose(null, null, "tegu", "gs401"))
        assertEquals("Mali-G2-Ultra-NX", MaliGpuNames.choose(null, null, "xring_o3_asic", ""))
        assertEquals("Mali-G78", MaliGpuNames.choose(null, null, "unknown-board", "exynos2100"))
    }

    @Test fun softwareRenderersAndBlankNamesAreNotGpuNames() {
        assertNull(MaliGpuNames.choose("device=llvmpipe (LLVM 17)\n", "SwiftShader Device", "unknown", ""))
        assertNull(MaliGpuNames.choose("device=\n", "  ", "", ""))
        assertEquals("Mali-G57", MaliGpuNames.choose("device=llvmpipe\n", null, "mt6833", ""))
    }

    @Test fun anUnknownChipGivesNullSoTheCallerKeepsTheChipName() {
        assertNull(MaliGpuNames.choose(null, null, "newchip9", "newplatform"))
    }

    @Test fun anAdrenoVulkanNameIsNotTakenHere() {
        assertNull(MaliGpuNames.choose("device=Turnip Adreno (TM) 830\n".replace("Turnip ", ""), null, "", ""))
    }
}
