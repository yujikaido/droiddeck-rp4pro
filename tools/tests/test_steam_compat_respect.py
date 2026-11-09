"""mod/compat-tool-respect: a tool the player picked (the general default or one game) survives restarts, and a
title is not moved onto a tool its prefix cannot take (a player log / a player log, Mad Max)."""
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
LIVE = runpy.run_path(str(BIN / "droiddeck-steam-compat"))
C = LIVE["COMPAT"]
TOOL = C["TOOL"]
EXT = "DroidDeck-Proton-11-11.11"
EXT_DIR = "DroidDeck-Proton 11-11.11"
MAD_MAX = "234140"
VDF = '"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }'
ENTRY = '"%s" { "name" "%s" "config" "" "priority" "%s" }'
VALVE_PREFIX = "11.0-100"
GE_PREFIX = "GE-Proton11-11"


class Fixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.steam = base / "Steam"
        (self.steam / "config").mkdir(parents=True)
        for name in C["SOURCES"]:
            depot = self.steam / "steamapps/common" / name
            (depot / "files/bin-arm64").mkdir(parents=True)
            (depot / "proton").write_text('#!/usr/bin/env python3\nCURRENT_PREFIX_VERSION = "%s"\n' % VALVE_PREFIX)
        tool = self.steam / "compatibilitytools.d" / EXT_DIR
        tool.mkdir(parents=True)
        (tool / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/proton %verb%" }')
        (tool / "compatibilitytool.vdf").write_text(C["EXTRA_COMPAT_TOOL_VDF"] % (EXT, EXT_DIR))
        (tool / C["EXTRA_WRAPPER"]).write_text("#!/bin/sh\n")
        (tool / "proton").write_text('#!/usr/bin/env python3\nCURRENT_PREFIX_VERSION = "%s"\n' % GE_PREFIX)
        self.state = base / "compat"
        self.state.mkdir()
        g = C["main"].__globals__
        g["COMPAT_DIR"] = str(self.state)
        g["LABEL_FILE"] = str(base / "no-label")
        self.addCleanup(g.__setitem__, "COMPAT_DIR", C["COMPAT_DIR"])
        self.config = self.steam / "config/config.vdf"
        self.catalog = C["tool_catalog"](str(self.steam))
        self.names = [t["name"] for t in self.catalog]
        self.assertIn(EXT, self.names)
        self.assertIn(TOOL, self.names)
        for app in (MAD_MAX,):
            (self.steam / ("steamapps/appmanifest_%s.acf" % app)).write_text("")

    def write_state(self, **state):
        (self.state / "state.json").write_text(json.dumps(state))

    def read_state(self):
        return json.loads((self.state / "state.json").read_text())

    def write_config(self, mapping, age=None):
        self.config.write_text(VDF % " ".join(ENTRY % (a, t, "75" if a == "0" else "250") for a, t in mapping.items()))
        if age is not None:
            then = (self.config.stat().st_mtime - age)
            os.utime(self.config, (then, then))

    def mapping(self):
        return C["read_config_mapping"](str(self.config))

    def prefix(self, app, version):
        folder = self.steam / "steamapps/compatdata" / app
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "version").write_text(version + "\n")
        (folder / "pfx").mkdir(exist_ok=True)
        return folder

    def start(self, apps=(MAD_MAX,), respect=True, picks=()):
        """One session start: the script before the client runs."""
        out = io.StringIO()
        env = {"DD_COMPAT_RESPECT": "1" if respect else "0"}
        with mock.patch.dict(os.environ, env), mock.patch.dict(C["register_default"].__globals__, steam_running=lambda: False), \
                contextlib.redirect_stdout(out):
            default = C["register_default"](str(self.config), list(apps), catalog=self.catalog, directory=str(self.state),
                                            settle=True, picks=list(picks))
        return default, out.getvalue()


