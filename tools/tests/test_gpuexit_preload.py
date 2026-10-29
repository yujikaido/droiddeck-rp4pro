import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

# argv[1]: how to end (exit, _exit, _Exit, return), argv[2]: the status; prints without a newline first.
PROGRAM = r"""
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static void bye(void) { printf("[atexit]"); }
int main(int argc, char **argv) {
  atexit(bye);
  printf("ran");
  int status = atoi(argv[2]);
  if (!strcmp(argv[1], "exit")) exit(status);
  if (!strcmp(argv[1], "_exit")) { fflush(stdout); _exit(status); }
  if (!strcmp(argv[1], "_Exit")) { fflush(stdout); _Exit(status); }
  return status;
}
"""


@unittest.skipUnless(shutil.which("cc"), "needs cc")
class GpuExitPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.lib = cls.tmp / "libgpuexit.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-Werror", "-o", str(cls.lib),
                        str(PRELOAD / "gpuexit.c"), "-ldl"], check=True)
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

    def run_it(self, exe, how, status, *args, env=None):
        home = Path(tempfile.mkdtemp(dir=self.tmp))
        e = {k: v for k, v in os.environ.items() if k not in ("XDG_DATA_HOME", "BL_WEBHELPER_GPU_DIAG")}
        e.update({"HOME": str(home), "LD_PRELOAD": str(self.lib)})
        e.update(env or {})
        (home / ".local/share/Steam/logs").mkdir(parents=True)
        r = subprocess.run([str(exe), how, str(status), *args], env=e, capture_output=True, text=True)
        log = home / ".local/share/Steam/logs/droiddeck_webhelper_gpu.txt"
        return r, (log.read_text() if log.exists() else None)

    def test_a_gpu_process_that_exits_with_1_says_who_called_exit(self):
        r, log = self.run_it(self.webhelper, "exit", 1, "--type=gpu-process", "--no-sandbox")
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stdout, "ran[atexit]")  # atexit handlers and stdio still run
        self.assertRegex(log, r"droiddeck: steamwebhelper GPU process \d+ started \(preload loaded\)\n")
        self.assertRegex(log, r"GPU process \d+ exit\(1\) called from steamwebhelper\+0x[0-9a-f]+.*, errno \d+ \(.*\), [0-9.]+ ms after start\n")
        self.assertEqual(log.count("\n"), 2)
        self.assertIn("exit(1) called from steamwebhelper+0x", r.stderr)

    def test__exit_and__Exit_keep_their_status(self):
        for how in ("_exit", "_Exit"):
            r, log = self.run_it(self.webhelper, how, 3, "--type=gpu-process")
            self.assertEqual(r.returncode, 3, how)
            self.assertEqual(r.stdout, "ran", how)  # no atexit handlers, as without the preload
            self.assertIn(f"{how}(3) called from steamwebhelper+0x", log, how)

    def test_a_clean_exit_only_says_it_started(self):
        for how in ("exit", "return"):
            r, log = self.run_it(self.webhelper, how, 0, "--type=gpu-process")
            self.assertEqual(r.returncode, 0, how)
            self.assertIn("started (preload loaded)", log, how)
            self.assertNotIn("called from", log, how)

    def test_other_processes_are_left_alone(self):
        for exe, args in ((self.webhelper, ("--type=renderer",)), (self.webhelper, ()), (self.other, ("--type=gpu-process",))):
            r, log = self.run_it(exe, "exit", 2, *args)
            self.assertEqual(r.returncode, 2, (exe.name, args))
            self.assertEqual(r.stdout, "ran[atexit]", (exe.name, args))
            self.assertIsNone(log, (exe.name, args))
            self.assertNotIn("droiddeck:", r.stderr, (exe.name, args))

    def test_switched_off(self):
        r, log = self.run_it(self.webhelper, "exit", 1, "--type=gpu-process", env={"BL_WEBHELPER_GPU_DIAG": "0"})
        self.assertEqual(r.returncode, 1)
        self.assertIsNone(log)

    def test_xdg_data_home_and_a_long_file_started_afresh(self):
        data = Path(tempfile.mkdtemp(dir=self.tmp))
        logs = data / "Steam/logs"
        logs.mkdir(parents=True)
        (logs / "droiddeck_webhelper_gpu.txt").write_text("x" * (300 * 1024))
        r, _ = self.run_it(self.webhelper, "exit", 1, "--type=gpu-process", env={"XDG_DATA_HOME": str(data)})
        self.assertEqual(r.returncode, 1)
        text = (logs / "droiddeck_webhelper_gpu.txt").read_text()
        self.assertTrue(text.startswith("20"), text[:40])
        self.assertIn("exit(1) called from", text)


if __name__ == "__main__":
    unittest.main()
