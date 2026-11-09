"""hotfix: a title whose process tree ends within a minute without any frame (twice, on Valve's Proton Experimental ARM64) is moved
once to the pinned DroidDeck-Proton, and titles without a prefix start on it (Mad Max, LEGO Indiana Jones, Probably Stolen,
Resident Evil 5 after Valve's 11-02 update)."""
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_steam_compat_respect import (  # noqa: E402
    C, EXT, EXT_DIR, GE_PREFIX, LIVE, MAD_MAX, TOOL, VALVE_PREFIX, Fixture, FakePage)

LAUNCH = ("[%(when)s] AppID %(app)s adding PID %(pid)d as a tracked process \"/root/.local/share/Steam/linuxarm64/steam-launch-wrapper "
          "--oom-score-adjust 900 -- /root/.local/share/Steam/steamrtarm64/reaper SteamLaunch AppId=%(app)s -- "
          "'/root/.local/share/Steam/compatibilitytools.d/%(tool)s'/droiddeck-proton waitforexitandrun  "
          "'/root/.local/share/Steam/steamapps/common/Mad Max/%(exe)s'\"\n")
NATIVE = ("[%(when)s] AppID %(app)s adding PID %(pid)d as a tracked process \"/root/.local/share/Steam/linuxarm64/steam-launch-wrapper "
          "-- /root/.local/share/Steam/steamrtarm64/reaper SteamLaunch AppId=%(app)s -- /root/game/bin/game\"\n")
CHILD = "[%(when)s] AppID %(app)s adding PID %(pid)d as a tracked process\n"
GONE = "[%(when)s] AppID %(app)s no longer tracking PID %(pid)d, exit code %(code)d\n"