class GlobalDefaultTest(Fixture):
    """Candidate (a): the player changed Steam's general default in the client."""

    def test_a_general_default_changed_in_the_client_is_kept_every_session(self):
        self.write_state(default=TOOL, auto={MAD_MAX: TOOL}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": EXT, MAD_MAX: EXT})
        for _ in range(3):
            default, out = self.start()
            self.assertEqual(default, EXT)
            self.assertEqual(self.mapping(), {"0": EXT, MAD_MAX: EXT})
        self.assertEqual(self.read_state()["source"], "steam")
        self.assertIn("default", out + "default")

    def test_the_script_says_why_the_default_moved(self):
        self.write_state(default=TOOL, auto={MAD_MAX: TOOL}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": EXT, MAD_MAX: TOOL})
        _, out = self.start()
        self.assertIn("app %s: %s -> %s (it follows the default (was %s))" % (MAD_MAX, TOOL, EXT, TOOL), out)
        self.assertEqual(self.mapping()[MAD_MAX], EXT)

    def test_a_picked_default_the_client_has_not_saved_yet_is_put_back(self):
        # The player picked EXT; the client's config.vdf (older than the pick) still shows ours.
        at = C["now_ms"]()
        self.write_state(default=EXT, auto={MAD_MAX: TOOL}, tools=self.catalog, own_proton=True, own_builds=[EXT],
                         chosen={"0": {"name": EXT, "at": at}})
        self.write_config({"0": TOOL, MAD_MAX: TOOL}, age=60)
        default, out = self.start()
        self.assertEqual(default, EXT)
        self.assertEqual(self.mapping(), {"0": EXT, MAD_MAX: EXT})
        self.assertIn("you picked in Steam", out)

    def test_a_default_the_client_saved_after_the_pick_wins(self):
        at = C["now_ms"]() - 60000
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT],
                         chosen={"0": {"name": EXT, "at": at}})
        self.write_config({"0": TOOL, MAD_MAX: TOOL})
        default, _ = self.start()
        self.assertEqual(default, TOOL)
        self.assertNotIn("0", self.read_state()["chosen"])

    def test_the_old_behaviour_resets_nothing_either_but_forgets_the_pick(self):
        self.write_state(default=TOOL, auto={MAD_MAX: TOOL}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": EXT, MAD_MAX: EXT})
        default, _ = self.start(respect=False)
        self.assertEqual(default, EXT)
        self.assertNotIn("chosen", self.read_state())

    def test_an_installed_default_the_client_does_not_list_is_not_replaced_by_ours(self):
        state = {"default": EXT}
        listed = [t for t in self.catalog if t["name"] != EXT]
        default, new = C["choose_default"]("", state, {}, listed, disk=set(self.names))
        self.assertEqual(default, EXT)
        self.assertEqual(new["default"], EXT)
        # without the switch: the earlier fall back to ours
        default, new = C["choose_default"]("", dict(state), {}, listed)
        self.assertEqual(default, TOOL)

    def test_a_default_that_is_gone_from_the_disk_falls_back_to_ours(self):
        listed = [t for t in self.catalog if t["name"] != EXT]
        default, _ = C["choose_default"]("", {"default": EXT}, {}, listed, disk={t["name"] for t in listed})
        self.assertEqual(default, TOOL)


class PerGameTest(Fixture):
    """Candidate (b): a per-game Force-tool pick, equal to what the app would have set, or named oddly."""

    def test_a_per_game_pick_survives_restarts(self):
        self.write_state(default=TOOL, auto={MAD_MAX: TOOL}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": TOOL, MAD_MAX: EXT})
        for _ in range(3):
            self.start()
            self.assertEqual(self.mapping(), {"0": TOOL, MAD_MAX: EXT})
        self.assertNotIn(MAD_MAX, self.read_state()["auto"])

    def test_a_pick_equal_to_the_followed_default_is_a_follower_without_the_players_record(self):
        # This is the hole: the same pick, the default then moves, and the game goes with it.
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": TOOL, MAD_MAX: EXT})
        self.start(respect=False)
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)

    def test_a_recorded_pick_equal_to_the_default_stays_when_the_default_moves(self):
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT],
                         chosen={MAD_MAX: {"name": EXT, "at": C["now_ms"]() - 1000}})
        self.write_config({"0": TOOL, MAD_MAX: EXT})
        default, _ = self.start()
        self.assertEqual(default, TOOL)
        self.assertEqual(self.mapping(), {"0": TOOL, MAD_MAX: EXT})
        state = self.read_state()
        self.assertNotIn(MAD_MAX, state["auto"])
        self.assertEqual(state["chosen"][MAD_MAX]["name"], EXT)

    def test_a_recorded_pick_the_client_has_not_saved_is_restored(self):
        self.write_state(default=TOOL, auto={}, tools=self.catalog, own_proton=True, own_builds=[EXT],
                         chosen={MAD_MAX: {"name": EXT, "at": C["now_ms"]()}})
        self.write_config({"0": TOOL}, age=60)
        _, out = self.start()
        self.assertEqual(self.mapping()[MAD_MAX], EXT)
        self.assertIn("which the client had not saved", out)

    def test_a_recorded_pick_the_client_now_shows_otherwise_is_forgotten(self):
        self.write_state(default=TOOL, auto={}, tools=self.catalog, own_proton=True, own_builds=[EXT],
                         chosen={MAD_MAX: {"name": EXT, "at": C["now_ms"]() - 60000}})
        self.write_config({"0": TOOL, MAD_MAX: C["FEX_TOOL"]})
        _, out = self.start()
        self.assertEqual(self.mapping()[MAD_MAX], C["FEX_TOOL"])
        self.assertNotIn(MAD_MAX, self.read_state()["chosen"])
        self.assertIn("forgot your pick", out)

    def test_names_differing_in_case_still_run_but_the_directory_name_is_not_a_tool(self):
        runnable = {name.lower() for name in self.names}
        self.assertFalse(C["unrunnable"](EXT.lower(), runnable))
        self.assertFalse(C["unrunnable"](EXT.upper(), runnable))
        # The directory name (with the space) is not a name the client knows: a mapping to it is stale.
        self.assertTrue(C["unrunnable"](EXT_DIR, runnable))
        # What Steam writes is the tool's internal name, which the adopted extra keeps.
        self.assertEqual(C["first_key"](str(self.steam / "compatibilitytools.d" / EXT_DIR / "compatibilitytool.vdf"),
                                        ["compatibilitytools", "compat_tools"]), EXT)

    def test_a_tool_on_the_disk_but_not_listed_is_not_stale(self):
        changes, _ = C["plan_mapping"]({"0": TOOL, MAD_MAX: EXT}, [MAD_MAX], [TOOL], TOOL, {MAD_MAX: TOOL})
        self.assertEqual(changes, {MAD_MAX: TOOL})
        changes, _ = C["plan_mapping"]({"0": TOOL, MAD_MAX: EXT}, [MAD_MAX], [TOOL], TOOL, {MAD_MAX: TOOL}, present=[EXT])
        self.assertEqual(changes, {})


