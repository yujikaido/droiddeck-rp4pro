"""The fast path leaves statx to proot on Android before 11, where the app seccomp policy refuses statx.

There the kernel answers the trampoline's statx with SIGSYS and proot emulates it as a guest call, translating the
host path the fast path already built a second time (uutils mkdir -p "Already exists").
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

FASTPATH = Path(__file__).resolve().parents[1] / "proot/fastpath/fastpath.c"

PROGRAM = r"""
#include "FASTPATH"

static int trampoline_statx;

static long host_sys(long nr, long a, long b, long c, long d, long e, long f) {
  if (nr == SYS_statx) trampoline_statx++;
  long r = syscall(nr, a, b, c, d, e, f);
  return r < 0 ? -errno : r;
}

int main(int argc, char **argv) {
  fp_sys = host_sys;
  snprintf(root, sizeof root, "%s", argv[1]);
  rootlen = strlen(root);
  pthread_once(&init_once, fp_init);
  fp_on = 1;
  struct statx st;
  memset(&st, 0, sizeof st);
  int r = statx(AT_FDCWD, argv[2], 0, STATX_BASIC_STATS, &st);
  printf("%s %d %s\n", trampoline_statx ? "trampoline" : "traced", r,
         r == 0 && S_ISDIR(st.stx_mode) ? "dir" : "-");
  return 0;
}
"""


class FastPathStatxAndroidTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("gcc") is None:
            raise unittest.SkipTest("gcc is not available")
        cls.work = Path(tempfile.mkdtemp())
        source = cls.work / "statx.c"
        source.write_text(PROGRAM.replace("FASTPATH", str(FASTPATH)))
        cls.program = cls.work / "statx"
        subprocess.run(["gcc", "-O1", "-w", "-pthread", "-o", str(cls.program), str(source), "-ldl"], check=True)
        cls.root = cls.work / "root"
        (cls.root / "usr/local/share").mkdir(parents=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.work, ignore_errors=True)

    def run_statx(self, api, path):
        env = {k: v for k, v in os.environ.items() if not k.startswith("PROOT_FP_")}
        if api is not None:
            env["PROOT_FP_ANDROID_API"] = api
        out = subprocess.run([str(self.program), str(self.root), path], env=env,
                             capture_output=True, text=True, timeout=20, check=True).stdout
        return out.split()

    def test_android_10_and_older_leave_statx_to_proot(self):
        # The test has no proot, so the traced call reaches the host kernel with the path as given:
        # a host path answers, which shows the call went to libc's statx and not to the trampoline.
        for api in ("26", "28", "29"):
            self.assertEqual(self.run_statx(api, str(self.root / "usr/local/share")), ["traced", "0", "dir"], api)

    def test_android_11_and_newer_keep_the_fast_path(self):
        for api in ("30", "33", "35", "36"):
            self.assertEqual(self.run_statx(api, "/usr/local/share"), ["trampoline", "0", "dir"], api)

    def test_without_an_api_level_the_fast_path_is_unchanged(self):
        for api in (None, "", "abc", "29x"):
            self.assertEqual(self.run_statx(api, "/usr/local/share"), ["trampoline", "0", "dir"], repr(api))


if __name__ == "__main__":
    unittest.main()
