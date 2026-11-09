package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class VkBridgeHeapCapTest {
    @Test
    fun theVramCapBecomesTheBridgesHeapCapButNotAboveTheSafeDefault() {
        assertEquals(3072, VkBridgeComponent.heapCapMb(3072, safe = true, ramMb = 7500))
        assertEquals(3584, VkBridgeComponent.heapCapMb(8192, safe = true, ramMb = 7500))
        assertEquals(4096, VkBridgeComponent.heapCapMb(8192, safe = true, ramMb = 11219))
        assertEquals(2048, VkBridgeComponent.heapCapMb(6144, safe = true, ramMb = 3700))
    }

    @Test
    fun noDefaultAboveTwelveAndAHalfGbOrInPerformanceMode() {
        assertEquals(8192, VkBridgeComponent.heapCapMb(8192, safe = true, ramMb = 15000))
        assertEquals(8192, VkBridgeComponent.heapCapMb(8192, safe = false, ramMb = 7500))
        assertEquals(8192, VkBridgeComponent.heapCapMb(8192, safe = true, ramMb = 0))
    }

    @Test
    fun offWithoutAVramCap() {
        assertEquals(0, VkBridgeComponent.heapCapMb(0, safe = true, ramMb = 7500))
    }
}
