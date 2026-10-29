package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuStandInTest {
    private fun pick(vararg present: String) = LinuxRuntime.bridgeStandIn { it in present }

    @Test fun maliKeepsItsKbaseDevice() {
        assertEquals("/dev/mali0", pick("/dev/mali0", "/dev/dri/renderD128"))
    }

    @Test fun xclipseGetsItsDrmRenderNode() {
        assertEquals("/dev/dri/renderD128", pick("/dev/dri/renderD128"))
        assertEquals("/dev/dri/renderD129", pick("/dev/dri/renderD129"))
    }

    @Test fun noGpuDeviceMeansNoStandIn() {
        assertNull(pick())
        assertNull(pick("/dev/dri/card0"))
    }
}