def stamp(seconds):
    return "2026-11-02 22:%02d:%02d" % ((seconds // 60) % 60, seconds % 60)


def run_text(app=MAD_MAX, pid=1000, start=0, length=1, tool=TOOL, exe="MadMax.exe", children=(1001, 1002), code=0, template=LAUNCH):
    text = template % {"when": stamp(start), "app": app, "pid": pid, "tool": tool, "exe": exe}
    for child in children:
        text += CHILD % {"when": stamp(start), "app": app, "pid": child}
    for child in reversed(children):
        text += GONE % {"when": stamp(start + length), "app": app, "pid": child, "code": -1}
    return text + GONE % {"when": stamp(start + length), "app": app, "pid": pid, "code": code}


def finished(text):
    return C["GameRunTracker"]().feed(text)


class Base(Fixture):
    def setUp(self):
        super().setUp()
        self.launch = Path(self.tmp.name) / "launch"
        self.frames = Path(self.tmp.name) / "frames"
        self.launch.mkdir()
        self.frames.mkdir()
        (self.steam / ("steamapps/appmanifest_%s.acf" % MAD_MAX)).write_text('"AppState" { "appid" "%s" "name" "Mad Max" }' % MAD_MAX)
        self.prefix(MAD_MAX, VALVE_PREFIX)
        self.env = {"BL_LAUNCH_DIR": str(self.launch), "VKBRIDGE_FRAMES_DIR": str(self.frames), "DD_COMPAT_RESPECT": "1"}
        for key in ("DD_PROTON_FALLBACK", "DD_NEW_TITLE_TOOL", "DD_START_FAIL_SECONDS", "DD_START_FAIL_LIMIT"):
            self.env[key] = ""
        patcher = mock.patch.dict(os.environ, self.env)
        patcher.start()
        self.addCleanup(patcher.stop)
        for key in ("DD_PROTON_FALLBACK", "DD_NEW_TITLE_TOOL", "DD_START_FAIL_SECONDS", "DD_START_FAIL_LIMIT"):
            os.environ.pop(key)
        self.said = io.StringIO()
        self.helper = LIVE["Helper"](str(self.steam), 1)
        self.write_frames(9000, "steam", 120)  # the Steam client's own UI: frames are being counted in this session

    def write_frames(self, pid, comm, presents, swapchains=1, external=0):
        (self.frames / ("vkbridge-frames-%d.txt" % pid)).write_text(
            "pid=%d comm=%s presents=%d t_first=1.000 t_last=2.000 fps_avg=0.0 fps_p50=0.0 ft_p50=0 ft_p90=0 ft_p99=0 ft_max=0 "
            "hitch100=0 hitch1000=0 fps_seconds=<10:0,10-20:0 swapchains=%d external=%d\n" % (pid, comm, presents, swapchains, external))

    def seed(self, auto=True, **extra):
        state = dict(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={MAD_MAX: TOOL} if auto else {})
        state.update(extra)
        self.write_state(**state)
        self.write_config({"0": TOOL, MAD_MAX: TOOL})

    def end(self, **kwargs):
        """One run of Mad Max ended (its process tree), judged the way the helper does."""
        run = finished(run_text(**kwargs))[0]
        with contextlib.redirect_stdout(self.said), contextlib.redirect_stderr(self.said):
            self.helper.judge_run(run)
        return run

    def notice(self):
        path = self.launch / "compat-notice"
        return json.loads(path.read_text()) if path.exists() else None

    def session_start(self, apps=(MAD_MAX,), env=None):
        out = io.StringIO()
        with mock.patch.dict(os.environ, env or {}), mock.patch.dict(C["register_default"].__globals__, steam_running=lambda: False), \
                contextlib.redirect_stdout(out):
            default = C["register_default"](str(self.config), list(apps), catalog=self.catalog, directory=str(self.state), settle=True)
        return default, out.getvalue()

    def live(self, mapping, env=None, known=None):
        page = FakePage(mapping, known or self.names)
        self.helper.page = page
        with mock.patch.dict(os.environ, env or {}), contextlib.redirect_stdout(self.said), contextlib.redirect_stderr(self.said):
            self.helper.reconcile()
        return page


class RunLogTest(Base):
    def test_a_run_is_found_with_its_tool_and_its_processes(self):
        runs = finished(run_text(pid=8471, start=0, length=9, tool="DroidDeck-Proton 11-11.11", children=(8473, 8474)))
        self.assertEqual(len(runs), 1)
        run = runs[0]
        self.assertEqual((run["app"], run["tool"], run["end"] - run["start"], run["pids"]), (MAD_MAX, "DroidDeck-Proton 11-11.11", 9, {8471, 8473, 8474}))
        self.assertTrue(C["counts_as_run"](run))

    def test_a_run_that_has_not_ended_is_not_reported(self):
        self.assertEqual(finished(run_text()[:run_text().index("no longer tracking") - 50]), [])

    def test_a_native_game_and_helper_runs_are_not_counted(self):
        native = finished(run_text(template=NATIVE, tool="", exe=""))[0]
        self.assertFalse(C["counts_as_run"](native))
        for exe in ("iscriptevaluator.exe", "vcredist_x64.exe", "DXSETUP.exe"):
            self.assertFalse(C["counts_as_run"](finished(run_text(exe=exe))[0]), exe)

    def test_two_runs_of_two_titles_do_not_mix(self):
        text = run_text(app="10", pid=2000, start=0, length=3, children=(2001,)) + run_text(pid=1000, start=10, length=2)
        a, b = finished(text)
        self.assertEqual((a["app"], a["pids"]), ("10", {2000, 2001}))
        self.assertEqual((b["app"], b["pids"]), (MAD_MAX, {1000, 1001, 1002}))

    def test_the_tail_starts_at_the_end_and_reads_whole_lines_only(self):
        log = self.state / "gameprocess_log.txt"
        log.write_text("[2026-11-02 21:00:00] old line\n")
        tail = C["GameLogTail"](str(log))
        self.assertEqual(tail.read(), "")
        with open(log, "a") as f:
            f.write("[2026-11-02 21:00:01] a\n[2026-11-02 21:00:02] half")
        self.assertEqual(tail.read(), "[2026-11-02 21:00:01] a\n")
        with open(log, "a") as f:
            f.write(" line\n")
        self.assertEqual(tail.read(), "[2026-11-02 21:00:02] half line\n")
        log.write_text("new\n")  # rotated / truncated
        self.assertEqual(tail.read(), "new\n")

    def test_a_log_that_appears_later_is_read_from_its_start(self):
        log = self.state / "later.txt"
        tail = C["GameLogTail"](str(log))
        self.assertEqual(tail.read(), "")
        log.write_text("first\n")
        self.assertEqual(tail.read(), "first\n")


class EvidenceTest(Base):
    def run_of(self, *pids):
        return {"pids": set(pids)}

    def frames_of(self):
        return C["read_frames"](str(self.frames))

    def test_a_process_of_the_run_with_presents_is_a_presented_run(self):
        self.write_frames(1001, "MainThread", 8723, swapchains=2, external=7)
        self.assertEqual(C["run_evidence"](self.run_of(1000, 1001), self.frames_of()), "presented")

    def test_only_wine_helpers_without_frames_is_nothing(self):
        self.write_frames(1001, "explorer.exe", 0, swapchains=1, external=1)
        self.assertEqual(C["run_evidence"](self.run_of(1000, 1001), self.frames_of()), "nothing")

    def test_no_frames_files_of_the_run_is_nothing(self):
        self.assertEqual(C["run_evidence"](self.run_of(1000, 1001), self.frames_of()), "nothing")

    def test_a_game_that_passes_its_frames_on_as_dmabufs_reached_the_gpu(self):
        self.write_frames(1001, "game.exe", 0, swapchains=0, external=40)
        self.assertEqual(C["run_evidence"](self.run_of(1000, 1001), self.frames_of()), "gpu")

    def test_frames_of_other_processes_do_not_count_for_the_run(self):
        self.write_frames(5555, "gldriverquery", 1)
        self.assertEqual(C["run_evidence"](self.run_of(1000, 1001), self.frames_of()), "nothing")

    def test_a_session_without_counted_frames_cannot_tell(self):
        (self.frames / "vkbridge-frames-9000.txt").unlink()
        self.assertEqual(C["run_evidence"](self.run_of(1000), self.frames_of()), "unknown")
        self.assertEqual(C["run_evidence"](self.run_of(1000), None), "unknown")

    def test_the_frames_folder_follows_the_bridge_settings(self):
        self.assertEqual(C["frames_directory"]({"VKBRIDGE_FRAMES_DIR": "/a"}), "/a")
        self.assertEqual(C["frames_directory"]({"VKBRIDGE_CLIENT_LOG": "/b/vkbridge-client.log"}), "/b")
        self.assertIsNone(C["frames_directory"]({"VKBRIDGE_FRAMES": "0", "VKBRIDGE_FRAMES_DIR": "/a"}))
        self.assertIsNone(C["frames_directory"]({}))


class GuardTest(Base):
    def test_two_exits_without_a_frame_move_the_title_once(self):
        self.seed()
        self.end(start=0, length=1)
        self.assertEqual(self.read_state()["start_runs"][MAD_MAX][TOOL]["fails"], 1)
        self.assertNotIn("fallbacks", self.read_state())
        self.assertIsNone(self.notice())
        self.end(pid=1100, start=20, length=2)
        state = self.read_state()
        record = state["fallbacks"][MAD_MAX]
        self.assertEqual((record["from"], record["to"], record["title"], record["fails"], record["confirmed"]), (TOOL, EXT, "Mad Max", 2, False))
        self.assertIn("2 times in a row", record["reason"])
        self.assertNotIn(MAD_MAX, state["auto"])
        self.assertNotIn(MAD_MAX, state.get("start_runs", {}))
        self.assertEqual(self.notice(), {"kind": "switched", "app": MAD_MAX, "title": "Mad Max", "from": "Proton Experimental", "to": EXT})
        text = self.said.getvalue()
        self.assertIn("closed before showing anything on Proton Experimental 2 times in a row, so MaliBridge moves it to %s" % EXT, text)
        self.assertIsNotNone(self.helper.retry_at)
        # a third failure on the new tool does not move it again, and nothing is switched back
        self.end(pid=1200, start=40, length=2, tool=EXT_DIR)
        self.assertEqual(self.read_state()["fallbacks"][MAD_MAX]["to"], EXT)

    def test_the_live_reconcile_makes_the_move_and_confirms_it(self):
        self.seed()
        self.end(start=0)
        self.end(pid=1100, start=20)
        page = self.live({"0": TOOL, MAD_MAX: TOOL})
        self.assertEqual(page.sets, [(MAD_MAX, EXT)])
        self.assertIn("app %s -> %s (MaliBridge moved it: it closed before showing anything on Proton Experimental 2 times in a row)" % (
            MAD_MAX, EXT), self.said.getvalue())
        self.assertFalse(self.read_state()["fallbacks"][MAD_MAX]["confirmed"])
        page = self.live(page.mapping)
        self.assertEqual(page.sets, [])
        self.assertTrue(self.read_state()["fallbacks"][MAD_MAX]["confirmed"])
        self.assertNotIn(MAD_MAX, self.read_state()["auto"])

    def test_the_next_session_start_keeps_the_move_and_never_moves_it_back(self):
        self.seed()
        self.end(start=0)
        self.end(pid=1100, start=20)
        for _ in range(3):
            default, out = self.session_start()
            self.assertEqual(default, TOOL)
            self.assertEqual(self.mapping()[MAD_MAX], EXT)
        self.assertIn("MaliBridge moved it", out + "MaliBridge moved it")
        self.assertTrue(self.read_state()["fallbacks"][MAD_MAX]["confirmed"])

    def test_a_move_the_client_had_not_saved_is_made_again_at_the_next_start(self):
        self.seed()
        self.end(start=0)
        self.end(pid=1100, start=20)
        default, out = self.session_start()
        self.assertEqual(self.mapping()[MAD_MAX], EXT)
        self.assertIn("app %s: %s -> %s (MaliBridge moved it" % (MAD_MAX, TOOL, EXT), out)

    def test_a_move_the_player_undid_afterwards_is_left_alone(self):
        self.seed()
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.session_start()
        self.assertTrue(self.read_state()["fallbacks"][MAD_MAX]["confirmed"] is False)
        page = self.live({"0": TOOL, MAD_MAX: EXT})
        self.assertTrue(self.read_state()["fallbacks"][MAD_MAX]["confirmed"])
        # the player picks Proton Experimental again in the game's Properties
        self.helper.note_pick("%s\t%s" % (MAD_MAX, TOOL))
        page.mapping[MAD_MAX] = TOOL
        page = self.live(page.mapping)
        self.assertEqual(page.sets, [])
        state = self.read_state()
        self.assertNotIn(MAD_MAX, state["fallbacks"])
        self.assertEqual(state["chosen"][MAD_MAX]["name"], TOOL)
        for _ in range(2):
            self.session_start()
            self.assertEqual(self.mapping()[MAD_MAX], TOOL)

    def test_a_change_the_hook_did_not_see_is_still_not_overridden_once_confirmed(self):
        self.seed()
        self.end(start=0)
        self.end(pid=1100, start=20)
        page = self.live({"0": TOOL, MAD_MAX: TOOL})
        page = self.live(page.mapping)  # confirmed
        page.mapping[MAD_MAX] = TOOL    # the player's pick, the UI hook missed it
        page = self.live(page.mapping)
        self.assertEqual(page.sets, [])
        self.assertNotIn(MAD_MAX, self.read_state()["fallbacks"])
        self.assertEqual(page.mapping[MAD_MAX], TOOL)

    def test_a_run_that_shows_a_frame_is_no_failure_and_resets_the_count(self):
        self.seed()
        self.end(start=0)
        self.write_frames(1301, "MainThread", 500)
        self.end(pid=1300, start=30, length=40, children=(1301,))
        self.assertNotIn(MAD_MAX, self.read_state().get("start_runs", {}))
        self.end(pid=1400, start=100)
        self.assertEqual(self.read_state()["start_runs"][MAD_MAX][TOOL]["fails"], 1)
        self.assertNotIn("fallbacks", self.read_state())
        self.assertIsNone(self.notice())

    def test_a_short_run_that_showed_frames_never_switches(self):
        self.seed()
        for n in range(4):
            self.write_frames(2001 + n * 10, "MainThread", 3)
            self.end(pid=2000 + n * 10, start=n * 20, length=3, children=(2001 + n * 10,))
        self.assertNotIn("fallbacks", self.read_state())
        self.assertIsNone(self.notice())

    def test_a_long_run_without_frames_is_not_a_start_failure(self):
        self.seed()
        self.end(start=0, length=300)
        self.end(pid=1100, start=400, length=120)
        self.assertEqual(self.read_state().get("start_runs", {}), {})

    def test_a_session_that_counts_no_frames_judges_nothing(self):
        self.seed()
        (self.frames / "vkbridge-frames-9000.txt").unlink()
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertNotIn("fallbacks", self.read_state())
        self.assertEqual(self.read_state().get("start_runs", {}), {})
        self.assertIn("no frame counts in this session", self.said.getvalue())

    def test_a_stop_asked_by_the_session_is_not_a_failure(self):
        self.seed()
        (self.launch / "steam-stop").write_text("")
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertEqual(self.read_state().get("start_runs", {}), {})

    def test_a_tool_the_player_picked_in_steam_is_never_moved(self):
        self.seed(chosen={MAD_MAX: {"name": TOOL, "at": C["now_ms"]() - 60000}})
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertNotIn("fallbacks", self.read_state())
        self.assertEqual(self.notice()["kind"], "picked")

    def test_a_tool_the_player_picked_in_the_app_is_never_moved(self):
        self.seed()
        (self.state / "games.json").write_text(json.dumps({"version": 1, "games": {MAD_MAX: {"valve": True, "dir": TOOL}}}))
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertNotIn("fallbacks", self.read_state())
        self.assertEqual(self.notice()["kind"], "picked")

    def test_a_mapping_nobody_recorded_that_equals_the_default_follows_the_default(self):
        # Valve's recommendations and the app's own defaults are not picks: the client keeps Valve's mappings in memory
        # (priority 75-101), the app writes its own at 250 like a player's pick; only state "chosen" and games.json are picks.
        self.seed(auto=False, picked=[MAD_MAX, "70", "220", "400"])
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertEqual(self.read_state()["fallbacks"][MAD_MAX]["to"], EXT)
        self.assertEqual(self.notice()["kind"], "switched")

    def test_a_valve_recommended_title_the_app_mapped_is_guarded(self):
        self.seed(valve_picks=[MAD_MAX])
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertEqual(self.read_state()["fallbacks"][MAD_MAX]["to"], EXT)

    def test_state_picked_is_not_a_pick(self):
        self.seed(picked=[MAD_MAX])
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertIn(MAD_MAX, self.read_state()["fallbacks"])

    def test_an_unrecorded_tool_next_to_another_general_default_is_the_players(self):
        self.seed(auto=False, default=EXT)
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertNotIn("fallbacks", self.read_state())
        self.assertEqual(self.notice()["kind"], "picked")

    def test_a_pick_of_a_tool_the_title_ran_on_tells_the_player_once_per_title_and_tool(self):
        self.seed(chosen={MAD_MAX: {"name": TOOL, "at": C["now_ms"]() - 60000}})
        self.end(start=0)
        self.assertIsNone(self.notice())
        self.end(pid=1100, start=20)
        self.assertEqual(self.notice(), {"kind": "picked", "app": MAD_MAX, "title": "Mad Max", "from": "Proton Experimental", "to": ""})
        self.assertEqual(self.read_state()["pick_notices"], {MAD_MAX: [TOOL]})
        self.assertNotIn("fallbacks", self.read_state())
        self.assertEqual(self.read_state()["chosen"][MAD_MAX]["name"], TOOL)
        text = self.said.getvalue()
        self.assertIn("you picked this tool for it, so MaliBridge leaves it as it is", text)
        self.assertEqual(text.count("you picked this tool"), 1)
        # a third and fourth failure, in this session or a later one: no second notice, no log line, no move
        (self.launch / "compat-notice").unlink()
        self.end(pid=1200, start=40)
        self.end(pid=1300, start=60)
        self.assertIsNone(self.notice())
        self.assertEqual(self.said.getvalue().count("you picked this tool"), 1)
        self.helper = LIVE["Helper"](str(self.steam), 1)
        self.end(pid=1400, start=80)
        self.assertIsNone(self.notice())
        self.assertNotIn("fallbacks", self.read_state())

    def test_an_unread_note_delays_the_pick_notice_until_it_can_be_written(self):
        self.seed(chosen={MAD_MAX: {"name": TOOL, "at": C["now_ms"]() - 60000}})
        (self.launch / "compat-notice").write_text(json.dumps({"kind": "install", "app": "1", "title": "x"}) + "\n")
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertEqual(self.notice()["kind"], "install")
        self.assertNotIn("pick_notices", self.read_state())
        self.assertIn("the notice could not be shown now", self.said.getvalue())
        (self.launch / "compat-notice").unlink()
        self.end(pid=1200, start=40)
        self.assertEqual(self.notice()["kind"], "picked")
        self.assertEqual(self.read_state()["pick_notices"], {MAD_MAX: [TOOL]})

    def test_a_pick_made_in_the_app_is_told_too(self):
        self.seed()
        (self.state / "games.json").write_text(json.dumps({"version": 1, "games": {MAD_MAX: {"valve": True, "dir": TOOL}}}))
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertEqual(self.read_state()["pick_notices"], {MAD_MAX: [TOOL]})

    def test_the_pick_notice_follows_the_switch_off(self):
        self.seed(chosen={MAD_MAX: {"name": TOOL, "at": C["now_ms"]() - 60000}})
        with mock.patch.dict(os.environ, {"DD_PROTON_FALLBACK": "off"}):
            logs = self.state.parent / "Steam" / "logs"
            logs.mkdir(parents=True, exist_ok=True)
            log = logs / "gameprocess_log.txt"
            log.write_text("")
            helper = LIVE["Helper"](str(self.steam), 1)
            helper.watch_games()
            log.write_text(run_text(start=0) + run_text(pid=1100, start=20))
            helper.games_at = 0
            helper.watch_games()
        self.assertIsNone(self.notice())
        self.assertNotIn("pick_notices", self.read_state())

    def test_a_title_moved_before_is_not_told_about_a_pick(self):
        self.seed(fallbacks={MAD_MAX: {"title": "Mad Max", "from": TOOL, "to": EXT, "fails": 2, "at": 1, "confirmed": True}})
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertIsNone(self.notice())

    def test_without_a_droiddeck_proton_nothing_is_switched_and_the_player_is_told(self):
        import shutil
        shutil.rmtree(self.steam / "compatibilitytools.d" / EXT_DIR)
        self.seed()
        self.end(start=0)
        self.end(pid=1100, start=20)
        self.assertNotIn("fallbacks", self.read_state())
        self.assertEqual(self.notice()["kind"], "install")
        self.assertIn("no DroidDeck-Proton is installed to move it to", self.said.getvalue())
        page = self.live({"0": TOOL, MAD_MAX: TOOL}, known=[n for n in self.names if n != EXT])
        self.assertEqual(page.sets, [])
        # once a DroidDeck-Proton is there, the next failed start moves it
        from test_steam_compat_respect import GE_PREFIX as ge
        tool = self.steam / "compatibilitytools.d" / EXT_DIR
        tool.mkdir(parents=True)
        (tool / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/proton %verb%" }')
        (tool / "compatibilitytool.vdf").write_text(C["EXTRA_COMPAT_TOOL_VDF"] % (EXT, EXT_DIR))
        (tool / C["EXTRA_WRAPPER"]).write_text("#!/bin/sh\n")
        (tool / "proton").write_text('CURRENT_PREFIX_VERSION = "%s"\n' % ge)
        self.end(pid=1200, start=60)
        self.assertEqual(self.read_state()["fallbacks"][MAD_MAX]["to"], EXT)

    def add_own_build(self, version):
        newer = self.steam / "compatibilitytools.d" / ("DroidDeck-Proton " + version)
        newer.mkdir(parents=True)
        (newer / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/proton %verb%" }')
        (newer / "compatibilitytool.vdf").write_text(C["EXTRA_COMPAT_TOOL_VDF"] % ("DroidDeck-Proton-" + version, "DroidDeck-Proton " + version))
        (newer / C["EXTRA_WRAPPER"]).write_text("#!/bin/sh\n")

    def test_the_target_is_the_validated_droiddeck_proton_not_a_newer_upstream_build(self):
        # 11-04: DroidDeck-Proton 11-12.1 appeared upstream; a title that cannot start goes to the validated build.
        self.add_own_build("11-12.1")
        self.seed()
        with mock.patch.dict(os.environ, {"DD_PROTON_VALIDATED_FILE": str(self.steam / "no-such-file")}):
            self.end(start=0)
            self.end(pid=1100, start=20)
        self.assertEqual(self.read_state()["fallbacks"][MAD_MAX]["to"], EXT)

    def test_the_default_validated_droiddeck_proton_is_the_target(self):
        # the list is in preference order (release prep T11): its first build installed is the target, not the newest
        self.add_own_build("11-11.12")
        listed = self.steam / "validated"
        listed.write_text("DroidDeck-Proton-11-11.12\nDroidDeck-Proton-11-11.11\n")
        self.seed()
        with mock.patch.dict(os.environ, {"DD_PROTON_VALIDATED_FILE": str(listed)}):
            self.end(start=0)
            self.end(pid=1100, start=20)
        self.assertEqual(self.read_state()["fallbacks"][MAD_MAX]["to"], "DroidDeck-Proton-11-11.12")

    def test_with_the_pin_off_the_newest_droiddeck_proton_is_the_target(self):
        self.add_own_build("11-12.1")
        self.seed()
        with mock.patch.dict(os.environ, {"DD_PROTON_PIN": "off"}):
            self.end(start=0)
            self.end(pid=1100, start=20)
        self.assertEqual(self.read_state()["fallbacks"][MAD_MAX]["to"], "DroidDeck-Proton-11-12.1")

    def test_failures_on_another_tool_are_counted_but_do_not_switch(self):
        self.seed()
        self.end(start=0, tool=EXT_DIR)
        self.end(pid=1100, start=20, tool=EXT_DIR)
        self.assertEqual(self.read_state()["start_runs"][MAD_MAX][EXT_DIR]["fails"], 2)
        self.assertNotIn("fallbacks", self.read_state())

    def test_the_switch_off_keeps_the_earlier_behaviour(self):
        self.seed()
        env = {"DD_PROTON_FALLBACK": "off"}
        with mock.patch.dict(os.environ, env):
            logs = self.state.parent / "Steam" / "logs"
            logs.mkdir(parents=True, exist_ok=True)
            log = logs / "gameprocess_log.txt"
            log.write_text("")
            helper = LIVE["Helper"](str(self.steam), 1)
            helper.watch_games()
            log.write_text(run_text(start=0) + run_text(pid=1100, start=20))
            helper.games_at = 0
            helper.watch_games()
            self.assertNotIn("fallbacks", self.read_state())
            self.assertNotIn("start_runs", self.read_state())
            self.assertIsNone(self.notice())
            default, out = self.session_start()
            self.assertEqual(self.mapping()[MAD_MAX], TOOL)

    def test_the_helper_reads_the_steam_log_from_the_end_and_judges_runs_as_they_end(self):
        self.seed()
        logs = self.steam / "logs"
        logs.mkdir()
        log = logs / "gameprocess_log.txt"
        log.write_text(run_text(pid=500, start=0, length=1, children=()) )  # an earlier session's run: never judged
        helper = LIVE["Helper"](str(self.steam), 1)
        with contextlib.redirect_stdout(self.said):
            with open(log, "a") as f:  # written before the helper's first look: it counts
                f.write(run_text(pid=1000, start=100, length=2))
            helper.games_at = 0
            helper.watch_games()
            with open(log, "a") as f:
                f.write(run_text(pid=1100, start=130, length=2))
            helper.games_at = 0
            helper.watch_games()
        self.assertEqual(self.read_state()["fallbacks"][MAD_MAX]["fails"], 2)

    def test_the_thresholds_can_be_set(self):
        self.seed()
        with mock.patch.dict(os.environ, {"DD_START_FAIL_LIMIT": "3", "DD_START_FAIL_SECONDS": "10"}):
            self.end(start=0)
            self.end(pid=1100, start=20)
            self.end(pid=1200, start=40, length=30)  # too long to count
            self.assertNotIn("fallbacks", self.read_state())
            self.end(pid=1300, start=80)
        self.assertEqual(self.read_state()["fallbacks"][MAD_MAX]["fails"], 3)


class NoToolBase(Base):
    """No DroidDeck-Proton is installed: the guard and the new-title default ask the app for one (compat-notice kind
    "install") and keep the move for when it is there."""
    def setUp(self):
        super().setUp()
        import shutil
        shutil.rmtree(self.steam / "compatibilitytools.d" / EXT_DIR)
        self.catalog = C["tool_catalog"](str(self.steam))
        self.without = [n for n in self.names if n != EXT]

    def install_tool(self):
        tool = self.steam / "compatibilitytools.d" / EXT_DIR
        tool.mkdir(parents=True)
        (tool / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/proton %verb%" }')
        (tool / "compatibilitytool.vdf").write_text(C["EXTRA_COMPAT_TOOL_VDF"] % (EXT, EXT_DIR))
        (tool / C["EXTRA_WRAPPER"]).write_text("#!/bin/sh\n")
        (tool / "proton").write_text('CURRENT_PREFIX_VERSION = "%s"\n' % GE_PREFIX)
        self.catalog = C["tool_catalog"](str(self.steam))

    def fail_twice(self):
        self.end(start=0)
        self.end(pid=1100, start=20)

    def take_notice(self):
        note = self.notice()
        if note:
            (self.launch / "compat-notice").unlink()
        return note


class PendingMoveTest(NoToolBase):
    def test_two_failures_without_a_tool_ask_the_app_and_keep_the_move(self):
        self.seed()
        self.fail_twice()
        state = self.read_state()
        self.assertNotIn("fallbacks", state)
        record = state["pending_fallbacks"][MAD_MAX]
        self.assertEqual((record["title"], record["from"], record["fails"]), ("Mad Max", TOOL, 2))
        self.assertEqual(state["auto"], {MAD_MAX: TOOL})  # still a follower: nothing was decided for it
        self.assertEqual(self.notice(), {"kind": "install", "app": MAD_MAX, "title": "Mad Max", "from": "Proton Experimental",
                                         "to": "", "reason": "failed"})

    def test_the_move_is_made_exactly_once_when_the_tool_is_there_at_the_next_session_start(self):
        self.seed()
        self.fail_twice()
        self.install_tool()
        _, out = self.session_start()
        self.assertEqual(self.mapping()[MAD_MAX], EXT)
        self.assertIn("DroidDeck-Proton is installed now; the move to %s that was waiting for it is made" % EXT, out)
        state = self.read_state()
        self.assertNotIn("pending_fallbacks", state)
        self.assertEqual(state["fallbacks"][MAD_MAX]["to"], EXT)
        self.assertNotIn(MAD_MAX, state["auto"])
        self.assertEqual(out.count("app %s: %s -> %s" % (MAD_MAX, TOOL, EXT)), 1, out)
        for _ in range(2):  # later starts do not move it again
            _, out = self.session_start()
            self.assertNotIn("app %s: %s -> " % (MAD_MAX, TOOL), out)
            self.assertEqual(self.mapping()[MAD_MAX], EXT)

    def test_the_live_reconcile_applies_the_waiting_move_once(self):
        self.seed()
        self.fail_twice()
        page = self.live({"0": TOOL, MAD_MAX: TOOL}, known=self.without)
        self.assertEqual(page.sets, [])
        self.assertIn("pending_fallbacks", self.read_state())
        self.install_tool()
        page = self.live(page.mapping)
        self.assertEqual(page.sets, [(MAD_MAX, EXT)])
        self.assertNotIn("pending_fallbacks", self.read_state())
        page = self.live(page.mapping)
        self.assertEqual(page.sets, [])
        self.assertTrue(self.read_state()["fallbacks"][MAD_MAX]["confirmed"])

    def test_a_tool_installed_but_not_listed_by_the_client_yet_waits_one_more_pass(self):
        self.seed()
        self.fail_twice()
        self.install_tool()
        page = self.live({"0": TOOL, MAD_MAX: TOOL}, known=self.without)
        self.assertEqual(page.sets, [])
        self.assertIn(MAD_MAX, self.read_state()["pending_fallbacks"])
        page = self.live(page.mapping, known=self.names)
        self.assertEqual(page.sets, [(MAD_MAX, EXT)])

    def test_a_pick_in_steam_meanwhile_drops_the_waiting_move(self):
        self.seed()
        self.fail_twice()
        self.helper.note_pick("%s\t%s" % (MAD_MAX, TOOL))
        self.live({"0": TOOL, MAD_MAX: TOOL}, known=self.without)
        self.install_tool()
        page = self.live({"0": TOOL, MAD_MAX: TOOL})
        self.assertEqual(page.sets, [])
        state = self.read_state()
        self.assertNotIn("pending_fallbacks", state)
        self.assertNotIn("fallbacks", state)
        for _ in range(2):
            self.session_start()
            self.assertEqual(self.mapping()[MAD_MAX], TOOL)

    def test_a_pick_in_the_app_meanwhile_drops_the_waiting_move(self):
        self.seed()
        self.fail_twice()
        (self.state / "games.json").write_text(json.dumps({"version": 1, "games": {MAD_MAX: {"valve": True, "dir": TOOL}}}))
        self.install_tool()
        _, out = self.session_start()
        self.assertIn("a tool was picked for it meanwhile", out)
        state = self.read_state()
        self.assertNotIn("pending_fallbacks", state)
        self.assertNotIn("fallbacks", state)
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)

    def test_a_title_that_left_the_followers_drops_the_waiting_move(self):
        self.seed()
        self.fail_twice()
        state = self.read_state()
        state["auto"] = {}
        self.write_state(**state)
        self.write_config({"0": EXT, MAD_MAX: TOOL})  # not a follower of Experimental, next to another general default
        self.install_tool()
        self.session_start()
        self.assertNotIn("pending_fallbacks", self.read_state())
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)

    def test_a_run_that_shows_a_frame_on_experimental_drops_the_waiting_move(self):
        self.seed()
        self.fail_twice()
        self.write_frames(2100, "MainThread", 500)
        self.end(pid=2000, start=60, length=30, children=(2100,))
        self.assertNotIn("pending_fallbacks", self.read_state())

    def test_a_third_failure_keeps_one_waiting_record(self):
        self.seed()
        self.fail_twice()
        at = self.read_state()["pending_fallbacks"][MAD_MAX]["at"]
        self.end(pid=1200, start=40)
        record = self.read_state()["pending_fallbacks"][MAD_MAX]
        self.assertEqual((record["at"], record["fails"]), (at, 3))

    def test_the_switch_off_leaves_the_old_behaviour(self):
        self.seed()
        self.fail_twice()
        self.install_tool()
        with mock.patch.dict(os.environ, {"DD_PROTON_FALLBACK": "off"}):
            self.session_start(env={"DD_PROTON_FALLBACK": "off"})
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)
        self.assertNotIn("fallbacks", self.read_state())


class InstallAskTest(NoToolBase):
    def test_the_live_helper_asks_again_in_a_new_session_but_once_per_session(self):
        self.seed()
        self.fail_twice()
        self.assertEqual(self.take_notice()["reason"], "failed")
        self.live({"0": TOOL, MAD_MAX: TOOL}, known=self.without)
        self.assertIsNone(self.notice())  # this session asked already
        self.helper = LIVE["Helper"](str(self.steam), 1)  # the next session
        self.live({"0": TOOL, MAD_MAX: TOOL}, known=self.without)
        note = self.take_notice()
        self.assertEqual((note["kind"], note["app"], note["reason"]), ("install", MAD_MAX, "failed"))
        self.live({"0": TOOL, MAD_MAX: TOOL}, known=self.without)
        self.assertIsNone(self.notice())

    def test_an_unread_note_is_not_overwritten(self):
        self.seed()
        (self.launch / "compat-notice").write_text('{"kind": "switched"}\n')
        self.fail_twice()
        self.assertEqual(self.notice(), {"kind": "switched"})
        self.take_notice()
        self.live({"0": TOOL, MAD_MAX: TOOL}, known=self.without)  # the ask is repeated, nothing was lost
        self.assertEqual(self.notice()["reason"], "failed")

    def test_no_tool_and_a_title_without_a_prefix_asks_for_one(self):
        self.write_config({"0": TOOL})
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, auto={})
        (self.steam / "steamapps/appmanifest_10.acf").write_text('"AppState" { "appid" "10" "name" "New game" }')
        page = self.live({"0": TOOL}, known=self.without)
        self.assertNotIn(("10", EXT), page.sets)
        note = self.take_notice()
        self.assertEqual((note["kind"], note["app"], note["title"], note["reason"]), ("install", "10", "New game", "new"))
        self.live(page.mapping, known=self.without)
        self.assertIsNone(self.notice())
        self.install_tool()
        page = self.live(page.mapping)
        self.assertIn(("10", EXT), page.sets)

    def test_titles_with_a_prefix_or_the_old_new_title_mode_or_the_switch_off_do_not_ask(self):
        self.write_config({"0": TOOL})
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, auto={})
        (self.steam / "steamapps/appmanifest_10.acf").write_text('"AppState" { "appid" "10" "name" "New game" }')
        self.prefix("10", VALVE_PREFIX)
        self.live({"0": TOOL, "10": TOOL}, known=self.without)
        self.assertIsNone(self.notice())
        (self.steam / "compatdata" / "10").mkdir(parents=True, exist_ok=True)
        import shutil
        for library in C["libraries"](str(self.steam)):
            shutil.rmtree(os.path.join(library, "steamapps", "compatdata", "10"), ignore_errors=True)
        for env in ({"DD_NEW_TITLE_TOOL": "experimental"}, {"DD_PROTON_FALLBACK": "off"}):
            self.helper = LIVE["Helper"](str(self.steam), 1)
            self.live({"0": TOOL}, known=self.without, env=env)
            self.assertIsNone(self.notice(), env)

    def test_a_player_pick_of_the_general_default_does_not_ask(self):
        self.write_config({"0": TOOL})
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, auto={}, source="app")
        (self.steam / "steamapps/appmanifest_10.acf").write_text('"AppState" { "appid" "10" "name" "New game" }')
        self.live({"0": TOOL}, known=self.without)
        self.assertIsNone(self.notice())


