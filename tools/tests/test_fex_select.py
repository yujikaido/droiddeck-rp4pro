import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-fex-update"
SOURCE = SCRIPT.read_text()
START = SOURCE.index("fex_ready() {")
END = SOURCE.index("\n}\n", START) + 3
X64_FEX = SOURCE[START:END]

HARNESS = textwrap.dedent("""\
    set -eu
    FEX_TAG=fex-2610-r5 FEX_SHA256=builtin FEX_URL=builtin-url
    step() { echo "STEP $*"; }
    # Installed FEX loads when LOADS=1; a build installs when its tag is in INSTALLABLE.
    fex_loads() { [ "${LOADS:-0}" = 1 ]; }
    fex_install() {
      echo "INSTALL $1 $2 $3"
      case " ${INSTALLABLE:-} " in *" $1 "*) echo "$1" > "$ROOT/tag"; : > "$ROOT/FEX"; chmod +x "$ROOT/FEX"; return 0 ;; esac
      return 1
    }
    """)


class FexSelectTest(unittest.TestCase):
    def run_select(self, installed=None, loads=False, catalog=None, installable=()):
        with tempfile.TemporaryDirectory() as root:
            if installed:
                Path(root, "tag").write_text(installed + "\n")
                fex = Path(root, "FEX")
                fex.write_text("")
                fex.chmod(0o755)
            body = (X64_FEX.replace("/usr/local/share/fex-emu/.droiddeck-tag", "$ROOT/tag")
                    .replace("/usr/local/bin/FEX", "$ROOT/FEX"))
            script = HARNESS + body + 'if fex_ready; then echo "NOW $FEX_NOW|$FEX_FROM"; else echo FAILED; fi\n'
            env = dict(os.environ, ROOT=root, LOADS="1" if loads else "0", INSTALLABLE=" ".join(installable))
            env.pop("BL_FEX_TAG", None)
            if catalog:
                env.update(BL_FEX_TAG=catalog, BL_FEX_SHA256="catsha", BL_FEX_URLS="a b")
            out = subprocess.run(["bash", "-c", script], env=env, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, out.returncode, out.stderr)
            lines = out.stdout.splitlines()
            return [l for l in lines if l.startswith("INSTALL")], lines[-1]

    def test_the_catalog_build_already_installed_is_used(self):
        installs, now = self.run_select(installed="fex-2610-r6", loads=True, catalog="fex-2610-r6")
        self.assertEqual([], installs)
        self.assertEqual("NOW fex-2610-r6|catalog", now)

    def test_a_newer_catalog_build_is_installed(self):
        installs, now = self.run_select(installed="fex-2610-r5", loads=True, catalog="fex-2610-r6", installable=["fex-2610-r6"])
        self.assertEqual(["INSTALL fex-2610-r6 catsha a b"], installs)
        self.assertEqual("NOW fex-2610-r6|catalog", now)

    def test_a_catalog_build_that_does_not_install_keeps_the_working_one(self):
        installs, now = self.run_select(installed="fex-2610-r5", loads=True, catalog="fex-2610-r6")
        self.assertEqual(["INSTALL fex-2610-r6 catsha a b"], installs)
        self.assertEqual("NOW fex-2610-r5|kept; fex-2610-r6 did not install", now)

    def test_without_a_catalog_the_built_in_build_replaces_an_older_one(self):
        installs, now = self.run_select(installed="fex-2610-r4", loads=True, installable=["fex-2610-r5"])
        self.assertEqual(["INSTALL fex-2610-r5 builtin builtin-url"], installs)
        self.assertEqual("NOW fex-2610-r5|built-in", now)

    def test_an_installed_build_that_does_not_load_is_replaced(self):
        installs, now = self.run_select(installed="fex-2610-r6", loads=False, catalog="fex-2610-r6", installable=["fex-2610-r6"])
        self.assertEqual(["INSTALL fex-2610-r6 catsha a b"], installs)
        self.assertEqual("NOW fex-2610-r6|catalog", now)

    def test_a_first_install_falls_back_to_the_built_in_build(self):
        installs, now = self.run_select(catalog="fex-2610-r6", installable=["fex-2610-r5"])
        self.assertEqual(["INSTALL fex-2610-r6 catsha a b", "INSTALL fex-2610-r5 builtin builtin-url"], installs)
        self.assertEqual("NOW fex-2610-r5|built-in", now)

    def test_nothing_usable_fails(self):
        installs, now = self.run_select(catalog="fex-2610-r6")
        self.assertEqual(2, len(installs))
        self.assertEqual("FAILED", now)


def function(name):
    start = SOURCE.index(name + "() {")
    return SOURCE[start:SOURCE.index("\n}\n", start) + 3]


