import re
import subprocess
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def host_wayland(env):
    text = SESSION.read_text()
    match = re.search(r"^vkbridge_host_wayland\(\) \{\n.*?^\}\n", text, re.M | re.S)
    assert match, "droiddeck-session lacks vkbridge_host_wayland()"
    full = {"PATH": "/usr/bin:/bin"}
    full.update(env)
    return subprocess.run(["bash", "-c", "set -u\n" + match.group(0) + "vkbridge_host_wayland\n"],
                          capture_output=True, text=True, timeout=10, check=True, env=full).stdout


class SessionHostWaylandTest(unittest.TestCase):
    """The bridge under a nested gamescope offers gralloc buffers to the app's own compositor: the session
    names its socket (VKBRIDGE_HOST_WAYLAND) from the Wayland display gamescope itself connects to."""

    def test_a_display_name_is_found_in_the_runtime_dir(self):
        self.assertEqual("/run/droiddeck/wayland-0\n",
                         host_wayland({"WAYLAND_DISPLAY": "wayland-0", "XDG_RUNTIME_DIR": "/run/droiddeck"}))

    def test_an_absolute_display_is_kept(self):
        self.assertEqual("/tmp/rt/wayland-0\n",
                         host_wayland({"WAYLAND_DISPLAY": "/tmp/rt/wayland-0", "XDG_RUNTIME_DIR": "/run/droiddeck"}))

    def test_no_display_names_nothing(self):
        self.assertEqual("", host_wayland({"XDG_RUNTIME_DIR": "/run/droiddeck"}))
        self.assertEqual("", host_wayland({"WAYLAND_DISPLAY": "", "XDG_RUNTIME_DIR": "/run/droiddeck"}))

    def test_exported_once_outside_gamescope_before_the_dev_settings(self):
        text = SESSION.read_text()
        block = text[text.index('export VKBRIDGE_SOCKET VKBRIDGE_CLIENT_LOG'):text.index('/root/.vkbridge-env')]
        self.assertIn('[ -z "${BL_INSIDE:-}" ] && [ -z "${VKBRIDGE_HOST_WAYLAND:-}" ]', block)
        self.assertIn('export VKBRIDGE_HOST_WAYLAND', block)

    def exported(self, env):
        """What the session block exports, run with the function it calls."""
        text = SESSION.read_text()
        func = re.search(r"^vkbridge_host_wayland\(\) \{\n.*?^\}\n", text, re.M | re.S).group(0)
        start = text.index('    if [ -z "${BL_INSIDE:-}" ] && [ -z "${VKBRIDGE_HOST_WAYLAND:-}" ]')
        block = text[start:text.index("    fi\n", start) + len("    fi\n")]
        full = {"PATH": "/usr/bin:/bin", "WAYLAND_DISPLAY": "wayland-0", "XDG_RUNTIME_DIR": "/run/droiddeck"}
        full.update(env)
        return subprocess.run(["bash", "-c", func + block + 'printf "%s" "${VKBRIDGE_HOST_WAYLAND-unset}"\n'],
                              capture_output=True, text=True, timeout=10, check=True, env=full).stdout

    def test_nested_zero_copy_is_off_unless_asked_for(self):
        # S10+: 76 instead of 93 fps in Balatro with it on, Dark Souls III the same
        self.assertEqual("unset", self.exported({}))
        self.assertEqual("unset", self.exported({"BL_VKBRIDGE_NESTED_ZEROCOPY": "0"}))
        self.assertEqual("/run/droiddeck/wayland-0", self.exported({"BL_VKBRIDGE_NESTED_ZEROCOPY": "1"}))
        self.assertEqual("unset", self.exported({"BL_VKBRIDGE_NESTED_ZEROCOPY": "1", "BL_INSIDE": "1"}))
        self.assertEqual("/given", self.exported({"VKBRIDGE_HOST_WAYLAND": "/given"}))


if __name__ == "__main__":
    unittest.main()
