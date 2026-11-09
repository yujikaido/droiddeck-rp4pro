"""droiddeck-session's proot self-test: two lines in session.log that tell the Android 10 failure classes apart
(a player log/statx answered for the wrong path, "mkdir: Already exists" / ls "No such file or directory";
getcwd/chdir "Function not implemented") and show what the fast path was told (PROOT_FP_ANDROID_API).
The failing commands are stubs here; the same function was run under the Android 10 arm64 filter with the published
proot pack on the qemu bench."""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def self_test_function():
    match = re.search(r"^proot_self_test\(\) \{\n.*?^\}\n", SESSION.read_text(), re.M | re.S)
    assert match, "proot_self_test not found in droiddeck-session"
    return match.group(0)


class ProotSelfTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()

    def stub(self, name, message, status=1):
        path = self.bin / name
        path.write_text("#!/bin/sh\necho %r >&2\nexit %d\n" % (message, status))
        path.chmod(0o755)

    def run_test(self, env=None):
        full = {"PATH": "%s:%s" % (self.bin, os.environ.get("PATH", "/usr/bin:/bin")), "HOME": str(self.tmp)}
        full.update(env or {})
        out = subprocess.run(["bash", "-c", "set -u\n%sproot_self_test; echo rc=$?" % self_test_function()],
                             env=full, capture_output=True, text=True, timeout=30)
        return out.stdout.splitlines()

    def test_header_names_what_the_fast_path_was_told(self):
        lines = self.run_test({"PROOT_FP_KEY": "k", "PROOT_FP_ANDROID_API": "29"})
        self.assertTrue(lines[0].startswith("== proot self-test: fast path on, android api 29, kernel "), lines)
        lines = self.run_test()
        self.assertTrue(lines[0].startswith("== proot self-test: fast path off, android api unset, kernel "), lines)

    @unittest.skipUnless(Path("/usr/local/share").is_dir() and Path("/usr/share").is_dir() and Path("/bin/pwd").exists(),
                         "needs /usr/local/share, /usr/share and /bin/pwd")
    def test_a_working_proot_passes_every_check(self):
        lines = self.run_test()
        self.assertEqual("== proot self-test: mkdir-p ok, ls ok, cd ok, new file ok", lines[-2])
        self.assertEqual("rc=0", lines[-1])

    def test_the_statx_class_is_named(self):
        self.stub("mkdir", "mkdir: Already exists")
        self.stub("ls", "ls: cannot access '/usr/local/share': No such file or directory", 2)
        lines = self.run_test()
        self.assertIn("mkdir-p FAILED (mkdir: Already exists), "
                      "ls FAILED (ls: cannot access '/usr/local/share': No such file or directory)", lines[-2])
        self.assertEqual("rc=0", lines[-1])

    def test_a_failing_new_file_is_named_and_never_fails_the_session(self):
        self.stub("mktemp", "mktemp: failed to create file via template: Function not implemented")
        lines = self.run_test()
        self.assertIn("new file FAILED (mktemp: failed to create file via template: Function not implemented)", lines[-2])
        self.assertEqual("rc=0", lines[-1])


if __name__ == "__main__":
    unittest.main()
