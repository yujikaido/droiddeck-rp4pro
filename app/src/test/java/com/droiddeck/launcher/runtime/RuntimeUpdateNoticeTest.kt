package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeUpdateNoticeTest {
    @Test fun saidOnTheBridgeForAnUpdateOverAnInstalledRuntime() {
        assertTrue(RuntimeUpdateNotice.wanted(true, "r9", "ubuntu-r1"))
        assertFalse(RuntimeUpdateNotice.wanted(true, "ubuntu-r1", "ubuntu-r1"))
        assertFalse(RuntimeUpdateNotice.wanted(true, null, "ubuntu-r1"))
        assertFalse(RuntimeUpdateNotice.wanted(false, "r9", "ubuntu-r1"))
        assertFalse(RuntimeUpdateNotice.wanted(true, "r9", null))
    }

    @Test fun sizeAndFreeSpace() {
        assertEquals(2900L, RuntimeUpdateNotice.downloadMb(2_899_500_000L))
        assertTrue(RuntimeUpdateNotice.short(3_000_000_000L, 8_000_000_000L))
        assertFalse(RuntimeUpdateNotice.short(3_000_000_000L, 12_000_000_000L))
        assertFalse(RuntimeUpdateNotice.short(0L, 0L))
    }
}
