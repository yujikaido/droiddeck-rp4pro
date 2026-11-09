import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static int score(void) {
  FILE *f = fopen("/proc/self/oom_score_adj", "r");
  int v = -1;
  if (f && fscanf(f, "%d", &v) != 1) v = -1;
  if (f) fclose(f);
  return v;
}

static int try_write(const char *path, const char *text) {
  int fd = open(path, O_WRONLY);
  if (fd < 0) return -1;
  ssize_t n = write(fd, text, strlen(text));
  close(fd);
  return n == (ssize_t)strlen(text) ? 0 : -2;
}

int main(int argc, char **argv) {
  const char *how = argv[1];
  char path[128];
  int pid = getpid();
  int before = score();
  if (!strcmp(how, "open")) try_write("/proc/self/oom_score_adj", "900");
  else if (!strcmp(how, "open64")) {
    int fd = open64("/proc/self/oom_score_adj", O_WRONLY);
    if (fd >= 0) { if (write(fd, "900", 3) < 0) {} close(fd); }
  } else if (!strcmp(how, "openat")) {
    int fd = openat(AT_FDCWD, "/proc/self/oom_score_adj", O_WRONLY);
    if (fd >= 0) { if (write(fd, "900", 3) < 0) {} close(fd); }
  } else if (!strcmp(how, "fopen")) {
    FILE *f = fopen("/proc/self/oom_score_adj", "w");
    if (f) { fprintf(f, "900"); fclose(f); }
  } else if (!strcmp(how, "fopen64")) {
    FILE *f = fopen64("/proc/self/oom_score_adj", "w");
    if (f) { fprintf(f, "900"); fclose(f); }
  } else if (!strcmp(how, "thread-self")) try_write("/proc/thread-self/oom_score_adj", "900");
  else if (!strcmp(how, "pid")) { snprintf(path, sizeof path, "/proc/%d/oom_score_adj", pid); try_write(path, "900"); }
  else if (!strcmp(how, "task")) { snprintf(path, sizeof path, "/proc/%d/task/%d/oom_score_adj", pid, pid); try_write(path, "900"); }
  else if (!strcmp(how, "legacy")) try_write("/proc/self/oom_adj", "15");
  else if (!strcmp(how, "chromium")) {
    /* Chromium's browser/zygote raising a renderer it started (kLowestRendererOomScore 300) the way
       base::WriteFile does it: open(O_WRONLY | O_CREAT | O_TRUNC, 0644) of /proc/<child>/oom_score_adj.
       The child prints its own score once the parent is done. */
    int ready[2];
    if (pipe(ready)) return 1;
    pid_t child = fork();
    if (child == 0) {
      char b;
      if (read(ready[0], &b, 1) != 1) _exit(1);
      printf("%d %d\n", before, score());
      fflush(stdout);
      _exit(0);
    }
    snprintf(path, sizeof path, "/proc/%d/oom_score_adj", (int)child);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) { if (write(fd, "300", 3) < 0) {} close(fd); }
    if (write(ready[1], "x", 1) != 1) return 1;
    waitpid(child, NULL, 0);
    return 0;
  }
  else if (!strcmp(how, "read-only")) {
    int fd = open("/proc/self/oom_score_adj", O_RDONLY);
    char b[16];
    printf("read %d\n", fd >= 0 && read(fd, b, sizeof b) > 0);
  } else if (!strcmp(how, "other-file")) {
    int fd = open(argv[2], O_WRONLY | O_TRUNC);
    if (fd >= 0) { if (write(fd, "900", 3) < 0) {} close(fd); }
  }
  printf("%d %d\n", before, score());
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc"), "needs a C compiler")
class OomAdjPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.dir = Path(cls.temp.name)
        (cls.dir / "program.c").write_text(PROGRAM)
        cls.preload = cls.dir / "libblsession.so"
        cls.program = cls.dir / "program"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-DBL_CLOCK_ANY_ARCH",
                        '-DCP_CTL="%s"' % (cls.dir / "clock"), "-o", str(cls.preload),
                        *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        subprocess.run(["cc", "-O2", "-o", str(cls.program), str(cls.dir / "program.c")], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_program(self, how, switch="1", *extra):
        env = {k: v for k, v in os.environ.items() if k != "BL_OOM_ADJ"}
        env["LD_PRELOAD"] = str(self.preload)
        if switch is not None:
            env["BL_OOM_ADJ"] = switch
        result = subprocess.run([str(self.program), how, *extra], env=env, capture_output=True, text=True,
                                check=True, timeout=30)
        before, after = map(int, result.stdout.splitlines()[-1].split())
        return before, after, result.stderr

    def test_a_raise_the_program_can_make_works_without_the_switch(self):
        before, after, stderr = self.run_program("open", None)
        if after == before:
            self.skipTest("this environment does not let a process write its own score")
        self.assertEqual(900, after)
        self.assertEqual("", stderr)

    def test_with_the_switch_every_way_of_writing_the_score_is_dropped(self):
        for how in ("open", "open64", "openat", "fopen", "fopen64", "thread-self", "pid", "task", "legacy", "chromium"):
            with self.subTest(how=how):
                before, after, stderr = self.run_program(how)
                self.assertEqual(before, after)
                self.assertIn("oom_score_adj was dropped", stderr)

    def test_chromium_raising_its_renderer_is_dropped(self):
        """B4 (a player log/Q5M9 POCO X8 Pro, gon070's vivo V2515): from v0.5.5 to v0.5.5.2 Steam's web helper raised every
        renderer to 300 and the SharedJSContext renderer was killed about once a second ("Tab Killed"); v0.5.4.2 and
        test2+ drop the write and the renderer keeps the app's score. Without the drop the child really gets 300 here, so
        this test fails if the drop stops catching the parent-writes-child's-file pattern."""
        before, after, _ = self.run_program("chromium", None)
        if after == before:
            self.skipTest("this environment does not let a process raise its child's score")
        self.assertEqual(300, after)
        before, after, stderr = self.run_program("chromium")
        self.assertEqual(before, after)
        self.assertIn("oom_score_adj was dropped", stderr)

    def test_the_switch_set_to_zero_or_empty_leaves_the_write_alone(self):
        before, after, _ = self.run_program("open", None)
        if after == before:
            self.skipTest("this environment does not let a process write its own score")
        for off in ("0", ""):
            with self.subTest(switch=off):
                self.assertEqual(900, self.run_program("open", off)[1])

    def test_reading_the_score_is_untouched(self):
        env = dict(os.environ, LD_PRELOAD=str(self.preload), BL_OOM_ADJ="1")
        out = subprocess.run([str(self.program), "read-only"], env=env, capture_output=True, text=True,
                             check=True, timeout=30).stdout
        self.assertIn("read 1", out)

    def test_other_files_are_written_normally(self):
        target = self.dir / "other"
        target.write_text("old")
        env = dict(os.environ, LD_PRELOAD=str(self.preload), BL_OOM_ADJ="1")
        subprocess.run([str(self.program), "other-file", str(target)], env=env, check=True, timeout=30,
                       capture_output=True)
        self.assertEqual("900", target.read_text())

    def test_it_says_so_once_per_process(self):
        _, _, stderr = self.run_program("open")
        self.assertEqual(1, stderr.count("oom_score_adj was dropped"))


class SessionTurnsItOnTest(unittest.TestCase):
    """v0.5.4.2 dropped Steam's 900 for every game; v0.5.5 lost it in a rebase and games on vivo/Samsung phones were killed at
    random (a player log/FTZH "Killed python3", Mad Max). The app must keep setting the switch for every session, before the
    droiddeck-env lines so that BL_OOM_ADJ=0 there still wins."""

    SERVICE = Path(__file__).resolve().parents[2] / "app/src/main/java/com/droiddeck/launcher/session/SessionService.kt"

    def test_every_session_gets_the_switch_before_the_player_overrides(self):
        text = self.SERVICE.read_text()
        self.assertIn('internal const val OOM_ADJ_ENV = "BL_OOM_ADJ"', text)
        body = text[text.index("private fun addClientEnvironment("):]
        body = body[:body.index("\n    private fun ", 10)]
        self.assertIn('guest.add("$OOM_ADJ_ENV=1")', body)
        self.assertLess(body.index('guest.add("$OOM_ADJ_ENV=1")'), body.index("extra.forEach { guest.add(it) }"))


if __name__ == "__main__":
    unittest.main()
