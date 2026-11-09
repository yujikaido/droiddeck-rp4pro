"""Golden test (11-05): a player log, Tab S9 FE (Mali-G68), app 0.5.5.3-test12. Its first test12 start printed only

  steam-compatibility: adopted DroidDeck-Proton 11-11.10 as DroidDeck-Proton-11-11.10
  steam-compatibility: adopted DroidDeck-Proton 11-12.1 as DroidDeck-Proton-11-12.1
  steam-compatibility: default, 5 installed title(s) and 103 title(s) with a Valve pick set to DroidDeck-Proton-11-12.1

and the client then ran the default and every title on the unvalidated 11-12.1 (compat_log AppID 0 and the titles at priority
250). The test12-t15 move-back (37d1725e9) only undid a default whose record said source "update" and only once (state
"moved_back"), and never touched a mapping nobody recorded; any other record kept 11-12.1 without a line. The states below are
the ones that give exactly that silent start; the player made none of them. Each is built with the real scripts where it can be
(v0.5.5.1 source/v0.5.5.1 and v0.5.5.2 6ba556647 read from git, then the script under test), with 103 Valve-pick titles and 5
installed titles whose prefixes 11-12.1 already ran (11.0-100).

Fix (fix/proton-adopt): at every start a DroidDeck-Proton build MaliBridge did not validate stays the default or under a title
only when a record shows the player picked it (a pick in Steam's menu seen by the live helper, the app's default or per-game
pick); everything else goes to the default validated build (11-11.10).

DD_TEST_SC_SCRIPT=<path> runs the script under test from another file (the t12/t15 script, for the fail-before run)."""
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
SCRIPT = "tools/linuxfs/overlay/usr/local/bin/steam-compatibility"


def from_git(rev):
    try:
        text = subprocess.run(["git", "-C", str(ROOT), "show", rev + ":" + SCRIPT], check=True, capture_output=True, text=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    tmp = tempfile.NamedTemporaryFile("w", suffix="-steam-compatibility", delete=False)
    tmp.write(text)
    tmp.close()
    try:
        return runpy.run_path(tmp.name)
    finally:
        os.unlink(tmp.name)


V0551 = from_git("25373bedf")   # source/v0.5.5.1: the build same tablet, 11-02 ran, default 11-11.11
V0552 = from_git("6ba556647")   # v0.5.5.2: deleted 11-11.11, put everything on 11-12.1
TOOL = NOW["TOOL"]


def own(version):
    return {"name": "DroidDeck-Proton-" + version, "display": "DroidDeck-Proton %s (Compatible)" % version,
            "dir": "DroidDeck-Proton " + version, "valve": False}


VALVE = {"name": TOOL, "display": "Proton Experimental ARM64", "dir": "Proton - Experimental (ARM64)", "valve": True}
B1010, B1011, B121 = own("11-11.10"), own("11-11.11"), own("11-12.1")
PICKS = [str(10 * i) for i in range(1, 104)]          # 103 titles with a Valve pick
INSTALLED = ["220", "620", "1222670", "227300", "440"]  # 5 installed titles (620/1222670/227300 ran on 11-12.1 in a player log)
ALL = sorted(set(PICKS) | set(INSTALLED), key=int)
WGMG_CATALOG = [VALVE, B1010, B121]


def config_text(entries):
    mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % (app, name, "75" if app == "0" else "250")
                      for app, name in entries)
    return '"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping


