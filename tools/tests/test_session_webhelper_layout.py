import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
KEYS = ("BL_VKBRIDGE_WEBHELPER_ARGS", "BL_VKBRIDGE_CEF_ANGLE", "BL_VKBRIDGE_CEF_VULKAN")


def layout_function():
    match = re.search(r"^bl_webhelper_layout\(\) \{\n.*?^\}\n", SESSION.read_text(), re.M | re.S)
    return match.group(0) if match else None


class WebhelperLayoutTest(unittest.TestCase):
    def layout(self, **settings):
        function = layout_function()
        self.assertIsNotNone(function, "droiddeck-session lacks bl_webhelper_layout()")
        env = {k: v for k, v in os.environ.items() if k not in KEYS}
        env.update(settings)
        return subprocess.run(["bash", "-c", "set -u\n" + function + "bl_webhelper_layout\n"], env=env,
                              capture_output=True, text=True, check=True).stdout.strip()

    def test_unset_keeps_steams_own_layout(self):
        self.assertEqual(self.layout(), "stock")

    def test_angle_on_vulkan_brings_chromiums_vulkan_compositing(self):
        self.assertEqual(self.layout(BL_VKBRIDGE_CEF_ANGLE="vulkan"),
                         "--use-gl=angle --use-angle=vulkan +Vulkan +VulkanFromANGLE +DefaultANGLEVulkan")

    def test_vulkan_compositing_can_be_left_off(self):
        self.assertEqual(self.layout(BL_VKBRIDGE_CEF_ANGLE="vulkan", BL_VKBRIDGE_CEF_VULKAN="0"),
                         "--use-gl=angle --use-angle=vulkan")

    def test_other_angle_backends_get_only_the_angle_switches(self):
        self.assertEqual(self.layout(BL_VKBRIDGE_CEF_ANGLE="gl"), "--use-gl=angle --use-angle=gl")

    def test_explicit_layout_wins(self):
        self.assertEqual(self.layout(BL_VKBRIDGE_CEF_ANGLE="vulkan", BL_VKBRIDGE_WEBHELPER_ARGS="--x +Y"),
                         "--x +Y")

    def test_the_steam_block_uses_it(self):
        self.assertIn("BL_WEBHELPER_ARGS=$(bl_webhelper_layout)", SESSION.read_text())


class CefGpuFailedLastTest(unittest.TestCase):
    """After a start where Steam turned its browser's GPU off, Chromium's log goes to session.log."""

    def failed_last(self, webhelper):
        match = re.search(r"^bl_cef_gpu_failed_last\(\) \{\n.*?^\}\n", SESSION.read_text(), re.M | re.S)
        self.assertIsNotNone(match, "droiddeck-session lacks bl_cef_gpu_failed_last()")
        with tempfile.TemporaryDirectory() as root:
            if webhelper is not None:
                (Path(root) / "logs").mkdir()
                (Path(root) / "logs/webhelper.txt").write_text(webhelper)
            return subprocess.run(["bash", "-c", "set -u\n" + match.group(0) + 'bl_cef_gpu_failed_last "$1"\n', "-", root],
                                  capture_output=True).returncode == 0

    def test_a_crash_count_start_is_seen(self):
        self.assertTrue(self.failed_last("x\n[1025/143200] Disabling GPU acceleration: Disabled/CrashCount\ny\n"))

    def test_a_working_start_or_no_log_is_not(self):
        self.assertFalse(self.failed_last("GPU process started\n"))
        self.assertFalse(self.failed_last(None))

    def test_the_steam_block_logs_without_verbosity(self):
        text = SESSION.read_text()
        self.assertIn('bl_cef_gpu_failed_last "$steam_root"; then\n', text)
        block = text.split('bl_cef_gpu_failed_last "$steam_root"; then\n', 1)[1].split("fi\n", 1)[0]
        self.assertIn("_bl_cef+=(-cef-enable-logging=stderr)", block)
        code = "\n".join(line for line in block.splitlines() if not line.lstrip().startswith("#"))
        self.assertNotIn("-cef-v=", code)


