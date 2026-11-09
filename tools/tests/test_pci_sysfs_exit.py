"""libpci ends the process when /sys/bus/pci is readable and its devices folder is not (vivo phones).

Chromium's GPU process loads libpci to list GPUs. On vivo phones Steam's browser GPU process exited with status 1 at every
start, each time after "pcilib: Cannot open /sys/bus/pci/devices", and Steam drew its menus in software.
LinuxRuntime.hidePciDevices() gives the guest an empty devices folder then; this checks, with the host's libpci, that the
unlistable folder is what ends the process and that an empty folder lets the scan finish.
"""
import ctypes.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

LIBPCI = ctypes.util.find_library("pci")

SCAN = r"""
import ctypes, sys
pci = ctypes.CDLL(sys.argv[1])
pci.pci_alloc.restype = ctypes.c_void_p
pci.pci_set_param.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
pci.pci_init.argtypes = [ctypes.c_void_p]
pci.pci_scan_bus.argtypes = [ctypes.c_void_p]
a = pci.pci_alloc()
pci.pci_set_param(a, b"sysfs.path", sys.argv[2].encode())
# the method libpci's own probe picks when it can read the sysfs path (struct pci_access starts with it)
ctypes.cast(a, ctypes.POINTER(ctypes.c_uint))[0] = pci.pci_lookup_method(b"linux-sysfs")
pci.pci_init(a)
pci.pci_scan_bus(a)
print("scanned")
"""


@unittest.skipUnless(LIBPCI, "no libpci on this host")
class PciSysfsExitTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.bus = Path(self.tmp.name) / "bus/pci"
        self.bus.mkdir(parents=True)

    def tearDown(self):
        devices = self.bus / "devices"
        if devices.exists():
            devices.chmod(0o755)
        self.tmp.cleanup()

    def scan(self):
        return subprocess.run([sys.executable, "-I", "-c", SCAN, LIBPCI, str(self.bus)], capture_output=True, text=True)

    def test_a_readable_bus_without_devices_ends_the_process_with_status_1(self):
        out = self.scan()
        self.assertEqual(out.returncode, 1)
        self.assertIn("pcilib: Cannot open %s/devices" % self.bus, out.stderr)

    @unittest.skipIf(os.geteuid() == 0, "root lists any folder")
    def test_an_unlistable_devices_folder_ends_it_too(self):
        (self.bus / "devices").mkdir()
        (self.bus / "devices").chmod(0)
        out = self.scan()
        self.assertEqual(out.returncode, 1)
        self.assertIn("pcilib: Cannot open", out.stderr)

    def test_an_empty_devices_folder_lets_the_scan_finish(self):
        (self.bus / "devices").mkdir()
        out = self.scan()
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(out.stdout.strip(), "scanned")
        self.assertNotIn("pcilib", out.stderr)

    def test_the_runtime_binds_that_empty_folder(self):
        java = Path(__file__).resolve().parents[2] / "app/src/main/java/com/droiddeck/launcher/runtime/LinuxRuntime.java"
        text = java.read_text()
        self.assertIn('if (hidePciDevices(new File("/sys/bus/pci"))) {', text)
        self.assertIn('"etc/droiddeck/empty").getPath() + ":/sys/bus/pci/devices"', text)


if __name__ == "__main__":
    unittest.main()