@unittest.skipIf(V0551 is None or V0552 is None, "v0.5.5.1/v0.5.5.2 steam-compatibility not readable from git here")
class AdoptWgmgTest(unittest.TestCase):
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
        for var in ("DD_PROTON_PIN", "DD_PROTON_MOVE_BACK", "DD_COMPAT_RESPECT"):
            os.environ.pop(var, None)
        self.log = []

    # --- helpers -----------------------------------------------------------------------------------------------------------
    def mapping(self):
        return NOW["read_config_mapping"](str(self.config))

    def write_mapping(self, entries):
        self.config.write_text(config_text(sorted(entries.items(), key=lambda kv: int(kv[0]))))

    def state(self):
        return json.loads((self.state_dir / "state.json").read_text())

    def write_state(self, state):
        (self.state_dir / "state.json").write_text(json.dumps(state))

    def start(self, module, catalog, apps=INSTALLED):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            default = module["register_default"](str(self.config), list(apps), catalog=catalog, directory=str(self.state_dir),
                                                 settle=True, picks=list(PICKS))
            print("steam-compatibility: default, %d installed title(s) and %d title(s) with a Valve pick set to %s" % (
                len(apps), len(PICKS), default))
        self.log.append(out.getvalue())
        return default

    def on(self, name, mapping=None):
        mapping = self.mapping() if mapping is None else mapping
        return sorted((app for app, tool in mapping.items() if tool == name), key=int)

    def history(self):
        """11-02, v0.5.5.1: default 11-11.11 with the 103 Valve picks on it; then v0.5.5.2's app put 11-12.1 in its
        place and v0.5.5.2's script moved all of it to 11-12.1; 11-12.1 ran the installed titles."""
        self.write_mapping({"0": TOOL})
        self.write_state({"default": TOOL})
        self.start(V0551, [VALVE], apps=[])
        self.start(V0551, [VALVE, B1011], apps=[])
        self.start(V0551, [VALVE, B1011], apps=[])
        self.assertEqual(set(self.on(B1011["name"])), {"0"} | set(PICKS), self.log[-1])
        for app in INSTALLED:
            path = self.steam / "steamapps" / "compatdata" / app
            path.mkdir(parents=True, exist_ok=True)
            (path / "version").write_text("11.0-100\n")
        self.start(V0552, [VALVE, B121])
        self.start(V0552, [VALVE, B121])
        self.assertEqual(set(self.on(B121["name"])), {"0"} | set(ALL), self.log[-1])

    def assert_wgmg_fixed(self):
        """What the start must leave: nothing on 11-12.1, the default and every title on 11-11.10, a line for the default."""
        after = self.mapping()
        self.assertEqual(self.on(B121["name"], after), [], self.log[-1])
        self.assertEqual(after["0"], B1010["name"], self.log[-1])
        self.assertEqual(set(self.on(B1010["name"], after)), {"0"} | set(ALL), self.log[-1])
        self.assertIn("default: moved back from %s to %s" % (B121["name"], B1010["name"]), self.log[-1])
        self.assertIn("set to %s" % B1010["name"], self.log[-1])
        # and the next start is quiet and keeps it
        self.start(NOW, WGMG_CATALOG)
        self.assertEqual(self.mapping(), after, self.log[-1])
        self.assertNotIn("moved back", self.log[-1])

    # --- the states that give a player log's silent start --------------------------------------------------------------------------
    def test_the_client_put_back_what_the_move_back_moved(self):
        """The move-back moved everything once; the client's config then named 11-12.1 again (Steam writes config.vdf from what
        it holds), no pick in Steam's menu was seen. The next start took it as "you changed it in Steam" and every start after
        that was a player log's: silent, all on 11-12.1."""
        self.history()
        self.start(NOW, WGMG_CATALOG)
        moved = self.mapping()
        self.assertEqual(moved["0"], B1010["name"], self.log[-1])
        self.write_mapping({app: B121["name"] for app in moved})  # the client's own copy, written back
        self.start(NOW, WGMG_CATALOG)  # t12-t15: "you changed it in Steam", and every start after it is a player log's
        self.assert_wgmg_fixed()

    def test_a_default_taken_from_the_clients_config_without_a_pick_seen(self):
        """state source "steam" with no pick in Steam's menu recorded (no "chosen"): what a stale state.json, or the client's
        config naming 11-12.1, leaves. t12-t15 called that the player's pick and kept it, silently."""
        self.history()
        state = self.state()
        state.update(source="steam")
        state.pop("chosen", None)
        self.write_state(state)
        self.start(NOW, WGMG_CATALOG)
        self.assert_wgmg_fixed()

    def test_titles_nobody_recorded_on_the_unvalidated_build(self):
        """v0.5.5.2 moved titles off the build it deleted ("X was removed") with no record t12-t15 reads; they stayed."""
        self.history()
        state = self.state()
        state["auto"] = {app: tool for app, tool in state.get("auto", {}).items() if app not in INSTALLED}
        self.write_state(state)
        self.start(NOW, WGMG_CATALOG)
        self.assert_wgmg_fixed()

    def test_the_plain_v0552_state_still_goes_back(self):
        self.history()
        self.start(NOW, WGMG_CATALOG)
        self.assert_wgmg_fixed()

    # --- what must never move ---------------------------------------------------------------------------------------------------
    def test_a_default_picked_in_steams_menu_stays_with_its_followers(self):
        self.history()
        state = self.state()
        state.update(source="steam", chosen={"0": {"name": B121["name"], "at": 2 ** 41}})
        self.write_state(state)
        self.start(NOW, WGMG_CATALOG)
        after = self.mapping()
        self.assertEqual(set(self.on(B121["name"], after)), {"0"} | set(ALL), self.log[-1])
        self.assertNotIn("moved back", self.log[-1])

    def test_the_log_says_who_set_a_droiddeck_proton_default(self):
        """a player log's zip had no state.json: the session log now names the default's origin, so the next log shows it."""
        self.history()
        state = self.state()
        state.update(source="steam", chosen={"0": {"name": B121["name"], "at": 2 ** 41}})
        self.write_state(state)
        self.start(NOW, WGMG_CATALOG)
        self.assertIn("default %s: picked in Steam's menu; not validated by MaliBridge" % B121["name"], self.log[-1])
        state.update(default=B1010["name"], source="update", chosen={})
        self.write_state(state)
        self.write_mapping({"0": B1010["name"]})
        self.start(NOW, WGMG_CATALOG)
        self.assertIn("default %s: set by the app\n" % B1010["name"], self.log[-1])

    def test_a_default_picked_in_the_app_stays(self):
        self.history()
        state = self.state()
        state.update(source="app", applied=5)
        self.write_state(state)
        (self.state_dir / "request.json").write_text(json.dumps({"seq": 5, "valve": False, "dir": B121["dir"]}))
        self.start(NOW, WGMG_CATALOG)
        self.assertEqual(self.mapping()["0"], B121["name"], self.log[-1])
        self.assertNotIn("moved back", self.log[-1])

    def test_a_title_picked_in_steams_menu_or_in_the_app_stays(self):
        self.history()
        state = self.state()
        state["chosen"] = {"620": {"name": B121["name"], "at": 2 ** 41}}
        self.write_state(state)
        (self.state_dir / "games.json").write_text(json.dumps({"version": 1, "games": {"227300": {"valve": False, "dir": B121["dir"]}}}))
        self.start(NOW, WGMG_CATALOG)
        after = self.mapping()
        self.assertEqual(after["0"], B1010["name"], self.log[-1])
        self.assertEqual((after["620"], after["227300"]), (B121["name"], B121["name"]), self.log[-1])
        self.assertEqual(after["1222670"], B1010["name"], self.log[-1])

    def test_nothing_moves_without_a_validated_build_or_with_the_switches_off(self):
        self.history()
        before = self.mapping()
        self.start(NOW, [VALVE, B121])  # 11-11.10 not installed yet (offline): nothing to move to
        self.assertEqual(self.mapping(), before, self.log[-1])
        for env in ({"DD_PROTON_MOVE_BACK": "0"}, {"DD_PROTON_PIN": "off"}):
            with mock.patch.dict(os.environ, env):
                self.start(NOW, WGMG_CATALOG)
            self.assertEqual(self.mapping()["0"], B121["name"], (env, self.log[-1]))
            self.assertNotIn("moved back", self.log[-1])

    def test_a_validated_default_and_validated_titles_are_left_alone(self):
        self.write_mapping({"0": B1010["name"], "1222670": B1011["name"]})
        self.write_state({"default": B1010["name"], "source": "steam", "own_proton": True})
        self.start(NOW, [VALVE, B1010, B1011, B121])
        after = self.mapping()
        self.assertEqual((after["0"], after["1222670"]), (B1010["name"], B1011["name"]), self.log[-1])
        self.assertNotIn("moved back", self.log[-1])


if __name__ == "__main__":
    unittest.main()