class CefGpuChoiceTest(unittest.TestCase):
    """Two GPU-process failures in a row put the next sessions' menus in software; the GPU is tried again later."""

    FAIL = "[1] Startup - webhelper launched pid: 1\n[2] Disabling GPU acceleration: Disabled/CrashCount\n"
    OK = "[1] Startup - webhelper launched pid: 1\nGPU process started\n"

    def setUp(self):
        text = SESSION.read_text()
        self.funcs = "".join(re.search(r"^%s\(\) \{\n.*?^\}\n" % n, text, re.M | re.S).group(0)
                             for n in ("bl_cef_gpu_failed_last", "bl_cef_gpu_choice"))
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "logs").mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def session(self, webhelper):
        """One session start: the web helper log as the last session left it, then the choice."""
        if webhelper is not None:
            (self.root / "logs/webhelper.txt").write_text(webhelper)
        out = subprocess.run(["bash", "-c", "set -u\n" + self.funcs + 'bl_cef_gpu_choice "$1"\n', "-", str(self.root)],
                             capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        return out.stdout.strip()

    def state(self):
        return (self.root / ".bl-cef-gpu-state").read_text().split()

    def test_first_start_tries_the_gpu(self):
        self.assertEqual(self.session(None), "gpu")
        self.assertEqual(self.state(), ["0", "0", "1", "2"])

    def test_one_failure_changes_nothing(self):
        self.session(None)
        self.assertEqual(self.session(self.FAIL), "gpu")
        self.assertEqual(self.state(), ["1", "0", "1", "2"])

    def test_a_working_start_clears_the_count(self):
        self.session(None)
        self.session(self.FAIL)
        self.assertEqual(self.session(self.OK), "gpu")
        self.assertEqual(self.state(), ["0", "0", "1", "2"])

    def test_two_failures_in_a_row_go_to_software(self):
        self.session(None)
        self.session(self.FAIL)
        self.assertEqual(self.session(self.FAIL), "soft")
        self.assertEqual(self.state(), ["2", "1", "0", "2"])

    def test_software_sessions_keep_the_count_and_the_gpu_is_tried_after_eight(self):
        self.session(None)
        self.session(self.FAIL)
        self.assertEqual([self.session(self.FAIL) for _ in range(8)], ["soft"] * 8)
        self.assertEqual(self.session(self.FAIL), "gpu")  # the retry
        self.assertEqual(self.state(), ["2", "8", "1", "2"])
        self.assertEqual(self.session(self.OK), "gpu")     # it works now
        self.assertEqual(self.state(), ["0", "0", "1", "2"])

    def test_a_failed_retry_goes_back_to_software(self):
        self.session(None)
        self.session(self.FAIL)
        for _ in range(9):
            self.session(self.FAIL)
        self.assertEqual(self.session(self.FAIL), "soft")
        self.assertEqual(self.state(), ["3", "1", "0", "2"])

    def test_only_the_last_web_helper_start_counts(self):
        self.session(None)
        # an old CrashCount line before a start that worked
        self.assertEqual(self.session(self.FAIL + self.OK), "gpu")
        self.assertEqual(self.state(), ["0", "0", "1", "2"])

    def test_a_damaged_state_file_starts_over(self):
        (self.root / ".bl-cef-gpu-state").write_text("x y\n")
        self.assertEqual(self.session(self.FAIL), "gpu")
        self.assertEqual(self.state(), ["0", "0", "1", "2"])

    def test_a_phone_put_in_software_before_the_fix_tries_the_gpu_again(self):
        # the state as the 0.5.4.3 test build left a vivo phone: two failures, software since then, no fix level
        (self.root / ".bl-cef-gpu-state").write_text("2 3 0\n")
        self.assertEqual(self.session(self.FAIL), "gpu")
        self.assertEqual(self.state(), ["0", "0", "1", "2"])
        self.assertEqual(self.session(self.OK), "gpu")
        self.assertEqual(self.state(), ["0", "0", "1", "2"])

    def test_after_the_fix_failures_count_as_before(self):
        (self.root / ".bl-cef-gpu-state").write_text("2 3 0\n")
        self.session(self.FAIL)
        self.assertEqual(self.session(self.FAIL), "gpu")
        self.assertEqual(self.session(self.FAIL), "soft")
        self.assertEqual(self.state(), ["2", "1", "0", "2"])

    def test_the_steam_block_leaves_the_players_choice_and_dev_switches_alone(self):
        text = SESSION.read_text()
        self.assertIn('[ -z "${BL_VKBRIDGE_CEF_GPU:-}" ] &&', text)
        block = text.split('[ "$(bl_cef_gpu_choice "$steam_root")" = soft ]; then\n', 1)[1].split("elif", 1)[0]
        self.assertIn("-cef-disable-gpu -cef-disable-gpu-compositing", block)
        kotlin = (SESSION.parents[6] / "app/src/main/java/com/droiddeck/launcher/session/SessionService.kt").read_text()
        self.assertIn('guest.add("BL_VKBRIDGE_CEF_GPU=1")', kotlin)


if __name__ == "__main__":
    unittest.main()
