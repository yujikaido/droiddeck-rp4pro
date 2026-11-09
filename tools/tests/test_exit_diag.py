"""Exit-at-start diagnostics: a title that closed at its start without showing a frame (the start-failure guard's "fail",
steam-compatibility start_run_outcome) gets Proton's log, DXVK at info and vkd3d warnings for its NEXT launch only, into the
session's log folder (exit-diag/), so the next Send logs already holds the cause (Little Nightmares a player log, Stray 
closed 6-8 s after the start, no frame, the bridge clean, no Proton log). Launches of titles that ran get nothing."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_steam_compat_respect import C, MAD_MAX, TOOL  # noqa: E402
from test_steam_compat_fallback import Base  # noqa: E402

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"


class ArmTest(Base):
    def flag(self, app=MAD_MAX, used=False):
        return self.state / "exit-diag" / (app + (".used" if used else ""))

    def test_a_start_without_a_frame_arms_one_diagnostic_launch_and_says_so(self):
        self.seed()
        self.end(start=0, length=6)
        self.assertTrue(self.flag().is_file())
        text = self.said.getvalue()
        self.assertIn("app %s (Mad Max): its next start writes Proton's log" % MAD_MAX, text)
        self.assertIn("DD_EXIT_DIAG=0", text)

    def test_it_is_armed_once_while_armed_and_not_again_after_the_diagnostic_launch_failed_too(self):
        self.seed(auto=False)
        self.end(start=0, length=6)
        self.end(pid=1100, start=20, length=6)
        self.assertEqual(self.said.getvalue().count("its next start writes Proton's log"), 1)
        # the diagnostic launch took it (droiddeck-game-env renames it), and that launch closed again: no second one
        self.flag().rename(self.flag(used=True))
        self.end(pid=1200, start=40, length=6)
        self.assertFalse(self.flag().exists())
        self.assertEqual(self.said.getvalue().count("its next start writes Proton's log"), 1)

    def test_a_run_that_shows_frames_clears_it_so_a_later_failure_gets_one_again(self):
        self.seed(auto=False)
        self.end(start=0, length=6)
        self.flag().rename(self.flag(used=True))
        self.write_frames(1301, "MainThread", 500)
        self.end(pid=1300, start=30, length=40, children=(1301,))
        self.assertFalse(self.flag().exists())
        self.assertFalse(self.flag(used=True).exists())
        self.end(pid=1400, start=100, length=6)
        self.assertTrue(self.flag().is_file())

    def test_a_long_run_or_a_session_without_frame_counts_arms_nothing(self):
        self.seed()
        self.end(start=0, length=300)
        self.assertFalse(self.flag().exists())
        (self.frames / "vkbridge-frames-9000.txt").unlink()
        self.end(pid=1100, start=400, length=6)
        self.assertFalse(self.flag().exists())

    def test_the_switch_turns_it_off(self):
        self.seed()
        with mock.patch.dict(os.environ, {"DD_EXIT_DIAG": "0"}):
            self.end(start=0, length=6)
        self.assertFalse(self.flag().exists())
        self.assertNotIn("its next start writes Proton's log", self.said.getvalue())

    def test_the_launch_without_the_client_arms_it_too(self):
        state = {}
        kind, _, _ = C["judge_launch"](state, str(self.steam), MAD_MAX, TOOL, 0, 6, "nothing")
        self.assertEqual(kind, "fail")
        self.assertTrue(C["arm_exit_diag"](MAD_MAX))
        self.assertFalse(C["arm_exit_diag"](MAD_MAX))
        C["clear_exit_diag"](MAD_MAX)
        self.assertFalse(self.flag().exists())


class LaunchTest(unittest.TestCase):
    """droiddeck-game-env takes the flag at the game's launch (one rename) and sets the logs for that launch only."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.session = self.home / "session"
        self.session.mkdir()
        self.flags = self.home / ".local/share/droiddeck-compat/exit-diag"
        self.probe = self.home / "proton"
        self.probe.write_text("#!" + sys.executable + "\nimport json, os\nprint(json.dumps(dict(os.environ)))\n")
        self.probe.chmod(0o755)
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(("PROTON_", "DXVK_", "VKD3D_", "WINEDEBUG", "DD_EXIT"))}
        self.env.update(HOME=self.tmp.name, STEAM_COMPAT_DATA_PATH=str(self.home / "compatdata/424840"), BL_DEBUG_DIR=str(self.session))

    def launch(self, verb="waitforexitandrun", **extra):
        r = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(self.probe), verb, "game.exe"],
                           env={**self.env, **extra}, text=True, capture_output=True, check=True)
        return json.loads(r.stdout), r.stderr

    def arm(self, app="424840"):
        self.flags.mkdir(parents=True, exist_ok=True)
        (self.flags / app).write_text("1\n")

    def test_a_launch_without_the_flag_gets_no_log_settings(self):
        env, err = self.launch()
        for key in ("PROTON_LOG", "PROTON_LOG_DIR", "DXVK_LOG_LEVEL", "DXVK_LOG_PATH", "VKD3D_DEBUG"):
            self.assertNotIn(key, env)
        self.assertNotIn("exit-diag", err)
        self.assertFalse((self.session / "exit-diag").exists())

    def test_the_armed_launch_gets_the_logs_once_and_takes_the_flag(self):
        self.arm()
        env, err = self.launch()
        out = str(self.session / "exit-diag")
        self.assertEqual((env["PROTON_LOG"], env["PROTON_LOG_DIR"], env["DXVK_LOG_PATH"]), ("1", out, out))
        self.assertEqual((env["DXVK_LOG_LEVEL"], env["VKD3D_DEBUG"]), ("info", "warn"))
        self.assertTrue(os.path.isdir(out))
        self.assertIn("droiddeck-game-env: app 424840 closed at its last start without showing a frame", err)
        self.assertIn("this launch only", err)
        self.assertFalse((self.flags / "424840").exists())
        self.assertTrue((self.flags / "424840.used").exists())
        env, err = self.launch()
        self.assertNotIn("PROTON_LOG", env)

    def test_another_title_or_a_non_game_verb_does_not_take_it(self):
        self.arm("999")
        env, _ = self.launch()
        self.assertNotIn("PROTON_LOG", env)
        self.arm()
        env, _ = self.launch(verb="run")
        self.assertNotIn("PROTON_LOG", env)
        self.assertTrue((self.flags / "424840").exists())

    def test_the_players_own_log_settings_are_kept(self):
        self.arm()
        env, err = self.launch(PROTON_LOG="1", PROTON_LOG_DIR="/sdcard/mine", DXVK_LOG_LEVEL="debug")
        self.assertEqual((env["PROTON_LOG_DIR"], env["DXVK_LOG_LEVEL"]), ("/sdcard/mine", "debug"))
        self.assertEqual(env["VKD3D_DEBUG"], "warn")

    def test_without_a_session_log_folder_nothing_is_set_and_it_says_why(self):
        self.arm()
        env, err = self.launch(BL_DEBUG_DIR="")
        self.assertNotIn("PROTON_LOG", env)
        self.assertIn("exit diagnostics skipped", err)

    def test_the_switch_turns_it_off_at_launch_too(self):
        self.arm()
        env, _ = self.launch(DD_EXIT_DIAG="0")
        self.assertNotIn("PROTON_LOG", env)


if __name__ == "__main__":
    unittest.main()
