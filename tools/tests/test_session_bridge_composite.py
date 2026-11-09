import os
import re
import subprocess
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
KEYS = ("BL_VKBRIDGE", "BL_VKBRIDGE_COMPOSITE", "gamescope_composite_force", "BL_BRIDGE_COMPOSITE")


def function(name):
    match = re.search(r"^%s\(\) \{\n.*?^\}\n" % name, SESSION.read_text(), re.M | re.S)
    return match.group(0) if match else None


class BridgeCompositeTest(unittest.TestCase):
    def run_fn(self, name, *args, **settings):
        body = function(name)
        self.assertIsNotNone(body, "droiddeck-session lacks %s()" % name)
        env = {k: v for k, v in os.environ.items() if k not in KEYS}
        env.update(settings)
        script = "set -u\n" + body + name + " " + " ".join("'%s'" % a for a in args) + "\n"
        return subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, check=True).stdout.strip()

    def mode(self, mode="steam", **settings):
        return self.run_fn("bl_bridge_composite_mode", mode, **settings)

    def test_menus_only_by_default_on_the_bridge(self):
        self.assertEqual(self.mode(BL_VKBRIDGE="1"), "ui")
        self.assertEqual(self.mode(BL_VKBRIDGE="1", BL_VKBRIDGE_COMPOSITE="auto"), "ui")

    def test_the_apps_choice(self):
        self.assertEqual(self.mode(BL_VKBRIDGE="1", BL_VKBRIDGE_COMPOSITE="always"), "always")
        self.assertEqual(self.mode(BL_VKBRIDGE="1", BL_VKBRIDGE_COMPOSITE="off"), "off")

    def test_off_without_the_bridge_outside_steam_or_with_gamescopes_own_setting(self):
        self.assertEqual(self.mode(), "off")
        self.assertEqual(self.mode(BL_VKBRIDGE="0", BL_VKBRIDGE_COMPOSITE="always"), "off")
        self.assertEqual(self.mode("desktop", BL_VKBRIDGE="1"), "off")
        self.assertEqual(self.mode(BL_VKBRIDGE="1", gamescope_composite_force="0"), "off")

    def test_composited_for_steams_menus_and_nothing_in_front_not_for_games(self):
        for app in ("769", "", "none", "0"):
            self.assertEqual(self.run_fn("bl_bridge_composite_for", app), "1", app)
        for app in ("1245620", "570", "2147483649"):
            self.assertEqual(self.run_fn("bl_bridge_composite_for", app), "0", app)

    def test_the_steam_block_uses_it(self):
        text = SESSION.read_text()
        self.assertIn('bl_composite=$(bl_bridge_composite_mode "$mode")', text)
        self.assertIn("export gamescope_composite_force=1 BL_BRIDGE_COMPOSITE=ui", text)
        self.assertIn('bl_xprop_set_all GAMESCOPE_COMPOSITE_FORCE "$want"', text)
        self.assertNotIn('[ "${BL_VKBRIDGE:-0}" = 1 ] && [ -z "${gamescope_composite_force:-}" ]; then\n    export gamescope_composite_force=1', text)


if __name__ == "__main__":
    unittest.main()
