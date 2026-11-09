"""Golden tests for the DroidDeck-Proton pin (11-04, Discord "Offir [HK]": "New DroidDeck-Proton 11-12.1 (Compatible), broke most
of my games, how can I go back. I can't open Nine Sols, Repo").

What happened on v0.5.5.2: the app downloaded DroidDeck-Proton 11-12.1 on its own, moved its per-game picks to it and deleted
11-11.x; at the next Steam start steam-compatibility moved every game mapped on the deleted build (and the default) to the newest
build, and titles without a prefix started on it. These tests pin what must hold now: a new upstream build that appears moves
nothing that exists, new titles and moves the app makes go to a validated build, and a player's pick stays."""
import contextlib
import io
import json
import os
from pathlib import Path
import re
import runpy
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
BIN = ROOT / "tools/linuxfs/overlay/usr/local/bin"
C = runpy.run_path(str(BIN / "steam-compatibility"))
EXTRA = runpy.run_path(str(BIN / "droiddeck-proton-extra"))
TOOL = C["TOOL"]


def own(version):
    return {"name": "DroidDeck-Proton-" + version, "display": "DroidDeck-Proton %s (Compatible)" % version,
            "dir": "DroidDeck-Proton " + version, "valve": False}


VALVE = {"name": TOOL, "display": "Proton Experimental ARM64", "dir": "Proton - Experimental (ARM64)", "valve": True}
TESTED = own("11-11.10")       # what the Ultra ran on 11-04
TESTED_2 = own("11-11.11")     # validated too (player logs on Mali)
UPSTREAM = own("11-12.1")      # appeared 11-03 20:31Z, not validated
GE_PREFIX = "GE-Proton11-11"   # what 11-11.x writes into a prefix's version file
NO_LIST = {"DD_PROTON_VALIDATED_FILE": "/nonexistent/proton-validated"}  # the built-in list, whatever the PC has in /etc


def config_text(entries):
    mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % (app, name, "75" if app == "0" else "250")
                      for app, name in entries)
    return '"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping


class PinTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.steam = Path(self.tmp.name) / "Steam"
        (self.steam / "config").mkdir(parents=True)
        (self.steam / "steamapps" / "compatdata").mkdir(parents=True)
        self.state_dir = Path(self.tmp.name) / "compat"
        self.state_dir.mkdir()
        env = mock.patch.dict(os.environ, dict(NO_LIST, BL_VKBRIDGE="1"))
        env.start()
        self.addCleanup(env.stop)

    def prefix(self, app, version=GE_PREFIX):
        path = self.steam / "steamapps" / "compatdata" / app
        path.mkdir(parents=True)
        (path / "version").write_text(version + "\n")

    def run_default(self, state, mapping, catalog, apps):
        (self.state_dir / "state.json").write_text(json.dumps(state))
        config = self.steam / "config" / "config.vdf"
        config.write_text(config_text(mapping))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            default = C["register_default"](str(config), list(apps), catalog=catalog, directory=str(self.state_dir), settle=True)
        self.out = out.getvalue()
        return default, C["read_config_mapping"](str(config)), json.loads((self.state_dir / "state.json").read_text())

    def test_a_new_upstream_build_appearing_moves_nothing_that_exists(self):
        # 42 picked 11-11.10, 43 follows the default (11-11.10), 44 is on Valve's Proton with its prefix; 45 is new (no prefix).
        for app in ("42", "43", "44"):
            self.prefix(app)
        state = {"default": TESTED["name"], "own_proton": True, "source": "update", "auto": {"43": TESTED["name"]},
                 "tools": [VALVE, TESTED], "own_builds": [TESTED["name"]]}
        mapping = [("0", TESTED["name"]), ("42", TESTED["name"]), ("43", TESTED["name"]), ("44", TOOL)]
        default, after, state = self.run_default(state, mapping, [VALVE, TESTED, UPSTREAM], ["42", "43", "44", "45"])
        self.assertEqual(default, TESTED["name"])
        self.assertEqual({app: after[app] for app in ("0", "42", "43", "44")},
                         {"0": TESTED["name"], "42": TESTED["name"], "43": TESTED["name"], "44": TOOL})
        self.assertNotEqual(after.get("45"), UPSTREAM["name"])
        self.assertNotIn(UPSTREAM["name"], after.values())
        # and again at the next start: still nothing on the upstream build
        default, after, _ = self.run_default(state, sorted(after.items()), [VALVE, TESTED, UPSTREAM], ["42", "43", "44", "45"])
        self.assertNotIn(UPSTREAM["name"], after.values())

    def test_a_new_title_starts_on_the_validated_build_not_the_newest(self):
        # Valve default (the Mali bridge kept it for an install with games); 46 has no prefix yet.
        self.prefix("42", "11.0-100")
        state = {"default": TOOL, "own_proton": True, "tools": [VALVE, TESTED], "own_builds": [TESTED["name"]]}
        _, after, _ = self.run_default(state, [("0", TOOL), ("42", TOOL)], [VALVE, TESTED, UPSTREAM], ["42", "46"])
        self.assertEqual(after["46"], TESTED["name"])
        self.assertEqual(after["42"], TOOL)
        self.assertEqual(after["0"], TOOL)

    def test_the_first_droiddeck_proton_default_is_the_validated_one(self):
        default, after, state = self.run_default({"default": TOOL}, [("0", TOOL)], [VALVE, TESTED, UPSTREAM], [])
        self.assertEqual(default, TESTED["name"])
        self.assertEqual(after["0"], TESTED["name"])

    def test_after_v0552_deleted_the_old_build_its_games_go_to_the_validated_one_and_moved_games_stay(self):
        # v0.5.5.2 deleted 11-11.10 and moved 42 to 11-12.1 (its prefix now says 11.0-100). The fixed app installed 11-11.11 next
        # to 11-12.1. 47 was still mapped on the deleted build (Steam was not started since): it goes to the validated build.
        self.prefix("42", "11.0-100")
        self.prefix("47")
        state = {"default": UPSTREAM["name"], "own_proton": True, "source": "update", "tools": [VALVE, UPSTREAM, TESTED],
                 "own_builds": [TESTED["name"], UPSTREAM["name"]]}
        mapping = [("0", UPSTREAM["name"]), ("42", UPSTREAM["name"]), ("47", TESTED["name"])]
        _, after, _ = self.run_default(state, mapping, [VALVE, UPSTREAM, TESTED_2], ["42", "47"])
        # 42: nobody recorded a pick of 11-12.1 for it (v0.5.5.2 moved it): it goes to the validated build too (a player log, 11-05)
        self.assertEqual(after["42"], TESTED_2["name"])
        self.assertEqual(after["47"], TESTED_2["name"])

    def test_a_players_pick_of_the_upstream_build_stays(self):
        self.prefix("42", "11.0-100")
        state = {"default": TESTED["name"], "own_proton": True, "tools": [VALVE, TESTED, UPSTREAM],
                 "own_builds": [TESTED["name"], UPSTREAM["name"]], "chosen": {"42": {"name": UPSTREAM["name"], "at": 1}}}
        _, after, _ = self.run_default(state, [("0", TESTED["name"]), ("42", UPSTREAM["name"])], [VALVE, TESTED, UPSTREAM], ["42"])
        self.assertEqual(after["42"], UPSTREAM["name"])

    def test_with_only_unvalidated_builds_the_newest_installed_is_used(self):
        self.assertEqual(C["newest_own_proton"]([VALVE, own("11-12.1"), own("11-12.2")]), "DroidDeck-Proton-11-12.2")
        self.assertIsNone(C["newest_own_proton"]([VALVE]))

    def test_the_pin_switch_and_the_list_file(self):
        catalog = [VALVE, TESTED, TESTED_2, UPSTREAM]
        self.assertEqual(C["newest_own_proton"](catalog), TESTED["name"])
        self.assertEqual(C["newest_own_proton"](catalog, environ={"DD_PROTON_PIN": "off"}), UPSTREAM["name"])
        listed = Path(self.tmp.name) / "validated"
        listed.write_text("# MaliBridge\nDroidDeck-Proton-11-11.10\n")
        self.assertEqual(C["newest_own_proton"](catalog, environ={"DD_PROTON_VALIDATED_FILE": str(listed)}), TESTED["name"])
        self.assertEqual(C["own_proton_moves"]({}, catalog)[0], TESTED["name"])
        listed.write_text("DroidDeck-Proton-11-11.11\nDroidDeck-Proton-11-11.10\n")  # the app's order decides
        self.assertEqual(C["newest_own_proton"](catalog, environ={"DD_PROTON_VALIDATED_FILE": str(listed)}), TESTED_2["name"])

    # Release prep T11 (owner 11-04): new installs get 11-11.10, the build our tablets ran, ahead of 11-11.11 (validated, selectable).
    # Fails on t10 (84b4f1e32): the newest validated build installed (11-11.11) became the default.
    def test_the_default_is_the_tablet_tested_build_ahead_of_the_newer_validated_one(self):
        self.assertEqual(C["VALIDATED_OWN_BUILDS"][0], TESTED["name"])
        self.assertEqual(EXTRA["validated_tags"](NO_LIST)[0], TESTED["name"])
        default, after, _ = self.run_default({"default": TOOL}, [("0", TOOL)], [VALVE, TESTED_2, TESTED, UPSTREAM], [])
        self.assertEqual(default, TESTED["name"])
        self.assertEqual(after["0"], TESTED["name"])
        # a game the player put on 11-11.11 keeps it
        self.prefix("42")
        state = {"default": TESTED["name"], "own_proton": True, "source": "update", "tools": [VALVE, TESTED, TESTED_2],
                 "own_builds": [TESTED["name"], TESTED_2["name"]]}
        _, after, _ = self.run_default(state, [("0", TESTED["name"]), ("42", TESTED_2["name"])], [VALVE, TESTED, TESTED_2], ["42"])
        self.assertEqual(after["42"], TESTED_2["name"])

    # Release prep T11 (owner 11-04): what v0.5.5.2's own logic put on 11-12.1 goes back to the default validated build; a pick the
    # player made never moves. Fails on t10 (84b4f1e32): nothing went back.
    def v0552_moved(self, source="update", chosen=None):
        for app in ("42", "43", "45", "46", "47"):
            self.prefix(app, "11.0-100")  # 11-12.1 ran them: the prefix guard holds moves nobody asked for
        state = {"default": UPSTREAM["name"], "own_proton": True, "source": source,
                 "auto": {"43": UPSTREAM["name"]}, "new_title_tools": {"45": UPSTREAM["name"]},
                 "fallbacks": {"46": {"title": "46", "from": TOOL, "to": UPSTREAM["name"], "fails": 2, "at": 1, "confirmed": True}},
                 "chosen": dict({"47": {"name": UPSTREAM["name"], "at": 1}}, **(chosen or {})),
                 "tools": [VALVE, UPSTREAM, TESTED], "own_builds": [TESTED["name"], UPSTREAM["name"]]}
        mapping = [("0", UPSTREAM["name"])] + [(app, UPSTREAM["name"]) for app in ("42", "43", "45", "46", "47")]
        return self.run_default(state, mapping, [VALVE, UPSTREAM, TESTED], ["42", "43", "45", "46", "47"])

    def test_what_v0552s_update_logic_put_on_the_unvalidated_build_goes_back(self):
        default, after, state = self.v0552_moved()
        self.assertEqual(default, TESTED["name"])
        self.assertEqual(after["0"], TESTED["name"])
        for app in ("43", "45", "46"):  # follower, new title the app started, start-failure move
            self.assertEqual(after[app], TESTED["name"], app)
        self.assertEqual(after["42"], TESTED["name"])  # a mapping nobody recorded: no pick of 11-12.1 seen (a player log, 11-05)
        self.assertEqual(after["47"], UPSTREAM["name"])  # the player picked 11-12.1 in the client
        self.assertEqual(sorted(state["moved_back"]), ["0", "42", "43", "45", "46"])
        self.assertEqual(state["auto"].get("43"), TESTED["name"])  # still follows the default
        self.assertEqual(state["new_title_tools"]["45"], TESTED["name"])
        self.assertEqual(state["fallbacks"]["46"]["to"], TESTED["name"])
        for app in ("43", "45", "46"):
            self.assertRegex(self.out, r"app %s \(.*\): moved back from %s to %s" % (app, UPSTREAM["name"], TESTED["name"]))
        self.assertIn("default: moved back from %s to %s" % (UPSTREAM["name"], TESTED["name"]), self.out)
        # the next start changes nothing, and a title the player then puts back on 11-12.1 in Steam's menu (the live helper
        # records the pick) stays there
        mapping = sorted(dict(after, **{"43": UPSTREAM["name"]}).items())
        state.setdefault("chosen", {})["43"] = {"name": UPSTREAM["name"], "at": 2 ** 41}
        (self.state_dir / "state.json").write_text(json.dumps(state))
        config = self.steam / "config" / "config.vdf"
        config.write_text(config_text(mapping))
        with contextlib.redirect_stdout(io.StringIO()) as out:
            C["register_default"](str(config), ["42", "43", "45", "46", "47"], catalog=[VALVE, UPSTREAM, TESTED],
                                  directory=str(self.state_dir), settle=True)
        self.assertNotIn("moved back", out.getvalue())
        self.assertEqual(C["read_config_mapping"](str(config))["43"], UPSTREAM["name"])

    def test_a_default_the_player_set_is_never_moved_back(self):
        # the player set 11-12.1 as the general default in Steam's menu (the live helper recorded it in "chosen"): it, its
        # followers and titles on it stay
        default, after, state = self.v0552_moved(source="steam", chosen={"0": {"name": UPSTREAM["name"], "at": 1}})
        self.assertEqual(default, UPSTREAM["name"])
        self.assertEqual((after["0"], after["43"], after["42"], after["47"]), (UPSTREAM["name"],) * 4)
        self.assertNotIn("0", state.get("moved_back", {}))
        # the app's own decisions for single titles still go back
        self.assertEqual((after["45"], after["46"]), (TESTED["name"], TESTED["name"]))
        # picked in the client's menu as the general default, even with source "update"
        self.setUp()
        default, after, _ = self.v0552_moved(chosen={"0": {"name": UPSTREAM["name"], "at": 1}})
        self.assertEqual((default, after["0"], after["43"]), (UPSTREAM["name"],) * 3)
        # picked in the app (source "app", request.json applied): stays
        self.setUp()
        (self.state_dir / "request.json").write_text(json.dumps({"seq": 3, "valve": False, "dir": UPSTREAM["dir"]}))
        default, after, _ = self.v0552_moved(source="app")
        self.assertEqual((default, after["0"]), (UPSTREAM["name"],) * 2)
        # source "steam" with NO pick seen in Steam's menu (the client's config or a stale state; a player log, 11-05): goes back
        self.setUp()
        default, after, state = self.v0552_moved(source="steam")
        self.assertEqual((default, after["0"], after["43"], after["42"]), (TESTED["name"],) * 4)
        self.assertEqual(after["47"], UPSTREAM["name"])

    def test_the_move_back_switch(self):
        with mock.patch.dict(os.environ, {"DD_PROTON_MOVE_BACK": "0"}):
            default, after, state = self.v0552_moved()
        self.assertEqual(default, UPSTREAM["name"])
        self.assertNotIn("moved_back", state)

    def test_a_launch_without_steam_uses_the_validated_build(self):
        state = {"default": TOOL}
        tool, why = C["launch_tool"](state, str(self.steam), "48", {}, [TOOL, TESTED["name"], UPSTREAM["name"]],
                                     catalog=[VALVE, TESTED, UPSTREAM], environ=NO_LIST, now=1)
        self.assertEqual(tool, TESTED["name"])

    def test_the_session_install_asks_for_the_default_validated_tag_first(self):
        asked = []
        globals_ = EXTRA["pick_asset"].__globals__
        def fetch(url):
            asked.append(url)
            tag = url.rsplit("/", 1)[1]
            stem = tag + "-aarch64"
            return {"tag_name": tag, "assets": [{"name": stem + ".tar.gz", "browser_download_url": "https://x/" + stem + ".tar.gz"}]}
        with mock.patch.dict(globals_, {"fetch_json": fetch}):
            tag, url, _ = EXTRA["pinned_asset"](EXTRA["validated_tags"](NO_LIST))
        self.assertEqual(tag, "DroidDeck-Proton-11-11.10")
        self.assertEqual([u.rsplit("/", 1)[1] for u in asked], ["DroidDeck-Proton-11-11.10"])

    def test_the_session_install_takes_the_next_validated_tag_when_one_vanished(self):
        asked = []
        globals_ = EXTRA["pick_asset"].__globals__
        def fetch(url):
            asked.append(url)
            if url.endswith("/tags/DroidDeck-Proton-11-11.10"):
                raise EXTRA["urllib"].error.URLError("gone")
            tag = url.rsplit("/", 1)[1]
            stem = tag + "-aarch64"
            return {"tag_name": tag, "assets": [{"name": stem + ".tar.gz", "browser_download_url": "https://x/" + stem + ".tar.gz"}]}
        with mock.patch.dict(globals_, {"fetch_json": fetch}):
            tag, url, _ = EXTRA["pinned_asset"](EXTRA["validated_tags"](NO_LIST))
        self.assertEqual(tag, "DroidDeck-Proton-11-11.11")
        self.assertEqual([u.rsplit("/", 1)[1] for u in asked], ["DroidDeck-Proton-11-11.10", "DroidDeck-Proton-11-11.11"])
        self.assertIsNone(EXTRA["validated_tags"]({"DD_PROTON_PIN": "0"}))


class OneListTest(unittest.TestCase):
    def test_the_app_and_both_guest_scripts_have_the_same_list(self):
        kotlin = (ROOT / "app/src/main/java/com/droiddeck/launcher/session/ProtonExtras.kt").read_text()
        found = re.search(r"VALIDATED_OWN\s*=\s*listOf\(([^)]*)\)", kotlin)
        self.assertIsNotNone(found, "ProtonExtras.VALIDATED_OWN not found")
        app = tuple(re.findall(r'"([^"]+)"', found.group(1)))
        self.assertEqual(app, C["VALIDATED_OWN_BUILDS"])
        self.assertEqual(app, EXTRA["VALIDATED_OWN_BUILDS"])
        self.assertNotIn("DroidDeck-Proton-11-12.1", app)


if __name__ == "__main__":
    unittest.main()
