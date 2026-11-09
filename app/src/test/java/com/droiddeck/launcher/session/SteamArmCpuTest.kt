package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamArmCpuTest {
    private val note9 = "processor\t: 0\nBogoMIPS\t: 52.00\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\nCPU implementer\t: 0x53\n" +
        "processor\t: 4\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n"
    private val modern = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\n" +
        "processor\t: 1\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp\n"

    @Test fun anArmv80CpuWithoutAtomicsIsNotSupported() {
        assertFalse(SteamArmCpu.supported(note9))
        assertTrue(SteamArmCpu.blocks("arm64", note9))
    }

    @Test fun aCpuWithAtomicsOnEveryCoreIsSupported() {
        assertTrue(SteamArmCpu.supported(modern))
        assertFalse(SteamArmCpu.blocks("arm64", modern))
    }

    @Test fun oneCoreWithoutAtomicsStillRulesItOutLikeTheScript() {
        val mixed = modern + "processor\t: 2\nFeatures\t: fp asimd crc32\n"
        assertFalse(SteamArmCpu.supported(mixed))
    }

    @Test fun missingCpuDataDoesNotBlock() {
        assertTrue(SteamArmCpu.supported(null))
        assertTrue(SteamArmCpu.supported(""))
        assertTrue(SteamArmCpu.supported("processor\t: 0\nmodel name\t: x86\n"))
        assertFalse(SteamArmCpu.blocks("arm64", null))
    }

    @Test fun theX64ClientIsNeverBlockedHere() {
        assertFalse(SteamArmCpu.blocks("x86_64", note9))
    }

    @Test fun atomicsMustBeAWholeWord() {
        assertFalse(SteamArmCpu.supported("Features\t: fp asimd noatomicsx\n"))
    }
}