class NothingChosenTest(Fixture):
    """The switch may be on only if titles nobody chose behave as before."""

    SCENARIOS = {
        "new install": ({"0": TOOL}, {}),
        "follower": ({"0": TOOL, MAD_MAX: TOOL, "10": TOOL}, {"default": TOOL, "auto": {MAD_MAX: TOOL, "10": TOOL}}),
        "valve pick kept": ({"0": TOOL, MAD_MAX: "proton-stable"}, {"default": TOOL, "auto": {"10": TOOL}}),
        "default moved in the client": ({"0": EXT, MAD_MAX: TOOL, "10": TOOL}, {"default": TOOL, "auto": {MAD_MAX: TOOL, "10": TOOL}}),
        "stale tool": ({"0": TOOL, MAD_MAX: "GE-Proton9-1"}, {"default": TOOL, "auto": {MAD_MAX: "GE-Proton9-1"}}),
        "removed default": ({"0": "droiddeck-proton-old", MAD_MAX: TOOL}, {"default": "droiddeck-proton-old", "auto": {MAD_MAX: TOOL}}),
    }

    def run_scenario(self, respect, mapping, state):
        for name in ("state.json",):
            path = self.state / name
            if path.exists():
                path.unlink()
        self.write_config(mapping)
        if state:
            self.write_state(tools=self.catalog, own_proton=True, own_builds=[EXT], **state)
        default, _ = self.start(apps=(MAD_MAX, "10"), respect=respect)
        saved = self.read_state()
        for key in ("chosen", "prefix_warnings", "valve_builds", "seq"):
            saved.pop(key, None)
        return default, " ".join(self.config.read_text().split()), saved

    def test_same_config_and_state_with_the_switch_on_and_off(self):
        for label, (mapping, state) in self.SCENARIOS.items():
            with self.subTest(label):
                self.assertEqual(self.run_scenario(True, mapping, state), self.run_scenario(False, mapping, state))

    def test_the_live_helper_plans_the_same_with_the_switch_on_and_off(self):
        for label, (mapping, state) in self.SCENARIOS.items():
            results = []
            for respect in (True, False):
                with self.subTest(label, respect=respect):
                    if (self.state / "state.json").exists():
                        (self.state / "state.json").unlink()
                    if state:
                        self.write_state(tools=self.catalog, own_proton=True, own_builds=[EXT], **state)
                    page = FakePage(mapping, self.names)
                    helper = LIVE["Helper"](str(self.steam), 1)
                    helper.page = page
                    with mock.patch.dict(os.environ, {"DD_COMPAT_RESPECT": "1" if respect else "0"}), \
                            contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                        helper.reconcile()
                    saved = self.read_state()
                    for key in ("chosen", "prefix_warnings", "valve_builds", "seq"):
                        saved.pop(key, None)
                    results.append((page.sets, saved))
            self.assertEqual(results[0], results[1], label)


