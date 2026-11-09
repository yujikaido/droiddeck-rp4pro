"""Steam's browser children: who ends them (preload/childkill.c, B4).

The fingerprint from the POCO X8 Pro class and vivo V2515 (gon070): the renderer of Steam's
SharedJSContext ends 0.3-4 s after every start and Chromium logs "Tab Killed". Chromium's zygote calls that
whatever sent the SIGKILL: Android, another guest process, or its own reap after a broken channel (it sends
SIGKILL itself and then waits). These tests imitate each case with a zygote that behaves like Chromium's
(fork + exec of a renderer, kill(SIGKILL) on a child it was told is dead, then a blocking waitpid) and check
that the log names the right one, and that nothing changes for the programs themselves."""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

# A Chromium-like zygote and its renderer. argv[1] is --type=zygote or --type=renderer.
# zygote: argv[2] scenario, argv[3] pid file. renderer: argv[2] what to do.
PROGRAM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static void nap(int ms) { struct timespec t = {ms / 1000, (ms % 1000) * 1000000L}; nanosleep(&t, NULL); }

int main(int argc, char **argv) {
  if (!strcmp(argv[1], "--type=renderer")) {
    if (!strcmp(argv[2], "exit13")) _exit(13);
    if (!strcmp(argv[2], "exit0")) return 0;
    for (;;) pause();
  }
  const char *scenario = argv[2];
  const char *what = !strcmp(scenario, "selfexit") ? "exit13" : !strcmp(scenario, "clean") ? "exit0" : "sleep";
  pid_t child = fork();
  if (child == 0) {
    execl(argv[0], argv[0], "--type=renderer", what, (char *)NULL); /* comm stays steamwebhelper, as after the zygote's fork */
    _exit(127);
  }
  FILE *f = fopen(argv[3], "w");
  fprintf(f, "%d\n", (int)child);
  fclose(f);
  if (!strcmp(scenario, "outside") || !strcmp(scenario, "guest")) {
    /* someone else ends it; Chromium learns it from the broken channel, then reaps the known-dead child */
    siginfo_t si;
    waitid(P_PID, child, &si, WEXITED | WNOWAIT);
    kill(child, SIGKILL);
  } else if (!strcmp(scenario, "chromium")) {
    nap(200);
    kill(child, SIGKILL);
  } else if (!strcmp(scenario, "signal0")) {
    nap(200);
    kill(child, 0);
    kill(child, SIGURG);
    kill(child, SIGTERM);
  }
  int status = -1;
  pid_t r = waitpid(child, &status, 0);
  errno = 0;
  int k = kill(999999999, SIGKILL);
  printf("waited %d status %d esrch %d\n", r == child, status, k == -1 && errno == ESRCH);
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc") and shutil.which("sh"), "needs cc and sh")
class WebhelperExitDiagTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.lib = cls.tmp / "libchildkill.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-Werror", "-o", str(cls.lib),
                        str(PRELOAD / "childkill.c"), str(PRELOAD / "gpuexit.c"), "-ldl"], check=True)
        source = cls.tmp / "program.c"
        source.write_text(PROGRAM)
        bindir = cls.tmp / "steamrtarm64"
        bindir.mkdir()
        cls.webhelper = bindir / "steamwebhelper"
        cls.other = bindir / "steam"
        subprocess.run(["cc", "-O2", "-o", str(cls.webhelper), str(source)], check=True)
        shutil.copy(cls.webhelper, cls.other)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def env(self, home, preload=True, **extra):
        e = {k: v for k, v in os.environ.items()
             if k not in ("XDG_DATA_HOME", "BL_WEBHELPER_EXIT_DIAG", "BL_WEBHELPER_GPU_DIAG", "BL_VKBRIDGE", "LD_PRELOAD")}
        e.update({"HOME": str(home), "BL_VKBRIDGE": "1"})
        if preload:
            e["LD_PRELOAD"] = str(self.lib)
        e.update(extra)
        return e

    def run_zygote(self, scenario, exe=None, outside=None, preload=True, **extra):
        home = Path(tempfile.mkdtemp(dir=self.tmp))
        (home / ".local/share/Steam/logs").mkdir(parents=True)
        pidfile = home / "child.pid"
        env = self.env(home, preload, **extra)
        proc = subprocess.Popen([str(exe or self.webhelper), "--type=zygote", scenario, str(pidfile)], env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        child = None
        deadline = time.monotonic() + 10
        while child is None and time.monotonic() < deadline:
            try:
                child = int(pidfile.read_text())
            except (OSError, ValueError):
                time.sleep(0.01)
        self.assertIsNotNone(child, "the zygote never started its renderer")
        if outside == "harness":
            time.sleep(0.2)
            os.kill(child, signal.SIGKILL)  # no preload here: as Android or the kernel would
        elif outside == "guest":
            time.sleep(0.2)
            subprocess.run(["sh", "-c", "kill -9 %d" % child], env=env, check=True, timeout=10)
        out, err = proc.communicate(timeout=20)
        log = home / ".local/share/Steam/logs/droiddeck_webhelper_exits.txt"
        return proc.returncode, out, err, (log.read_text() if log.exists() else None), child

    def test_a_kill_from_outside_the_guest_is_named_so(self):
        """The Q5M9 fingerprint: SIGKILL from nobody in the guest; the zygote's own kill finds a dead child."""
        rc, out, err, log, child = self.run_zygote("outside", outside="harness")
        self.assertEqual(rc, 0, err)
        self.assertIn("waited 1 status 9 esrch 1", out)
        self.assertRegex(log, r"steamwebhelper zygote \(pid \d+\) sends SIGKILL \(9\) to steamwebhelper pid %d \(ended\), "
                              r"state Z \(it had already ended\), oom_score_adj -?\d+, via kill\n" % child)
        self.assertRegex(log, r"child %d ended by SIGKILL \(9\); its oom_score_adj -?\d+ \(this process -?\d+\), MemAvailable \d+ MB; "
                              r"no steamwebhelper process sent it while it ran \(the kills seen came after it had ended\): it came "
                              r"from outside Steam's browser \(another guest program, Android or the kernel; " % child)
        self.assertIn("came from outside Steam's browser", err)  # steamwebhelper processes also say it on stderr
        # BL_WEBHELPER_EXIT_DIAG=all (every guest process hooked, B4 test builds): the same reap says "outside the guest"
        rc, out, err, log, child = self.run_zygote("outside", outside="harness", BL_WEBHELPER_EXIT_DIAG="all")
        self.assertEqual(rc, 0, err)
        self.assertRegex(log, r"child %d ended by SIGKILL \(9\);.*nobody in the guest sent it while it ran \(the kills seen came after "
                              r"it had ended\): it came from outside the guest \(Android or the kernel\)\n" % child)

    def test_other_guest_processes_kill_straight_through(self):
        """Release prep T11 (owner 11-04): only steamwebhelper processes are hooked. Another guest program's kill of a webhelper
        process goes straight to the system (nothing read, nothing written; fails on t10 c6268f525, which wrote the sender's line
        from every guest process); the webhelper's own reap still says it came from outside Steam's browser."""
        rc, out, err, log, child = self.run_zygote("guest", outside="guest")
        self.assertEqual(rc, 0, err)
        self.assertIn("waited 1 status 9", out)
        self.assertNotIn("sh -c", log)
        self.assertNotRegex(log, r"\(pid \d+\) sends SIGKILL \(9\) to steamwebhelper pid %d \(renderer\), state [SR]" % child)
        self.assertRegex(log, r"child %d ended by SIGKILL \(9\);.*; no steamwebhelper process sent it while it ran" % child)
        # and a non-webhelper program's own kills and waits: same results, no file at all
        rc, out, err, log, _ = self.run_zygote("chromium", exe=self.other)
        self.assertEqual((rc, log), (0, None), err)
        self.assertIn("waited 1 status 9 esrch 1", out)
        self.assertNotIn("droiddeck:", err)

    def test_a_guest_sender_is_named_with_every_process_hooked(self):
        rc, out, err, log, child = self.run_zygote("guest", outside="guest", BL_WEBHELPER_EXIT_DIAG="all")
        self.assertEqual(rc, 0, err)
        self.assertIn("waited 1 status 9", out)
        self.assertRegex(log, r"sh -c \(pid \d+\) sends SIGKILL \(9\) to steamwebhelper pid %d \(renderer\), state [SR], "
                              r"oom_score_adj -?\d+, via kill\n" % child)
        self.assertRegex(log, r"child %d ended by SIGKILL \(9\);.*; sent by sh -c \(pid \d+\) while it ran\n" % child)

    def test_chromium_s_own_kill_of_a_running_child_is_named(self):
        rc, out, err, log, child = self.run_zygote("chromium")
        self.assertEqual(rc, 0, err)
        self.assertIn("waited 1 status 9", out)
        self.assertRegex(log, r"to steamwebhelper pid %d \(renderer\), state [SR], " % child)
        self.assertRegex(log, r"child %d ended by SIGKILL \(9\);.*; this process ended it while it still ran \(Chromium's own kill\)\n"
                         % child)

    def test_a_renderer_that_exits_by_itself_says_where(self):
        rc, out, err, log, child = self.run_zygote("selfexit")
        self.assertEqual(rc, 0, err)
        self.assertIn("waited 1 status %d" % (13 << 8), out)
        self.assertRegex(log, r"steamwebhelper renderer \(pid %d\) _exit\(13\) called from steamwebhelper\+0x[0-9a-f]+, errno \d+\n" % child)
        self.assertRegex(log, r"child %d ended with status 13;.*; it exited by itself\n" % child)

    def test_a_clean_life_writes_nothing(self):
        rc, out, err, log, _ = self.run_zygote("clean")
        self.assertEqual(rc, 0, err)
        self.assertIn("waited 1 status 0 esrch 1", out)
        self.assertIsNone(log)
        self.assertNotIn("droiddeck:", err)

    def test_signal_0_and_harmless_signals_are_not_written(self):
        rc, out, err, log, child = self.run_zygote("signal0")
        self.assertEqual(rc, 0, err)
        self.assertIn("status 15", out)
        self.assertEqual(log.count("sends "), 1, log)  # only the SIGTERM
        self.assertIn("sends SIGTERM (15) to steamwebhelper pid %d (renderer)" % child, log)

    def test_the_same_results_without_the_preload(self):
        for scenario, outside in (("outside", "harness"), ("chromium", None), ("selfexit", None), ("clean", None)):
            with self.subTest(scenario=scenario):
                a = self.run_zygote(scenario, outside=outside)
                b = self.run_zygote(scenario, outside=outside, preload=False)
                self.assertEqual(a[0], b[0])
                self.assertEqual(a[1], b[1])  # status bits and errno exactly as without it
                self.assertIsNone(b[3])

    def test_other_programs_are_left_alone(self):
        # A zygote that is not steamwebhelper (Steam's own client here) and its children: no line from the reap.
        rc, out, err, log, _ = self.run_zygote("chromium", exe=self.other)
        self.assertEqual(rc, 0, err)
        self.assertIn("waited 1 status 9", out)
        self.assertIsNone(log)

    def test_switched_off(self):
        rc, out, err, log, _ = self.run_zygote("outside", outside="harness", BL_WEBHELPER_EXIT_DIAG="0")
        self.assertEqual(rc, 0, err)
        self.assertIsNone(log)
        self.assertNotIn("droiddeck:", err)

    def test_off_without_the_bridge_and_forced_on(self):
        rc, _, err, log, _ = self.run_zygote("outside", outside="harness", BL_VKBRIDGE="0")
        self.assertEqual(rc, 0, err)
        self.assertIsNone(log)
        rc, _, err, log, _ = self.run_zygote("outside", outside="harness", BL_VKBRIDGE="0", BL_WEBHELPER_EXIT_DIAG="1")
        self.assertEqual(rc, 0, err)
        self.assertIn("came from outside Steam's browser", log)

    def test_a_long_file_is_started_afresh(self):
        home = Path(tempfile.mkdtemp(dir=self.tmp))
        data = home / "data"
        logs = data / "Steam/logs"
        logs.mkdir(parents=True)
        (logs / "droiddeck_webhelper_exits.txt").write_text("x" * (300 * 1024))
        rc, _, err, _, _ = self.run_zygote("chromium", XDG_DATA_HOME=str(data))
        self.assertEqual(rc, 0, err)
        text = (logs / "droiddeck_webhelper_exits.txt").read_text()
        self.assertTrue(text.startswith("20"), text[:40])
        self.assertIn("Chromium's own kill", text)


if __name__ == "__main__":
    unittest.main()