class NewTitleTest(Base):
    def setUp(self):
        super().setUp()
        self.write_config({"0": TOOL})
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        (self.steam / "steamapps/appmanifest_10.acf").write_text('"AppState" { "appid" "10" "name" "New game" }')

    def test_a_title_without_a_prefix_starts_on_droiddeck_proton(self):
        _, out = self.session_start(apps=(MAD_MAX, "10"))
        self.assertEqual(self.mapping()["10"], EXT)
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)
        self.assertIn("app 10: nothing -> %s (it is new here and starts on the pinned DroidDeck Proton (no tool yet))" % EXT, out)
        state = self.read_state()
        self.assertEqual(state["new_title_tools"], {"10": EXT})
        self.assertNotIn("10", state["auto"])
        self.assertEqual(state["default"], TOOL)

    def test_a_title_with_a_prefix_keeps_its_tool(self):
        self.write_config({"0": TOOL, MAD_MAX: TOOL})
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={MAD_MAX: TOOL})
        self.session_start(apps=(MAD_MAX,))
        self.assertEqual(self.mapping()[MAD_MAX], TOOL)
        self.assertNotIn("new_title_tools", self.read_state())

    def test_a_follower_that_never_ran_moves_and_stays_when_its_prefix_exists(self):
        self.write_config({"0": TOOL, "10": TOOL})
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={"10": TOOL})
        self.session_start(apps=("10",))
        self.assertEqual(self.mapping()["10"], EXT)
        self.prefix("10", GE_PREFIX)
        for _ in range(2):
            self.session_start(apps=("10",))
            self.assertEqual(self.mapping()["10"], EXT)

    def test_the_old_default_for_new_titles_is_one_switch_away(self):
        for env in ({"DD_NEW_TITLE_TOOL": "experimental"}, {"DD_PROTON_FALLBACK": "off"}, {"DD_PROTON_FALLBACK": "0"}):
            with self.subTest(env=env):
                self.write_config({"0": TOOL})
                self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
                self.session_start(apps=("10",), env=env)
                self.assertEqual(self.mapping()["10"], TOOL)

    def test_without_a_droiddeck_proton_the_default_stays(self):
        import shutil
        shutil.rmtree(self.steam / "compatibilitytools.d" / EXT_DIR)
        self.catalog = C["tool_catalog"](str(self.steam))
        self.session_start(apps=("10",))
        self.assertEqual(self.mapping()["10"], TOOL)

    def test_a_general_default_the_player_picked_is_followed_not_overridden(self):
        self.write_state(default=TOOL, source="steam", tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        self.session_start(apps=("10",))
        self.assertEqual(self.mapping()["10"], TOOL)
        self.write_state(default=TOOL, chosen={"0": {"name": TOOL, "at": C["now_ms"]()}}, tools=self.catalog, own_proton=True,
                         own_builds=[EXT], auto={})
        self.write_config({"0": TOOL})
        self.session_start(apps=("10",))
        self.assertEqual(self.mapping()["10"], TOOL)

    def test_a_title_the_player_picked_is_not_touched(self):
        self.write_config({"0": TOOL, "10": TOOL})
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={},
                         chosen={"10": {"name": TOOL, "at": C["now_ms"]() - 60000}})
        self.session_start(apps=("10",))
        self.assertEqual(self.mapping()["10"], TOOL)
        (self.state / "games.json").write_text(json.dumps({"version": 1, "games": {"10": {"valve": True, "dir": "Proton Experimental (ARM64)"}}}))
        self.write_state(default=TOOL, tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        self.write_config({"0": TOOL, "10": TOOL})
        self.session_start(apps=("10",))
        self.assertEqual(self.mapping()["10"], TOOL)

    def test_a_default_that_is_not_experimental_is_followed(self):
        self.write_config({"0": EXT})
        self.write_state(default=EXT, source="steam", tools=self.catalog, own_proton=True, own_builds=[EXT], auto={})
        self.session_start(apps=("10",))
        self.assertEqual(self.mapping().get("10", EXT), EXT)
        self.assertNotIn("new_title_tools", self.read_state())

    def test_the_live_helper_starts_a_new_title_on_droiddeck_proton(self):
        (self.steam / "steamapps/appmanifest_10.acf").write_text("")
        page = self.live({"0": TOOL})
        self.assertIn(("10", EXT), page.sets)
        self.assertEqual(self.read_state()["new_title_tools"], {"10": EXT})
        self.assertNotIn("10", self.read_state()["auto"])
        self.assertEqual([s for s in page.sets if s[0] == "10"], [("10", EXT)])
        page = self.live(page.mapping)
        self.assertEqual([s for s in page.sets if s[0] == "10"], [])

    def test_the_live_helper_leaves_a_title_with_a_prefix_on_its_tool(self):
        self.prefix("10", VALVE_PREFIX)
        page = self.live({"0": TOOL, "10": TOOL})
        self.assertEqual([s for s in page.sets if s[0] == "10"], [])


class ExistingTitlesTest(Base):
    """Titles that have a prefix are planned exactly as before, with the fallback on or off."""

    SCENARIOS = {
        "follower": ({"0": TOOL, MAD_MAX: TOOL, "10": TOOL}, {"default": TOOL, "auto": {MAD_MAX: TOOL, "10": TOOL}}),
        "valve pick kept": ({"0": TOOL, MAD_MAX: "proton-stable"}, {"default": TOOL, "auto": {"10": TOOL}}),
        "default moved in the client": ({"0": EXT, MAD_MAX: TOOL, "10": TOOL}, {"default": TOOL, "auto": {MAD_MAX: TOOL, "10": TOOL}}),
        "stale tool": ({"0": TOOL, MAD_MAX: "GE-Proton9-1"}, {"default": TOOL, "auto": {MAD_MAX: "GE-Proton9-1"}}),
        "ge prefix on a valve default": ({"0": TOOL, MAD_MAX: TOOL, "10": TOOL}, {"default": TOOL, "auto": {MAD_MAX: TOOL}}),
    }

    def run_scenario(self, off, mapping, state):
        path = self.state / "state.json"
        if path.exists():
            path.unlink()
        self.write_config(mapping)
        self.write_state(tools=self.catalog, own_proton=True, own_builds=[EXT], **state)
        self.session_start(apps=(MAD_MAX, "10"), env={"DD_PROTON_FALLBACK": "off" if off else "1"})
        saved = self.read_state()
        for key in ("chosen", "prefix_warnings", "valve_builds", "seq"):
            saved.pop(key, None)
        return " ".join(self.config.read_text().split()), saved

    def test_same_config_and_state_with_the_fallback_on_and_off(self):
        (self.steam / "steamapps/appmanifest_10.acf").write_text("")
        self.prefix("10", GE_PREFIX)
        for label, (mapping, state) in self.SCENARIOS.items():
            with self.subTest(label):
                self.assertEqual(self.run_scenario(False, mapping, state), self.run_scenario(True, mapping, state))

    def test_on_the_mali_bridge_a_game_with_a_prefix_keeps_its_proton_and_a_game_without_one_starts_on_droiddeck_proton(self):
        (self.steam / "steamapps/appmanifest_10.acf").write_text("")
        (self.steam / "steamapps/appmanifest_11.acf").write_text("")
        self.write_config({"0": TOOL, MAD_MAX: TOOL, "10": TOOL, "11": TOOL})
        self.write_state(default=TOOL, tools=[], auto={MAD_MAX: TOOL, "10": TOOL, "11": TOOL})
        self.prefix("10", VALVE_PREFIX)
        default, _ = self.session_start(apps=(MAD_MAX, "10", "11"), env={"BL_VKBRIDGE": "1"})
        self.assertEqual(default, TOOL)
        self.assertEqual(self.mapping(), {"0": TOOL, MAD_MAX: TOOL, "10": TOOL, "11": EXT})


class SwitchTest(unittest.TestCase):
    def test_defaults_and_switches(self):
        self.assertTrue(C["fallback_enabled"]({}))
        for off in ("0", "off", "OFF", "false", "no"):
            self.assertFalse(C["fallback_enabled"]({"DD_PROTON_FALLBACK": off}), off)
        self.assertEqual(C["new_title_mode"]({}), "droiddeck")
        self.assertEqual(C["new_title_mode"]({"DD_NEW_TITLE_TOOL": "experimental"}), "experimental")
        self.assertEqual(C["env_int"]("X", 2, 1, 10, {"X": "x"}), 2)
        self.assertEqual(C["env_int"]("X", 2, 1, 10, {"X": "99"}), 2)
        self.assertEqual(C["env_int"]("X", 2, 1, 10, {"X": "4"}), 4)


if __name__ == "__main__":
    unittest.main()