class FakePage:
    def __init__(self, mapping, known):
        self.mapping = dict(mapping)
        self.known = list(known)
        self.sets = []

    def compat(self, call):
        name, _, args = call.partition("(")
        args = json.loads("[%s]" % args[:-1])
        if name == "get":
            return {"selected": self.mapping.get(str(args[0]), ""), "fallback": "proton_experimental", "tools": self.known}
        if name == "many":
            return {app: self.mapping.get(app, "") for app in args[0]}
        if name == "set":
            self.mapping[str(args[0])] = args[1]
            self.sets.append((str(args[0]), args[1]))
            return True
        raise AssertionError(call)


class LiveTest(Fixture):
    def setUp(self):
        super().setUp()
        self.helper = LIVE["Helper"](str(self.steam), 1)
        self.said = io.StringIO()

    def reconcile(self, page):
        self.helper.page = page
        with mock.patch.dict(os.environ, {"DD_COMPAT_RESPECT": "1"}), contextlib.redirect_stderr(self.said), contextlib.redirect_stdout(self.said):
            self.helper.reconcile()
        return self.read_state()

    def test_a_client_that_has_not_listed_the_default_yet_changes_nothing(self):
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        page = FakePage({"0": EXT, MAD_MAX: EXT}, [t for t in self.names if t != EXT])
        state = self.reconcile(page)
        self.assertEqual(page.sets, [])
        self.assertEqual(state["default"], EXT)
        self.assertIsNotNone(self.helper.retry_at)
        self.assertIn("does not list %s yet" % EXT, self.said.getvalue())
        # listed a moment later: still nothing to change
        page.known = self.names
        state = self.reconcile(page)
        self.assertEqual((page.sets, state["default"]), ([], EXT))

    def test_the_old_planning_would_have_reset_it(self):
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        page = FakePage({"0": EXT, MAD_MAX: EXT}, [t for t in self.names if t != EXT])
        self.helper.page = page
        with mock.patch.dict(os.environ, {"DD_COMPAT_RESPECT": "0"}), contextlib.redirect_stderr(self.said), contextlib.redirect_stdout(self.said):
            self.helper.reconcile()
        self.assertEqual(self.read_state()["default"], TOOL)
        self.assertIn(("0", TOOL), page.sets)

    def test_a_pick_seen_in_the_ui_is_recorded_once_the_mapping_shows_it(self):
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        page = FakePage({"0": EXT, MAD_MAX: EXT}, self.names)
        self.helper.note_pick("%s\t%s" % (MAD_MAX, EXT))
        state = self.reconcile(page)
        self.assertEqual(state["chosen"][MAD_MAX]["name"], EXT)
        self.assertNotIn(MAD_MAX, state["auto"])
        # the default moves (the app asks for ours); the picked game stays
        (self.state / "request.json").write_text(json.dumps({"seq": 99, "valve": True, "dir": "Proton Experimental (ARM64)"}))
        state = self.reconcile(page)
        self.assertEqual(page.mapping, {"0": TOOL, MAD_MAX: EXT})
        self.assertEqual(state["default"], TOOL)

    def test_without_the_record_the_same_game_goes_with_the_default(self):
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        page = FakePage({"0": EXT, MAD_MAX: EXT}, self.names)
        (self.state / "request.json").write_text(json.dumps({"seq": 99, "valve": True, "dir": "Proton Experimental (ARM64)"}))
        self.reconcile(page)
        self.assertEqual(page.mapping[MAD_MAX], TOOL)

    def test_a_claim_the_mapping_never_shows_is_dropped(self):
        self.write_state(default=TOOL, auto={}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        page = FakePage({"0": TOOL, MAD_MAX: TOOL}, self.names)
        self.helper.claims.append((MAD_MAX, EXT, C["now_ms"]() - 60000))
        state = self.reconcile(page)
        self.assertNotIn(MAD_MAX, state.get("chosen", {}))
        self.assertEqual(self.helper.claims, [])

    def test_clearing_a_pick_forgets_it(self):
        self.write_state(default=TOOL, auto={}, tools=self.catalog, own_proton=True, own_builds=[EXT],
                         chosen={MAD_MAX: {"name": EXT, "at": 1}})
        page = FakePage({"0": TOOL, MAD_MAX: EXT}, self.names)
        self.helper.note_pick("%s\t" % MAD_MAX)
        state = self.reconcile(page)
        self.assertNotIn(MAD_MAX, state["chosen"])
        self.assertIn("you cleared your pick", self.said.getvalue())

    def test_garbage_from_the_page_is_ignored(self):
        for payload in (None, 5, "0234\tx", "abc\tx", "12\t" + "x" * 300, "12\tab\ncd", "99999999999\tx"):
            self.helper.note_pick(payload)
        self.assertEqual(self.helper.claims, [])

    def test_a_live_follower_change_is_logged_with_its_reason(self):
        self.write_state(default=TOOL, auto={MAD_MAX: TOOL}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        page = FakePage({"0": EXT, MAD_MAX: TOOL}, self.names)
        self.reconcile(page)
        self.assertIn("app %s -> %s (it follows the default" % (MAD_MAX, EXT), self.said.getvalue())


class PrefixTest(Fixture):
    def test_the_two_directions_and_a_newer_prefix(self):
        risk = C["prefix_risk"]
        self.assertEqual(risk(GE_PREFIX, VALVE_PREFIX), "family")
        self.assertEqual(risk(VALVE_PREFIX, GE_PREFIX), "family")
        self.assertEqual(risk("GE-Proton11-12", GE_PREFIX), "newer")
        self.assertEqual(risk("11.0-200", "11.0-100"), "newer")
        self.assertIsNone(risk(GE_PREFIX, "GE-Proton11-12"))
        self.assertIsNone(risk("11.0-100", "11.0-200"))
        self.assertIsNone(risk(GE_PREFIX, GE_PREFIX))
        self.assertIsNone(risk(None, GE_PREFIX))
        self.assertIsNone(risk(GE_PREFIX, None))

    def test_the_tools_prefix_versions_are_read_from_their_proton_scripts(self):
        by = {t["name"]: t for t in self.catalog}
        self.assertEqual(C["tool_prefix_version"](str(self.steam), by[TOOL]), VALVE_PREFIX)
        self.assertEqual(C["tool_prefix_version"](str(self.steam), by[EXT]), GE_PREFIX)
        self.assertEqual(C["prefix_version"](str(self.steam), MAD_MAX), None)
        self.prefix(MAD_MAX, GE_PREFIX)
        self.assertEqual(C["prefix_version"](str(self.steam), MAD_MAX), GE_PREFIX)

    def test_a_follower_is_not_moved_onto_a_tool_its_prefix_cannot_take_ml_ckex(self):
        # Mad Max ran on DroidDeck-Proton (prefix GE-Proton11-11); the default goes back to ours.
        folder = self.prefix(MAD_MAX, GE_PREFIX)
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": TOOL, MAD_MAX: EXT})
        default, out = self.start()
        self.assertEqual(default, TOOL)
        self.assertEqual(self.mapping()[MAD_MAX], EXT)
        self.assertIn("app %s: kept %s (it would move to %s" % (MAD_MAX, EXT, TOOL), out)
        self.assertNotIn("WARNING", out)
        self.assertEqual(self.read_state()["auto"], {MAD_MAX: EXT})
        self.assertTrue((folder / "pfx").is_dir())
        self.assertEqual((folder / "version").read_text().strip(), GE_PREFIX)

    def test_and_the_other_way_round_ml_23qy(self):
        self.prefix(MAD_MAX, VALVE_PREFIX)
        self.write_state(default=TOOL, auto={MAD_MAX: TOOL}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": EXT, MAD_MAX: TOOL})
        default, out = self.start()
        self.assertEqual(default, EXT)
        self.assertEqual(self.mapping(), {"0": EXT, MAD_MAX: TOOL})
        self.assertIn("kept %s" % TOOL, out)
        # every session says the same and still changes nothing
        _, again = self.start()
        self.assertIn("kept %s" % TOOL, again)
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)

    def test_a_title_that_has_to_change_changes_with_a_plain_warning(self):
        folder = self.prefix(MAD_MAX, GE_PREFIX)
        self.write_state(default=TOOL, auto={MAD_MAX: "proton-9.0-old"}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": TOOL, MAD_MAX: "proton-9.0-old"})
        _, out = self.start()
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)
        self.assertIn("WARNING app %s: its prefix was made by a GE-based Proton (DroidDeck-Proton) %s" % (MAD_MAX, GE_PREFIX), out)
        self.assertIn("Prefix has an invalid version", out)
        self.assertIn("back and forth", out)
        self.assertIn("The prefix is not deleted", out)
        self.assertEqual(self.read_state()["prefix_warnings"][MAD_MAX][:10], "app 234140")
        self.assertTrue(folder.is_dir())

    def test_the_players_per_game_pick_from_the_app_is_made_with_a_warning(self):
        self.prefix(MAD_MAX, GE_PREFIX)
        self.write_state(default=TOOL, auto={}, tools=self.catalog, own_proton=True, own_builds=[EXT], games_applied={})
        self.write_config({"0": TOOL, MAD_MAX: EXT})
        (self.state / "games.json").write_text(json.dumps({"version": 1, "games": {MAD_MAX: {"valve": True, "dir": "Proton Experimental (ARM64)"}}}))
        _, out = self.start()
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)
        self.assertIn("(the app's per-game pick)", out)
        self.assertIn("WARNING app %s" % MAD_MAX, out)

    def test_the_warning_is_printed_even_with_the_switch_off_and_the_change_is_then_made(self):
        self.prefix(MAD_MAX, GE_PREFIX)
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": TOOL, MAD_MAX: EXT})
        _, out = self.start(respect=False)
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)
        self.assertIn("WARNING app %s" % MAD_MAX, out)

    def test_a_title_with_no_prefix_or_a_matching_one_is_moved_as_before(self):
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": TOOL, MAD_MAX: EXT})
        _, out = self.start()
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)
        self.assertNotIn("WARNING", out)
        self.prefix(MAD_MAX, VALVE_PREFIX)
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": TOOL, MAD_MAX: EXT})

    def test_a_title_running_on_the_wrong_tool_already_gets_the_warning_each_session(self):
        # Mad Max after the incident: prefix by Valve's Proton, mapped to DroidDeck-Proton by the player.
        self.prefix(MAD_MAX, VALVE_PREFIX)
        self.write_state(default=EXT, auto={}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        self.write_config({"0": EXT, MAD_MAX: EXT})
        _, out = self.start()
        self.assertIn("WARNING app %s: its prefix was made by Valve's Proton %s" % (MAD_MAX, VALVE_PREFIX), out)

    def test_the_live_helper_holds_back_and_warns_too(self):
        self.prefix(MAD_MAX, GE_PREFIX)
        self.write_state(default=EXT, auto={MAD_MAX: EXT}, tools=self.catalog, own_proton=True, own_builds=[EXT])
        page = FakePage({"0": EXT, MAD_MAX: EXT}, self.names)
        (self.state / "request.json").write_text(json.dumps({"seq": 99, "valve": True, "dir": "Proton Experimental (ARM64)"}))
        helper = LIVE["Helper"](str(self.steam), 1)
        helper.page = page
        said = io.StringIO()
        with mock.patch.dict(os.environ, {"DD_COMPAT_RESPECT": "1"}), contextlib.redirect_stderr(said), contextlib.redirect_stdout(said):
            helper.reconcile()
        self.assertEqual(page.sets, [("0", TOOL)])
        self.assertEqual(page.mapping[MAD_MAX], EXT)
        self.assertIn("app %s: kept %s" % (MAD_MAX, EXT), said.getvalue())


class ValveBuildTest(Fixture):
    def manifest(self, build):
        (self.steam / ("steamapps/appmanifest_%s.acf" % C["PROTON_ARM64_APPID"])).write_text(
            '"AppState" { "appid" "%s" "buildid" "%s" }' % (C["PROTON_ARM64_APPID"], build))

    def test_a_valve_update_is_logged_with_the_before_and_after(self):
        self.manifest("26116000")
        self.write_config({"0": TOOL})
        _, first = self.start()
        self.assertNotIn("Valve updated", first)
        self.manifest("26306334")
        _, second = self.start()
        self.assertIn("Valve updated tool %s (Proton Experimental ARM64, droiddeck-proton-arm64): BuildID 26116000 -> 26306334"
                      % C["PROTON_ARM64_APPID"], second)
        _, third = self.start()
        self.assertNotIn("Valve updated", third)


@unittest.skipUnless(shutil.which("node"), "node is not installed")
class PickHookTest(unittest.TestCase):
    HARNESS = r"""
globalThis.window = globalThis;
const enc = new TextEncoder();
const vint = (n, out) => { n = BigInt(n); do { let x = Number(n & 0x7fn); n >>= 7n; if (n) x |= 0x80; out.push(x); } while (n); };
const str = (f, s, out) => { const b = enc.encode(s); vint(f * 8 + 2, out); vint(b.length, out); for (const x of b) out.push(x); };
const frame = (method, app, name) => {
  const h = [], b = [];
  vint(10 * 8 + 1, h); for (let i = 0; i < 8; i++) h.push(i === 0 ? 7 : 0);
  str(12, method, h);
  if (app !== null) { vint(1 * 8, b); vint(app, b); }
  if (name !== null) str(2, name, b);
  const out = new Uint8Array(8 + h.length + b.length), dv = new DataView(out.buffer);
  dv.setUint32(0, (0x80000000 | 146) >>> 0, true); dv.setUint32(4, h.length, true);
  out.set(h, 8); out.set(b, 8 + h.length);
  return out;
};
class FakeSocket { send(data) { sent.push(data); } }
const sent = [], payloads = [];
globalThis.WebSocket = FakeSocket;
window.droiddeckCompatPicked = (p) => payloads.push(p);
const install = require("fs").readFileSync(process.argv[2], "utf8");
if (eval(install) !== "installed" || eval(install) !== "present") throw new Error("install is not idempotent");
const ui = new FakeSocket();
ui.send(frame("CompatManager.SpecifyCompatTool#1", 234140, "DroidDeck-Proton-11-11.11"));
ui.send(frame("CompatManager.SpecifyCompatTool#1", 234140, ""));
ui.send(frame("CompatManager.SpecifyCompatTool#1", null, "droiddeck-proton-arm64"));
ui.send(frame("CompatManager.GetCompatTools#1", 234140, null));
ui.send(frame("Other.Call#1", 1, "x"));
ui.send(new Uint8Array(3));
ui.send("text frame");
ui.send(frame("CompatManager.SpecifyCompatTool#1", 5, "own").buffer);
window.__droiddeckCompat = { ws: new FakeSocket() };
window.__droiddeckCompat.ws.send(frame("CompatManager.SpecifyCompatTool#1", 7, "ours"));
console.log(JSON.stringify({ payloads, sent: sent.length }));
"""

    def test_the_ui_calls_are_reported_and_the_helpers_own_are_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            script = Path(tmp) / "pick.js"
            script.write_text(LIVE["PICK_JS"])
            harness = Path(tmp) / "harness.js"
            harness.write_text(self.HARNESS)
            result = subprocess.run(["node", str(harness), str(script)], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            out = json.loads(result.stdout)
        self.assertEqual(out["payloads"], ["234140\tDroidDeck-Proton-11-11.11", "234140\t", "0\tdroiddeck-proton-arm64", "5\town"])
        self.assertEqual(out["sent"], 9)


class SwitchTest(unittest.TestCase):
    def test_the_switch(self):
        self.assertTrue(C["respect_enabled"]({}))
        self.assertTrue(C["respect_enabled"]({"DD_COMPAT_RESPECT": "1"}))
        for off in ("0", "off", "False", " no "):
            self.assertFalse(C["respect_enabled"]({"DD_COMPAT_RESPECT": off}), off)


if __name__ == "__main__":
    unittest.main()
