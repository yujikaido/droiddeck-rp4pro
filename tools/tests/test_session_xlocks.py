import os
import re
import shutil
import socket
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def function(name, tmp):
    match = re.search(r"^%s\(\) \{\n.*?^\}\n" % name, SESSION.read_text(), re.M | re.S)
    return match.group(0).replace("/tmp/", str(tmp) + "/")


class XLocksTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.tmp = self.root / "tmp"
        (self.tmp / ".X11-unix").mkdir(parents=True)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.procs = []
        self.addCleanup(self.stop)

    def stop(self):
        for p in self.procs:
            p.kill()
            p.wait()

    def process(self, name):
        exe = self.bin / name
        if not exe.exists():
            shutil.copy(shutil.which("sleep"), exe)
        p = subprocess.Popen([str(exe), "60"])
        self.procs.append(p)
        return p.pid

    def lock(self, n, pid):
        (self.tmp / (".X%d-lock" % n)).write_text("%10d\n" % pid)
        self.sock(n)

    def sock(self, n):
        s = socket.socket(socket.AF_UNIX)
        s.bind(str(self.tmp / ".X11-unix" / ("X%d" % n)))
        s.close()

    def has(self, rel):
        return os.path.lexists(self.tmp / rel)

    def run_bash(self, name, env_extra=None, call=None):
        env = {k: v for k, v in os.environ.items() if k not in ("DISPLAY", "STEAM_GAME_DISPLAY_0")}
        env.update(env_extra or {})
        script = function(name, self.tmp) + (call or name) + '\nprintf "DISPLAY=%s GAME=%s\\n" "${DISPLAY:-}" "${STEAM_GAME_DISPLAY_0:-}"\n'
        return subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, timeout=30, check=True).stdout

    def test_a_lock_whose_pid_was_reused_by_another_program_goes_with_its_socket(self):
        self.lock(1, self.process("other-program"))
        out = self.run_bash("clear_stale_xlocks")
        self.assertIn("removed 1 stale X server lock(s)/socket(s) from earlier sessions (:1)", out)
        self.assertFalse(self.has(".X1-lock"))
        self.assertFalse(self.has(".X11-unix/X1"))

    def test_a_lock_of_a_dead_pid_goes(self):
        p = subprocess.Popen(["true"])
        p.wait()
        self.lock(1, p.pid)
        self.assertIn("(:1)", self.run_bash("clear_stale_xlocks"))
        self.assertFalse(self.has(".X1-lock"))

    def test_a_live_xwayland_keeps_its_lock_and_socket(self):
        self.lock(0, self.process("Xwayland"))
        self.lock(1, self.process("other-program"))
        out = self.run_bash("clear_stale_xlocks")
        self.assertIn("removed 1 stale", out)
        self.assertTrue(self.has(".X0-lock"))
        self.assertTrue(self.has(".X11-unix/X0"))
        self.assertFalse(self.has(".X1-lock"))
        self.assertFalse(self.has(".X11-unix/X1"))

    def test_a_socket_without_a_lock_goes_and_the_directory_stays(self):
        self.sock(2)
        self.assertIn("(:2)", self.run_bash("clear_stale_xlocks"))
        self.assertFalse(self.has(".X11-unix/X2"))
        self.assertTrue(self.has(".X11-unix"))

    def test_unrelated_files_stay_and_a_clean_tmp_says_nothing(self):
        (self.tmp / ".XIM-unix").write_text("x")
        (self.tmp / "notes-lock").write_text("x")
        self.assertEqual("DISPLAY= GAME=\n", self.run_bash("clear_stale_xlocks"))
        self.assertTrue(self.has(".XIM-unix") and self.has("notes-lock"))

    def test_a_garbage_lock_goes(self):
        (self.tmp / ".X3-lock").write_text("not a pid\n")
        self.assertIn("(:3)", self.run_bash("clear_stale_xlocks"))

    def test_display_that_names_no_live_server_follows_the_real_ones(self):
        self.lock(0, self.process("Xwayland"))
        self.lock(2, self.process("Xwayland"))
        out = self.run_bash("bl_fix_displays", {"DISPLAY": ":1", "STEAM_GAME_DISPLAY_0": ":1"})
        self.assertIn("DISPLAY=:1 is not a live Xwayland, using :0", out)
        self.assertIn("STEAM_GAME_DISPLAY_0=:1 is not a live Xwayland, using :2", out)
        self.assertIn("DISPLAY=:0 GAME=:2\n", out)

    def test_displays_that_are_live_are_left_alone(self):
        self.lock(0, self.process("Xwayland"))
        self.lock(1, self.process("Xwayland"))
        out = self.run_bash("bl_fix_displays", {"DISPLAY": ":0", "STEAM_GAME_DISPLAY_0": ":1"})
        self.assertEqual("DISPLAY=:0 GAME=:1\n", out)

    def test_no_live_server_changes_nothing(self):
        self.lock(1, self.process("other-program"))
        self.assertEqual("DISPLAY=:1 GAME=\n", self.run_bash("bl_fix_displays", {"DISPLAY": ":1"}))

    def test_cleanup_runs_before_gamescope_in_the_outer_session(self):
        text = SESSION.read_text()
        self.assertLess(text.index("\n[ -z \"${BL_INSIDE:-}\" ] && clear_stale_xlocks"), text.index("exec gamescope"))
        self.assertLess(text.index("\n[ -z \"${BL_INSIDE:-}\" ] && clear_stale_xlocks"), text.index("exec /usr/local/bin/droiddeck-desktop"))


if __name__ == "__main__":
    unittest.main()
