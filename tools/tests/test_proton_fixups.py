import hashlib
import json
import os
import runpy
import subprocess
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))
D3D8 = "files/lib/wine/dxvk/aarch64-windows/d3d8.dll"
D3D11 = "files/lib/wine/dxvk/aarch64-windows/d3d11.dll"
I386_D3D8 = "files/lib/wine/dxvk/i386-windows/d3d8.dll"

# Stands in for Valve's proton: the same fixups rule (lstat every listed path, record the json's mtime
# only when all of them are there), then exits 7 as if the game had run.
FAKE_PROTON = """#!/usr/bin/python3
import json, os, stat, sys
base = os.path.dirname(os.path.abspath(__file__)) + "/"
manifest = base + "steampipe_fixups.json"
marker = base + "files/steampipe_fixups_mtime"
current = None
if os.path.exists(marker):
    with open(marker) as f:
        current = f.readline().strip()
stamp = str(os.path.getmtime(manifest))
if current != stamp:
    for rel in json.load(open(manifest))["no_write_paths"]:
        path = os.path.join(base, rel)
        info = os.lstat(path)
        os.chmod(path, info.st_mode & ~(stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH))
    with open(marker, "w") as f:
        f.write(stamp + "\\n")
print(json.dumps(sys.argv[1:]))
sys.exit(7)
"""


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class ProtonFixupsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name)
        self.steam = self.home / "Steam"
        self.depot = self.steam / "steamapps/common" / COMPAT["SOURCES"][0]
        (self.depot / "files/bin-arm64").mkdir(parents=True)
        (self.depot / "version").write_text("1790245028 experimental-11.0-20260924-arm64\n")
        for rel in (D3D11, I386_D3D8):
            (self.depot / rel).parent.mkdir(parents=True, exist_ok=True)
            (self.depot / rel).write_text("stock " + rel)
        (self.depot / D3D8).parent.mkdir(parents=True, exist_ok=True)
        (self.depot / "steampipe_fixups.json").write_text(json.dumps(
            {"empty_dirs": [], "id": "1", "no_write_paths": [D3D11, D3D8, I386_D3D8]}))
        (self.depot / "files/steampipe_fixups_mtime").write_text("0\n")
        proton = self.depot / "proton"
        proton.write_text(FAKE_PROTON)
        proton.chmod(0o755)
        self.components = self.home / ".local/share/droiddeck-components"
        self.components.mkdir(parents=True)
        tools = self.steam / "compatibilitytools.d"
        COMPAT["build_tool"](str(tools / COMPAT["TOOL"]), str(self.depot))
        self.launcher = tools / COMPAT["TOOL"] / COMPAT["LAUNCHER"]
        self.launcher.write_text(self.launcher.read_text().replace("/usr/local/bin/droiddeck-game-env", str(BIN / "droiddeck-game-env")))

    def tearDown(self):
        os.chmod(self.depot / D3D11, 0o644)
        os.chmod(self.depot / I386_D3D8, 0o644)
        if (self.depot / D3D8).exists():
            os.chmod(self.depot / D3D8, 0o644)
        self.tmp.cleanup()

    def env(self):
        return {"PATH": os.environ.get("PATH", os.defpath), "HOME": str(self.home), "STEAM_COMPAT_DATA_PATH": "/compatdata/1029210",
                "STEAM_COMPAT_CLIENT_INSTALL_PATH": str(self.steam)}

    def launch(self, verb="waitforexitandrun"):
        return subprocess.run([str(self.launcher), verb, "game.exe"], env=self.env(), text=True, capture_output=True)

    def tree(self):
        return sorted(str(p.relative_to(self.depot)) for p in self.depot.rglob("*"))

    def test_proton_alone_dies_on_the_missing_listed_file(self):
        result = subprocess.run([str(self.depot / "proton"), "waitforexitandrun", "game.exe"], env=self.env(), text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 7)
        self.assertIn("FileNotFoundError", result.stderr)
        self.assertIn("d3d8.dll", result.stderr)

    def test_the_launch_table_copy_is_put_back_and_proton_starts(self):
        saved = self.components / "store/original-x-y-dxvk/files/lib/wine/dxvk/aarch64-windows/d3d8.dll"
        saved.parent.mkdir(parents=True)
        saved.write_text("stock d3d8")
        (self.components / "desired.tsv").write_text("*\t%s\texperimental-11.0-20260924-arm64\tdxvk\t%s\t%s\t%s\n" % (
            self.depot.name, D3D8, digest(saved), saved))
        result = self.launch("run")
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertEqual((self.depot / D3D8).read_text(), "stock d3d8")
        self.assertIn("put back 1 listed file(s)", result.stderr)
        self.assertEqual((self.depot / "files/steampipe_fixups_mtime").read_text().strip(),
                         str(os.path.getmtime(self.depot / "steampipe_fixups.json")))

    def test_a_copy_that_does_not_match_its_hash_is_not_used(self):
        saved = self.home / "changed.dll"
        saved.write_text("changed")
        (self.components / "desired.tsv").write_text("*\t%s\texperimental-11.0-20260924-arm64\tdxvk\t%s\t%s\t%s\n" % (
            self.depot.name, D3D8, "0" * 64, saved))
        result = self.launch("run")
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertFalse((self.depot / D3D8).exists())
        self.assertIn("the step is marked done", result.stderr)

    def test_droiddecks_saved_original_is_used(self):
        store = self.components / "store/original-Proton_Experimental_ARM64-experimental-11.0-20260924-arm64-dxvk"
        (store / D3D8).parent.mkdir(parents=True)
        (store / D3D8).write_text("saved original")
        (store / ".complete").write_text("1\n")
        result = self.launch()
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertEqual((self.depot / D3D8).read_text(), "saved original")

    def test_with_no_copy_anywhere_the_step_is_marked_done_and_nothing_else_changes(self):
        before = self.tree()
        unlisted = self.depot / "files/lib/wine/dxvk/aarch64-windows/d3d9.dll"
        result = self.launch()
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertEqual(self.tree(), before)
        self.assertFalse(unlisted.exists())
        self.assertFalse((self.depot / D3D8).exists())
        self.assertEqual((self.depot / "files/steampipe_fixups_mtime").read_text(),
                         str(os.path.getmtime(self.depot / "steampipe_fixups.json")) + "\n")
        self.assertEqual([line for line in result.stderr.splitlines() if "steampipe fixups" in line],
                         ["droiddeck-proton: steampipe fixups: 1 listed file(s) missing in %s and no copy to put back (%s); "
                          "the step is marked done so Proton starts" % (self.depot.name, D3D8)])

    def test_a_finished_step_is_left_alone(self):
        stamp = str(os.path.getmtime(self.depot / "steampipe_fixups.json"))
        (self.depot / "files/steampipe_fixups_mtime").write_text(stamp + "\n")
        result = self.launch()
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertNotIn("steampipe fixups", result.stderr)
        self.assertFalse((self.depot / D3D8).exists())

    def test_a_sync_pack_gets_the_file_from_its_stock_depot(self):
        stock = self.home / "stock"
        (stock / D3D8).parent.mkdir(parents=True)
        (stock / D3D8).write_text("from stock")
        (self.depot / ".droiddeck-esync.json").write_text(json.dumps({"stock": str(stock)}))
        result = self.launch()
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertEqual((self.depot / D3D8).read_text(), "from stock")

    def test_third_party_wrapper_runs_the_check_too(self):
        body = (COMPAT["EXTRA_WRAPPER_SH"] % "proton")
        self.assertLess(body.index('bl_components "$here"'), body.index('bl_fixups "$here"'))
        self.assertLess(body.index('bl_fixups "$here"'), body.index("bl_run "))
        self.assertLess(COMPAT["LAUNCHER_SH"].index('bl_fixups "$depot"'), COMPAT["LAUNCHER_SH"].index("bl_run ${BL_TASKSET"))


if __name__ == "__main__":
    unittest.main()
