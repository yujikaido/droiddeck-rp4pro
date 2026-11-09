package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSupportBridgeTest {
    @Test fun maliWithSetupSwitchOffUsesTheBridgeAnyway() {
        assertTrue(DeviceSupport.vkBridgeFor(adreno = false, mali = true, switchOn = false, forceOff = false))
        assertTrue(DeviceSupport.bridgeTurnedOnFor(adreno = false, mali = true, switchOn = false, forceOff = false))
    }

    @Test fun maliWithSwitchOnIsNotReportedAsTurnedOn() {
        assertTrue(DeviceSupport.vkBridgeFor(adreno = false, mali = true, switchOn = true, forceOff = false))
        assertFalse(DeviceSupport.bridgeTurnedOnFor(adreno = false, mali = true, switchOn = true, forceOff = false))
    }

    @Test fun forceOffKeepsTheBridgeOffOnMali() {
        assertFalse(DeviceSupport.vkBridgeFor(adreno = false, mali = true, switchOn = false, forceOff = true))
        assertFalse(DeviceSupport.bridgeTurnedOnFor(adreno = false, mali = true, switchOn = false, forceOff = true))
    }

    @Test fun nonMaliKeepsTheSetupSwitch() {
        // an unknown GPU (neither Adreno nor Mali): the switch alone decides, as before
        assertFalse(DeviceSupport.vkBridgeFor(adreno = false, mali = false, switchOn = false, forceOff = false))
        assertTrue(DeviceSupport.vkBridgeFor(adreno = false, mali = false, switchOn = true, forceOff = false))
        assertFalse(DeviceSupport.bridgeTurnedOnFor(adreno = false, mali = false, switchOn = false, forceOff = false))
        // Adreno never uses the bridge
        for (sw in listOf(false, true)) for (fo in listOf(false, true)) {
            assertFalse(DeviceSupport.vkBridgeFor(adreno = true, mali = false, switchOn = sw, forceOff = fo))
            assertFalse(DeviceSupport.bridgeTurnedOnFor(adreno = true, mali = false, switchOn = sw, forceOff = fo))
        }
    }

    @Test fun forceOffDoesNotTurnTheBridgeOnOrOffWhenSwitchIsOn() {
        assertEquals(true, DeviceSupport.vkBridgeFor(adreno = false, mali = false, switchOn = true, forceOff = true))
    }
}
