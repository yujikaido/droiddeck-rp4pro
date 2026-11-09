"""fix/v0552-games: the start-failure guard and the DroidDeck-Proton start for new titles also cover non-Steam shortcuts and
games started without the Steam client (droiddeck-proton-run --app; Resident Evil 3 on Proton Experimental, 11-03), Steam's
log names a shortcut by its 64-bit game id, and a run that showed frames says so in the log (the next start's
"(1 in a row)" was read as the verdict on the run that had presented 87 frames)."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_steam_compat_fallback import CHILD, GONE, Base, stamp  # noqa: E402
from test_steam_compat_respect import BIN, C, EXT, EXT_DIR, MAD_MAX, TOOL  # noqa: E402

SHORTCUT = "2426315322"  # the id config.vdf and the app use; Steam's game log uses (id << 32) | 0x02000000
SHORTCUT_GAME = str(int(SHORTCUT) << 32 | 0x02000000)
SHORTCUT_LAUNCH = ("[%(when)s] AppID %(logged)s adding PID %(pid)d as a tracked process \"/root/.local/share/Steam/linuxarm64/"
                   "steam-launch-wrapper --oom-score-adjust 900 -- /root/.local/share/Steam/steamrtarm64/reaper SteamLaunch "
                   "AppId=%(app)s -- '/root/.local/share/Steam/compatibilitytools.d/%(tool)s'/droiddeck-proton waitforexitandrun  "
                   "\"/root/Storage/Download/RE3/re3.exe\"\"\n")


def shortcut_run(pid=3000, start=0, length=7, tool=TOOL, children=(3001,)):
    text = SHORTCUT_LAUNCH % {"when": stamp(start), "logged": SHORTCUT_GAME, "app": SHORTCUT, "pid": pid, "tool": tool}
    for child in children:
        text += CHILD % {"when": stamp(start), "app": SHORTCUT_GAME, "pid": child}
    for child in children:
        text += GONE % {"when": stamp(start + length), "app": SHORTCUT_GAME, "pid": child, "code": -1}
    return text + GONE % {"when": stamp(start + length), "app": SHORTCUT_GAME, "pid": pid, "code": 0}


class ShortcutBase(Base):
    def setUp(self):
        super().setUp()
        self.games = Path(self.tmp.name) / "added-games.json"
        self.games.write_text(json.dumps([{"appid": int(SHORTCUT), "name": "Resident Evil 3", "exe": "/root/Storage/Download/RE3/re3.exe"}]))
        os.environ["BL_ADDED_GAMES"] = str(self.games)

    def write_frames_at(self, pid, comm, presents, last, swapchains=1, external=0):
        (self.frames / ("vkbridge-frames-%d.txt" % pid)).write_text(
            "pid=%d comm=%s presents=%d t_first=%.3f t_last=%.3f fps_avg=0.0 fps_p50=0.0 ft_p50=0 ft_p90=0 ft_p99=0 ft_max=0 "
            "hitch100=0 hitch1000=0 fps_seconds=<10:0 swapchains=%d external=%d\n" % (pid, comm, presents, last, last, swapchains, external))


class VerdictLogTest(Base):
    """Mad Max ran 46 s and presented 87 frames (MainThread), the next start died in 17 s with only explorer.exe."""

    def test_a_run_that_presented_resets_the_count_and_says_so_and_the_next_failure_names_its_start(self):
        self.seed(start_runs={MAD_MAX: {EXT_DIR: {"fails": 5, "last": 1}}})
        self.write_frames(1001, "MainThread", 87, swapchains=2, external=7)
        self.end(pid=1000, start=0, length=46, tool=EXT_DIR, children=(1001, 1002))
        self.assertNotIn(MAD_MAX, self.read_state().get("start_runs", {}))
        self.write_frames(1101, "explorer.exe", 0, swapchains=1, external=1)
        self.end(pid=1100, start=62, length=17, tool=EXT_DIR, children=(1101, 1102))
        self.assertEqual(self.read_state()["start_runs"][MAD_MAX][EXT_DIR]["fails"], 1)
        text = self.said.getvalue()
        self.assertIn("app 234140 (Mad Max): the start at 22:00:00 showed frames on DroidDeck-Proton 11-11.11, so its count of starts "
                      "without a frame (5) is reset", text)
        self.assertIn("app 234140 (Mad Max): the start at 22:01:02 closed after 17 s on DroidDeck-Proton 11-11.11 without showing a "
                      "frame (1 in a row)", text)

    def test_a_good_run_without_an_earlier_failure_stays_quiet(self):
        self.seed()
        self.write_frames(1001, "MainThread", 87)
        self.end(pid=1000, start=0, length=46, children=(1001,))
        self.assertNotIn("showed frames", self.said.getvalue())


class SteamShortcutRunTest(ShortcutBase):
    def test_a_shortcut_run_is_counted_under_the_id_the_client_maps(self):
        run = C["GameRunTracker"]().feed(shortcut_run())[0]
        self.assertEqual((run["app"], run["logged"], run["pids"], run["end"] - run["start"]), (SHORTCUT, SHORTCUT_GAME, {3000, 3001}, 7))
        self.assertTrue(C["counts_as_run"](run))

    def test_two_shortcut_starts_without_a_frame_move_it_under_its_own_id_with_its_name(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={SHORTCUT: TOOL})
        self.write_config({"0": TOOL, SHORTCUT: TOOL})
        for pid, start in ((3000, 0), (3100, 30)):
            run = C["GameRunTracker"]().feed(shortcut_run(pid=pid, start=start, children=(pid + 1,)))[0]
            with contextlib.redirect_stdout(self.said), contextlib.redirect_stderr(self.said):
                self.helper.judge_run(run)
        record = self.read_state()["fallbacks"][SHORTCUT]
        self.assertEqual((record["to"], record["title"]), (EXT, "Resident Evil 3"))
        self.assertNotIn(SHORTCUT_GAME, self.read_state().get("fallbacks", {}))
        self.assertEqual(self.notice()["app"], SHORTCUT)
        page = self.live({"0": TOOL, SHORTCUT: TOOL})
        self.assertIn((SHORTCUT, EXT), page.sets)

    def test_a_shortcut_prefix_under_the_signed_id_counts_as_a_prefix(self):
        self.assertFalse(C["prefix_exists"](str(self.steam), SHORTCUT))
        (self.steam / "steamapps/compatdata" / str(int(SHORTCUT) - 2 ** 32)).mkdir(parents=True)
        self.assertTrue(C["prefix_exists"](str(self.steam), SHORTCUT))


class LaunchEvidenceTest(ShortcutBase):
    def test_frames_after_the_launch_from_any_game_process_count(self):
        self.write_frames_at(24580, "re3.exe", 3, last=150.0)
        self.assertEqual(C["launch_evidence"](str(self.frames), 100.0, 0), "presented")

    def test_frames_from_before_the_launch_or_from_the_session_itself_do_not(self):
        (self.frames / "vkbridge-frames-9000.txt").unlink()
        self.write_frames_at(24000, "re3.exe", 50, last=90.0, swapchains=0)
        self.write_frames_at(13348, "gamescope-wl", 400, last=150.0, swapchains=0, external=57)
        self.write_frames_at(24537, "explorer.exe", 0, last=0.0, swapchains=1, external=1)
        self.assertEqual(C["launch_evidence"](str(self.frames), 100.0, 0), "nothing")

    def test_a_game_that_hands_its_frames_on_reached_the_gpu(self):
        self.write_frames_at(24580, "game.exe", 0, last=0.0, swapchains=0, external=12)
        self.assertEqual(C["launch_evidence"](str(self.frames), 100.0, 0), "gpu")

    def test_without_frames_files_nothing_can_be_told(self):
        for path in self.frames.iterdir():
            path.unlink()
        self.assertEqual(C["launch_evidence"](str(self.frames), 100.0, 0), "unknown")
        self.assertEqual(C["launch_evidence"](None, 100.0, 0), "unknown")
        self.assertEqual(C["launch_evidence"](str(self.frames / "missing"), 100.0, 0), "unknown")


class LaunchToolTest(ShortcutBase):
    def choose(self, current, requests=(), env=None):
        state = self.read_state()
        with mock.patch.dict(os.environ, env or {}):
            name, why = C["launch_tool"](state, str(self.steam), SHORTCUT, current, set(self.names), dict.fromkeys(requests, {}),
                                         catalog=self.catalog)
        return name, why, state

    def test_a_new_shortcut_starts_on_droiddeck_proton_and_the_next_steam_session_maps_it_there_too(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        self.write_config({"0": TOOL})
        name, why, state = self.choose({"0": TOOL})
        self.assertEqual((name, why), (EXT, C["NEW_TITLE_REASON"]))
        self.assertEqual((state["fallbacks"][SHORTCUT]["from"], state["fallbacks"][SHORTCUT]["to"]), (TOOL, EXT))
        self.assertEqual(state["fallbacks"][SHORTCUT]["title"], "Resident Evil 3")
        self.write_state(**state)
        self.prefix(SHORTCUT, "GE-Proton11-11")
        self.session_start(apps=(SHORTCUT,))
        self.assertEqual(self.mapping().get(SHORTCUT), EXT)
        self.assertEqual(self.choose({"0": TOOL, SHORTCUT: EXT})[0], EXT)

    def test_a_follower_shortcut_with_a_prefix_keeps_experimental_until_the_guard_moves_it(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={SHORTCUT: TOOL})
        self.prefix(SHORTCUT, "11.0-100")
        self.assertEqual(self.choose({"0": TOOL, SHORTCUT: TOOL})[:2], (None, None))
        state = self.read_state()
        for second in (0, 30):
            kind, decision, record = C["judge_launch"](state, str(self.steam), SHORTCUT, TOOL, second, second + 7, "nothing")
        self.assertEqual((kind, decision, record["to"]), ("fail", "switch", EXT))
        self.write_state(**state)
        name, why, _ = self.choose({"0": TOOL, SHORTCUT: TOOL})
        self.assertEqual(name, EXT)
        self.assertIn("2 times in a row", why)

    def test_the_players_pick_wins(self):
        base = dict(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        self.write_state(**base, chosen={SHORTCUT: {"name": TOOL, "at": 5}})
        self.assertEqual(self.choose({"0": TOOL, SHORTCUT: TOOL})[:2], (None, None))
        self.write_state(**base)
        self.assertEqual(self.choose({"0": TOOL}, requests=(SHORTCUT,))[:2], (None, None))
        self.assertEqual(self.choose({"0": TOOL, SHORTCUT: "GE-Proton11-11-aarch64"})[:2], (None, None))
        self.write_state(**dict(base, default=EXT, source="app"))
        self.assertEqual(self.choose({"0": EXT})[:2], (None, None))

    def test_a_pick_made_after_the_move_wins_over_the_move(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={},
                         fallbacks={SHORTCUT: {"from": TOOL, "to": EXT, "at": 10, "confirmed": False, "title": "x", "fails": 2}},
                         chosen={SHORTCUT: {"name": TOOL, "at": 20}})
        self.assertEqual(self.choose({"0": TOOL, SHORTCUT: TOOL})[:2], (None, None))

    def test_the_switches_keep_the_old_behaviour(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        for env in ({"DD_PROTON_FALLBACK": "0"}, {"DD_NEW_TITLE_TOOL": "experimental"}):
            with self.subTest(env=env):
                name, _, state = self.choose({"0": TOOL}, env=env)
                self.assertIsNone(name)
                self.assertNotIn("fallbacks", state)

    def test_a_launch_that_showed_frames_resets_the_count(self):
        state = {"start_runs": {SHORTCUT: {TOOL: {"fails": 1, "last": 1}}}}
        kind, decision, _ = C["judge_launch"](state, str(self.steam), SHORTCUT, TOOL, 0, 40, "presented")
        self.assertEqual((kind, decision, state.get("start_runs", {})), ("ok", None, {}))


class ProtonRunTest(ShortcutBase):
    """droiddeck-proton-run --app end to end, with stand-in tools that record how they were started."""

    def setUp(self):
        super().setUp()
        self.home = Path(self.tmp.name) / "home"
        (self.home / ".local/share").mkdir(parents=True)
        os.symlink(self.state, self.home / ".local/share/droiddeck-compat")
        os.symlink(self.steam, self.home / ".local/share/Steam")
        self.calls = Path(self.tmp.name) / "calls"
        tools = self.steam / "compatibilitytools.d"
        valve = tools / TOOL
        valve.mkdir(parents=True, exist_ok=True)
        (valve / "compatibilitytool.vdf").write_text(
            '"compatibilitytools" { "compat_tools" { "%s" { "install_path" "." "display_name" "Proton Experimental ARM64" '
            '"from_oslist" "windows" "to_oslist" "linux" } } }' % TOOL)
        for folder in (valve, tools / EXT_DIR):
            (folder / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/run %verb%" }')
            script = folder / "run"
            script.write_text("#!/bin/sh\necho \"$(basename \"$(dirname \"$0\")\") $1\" >> %s\n"
                              "[ -z \"$HANG\" ] || { echo $$ > \"$HANG\"; exec sleep 30; }\n"
                              "[ -z \"$PRESENT\" ] || %s -c 'import time,os; open(os.path.join(os.environ[\"VKBRIDGE_FRAMES_DIR\"],"
                              "\"vkbridge-frames-777.txt\"),\"w\").write(\"pid=777 comm=re3.exe presents=5 t_first=1 t_last=%%.3f swapchains=1 "
                              "external=0\\n\" %% time.monotonic())'\nexit 3\n" % (self.calls, sys.executable))
            script.chmod(0o755)
        if not (tools / EXT_DIR / "compatibilitytool.vdf").read_text().count("windows"):
            self.skipTest("the fixture's DroidDeck-Proton is not a Windows tool")
        self.game = Path(self.tmp.name) / "RE3"
        self.game.mkdir()
        (self.game / "re3.exe").write_text("MZ")
        self.write_frames_at(13348, "gamescope-wl", 0, last=0.0, swapchains=0, external=57)
        (self.frames / "vkbridge-frames-9000.txt").unlink()

    def start_game(self, present=False, extra=None):
        env = {k: v for k, v in os.environ.items() if not k.startswith("DD_")}
        env.update(HOME=str(self.home), STEAM_COMPAT_CLIENT_INSTALL_PATH=str(self.steam), VKBRIDGE_FRAMES_DIR=str(self.frames),
                   BL_LAUNCH_DIR=str(self.launch), PRESENT="1" if present else "")
        env.pop("BL_DESKTOP", None)
        env.update(extra or {})
        result = subprocess.run([sys.executable, str(BIN / "droiddeck-proton-run"), "--app", SHORTCUT, "--dir", str(self.game),
                                 "--options", "", "--", str(self.game / "re3.exe")], env=env, capture_output=True, text=True,
                                timeout=60)
        return result.returncode, result.stderr + result.stdout

    def started(self):
        return self.calls.read_text().splitlines() if self.calls.exists() else []

    def test_re3_on_experimental_is_counted_moved_after_two_and_started_on_droiddeck_proton_then(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={SHORTCUT: TOOL})
        self.write_config({"0": TOOL, SHORTCUT: TOOL})
        self.prefix(SHORTCUT, "11.0-100")
        code, out = self.start_game()
        self.assertEqual(code, 3)
        self.assertIn("with %s (Steam's choice for this title)" % TOOL, out)
        self.assertIn("closed after 0 s on %s without showing a frame (1 in a row)" % TOOL, out)
        code, out = self.start_game()
        self.assertIn("so MaliBridge starts it on %s from the next launch" % EXT, out)
        self.assertEqual(self.notice()["kind"], "switched")
        self.assertEqual(self.notice()["title"], "Resident Evil 3")
        code, out = self.start_game()
        self.assertIn("with %s (MaliBridge moved it" % EXT, out)
        self.assertEqual(self.started(), ["%s waitforexitandrun" % TOOL] * 2 + ["%s waitforexitandrun" % EXT_DIR])

    def test_a_new_shortcut_starts_on_droiddeck_proton(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        self.write_config({"0": TOOL})
        code, out = self.start_game(present=True)
        self.assertIn("with %s (%s)" % (EXT, C["NEW_TITLE_REASON"]), out)
        self.assertEqual(self.started(), ["%s waitforexitandrun" % EXT_DIR])
        self.assertEqual(self.read_state()["fallbacks"][SHORTCUT]["to"], EXT)
        self.assertNotIn("without showing a frame", out)

    def test_a_launch_that_shows_frames_is_not_counted(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={SHORTCUT: TOOL},
                         start_runs={SHORTCUT: {TOOL: {"fails": 1, "last": 1}}})
        self.write_config({"0": TOOL, SHORTCUT: TOOL})
        self.prefix(SHORTCUT, "11.0-100")
        code, out = self.start_game(present=True)
        self.assertIn("showed frames on %s" % TOOL, out)
        self.assertNotIn(SHORTCUT, self.read_state().get("start_runs", {}))

    def hanging(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={SHORTCUT: TOOL})
        self.write_config({"0": TOOL, SHORTCUT: TOOL})
        self.prefix(SHORTCUT, "11.0-100")
        marker = Path(self.tmp.name) / "tool-pid"
        env = {k: v for k, v in os.environ.items() if not k.startswith("DD_") and k != "BL_DESKTOP"}
        env.update(HOME=str(self.home), STEAM_COMPAT_CLIENT_INSTALL_PATH=str(self.steam), VKBRIDGE_FRAMES_DIR=str(self.frames),
                   BL_LAUNCH_DIR=str(self.launch), HANG=str(marker))
        runner = subprocess.Popen([sys.executable, str(BIN / "droiddeck-proton-run"), "--app", SHORTCUT, "--dir", str(self.game),
                                   "--options", "", "--", str(self.game / "re3.exe")], env=env, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True)
        self.addCleanup(lambda: runner.poll() is None and runner.kill())
        for _ in range(200):
            if marker.exists() and marker.read_text().strip():
                break
            time.sleep(0.05)
        tool = int(marker.read_text())
        self.addCleanup(lambda: os.path.exists("/proc/%d" % tool) and os.kill(tool, 9))
        return runner, tool

    @staticmethod
    def gone(pid):
        for _ in range(100):
            try:
                with open("/proc/%d/stat" % pid) as f:
                    if f.read().split(") ")[1].startswith("Z"):
                        return True
            except OSError:
                return True
            time.sleep(0.05)
        return False

    def test_a_stop_from_the_session_reaches_the_tool_and_is_not_counted(self):
        runner, tool = self.hanging()
        runner.send_signal(15)
        out, _ = runner.communicate(timeout=30)
        self.assertEqual(runner.returncode, 128 + 15)
        self.assertTrue(self.gone(tool))
        self.assertNotIn("without showing a frame", out)
        self.assertNotIn(SHORTCUT, self.read_state().get("start_runs", {}))

    def test_the_tool_dies_with_the_launcher_as_it_did_when_it_ran_in_its_place(self):
        runner, tool = self.hanging()
        runner.kill()
        runner.communicate(timeout=30)
        self.assertTrue(self.gone(tool))

    def test_another_clients_library_is_left_to_its_mapping(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        self.write_config({"0": TOOL})
        (self.home / ".local/share/Steam").unlink()
        (self.home / ".local/share/Steam").mkdir()
        code, out = self.start_game()
        self.assertIn("with %s (Steam's default)" % TOOL, out)
        self.assertNotIn("fallbacks", self.read_state())
        self.assertNotIn("without showing a frame", out)

    def test_with_the_switch_off_the_tool_runs_in_place_as_before(self):
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        self.write_config({"0": TOOL})
        code, out = self.start_game(extra={"DD_PROTON_FALLBACK": "0"})
        self.assertEqual(code, 3)
        self.assertIn("with %s (Steam's default)" % TOOL, out)
        self.assertEqual(self.started(), ["%s waitforexitandrun" % TOOL])
        self.assertNotIn("fallbacks", self.read_state())
        self.assertNotIn("start_runs", self.read_state())
        self.assertNotIn("without showing a frame", out)


if __name__ == "__main__":
    unittest.main()
