"""Release audit (t11, 11-05): the Proton move-back (37d1725e9) run on the state v0.5.5.2's OWN code leaves, not on a hand-written one.

The state is made by running v0.5.5.2's steam-compatibility (6ba556647, read from git) through the real sequence:
  1. a player on v0.5.5.2 with DroidDeck-Proton 11-11.11 (what v0.5.5.2 had auto-installed before 11-03) and games on it;
  2. v0.5.5.2's app installs 11-12.1, moves games.json picks to it (ProtonDefault.moveGames) and deletes 11-11.11 (replaceOlder);
  3. Steam starts on v0.5.5.2: steam-compatibility moves what was on the deleted build to 11-12.1 ("X was removed"), then the
     games run on 11-12.1 (their prefixes say 11.0-100);
  4. t11 is installed; its app installs the validated default 11-11.10 next to 11-12.1 (online, unmetered only);
  5. Steam starts on t11.
Every title in this fixture was put on 11-12.1 by v0.5.5.2's code, none by the player: after step 5 none should stay on 11-12.1,
and nothing may be deleted. Also: with 11-11.10 not installed yet (offline), nothing changes and nothing is recorded, so the move
happens at the first start after the app got it.

DD_TEST_SC_SCRIPT=<path> runs the guest steps 4-5 with another steam-compatibility (the t10 script, for the fail-before run)."""
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
BIN = ROOT / "tools/linuxfs/overlay/usr/local/bin"
NOW = runpy.run_path(os.environ.get("DD_TEST_SC_SCRIPT") or str(BIN / "steam-compatibility"))
V0552_COMMIT = "6ba556647"


def load_v0552():
    try:
        text = subprocess.run(["git", "-C", str(ROOT), "show", V0552_COMMIT + ":tools/linuxfs/overlay/usr/local/bin/steam-compatibility"],
                              check=True, capture_output=True, text=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    tmp = tempfile.NamedTemporaryFile("w", suffix="-steam-compatibility-v0552", delete=False)
    tmp.write(text)
    tmp.close()
    try:
        return runpy.run_path(tmp.name)
    finally:
        os.unlink(tmp.name)


OLD = load_v0552()
TOOL = NOW["TOOL"]


def own(version):
    return {"name": "DroidDeck-Proton-" + version, "display": "DroidDeck-Proton %s (Compatible)" % version,
            "dir": "DroidDeck-Proton " + version, "valve": False}


VALVE = {"name": TOOL, "display": "Proton Experimental ARM64", "dir": "Proton - Experimental (ARM64)", "valve": True}
B1010 = own("11-11.10")   # t11's default validated build
B1011 = own("11-11.11")   # what v0.5.5.2 players had before 11-03 (auto-installed 11-01)
B121 = own("11-12.1")     # v0.5.5.2 auto-installed it 11-03 20:31Z and deleted 11-11.11
GAMES_JSON = "games.json"


def config_text(entries):
    mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % (app, name, "75" if app == "0" else "250")
                      for app, name in entries)
    return '"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping


