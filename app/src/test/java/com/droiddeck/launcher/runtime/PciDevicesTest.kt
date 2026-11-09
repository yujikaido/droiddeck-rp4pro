package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/** When the guest gets an empty /sys/bus/pci/devices, so libpci does not exit(1) in Steam's browser GPU process. */
class PciDevicesTest {
    private val sys = Files.createTempDirectory("guest-pci").toFile()
    private val bus = File(sys, "bus/pci")
    private val locked = mutableListOf<File>()

    @After fun tearDown() {
        locked.forEach { it.setReadable(true, false); it.setExecutable(true, false) }
        sys.deleteRecursively()
    }

    private fun lock(f: File) {
        locked += f
        f.setReadable(false, false)
        f.setExecutable(false, false)
        // root reads everything: the permission cases mean nothing then
        assumeFalse(f.canRead())
    }

    private fun device(name: String) = File(bus, "devices/$name").apply { mkdirs(); File(this, "vendor").writeText("0x13b5\n") }

    @Test fun noPciBusLeavesSysfsAlone() {
        assertFalse(LinuxRuntime.hidePciDevices(bus))
    }

    @Test fun readableDevicesLeaveSysfsAlone() {
        device("0000:00:00.0")
        assertFalse(LinuxRuntime.hidePciDevices(bus))
        File(bus, "devices/0000:00:00.0").deleteRecursively()
        assertFalse(LinuxRuntime.hidePciDevices(bus)) // an empty, listable devices folder is fine too
    }

    @Test fun vivoReadableBusWithoutADevicesFolderIsHidden() {
        bus.mkdirs()
        File(bus, "drivers").mkdirs()
        assertTrue(LinuxRuntime.hidePciDevices(bus))
    }

    @Test fun vivoDevicesFolderThatCannotBeListedIsHidden() {
        device("0000:00:00.0")
        lock(File(bus, "devices"))
        assertTrue(LinuxRuntime.hidePciDevices(bus))
    }

    @Test fun anUnreadableVendorFileIsStillHidden() {
        val dev = device("0000:00:00.0")
        lock(File(dev, "vendor"))
        assertTrue(LinuxRuntime.hidePciDevices(bus))
    }

    @Test fun anUnreadableBusLeavesSysfsAloneAsBefore() {
        bus.mkdirs()
        lock(bus)
        assertFalse(LinuxRuntime.hidePciDevices(bus))
    }

    @Test fun bindsAskAboutTheRealBus() {
        val src = File("src/main/java/com/droiddeck/launcher/runtime/LinuxRuntime.java").readText()
        assertTrue(src.contains("hidePciDevices(new File(\"/sys/bus/pci\"))"))
        assertTrue(src.contains(":/sys/bus/pci/devices\");"))
    }
}
