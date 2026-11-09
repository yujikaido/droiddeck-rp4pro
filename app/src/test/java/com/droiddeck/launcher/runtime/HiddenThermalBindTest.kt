package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Ledger row 17 (39aaeb111): Steam's Performance Overlay never showed on Samsung's Galaxy Tab S10 Ultra.
 * One UI refuses to list /sys/class/thermal to apps; mangoapp walks it for a CPU temperature when no hwmon
 * sensor is bound and aborts on the permission error. An empty directory bound over it reads as "no sensor".
 * The bind must be added only when the listing is refused (null), never on devices that allow it.
 * The thermal directory is injected: nothing here reads the machine running the test.
 */
class HiddenThermalBindTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A directory whose listing is refused, as One UI does (File.list() answers null). */
    private fun refused(path: File) = object : File(path.path) {
        override fun list(): Array<String>? = null
    }

    private fun bindsOf(cmd: List<String>): List<String> =
        cmd.withIndex().filter { it.value == "-b" }.map { cmd[it.index + 1] }

    @Test fun aRefusedListingGetsAnEmptyDirectoryBoundOverIt() {
        val base = tmp.newFolder("drm")
        val cmd = mutableListOf<String>()
        LinuxRuntime.bindEmptyThermalIfHidden(cmd, base, refused(File(tmp.root, "thermal")))
        val empty = File(base, "empty-thermal")
        assertEquals(listOf("${empty.path}:/sys/class/thermal"), bindsOf(cmd))
        assertTrue(empty.isDirectory)
        assertEquals(0, empty.list()!!.size)
    }

    @Test fun aListableThermalDirectoryIsLeftAlone() {
        val base = tmp.newFolder("drm")
        val thermal = tmp.newFolder("thermal")
        File(thermal, "thermal_zone0").mkdir()
        val cmd = mutableListOf<String>()
        LinuxRuntime.bindEmptyThermalIfHidden(cmd, base, thermal)
        assertTrue(cmd.isEmpty())
        assertTrue(!File(base, "empty-thermal").exists())
    }

    @Test fun aSecondSessionReusesTheEmptyDirectory() {
        val base = tmp.newFolder("drm")
        val hidden = refused(File(tmp.root, "thermal"))
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        LinuxRuntime.bindEmptyThermalIfHidden(first, base, hidden)
        LinuxRuntime.bindEmptyThermalIfHidden(second, base, hidden)
        assertEquals(first, second)
    }

    @Test fun noBindWhenTheEmptyDirectoryCannotBeMade() {
        // base is a plain file: mkdirs() under it fails
        val base = tmp.newFile("not-a-directory")
        val cmd = mutableListOf<String>()
        LinuxRuntime.bindEmptyThermalIfHidden(cmd, base, refused(File(tmp.root, "thermal")))
        assertTrue(cmd.isEmpty())
    }
}
