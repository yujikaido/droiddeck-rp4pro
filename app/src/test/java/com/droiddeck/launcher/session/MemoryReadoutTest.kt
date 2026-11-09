package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReadoutTest {
    private val meminfo = """
        MemTotal:       11379920 kB
        MemFree:          527864 kB
        MemAvailable:    3235768 kB
        Buffers:            1024 kB
        SwapTotal:      16777212 kB
        SwapFree:       10358080 kB
    """.trimIndent()

    @Test fun memoryAndSwapInUseComeFromMeminfo() {
        val device = MemoryReadout.parseMeminfo(meminfo)!!
        assertEquals(11379920L - 3235768L, device.usedKb)
        assertEquals(16777212L - 10358080L, device.swapUsedKb)
        assertEquals("7.8", MemoryReadout.gb(device.usedKb))
        assertEquals("10.9", MemoryReadout.gb(device.totalKb))
        assertEquals(0L, MemoryReadout.parseMeminfo("MemTotal: 100 kB\nMemAvailable: 40 kB\n")!!.swapUsedKb)
        assertNull(MemoryReadout.parseMeminfo("MemTotal: 100 kB\n"))
    }

    @Test fun aProcessCountsItsResidentAnonymousAndSharedMemory() {
        val status = """
            Name:	Diablo IV.exe
            VmRSS:	 2000000 kB
            RssAnon:	 1500000 kB
            RssFile:	  400000 kB
            RssShmem:	  100000 kB
            VmSwap:	  300000 kB
        """.trimIndent()
        assertEquals(1600000L, MemoryReadout.residentKb(status))
        assertEquals(0L, MemoryReadout.residentKb("Name:\tkthreadd\n"))
    }

    @Test fun theGameIsWhatRunsInAWinePrefix() {
        fun env(vararg vars: String) = vars.joinToString("\u0000", postfix = "\u0000").toByteArray()
        assertTrue(MemoryReadout.inWinePrefix(env("HOME=/root", "WINEPREFIX=/root/pfx", "DISPLAY=:0")))
        assertTrue(MemoryReadout.inWinePrefix("WINEPREFIX=/x".toByteArray()))
        assertFalse(MemoryReadout.inWinePrefix(env("HOME=/root", "STEAM_COMPAT_DATA_PATH=/x", "MY_WINEPREFIX=/y")))
        assertFalse(MemoryReadout.inWinePrefix(ByteArray(0)))
    }

    @Test fun androidIsWhatIsLeftOfTheMemoryInUse() {
        val device = MemoryReadout.Device(totalKb = 10_000, availableKb = 3_000, swapTotalKb = 0, swapFreeKb = 0)
        assertEquals(3_000L, MemoryReadout.androidKb(device, MemoryReadout.Split(gameKb = 2_000, droiddeckKb = 1_000, gpuKb = 1_000)))
        assertEquals(4_000L, MemoryReadout.androidKb(device, MemoryReadout.Split(gameKb = 2_000, droiddeckKb = 1_000, gpuKb = null)))
        assertEquals(0L, MemoryReadout.androidKb(device, MemoryReadout.Split(gameKb = 6_000, droiddeckKb = 2_000, gpuKb = 1_000)))
    }

    @Test fun theLogLineSaysEveryPartInMegabytes() {
        val device = MemoryReadout.Device(totalKb = 11_379_920, availableKb = 1_048_576, swapTotalKb = 16_777_212, swapFreeKb = 8_388_606)
        val split = MemoryReadout.Split(gameKb = 2_097_152, droiddeckKb = 524_288, gpuKb = 3_145_728)
        assertEquals(
            "used 10089/11113 MB, available 1024 MB, swap 8191/16383 MB | game 2048, droiddeck 512, android 4457, gpu 3072 MB",
            MemoryLog.line(device, split),
        )
        assertTrue(MemoryLog.line(device, split.copy(gpuKb = null)).endsWith("gpu ? MB"))
    }
}

class MemoryReadoutSplitTest {
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    private fun proc(dir: java.io.File, pid: Int, ppid: Int, rssKb: Long, cmdline: String = "x", environ: String = "") {
        val d = java.io.File(dir, "$pid").apply { mkdirs() }
        java.io.File(d, "stat").writeText("$pid (p q) S $ppid 1 1 0")
        java.io.File(d, "status").writeText("Name:\tp\nRssAnon:\t $rssKb kB\nRssShmem:\t 0 kB\n")
        java.io.File(d, "cmdline").writeBytes(cmdline.replace(' ', '\u0000').toByteArray())
        java.io.File(d, "environ").writeBytes(environ.toByteArray())
    }

    @Test fun aNativeLinuxSteamGameUnderFexCountsAsGame() {
        val p = tmp.newFolder("proc1")
        proc(p, 100, 1, 1000)                                             // root: the app
        proc(p, 200, 100, 50, "proot steam")                              // steam client
        proc(p, 300, 200, 20, "reaper SteamLaunch AppId=2868840 -- x")    // game launcher
        proc(p, 400, 300, 3000, "FEXInterpreter StS2")                    // native game, no WINEPREFIX
        proc(p, 500, 400, 500, "StS2 child")
        val s = MemoryReadout.split(100, p)
        assertEquals(3520L, s.gameKb)
        assertEquals(1050L, s.droiddeckKb)
    }

    @Test fun aNonSteamWineLaunchStillCountsByItsPrefix() {
        val p = tmp.newFolder("proc2")
        proc(p, 100, 1, 1000)
        proc(p, 200, 100, 40, "bash run.sh")
        proc(p, 300, 200, 2000, "wine game.exe", "HOME=/root\u0000WINEPREFIX=/pfx\u0000")
        val s = MemoryReadout.split(100, p)
        assertEquals(2000L, s.gameKb)
        assertEquals(1040L, s.droiddeckKb)
    }
}
