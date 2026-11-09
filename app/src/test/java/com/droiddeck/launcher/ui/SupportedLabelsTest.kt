package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import com.droiddeck.launcher.sessionEndNoVulkanRes
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportedLabelsTest {
    @Test fun aMaliWithTheBridgeOnIsNotUnsupportedInComponents() {
        assertEquals(DeviceChipKind.BRIDGE, deviceChipKind(supported = false, unsupported = true, bridge = true))
    }

    @Test fun aGpuWithTheBridgeOffStaysUnsupported() {
        assertEquals(DeviceChipKind.UNSUPPORTED, deviceChipKind(supported = false, unsupported = true, bridge = false))
    }

    @Test fun anAdrenoChipIsUnchangedWhateverTheBridge() {
        assertEquals(DeviceChipKind.TESTED, deviceChipKind(true, false, false))
        assertEquals(DeviceChipKind.TESTED, deviceChipKind(true, false, true))
        assertEquals(DeviceChipKind.UNTESTED, deviceChipKind(false, false, false))
    }

    @Test fun setupRowOnMali() {
        assertEquals(R.string.setup_gpu_bridge, setupGpuTitleRes(bridge = true, mali = true))
        assertEquals(R.string.setup_gpu_bridge_detail, setupGpuDetailRes(bridge = true, mali = true))
        assertEquals(R.string.setup_gpu_bridge_is_off, setupGpuTitleRes(bridge = false, mali = true))
        assertEquals(R.string.setup_gpu_bridge_is_off_detail, setupGpuDetailRes(bridge = false, mali = true))
    }

    @Test fun setupRowOnOtherGpusKeepsUnsupportedWhenBridgeIsOff() {
        assertEquals(R.string.setup_gpu_unsupported, setupGpuTitleRes(bridge = false, mali = false))
        assertEquals(R.string.setup_gpu_unsupported_detail, setupGpuDetailRes(bridge = false, mali = false))
        assertEquals(R.string.setup_gpu_bridge, setupGpuTitleRes(bridge = true, mali = false))
    }

    @Test fun setupChecksStartOpenOnlyWithTheBridgeOn() {
        assertEquals(true, setupChecksOpenAtStart(adreno = false, bridge = true))
        assertEquals(false, setupChecksOpenAtStart(adreno = false, bridge = false))
        assertEquals(false, setupChecksOpenAtStart(adreno = true, bridge = false))
        assertEquals(false, setupChecksOpenAtStart(adreno = true, bridge = true))
    }

    @Test fun noVulkanDeviceHint() {
        assertEquals(R.string.session_end_vulkan_device, sessionEndNoVulkanRes(adreno = true, bridge = false))
        assertEquals(R.string.session_end_bridge_no_device, sessionEndNoVulkanRes(adreno = false, bridge = true))
        assertEquals(R.string.session_end_gpu_unsupported, sessionEndNoVulkanRes(adreno = false, bridge = false))
    }
}
