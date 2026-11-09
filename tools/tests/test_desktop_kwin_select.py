import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/desktop/droiddeck-desktop"


def function_text():
    source = SCRIPT.read_text()
    match = re.search(r"^droiddeck_kwin_select\(\) \{\n.*?^\}\n", source, re.S | re.M)
    assert match, "droiddeck_kwin_select not found"
    return match.group(0)


class DesktopKwinSelectTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def run_select(self, marker, driver=None, render_node=True, preset_nodes=None, standin=None, kwin_lib=None, extra=None):
        """Runs the function with a fake marker file, /sys/dev/char, render node and libkwin; returns the env.
        standin: the bridge's node name as LinuxRuntime.bindGpuNode lays it out (device/drm/<name> + /dev/dri/<name>).
        kwin_lib: the libkwin's bytes (None = no libkwin); never the host's own."""
        if marker is not None:
            (self.root / "marker").write_text(marker + "\n")
        char = self.root / "char"
        dri = self.root / "dri"
        dri.mkdir()
        if driver is not None:
            uevent = char / "238:0" / "device" / "uevent"
            uevent.parent.mkdir(parents=True)
            uevent.write_text("DRIVER=%s\nMODALIAS=platform:%s\n" % (driver, driver))
            if standin:
                (uevent.parent / "drm" / standin).mkdir(parents=True)
                (dri / standin).write_text("")
        lib = self.root / "lib" / "libkwin.so.6.8.2"
        if kwin_lib is not None:
            lib.parent.mkdir()
            lib.write_bytes(kwin_lib)
        node = self.root / "renderD0"
        if render_node:
            node.write_text("")
        env = {
            "PATH": os.environ["PATH"],
            "DD_KWIN_MARKER": str(self.root / "marker"),
            "DD_SYS_CHAR": str(char),
            "DD_RENDER_NODE": str(node),
            "DD_NODE_TEST": "-e",
            "DD_KWIN_WRAP": str(self.root / "wrap"),
            "DD_DEV_DRI": str(dri),
            "DD_KWIN_LIBS": str(self.root / "lib" / "libkwin.so.*"),
            "KWIN_COMPOSE": "Q", "QT_QUICK_BACKEND": "software",
            "LIBGL_ALWAYS_SOFTWARE": "1", "GALLIUM_DRIVER": "llvmpipe",
        }
        if preset_nodes:
            env["KWIN_RENDER_NODES"] = preset_nodes
        env.update(extra or {})
        code = function_text() + "\ndroiddeck_kwin_select\nenv\n"
        out = subprocess.run(["bash", "-c", code], env=env, capture_output=True, text=True, check=True).stdout
        return dict(line.split("=", 1) for line in out.splitlines() if "=" in line), out

    def test_adreno_with_marker_keeps_opengl_and_node(self):
        env, _ = self.run_select("opengl", driver="kgsl-3d0")
        self.assertEqual(env["KWIN_COMPOSE"], "O2")
        self.assertEqual(env["GALLIUM_DRIVER"], "zink")
        self.assertEqual(env["KWIN_RENDER_NODES"], str(self.root / "renderD0"))
        self.assertNotIn("LIBGL_ALWAYS_SOFTWARE", env)
        self.assertNotIn("QT_QUICK_BACKEND", env)
        self.assertTrue((self.root / "wrap" / "kwin_wayland").exists())
        self.assertTrue(env["PATH"].startswith(str(self.root / "wrap")))

    def test_adreno_without_sysfs_keeps_opengl(self):
        env, _ = self.run_select("opengl")
        self.assertEqual(env["KWIN_COMPOSE"], "O2")
        self.assertEqual(env["KWIN_RENDER_NODES"], str(self.root / "renderD0"))

    def test_adreno_without_node_sets_no_node_like_before(self):
        env, _ = self.run_select("opengl", driver="kgsl-3d0", render_node=False)
        self.assertEqual(env["KWIN_COMPOSE"], "O2")
        self.assertNotIn("KWIN_RENDER_NODES", env)

    def test_preset_render_nodes_kept(self):
        env, _ = self.run_select("opengl", driver="kgsl-3d0", preset_nodes="/dev/dri/renderD5")
        self.assertEqual(env["KWIN_RENDER_NODES"], "/dev/dri/renderD5")

    def test_mali_bridge_node_keeps_software_compositor(self):
        env, out = self.run_select("opengl", driver="mali")
        self.assertEqual(env["KWIN_COMPOSE"], "Q")
        self.assertEqual(env["QT_QUICK_BACKEND"], "software")
        self.assertEqual(env["LIBGL_ALWAYS_SOFTWARE"], "1")
        self.assertEqual(env["GALLIUM_DRIVER"], "llvmpipe")
        self.assertNotIn("KWIN_RENDER_NODES", env)
        self.assertNotIn("MESA_LOADER_DRIVER_OVERRIDE", env)
        self.assertIn("Mali bridge", out)
        self.assertFalse((self.root / "wrap").exists())

    PATCHED = b"\x7fELF..." + b"x" * 70000 + b"Using explicitly selected nested render node" + b"\0more"

    def test_mali_with_droiddecks_kwin_composites_on_the_stand_in_node(self):
        # KWin 6.8 with patch 0002 (DroidDeck Preview's desktop: "Using explicitly selected nested render
        # node /dev/dri/renderD105") gets the bridge's node and Zink instead of a black screen.
        env, out = self.run_select("opengl", driver="mali", standin="renderD105", kwin_lib=self.PATCHED)
        self.assertEqual(env["KWIN_COMPOSE"], "O2")
        self.assertEqual(env["GALLIUM_DRIVER"], "zink")
        self.assertEqual(env["MESA_LOADER_DRIVER_OVERRIDE"], "zink")
        self.assertEqual(env["KWIN_RENDER_NODES"], str(self.root / "dri" / "renderD105"))
        self.assertNotIn("LIBGL_ALWAYS_SOFTWARE", env)
        self.assertNotIn("QT_QUICK_BACKEND", env)
        self.assertIn("composites on the GPU", out)

    def test_mali_with_a_kwin_without_the_patch_keeps_software(self):
        # The Kubuntu package's KWin 6.6.6 can't take the node (v0.5.5 logs: Failed to open drm node: "").
        env, out = self.run_select("opengl", driver="mali", standin="renderD105", kwin_lib=b"\x7fELF plain kwin")
        self.assertEqual(env["KWIN_COMPOSE"], "Q")
        self.assertNotIn("KWIN_RENDER_NODES", env)
        self.assertIn("keeps the software compositor", out)
        env, _ = self.run_select_again("opengl", driver="mali", standin="renderD105", kwin_lib=None)
        self.assertEqual(env["KWIN_COMPOSE"], "Q")

    def test_mali_without_a_bound_node_keeps_software(self):
        env, _ = self.run_select("opengl", driver="mali", kwin_lib=self.PATCHED)
        self.assertEqual(env["KWIN_COMPOSE"], "Q")
        self.assertNotIn("KWIN_RENDER_NODES", env)

    def test_the_software_switch_wins_on_the_patched_kwin(self):
        env, _ = self.run_select("opengl", driver="mali", standin="renderD105", kwin_lib=self.PATCHED,
                                 extra={"BL_DESKTOP_KWIN": "software"})
        self.assertEqual(env["KWIN_COMPOSE"], "Q")
        self.assertNotIn("KWIN_RENDER_NODES", env)

    def test_a_preset_node_is_kept_on_mali(self):
        env, _ = self.run_select("opengl", driver="mali", standin="renderD105", kwin_lib=self.PATCHED,
                                 preset_nodes="/dev/dri/renderD9")
        self.assertEqual(env["KWIN_RENDER_NODES"], "/dev/dri/renderD9")

    def test_adreno_ignores_the_mali_path(self):
        env, _ = self.run_select("opengl", driver="kgsl-3d0", standin="renderD0", kwin_lib=self.PATCHED)
        self.assertEqual(env["KWIN_RENDER_NODES"], str(self.root / "renderD0"))

    def run_select_again(self, *args, **kwargs):
        self.temp2 = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp2.cleanup)
        self.root = Path(self.temp2.name)
        return self.run_select(*args, **kwargs)

    def test_package_without_marker_stays_software(self):
        env, _ = self.run_select(None)
        self.assertEqual(env["KWIN_COMPOSE"], "Q")
        self.assertNotIn("KWIN_RENDER_NODES", env)


if __name__ == "__main__":
    unittest.main()
