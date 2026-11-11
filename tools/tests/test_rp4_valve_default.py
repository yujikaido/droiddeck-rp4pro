"""RP4 Pro build: Proton Experimental ARM64 is the default, DroidDeck-Proton a per-game pick, and a title whose prefix the
new default cannot take stays where it ran."""
import json
import os
import unittest
from unittest import mock

from test_steam_compat_respect import C, EXT, EXT_DIR, GE_PREFIX, MAD_MAX, TOOL, VALVE_PREFIX, Fixture

YS = "207350"
YS6 = "1294810"
NEW = "10"


class ValveDefaultTest(Fixture):
    def setUp(self):
        super().setUp()
        for app in (YS, YS6, NEW):
            (self.steam / ("steamapps/appmanifest_%s.acf" % app)).write_text("")

    def test_the_apps_own_droiddeck_proton_default_goes_back_and_ge_prefixes_stay(self):
        # The device after v0.5.5.3: the app made DroidDeck-Proton the default and moved its followers onto it.
        self.prefix(YS, VALVE_PREFIX)
        self.prefix(YS6, GE_PREFIX)
        self.write_state(default=EXT, source="update", auto={MAD_MAX: EXT, YS: EXT, YS6: EXT}, tools=self.catalog,
                         own_proton=True, own_builds=[EXT])
        self.write_config({"0": EXT, MAD_MAX: EXT, YS: EXT, YS6: EXT})
        default, out = self.start(apps=(MAD_MAX, YS, YS6))
        self.assertEqual(default, TOOL)
        self.assertEqual(self.mapping(), {"0": TOOL, MAD_MAX: TOOL, YS: TOOL, YS6: EXT})
        self.assertIn("default: %s -> %s" % (EXT, TOOL), out)
        self.assertNotIn("WARNING", out)
        default, out = self.start(apps=(MAD_MAX, YS, YS6))
        self.assertEqual(self.mapping(), {"0": TOOL, MAD_MAX: TOOL, YS: TOOL, YS6: EXT})

    def test_a_default_picked_in_steams_menu_stays(self):
        self.write_state(default=EXT, source="steam", auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True,
                         own_builds=[EXT], chosen={"0": {"name": EXT, "at": C["now_ms"]()}})
        self.write_config({"0": EXT, MAD_MAX: EXT})
        default, _ = self.start()
        self.assertEqual(default, EXT)

    def test_a_per_game_pick_stays(self):
        self.prefix(YS6, GE_PREFIX)
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT],
                         chosen={YS6: {"name": EXT, "at": C["now_ms"]()}})
        self.write_config({"0": TOOL, YS6: EXT})
        self.start(apps=(YS6,))
        self.assertEqual(self.mapping()[YS6], EXT)

    def test_a_new_title_starts_on_experimental(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        self.write_config({"0": TOOL})
        self.start(apps=(NEW,))
        self.assertIn(self.mapping().get(NEW, TOOL), (TOOL, ""))
        self.assertNotIn(NEW, self.read_state().get("new_title_tools", {}))

    def test_the_switch_gives_upstreams_default_back(self):
        self.write_state(default=EXT, source="update", auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True,
                         own_builds=[EXT])
        self.write_config({"0": EXT, MAD_MAX: EXT})
        with mock.patch.dict(os.environ, {"DD_PROTON_OWN_DEFAULT": "1"}):
            default, _ = self.start()
        self.assertEqual(default, EXT)


class ImplicitFollowerTest(Fixture):
    def test_a_title_with_no_mapping_is_pinned_to_the_default_its_prefix_came_from(self):
        # It ran on the general default without a mapping of its own; the default moving must not take its prefix along.
        self.prefix(MAD_MAX, VALVE_PREFIX)
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        self.write_config({"0": TOOL})
        (self.state / "request.json").write_text(json.dumps({"seq": 1, "valve": False, "dir": EXT_DIR}))
        default, out = self.start()
        self.assertEqual(default, EXT)
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)
        self.assertIn("kept %s" % TOOL, out)


if __name__ == "__main__":
    unittest.main()