class FexHoldTest(unittest.TestCase):
    """MaliBridge: a build put back with --previous is held; the pinned build does not replace it until --unhold."""

    def run_held(self, installed, hold, catalog, installable=()):
        with tempfile.TemporaryDirectory() as root:
            Path(root, "tag").write_text(installed + "\n")
            Path(root, "hold").write_text(hold + "\n")
            Path(root, "FEX").write_text("")
            Path(root, "FEX").chmod(0o755)
            body = (X64_FEX.replace("/usr/local/share/fex-emu/.droiddeck-tag", "$ROOT/tag")
                    .replace("/usr/local/share/fex-emu/.droiddeck-hold", "$ROOT/hold")
                    .replace("/usr/local/bin/FEX", "$ROOT/FEX"))
            script = HARNESS + body + 'if fex_ready; then echo "NOW $FEX_NOW|$FEX_FROM"; else echo FAILED; fi\n'
            env = dict(os.environ, ROOT=root, LOADS="1", INSTALLABLE=" ".join(installable),
                       BL_FEX_TAG=catalog, BL_FEX_SHA256="catsha", BL_FEX_URLS="a b")
            out = subprocess.run(["bash", "-c", script], env=env, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, out.returncode, out.stderr)
            lines = out.stdout.splitlines()
            return [l for l in lines if l.startswith("INSTALL")], lines[-1]

    def test_a_held_build_is_kept_over_the_pinned_one(self):
        installs, now = self.run_held("fex-2610-r8", "fex-2610-r8", "fex-2610-r9", installable=["fex-2610-r9"])
        self.assertEqual([], installs)
        self.assertTrue(now.startswith("NOW fex-2610-r8|held"), now)

    def test_a_hold_for_another_build_does_not_stop_the_pinned_one(self):
        installs, now = self.run_held("fex-2610-r8", "fex-2610-r7", "fex-2610-r9", installable=["fex-2610-r9"])
        self.assertEqual(["INSTALL fex-2610-r9 catsha a b"], installs)
        self.assertEqual("NOW fex-2610-r9|catalog", now)


class FexPreviousTest(unittest.TestCase):
    """MaliBridge: the FEX build a replacement took out is kept so --previous can put it back."""

    def keep(self, before, tag, with_files=True):
        with tempfile.TemporaryDirectory() as root:
            files = Path(root, "old")
            if with_files:
                (files / "usr/local/bin").mkdir(parents=True)
                (files / "usr/local/bin/FEX").write_text("old fex\n")
            script = ('set -eu\nFEX_PREVIOUS="$ROOT/previous"\n' + function("fex_keep_previous") +
                      f'fex_keep_previous "{before}" "{tag}" "$ROOT/old"\n')
            out = subprocess.run(["bash", "-c", script], env=dict(os.environ, ROOT=root), text=True, capture_output=True, timeout=20)
            self.assertEqual(0, out.returncode, out.stderr)
            prev = Path(root, "previous")
            if not (prev / "fex.tzst").is_file():
                return None
            listing = subprocess.run(["bash", "-c", f'zstd -dc "{prev}/fex.tzst" | tar -t'], text=True, capture_output=True).stdout
            sha = subprocess.run(["sha256sum", str(prev / "fex.tzst")], text=True, capture_output=True).stdout.split()[0]
            return (prev / "tag").read_text().strip(), (prev / "sha256").read_text().strip() == sha, listing

    def test_the_replaced_build_is_kept_with_its_tag_and_digest(self):
        tag, sha_ok, listing = self.keep("fex-2610-r8", "fex-2610-r9")
        self.assertEqual("fex-2610-r8", tag)
        self.assertTrue(sha_ok)
        self.assertIn("./usr/local/bin/FEX", listing)

    def test_nothing_is_kept_without_a_different_previous_build(self):
        self.assertIsNone(self.keep("", "fex-2610-r9"))
        self.assertIsNone(self.keep("fex-2610-r9", "fex-2610-r9"))
        self.assertIsNone(self.keep("fex-2610-r8", "fex-2610-r9", with_files=False))

    def test_previous_reinstalls_the_kept_build_checked_and_holds_it(self):
        with tempfile.TemporaryDirectory() as root:
            prev = Path(root, "previous")
            prev.mkdir()
            (prev / "fex.tzst").write_text("archive")
            (prev / "tag").write_text("fex-2610-r8\n")
            (prev / "sha256").write_text("abc\n")
            script = ('set -eu\nFEX_PREVIOUS="$ROOT/previous"\nFEX_HOLD="$ROOT/hold"\n'
                      'fex_install() { echo "INSTALL $1 $2 $3"; [ -s "${3#file://}" ]; }\n' +
                      function("fex_previous") + 'fex_previous\n')
            out = subprocess.run(["bash", "-c", script], env=dict(os.environ, ROOT=root), text=True, capture_output=True, timeout=10)
            self.assertEqual(0, out.returncode, out.stderr)
            self.assertEqual(f"INSTALL fex-2610-r8 abc file://{prev}/restore.tzst", out.stdout.strip())
            self.assertEqual("fex-2610-r8", Path(root, "hold").read_text().strip())
            self.assertFalse((prev / "restore.tzst").exists())

    def test_previous_without_a_kept_build_fails_and_holds_nothing(self):
        with tempfile.TemporaryDirectory() as root:
            script = ('set -eu\nFEX_PREVIOUS="$ROOT/previous"\nFEX_HOLD="$ROOT/hold"\n'
                      'fex_install() { echo INSTALL; }\n' + function("fex_previous") + 'fex_previous || echo FAILED\n')
            out = subprocess.run(["bash", "-c", script], env=dict(os.environ, ROOT=root), text=True, capture_output=True, timeout=10)
            self.assertEqual("FAILED", out.stdout.strip())
            self.assertFalse(Path(root, "hold").exists())


if __name__ == "__main__":
    unittest.main()