@unittest.skipIf(OLD is None, "v0.5.5.2's steam-compatibility (git %s) is not readable here" % V0552_COMMIT)
class MoveBackOnV0552StateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.steam = Path(self.tmp.name) / "Steam"
        (self.steam / "config").mkdir(parents=True)
        (self.steam / "steamapps" / "compatdata").mkdir(parents=True)
        self.state_dir = Path(self.tmp.name) / "compat"
        self.state_dir.mkdir()
        self.config = self.steam / "config" / "config.vdf"
        env = mock.patch.dict(os.environ, {"BL_VKBRIDGE": "1", "DD_PROTON_VALIDATED_FILE": "/nonexistent/proton-validated"})
        env.start()
        self.addCleanup(env.stop)
        self.log = []

    # --- helpers -----------------------------------------------------------------------------------------------------------
    def prefix(self, app, version="GE-Proton11-11"):
        path = self.steam / "steamapps" / "compatdata" / app
        path.mkdir(parents=True, exist_ok=True)
        (path / "version").write_text(version + "\n")

    def mapping(self):
        return NOW["read_config_mapping"](str(self.config))

    def set_mapping(self, **apps):
        current = dict(self.mapping() or {})
        current.update({k.lstrip("_"): v for k, v in apps.items()})
        self.config.write_text(config_text(sorted(current.items())))

    def state(self):
        return json.loads((self.state_dir / "state.json").read_text())

    def write_state(self, state):
        (self.state_dir / "state.json").write_text(json.dumps(state))

    def games_json(self, games):
        (self.state_dir / GAMES_JSON).write_text(json.dumps({"version": 1, "games": games}))

    def start(self, module, apps, catalog):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            default = module["register_default"](str(self.config), list(apps), catalog=catalog, directory=str(self.state_dir),
                                                 settle=True)
        self.log.append(out.getvalue())
        return default

    def v0552_update_to_121(self, apps, before):
        """Step 2 (v0.5.5.2's app: replaceOlder) + step 3 (a v0.5.5.2 Steam start, then the games ran on 11-12.1)."""
        games = json.loads((self.state_dir / GAMES_JSON).read_text()) if (self.state_dir / GAMES_JSON).exists() else None
        if games:
            games["games"] = {app: ({"valve": False, "dir": B121["dir"]} if req and not req["valve"] and req["dir"] == B1011["dir"]
                                    else req) for app, req in games["games"].items()}
            (self.state_dir / GAMES_JSON).write_text(json.dumps(games))
        catalog = [t for t in before if t["name"] != B1011["name"]] + [B121]
        self.start(OLD, apps, catalog)
        for app in apps:
            if (self.steam / "steamapps" / "compatdata" / app).exists() or app in ("45",):
                self.prefix(app, "11.0-100")  # 11-12.1 ran it
        self.start(OLD, apps, catalog)  # the next v0.5.5.2 start (settles what the client then showed)
        on_121 = {app: tool for app, tool in self.mapping().items() if tool == B121["name"]}
        return catalog, on_121

    # --- scenario A: fresh install (DroidDeck-Proton default by the app's update logic), games on it ---------------------------
    def scenario_a(self):
        self.config.write_text(config_text([("0", TOOL)]))
        self.write_state({"default": TOOL})
        catalog = [VALVE, B1011]
        self.assertEqual(self.start(OLD, [], catalog), B1011["name"])  # v0.5.5.2: the first default = the newest DroidDeck-Proton
        apps = ["42", "43", "44", "48"]
        for app in apps:
            self.prefix(app)
        # 42: picked 11-11.11 in the client (recorded by the live helper); 48: a tool on 11-11.11 the client wrote that nobody
        # recorded; 44: a per-game pick in the app; 43: follows the default.
        self.set_mapping(_42=B1011["name"], _48=B1011["name"])
        state = self.state()
        state["chosen"] = {"42": {"name": B1011["name"], "at": 1}}
        self.write_state(state)
        self.games_json({"44": {"valve": False, "dir": B1011["dir"]}})
        self.start(OLD, apps, catalog)
        before = self.mapping()
        self.assertEqual({a: before[a] for a in ["0"] + apps}, {a: B1011["name"] for a in ["0"] + apps}, self.log[-1])
        catalog, on_121 = self.v0552_update_to_121(apps, catalog)
        return apps, catalog, on_121

    # --- scenario B: install with games (Mali bridge kept Valve's default); a new title and a start-failure move ---------------
    def scenario_b(self):
        self.prefix("40")
        self.config.write_text(config_text([("0", TOOL), ("40", TOOL)]))
        self.write_state({"default": TOOL, "tools": [VALVE]})
        catalog = [VALVE, B1011]
        apps = ["40", "45", "46"]
        self.prefix("46")
        self.start(OLD, apps, catalog)  # 45 has no prefix: v0.5.5.2 starts it on the newest DroidDeck-Proton
        state = self.state()
        self.assertEqual(state.get("new_title_tools", {}).get("45"), B1011["name"], self.log[-1])
        # 46 closed twice on Experimental: v0.5.5.2's start-failure guard moved it (its record shape, line 1549)
        state.setdefault("fallbacks", {})["46"] = {"title": "46", "from": TOOL, "to": B1011["name"], "fails": 2, "at": 1,
                                                   "confirmed": False, "reason": "it closed before showing anything"}
        self.write_state(state)
        self.start(OLD, apps, catalog)
        self.assertEqual(self.mapping().get("46"), B1011["name"], self.log[-1])
        self.prefix("45")
        catalog, on_121 = self.v0552_update_to_121(apps, catalog)
        return apps, catalog, on_121

    def t11_start(self, apps, catalog):
        return self.start(NOW, apps, catalog)

    # --- tests ---------------------------------------------------------------------------------------------------------------
    def after_t11(self, scenario):
        apps, catalog, on_121 = scenario()
        self.assertTrue(on_121, "v0.5.5.2 should have put titles on 11-12.1:\n" + "\n".join(self.log))
        self.t11_start(apps, catalog + [B1010])  # step 4: t11's app installed the default validated build next to 11-12.1
        return on_121, self.mapping()

    def test_a_the_default_and_its_followers_go_back(self):
        on_121, after = self.after_t11(self.scenario_a)
        self.assertEqual(set(on_121), {"0", "42", "43", "44", "48"}, "\n".join(self.log))  # v0.5.5.2 moved all of them
        self.assertEqual((after["0"], after["43"]), (B1010["name"], B1010["name"]), self.log[-1])
        self.assertIn("default: moved back from %s to %s" % (B121["name"], B1010["name"]), self.log[-1])

    # GAP (audit 11-05): v0.5.5.2 moved these off the deleted 11-11.11 without a record the t11 script reads ("app N: X -> Y (X was
    # removed)" is only printed; the app's moveGames rewrote games.json; the live helper's pick record was forgotten at the next
    # start), so t11 treats them as the player's picks and leaves them on the unvalidated 11-12.1. Since fix/proton-adopt (11-05,
    # a player log) 42 and 48 go back (no pick of 11-12.1 seen); 44 (games.json, the app's per-game pick) still stays: it reads as an
    # in-app pick of 11-12.1, the same as one the player made.
    @unittest.expectedFailure
    def test_a_gap_client_pick_app_pick_and_unrecorded_mapping_on_the_deleted_build_go_back(self):
        on_121, after = self.after_t11(self.scenario_a)
        self.assertEqual({app: after[app] for app in ("42", "44", "48")}, {app: B1010["name"] for app in ("42", "44", "48")},
                         self.log[-1])

    # Was a GAP (audit 11-05): the new-title record still names 11-11.11 and the start-failure record was dropped by v0.5.5.2 ("not
    # installed any more"); both titles were then moved to 11-12.1 by the removed-build rewrite. Closed by fix/proton-adopt (11-05).
    def test_b_gap_new_title_and_start_failure_move_on_the_deleted_build_go_back(self):
        on_121, after = self.after_t11(self.scenario_b)
        self.assertEqual(set(on_121), {"45", "46"}, "\n".join(self.log))
        self.assertEqual((after["45"], after["46"]), (B1010["name"], B1010["name"]), self.log[-1])

    def test_b_nothing_is_left_on_the_unvalidated_build(self):
        on_121, after = self.after_t11(self.scenario_b)
        self.assertEqual({app: after[app] for app in on_121}, {"45": B1010["name"], "46": B1010["name"]}, self.log[-1])
        self.assertEqual(after["40"], TOOL)

    def test_a_only_the_apps_per_game_pick_is_left(self):
        on_121, after = self.after_t11(self.scenario_a)
        self.assertEqual({app: after[app] for app in on_121},
                         {"0": B1010["name"], "42": B1010["name"], "43": B1010["name"], "44": B121["name"], "48": B1010["name"]},
                         self.log[-1])

    def test_once_and_nothing_deleted_and_offline_waits(self):
        apps, catalog, on_121 = self.scenario_a()
        tools_before = sorted(t["name"] for t in catalog)
        # 11-11.10 not installed yet (offline / metered: the app installs it only online): nothing changes, nothing recorded
        before = self.mapping()
        self.t11_start(apps, catalog)
        self.assertEqual(self.mapping(), before, self.log[-1])
        self.assertNotIn("moved_back", self.state())
        # the script never deletes a tool (the app's tidy decides; 11-12.1 is the newest installed, never removed)
        self.assertEqual(sorted(t["name"] for t in catalog), tools_before)
        # once installed: what moves back moves once; a second start changes nothing
        self.t11_start(apps, catalog + [B1010])
        moved = self.mapping()
        self.t11_start(apps, catalog + [B1010])
        self.assertEqual(self.mapping(), moved)
        self.assertNotIn("moved back", self.log[-1])

    def test_a_pick_the_player_made_of_121_after_the_move_stays(self):
        apps, catalog, _ = self.scenario_a()
        # after v0.5.5.2's move the player picked 11-12.1 for 43 in the client (live helper record, newer than the move)
        self.set_mapping(_43=B121["name"])
        state = self.state()
        state.setdefault("chosen", {})["43"] = {"name": B121["name"], "at": 2 ** 41}
        self.write_state(state)
        self.t11_start(apps, catalog + [B1010])
        self.assertEqual(self.mapping()["43"], B121["name"], self.log[-1])


if __name__ == "__main__":
    unittest.main()
