import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-seed-redists"

GAME_SCRIPT = r'''"InstallScript"
{
	"Run Process"
	{
		"VCRedist2022"
		{
			"HasRunKey"		"HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2022"
			"process 1"		"%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe"
			"command 1"		"/q /norestart"
			"NoCleanUp"		"1"
		}
	}
}
'''

VULKAN_SCRIPT = r'''"InstallScript"
{
	"Run Process"
	{
		"Vulkan111080"
		{
			"HasRunKey"		"HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\1174180"
			"process 1"		"%INSTALLDIR%\\Redistributables\\VulkanRT-1.1.108.0-Installer.exe"
			"command 1"		"/S"
			"NoCleanUp"		"0"
		}
		"RockstarLauncher"
		{
			"HasRunKey"		"HKEY_LOCAL_MACHINE\\Software\\Rockstar Games\\Steam\\Launcher"
			"process 1"		"%INSTALLDIR%\\Redistributables\\Rockstar-Games-Launcher.exe"
			"command 1"		"/s /t"
			"NoCleanUp"		"0"
		}
	}
}
'''


class SeedRedistsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.steam = self.tmp / "Steam"
        self.reg = self.steam / "steamapps/compatdata/1234/pfx/system.reg"
        self.reg.parent.mkdir(parents=True)
        self.reg.write_text("WINE REGISTRY Version 2\n")

    def game(self, name="Some Game", script=GAME_SCRIPT, library=None):
        root = Path(library or self.steam)
        path = root / "steamapps/common" / name / "_CommonRedist/vcredist/2022/installscript.vdf"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(script)
        manifest = root / "steamapps" / ("appmanifest_%d.acf" % (abs(hash(name)) % 100000))
        manifest.write_text('"AppState" { "installdir" "%s" }' % name)
        return path

    def run_seed(self, libraries=""):
        env = dict(os.environ, DROIDDECK_LIBRARIES=libraries)
        return subprocess.run(["bash", str(SCRIPT), str(self.steam)], env=env, capture_output=True, text=True, check=True)

    def test_a_games_own_install_script_is_marked_as_run(self):
        self.game()
        self.run_seed()
        reg = self.reg.read_text()
        self.assertIn("[Software\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2022]", reg)
        self.assertIn("[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2022]", reg)
        self.assertIn('"VCRedist2022"=dword:00000001', reg)

    def test_games_in_other_libraries_are_read(self):
        sd = self.tmp / "sd"
        self.game(library=sd)
        self.run_seed(str(sd))
        self.assertIn('"VCRedist2022"=dword:00000001', self.reg.read_text())

    def test_without_the_shared_redistributables_the_known_list_covers_vc_2005(self):
        # Just Cause 2 wants VC++ 2005, and the Common Redistributables may land in the launch's minute.
        self.run_seed()
        reg = self.reg.read_text()
        self.assertIn("[Software\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2005]", reg)
        self.assertIn('"x86"=dword:00000001', reg)
        self.assertIn('"x64"=dword:00000001', reg)
        self.assertIn("[Software\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2008]", reg)

    def test_the_vc_runtime_is_recorded_for_every_architecture_and_view(self):
        self.run_seed()
        reg = self.reg.read_text()
        for view in ("Software", "Software\\\\Wow6432Node"):
            for arch in ("x86", "x64", "arm64"):
                self.assertIn("[%s\\\\Microsoft\\\\VisualStudio\\\\14.0\\\\VC\\\\Runtimes\\\\%s]" % (view, arch), reg)
        self.assertIn('"Installed"=dword:00000001', reg)
        self.assertIn('"Major"=dword:0000000e', reg)
        self.assertIn('"Version"="v14.51.36247.00"', reg)

    def test_a_seeded_prefix_is_left_alone_until_a_game_changes(self):
        self.run_seed()
        once = self.reg.read_text()
        self.run_seed()
        self.assertEqual(once, self.reg.read_text())
        self.game()
        self.run_seed()
        self.assertIn('"VCRedist2022"=dword:00000001', self.reg.read_text())

    def test_markers_a_replaced_registry_lost_are_seeded_again(self):
        self.game()
        stock = self.reg.read_text()
        self.run_seed()
        ledger = (self.reg.parent / ".droiddeck-redists").read_text()
        self.assertIn("VCRedist2022", ledger)
        self.reg.write_text(stock)
        self.run_seed()
        reg = self.reg.read_text()
        self.assertEqual(reg.count('"VCRedist2022"=dword:00000001'), 2, "both views again")
        self.assertIn("[Software\\\\Microsoft\\\\VisualStudio\\\\14.0\\\\VC\\\\Runtimes\\\\x86]", reg)
        self.assertEqual(sorted((self.reg.parent / ".droiddeck-redists").read_text().splitlines()), sorted(ledger.splitlines()))
        self.run_seed()
        self.assertEqual(reg, self.reg.read_text())

    def test_a_marker_kept_in_only_one_view_is_seeded_again(self):
        self.game()
        self.run_seed()
        wow = "[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2022]"
        blocks = self.reg.read_text().split("\n\n")
        self.reg.write_text("\n\n".join(b for b in blocks if not (b.startswith(wow) and '"VCRedist2022"' in b)))
        self.assertEqual(self.reg.read_text().count('"VCRedist2022"'), 1)
        self.run_seed()
        reg = self.reg.read_text()
        self.assertEqual(reg.count('"VCRedist2022"'), 3, "the one left plus both views again")
        self.assertTrue(any(b.startswith(wow) and '"VCRedist2022"' in b for b in reg.split("\n\n")))

    def test_a_vc_runtime_another_architecture_lost_is_seeded_again(self):
        self.run_seed()
        arm64 = "[Software\\\\Wow6432Node\\\\Microsoft\\\\VisualStudio\\\\14.0\\\\VC\\\\Runtimes\\\\arm64]"
        blocks = self.reg.read_text().split("\n\n")
        self.reg.write_text("\n\n".join(b for b in blocks if not b.startswith(arm64)))
        self.assertNotIn(arm64, self.reg.read_text())
        self.run_seed()
        reg = self.reg.read_text()
        self.assertIn(arm64, reg)
        self.assertEqual(reg.count("[Software\\\\Microsoft\\\\VisualStudio\\\\14.0\\\\VC\\\\Runtimes\\\\x86]"), 2)

    def test_a_marker_written_in_one_view_only_is_kept_as_it_is(self):
        self.game(script=GAME_SCRIPT.replace("Software\\\\Valve\\\\Steam\\\\Apps\\\\CommonRedist\\\\vcredist\\\\2022", "SOFTWARE\\\\AGEIA Technologies").replace("VCRedist2022", "PhysX Version"))
        self.run_seed()
        once = self.reg.read_text()
        self.assertEqual(once.count('"PhysX Version"=dword:00000001'), 1)
        result = self.run_seed()
        self.assertEqual(once, self.reg.read_text())
        self.assertNotIn("gone", result.stdout)

    def test_an_evaluator_script_nested_deeper_is_read(self):
        script = self.tmp / "legacycompat/evaluatorscript_409710.vdf"
        script.parent.mkdir(parents=True)
        script.write_text('"evaluatorscript"\n{\n\t"1"\n' + "\n".join("\t" + line for line in GAME_SCRIPT.replace("VCRedist2022", "x64 14.99.1.0").splitlines()) + "\n}\n")
        env = dict(os.environ, DROIDDECK_LIBRARIES="", DROIDDECK_SEED_SCRIPTS=str(script))
        subprocess.run(["bash", str(SCRIPT), str(self.steam)], env=env, capture_output=True, text=True, check=True)
        self.assertIn('"x64 14.99.1.0"=dword:00000001', self.reg.read_text())

    def test_the_prefix_date_is_kept(self):
        os.utime(self.reg, (1_000_000_000, 1_000_000_000))
        self.run_seed()
        self.assertEqual(int(self.reg.stat().st_mtime), 1_000_000_000)

    def manifest(self, appid, name, library=None, **fields):
        root = Path(library or self.steam)
        body = "".join('\t"%s"\t\t"%s"\n' % (key, value) for key, value in
                       [("appid", appid), ("installdir", name)] + sorted(fields.items()))
        path = root / "steamapps" / ("appmanifest_%s.acf" % appid)
        path.write_text('"AppState"\n{\n%s}\n' % body)
        return path

    def cached_scripts(self):
        lines = (self.steam / ".droiddeck-game-scripts").read_text().splitlines()
        self.assertEqual(lines[0], "droiddeck-game-scripts 2")
        return sorted(line.split("\t")[2] for line in lines[1:] if line.split("\t")[2])

    def test_a_launch_rewriting_last_played_does_not_search_the_game_again(self):
        # Every launch rewrites a manifest.
        sd = self.tmp / "sd"
        script = self.game(name="Balatro", library=sd)
        self.manifest("2379780", "Balatro", sd, buildid="100", LastPlayed="1", StateFlags="4")
        for stale in (sd / "steamapps").glob("appmanifest_*.acf"):
            if stale.name != "appmanifest_2379780.acf":
                stale.unlink()
        self.run_seed(str(sd))
        self.assertEqual(self.cached_scripts(), [str(script)])
        script.unlink()
        self.manifest("2379780", "Balatro", sd, buildid="100", LastPlayed="2", StateFlags="4")
        self.run_seed(str(sd))
        self.assertEqual(self.cached_scripts(), [str(script)], "kept: only LastPlayed moved")
        self.manifest("2379780", "Balatro", sd, buildid="101", LastPlayed="2", StateFlags="4")
        self.run_seed(str(sd))
        self.assertEqual(self.cached_scripts(), [], "searched again after an update")

    def test_an_old_cache_and_a_removed_game_are_dropped(self):
        (self.steam / ".droiddeck-game-scripts").write_text("123 45\n/gone/installscript.vdf\n")
        script = self.game()
        self.run_seed()
        self.assertEqual(self.cached_scripts(), [str(script)])
        for manifest in (self.steam / "steamapps").glob("appmanifest_*.acf"):
            manifest.unlink()
        self.run_seed()
        self.assertEqual(self.cached_scripts(), [])

    def test_the_shared_redistributables_folder_is_not_read_as_a_game(self):
        shared = self.game(name="Steamworks Shared")
        self.run_seed()
        self.assertNotIn(str(shared), self.cached_scripts())

    def test_a_launch_pass_marks_its_prefix_and_the_stock_ones_without_searching(self):
        other = self.steam / "steamapps/compatdata/999/pfx/system.reg"
        other.parent.mkdir(parents=True)
        other.write_text("WINE REGISTRY Version 2\n")
        stock = self.steam / "steamapps/common/Proton - Experimental/files/share/default_pfx/system.reg"
        stock.parent.mkdir(parents=True)
        stock.write_text("WINE REGISTRY Version 2\n")
        self.run_seed()
        self.game()  # not searched by a launch pass: the cache does not know it yet
        script = self.tmp / "legacycompat/evaluatorscript_1234.vdf"
        script.parent.mkdir(parents=True)
        script.write_text('"evaluatorscript"\n{\n\t"1"\n' + "\n".join("\t" + line for line in GAME_SCRIPT.replace("VCRedist2022", "x64 14.99.1.0").splitlines()) + "\n}\n")
        before = other.read_text()
        env = dict(os.environ, DROIDDECK_LIBRARIES="", DROIDDECK_SEED_SCRIPTS=str(script),
                   DROIDDECK_SEED_PREFIX=str(self.reg.parent.parent))
        subprocess.run(["bash", str(SCRIPT), str(self.steam)], env=env, capture_output=True, text=True, check=True)
        self.assertIn('"x64 14.99.1.0"=dword:00000001', self.reg.read_text())
        self.assertIn('"x64 14.99.1.0"=dword:00000001', stock.read_text())
        self.assertNotIn('"VCRedist2022"=dword:00000001', self.reg.read_text())
        self.assertEqual(before, other.read_text(), "another game's prefix waits for the session's pass")
        self.run_seed()
        self.assertIn('"VCRedist2022"=dword:00000001', other.read_text())

    def rdr2(self):
        path = self.steam / "steamapps/common/Red Dead Redemption 2/installscript.vdf"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(VULKAN_SCRIPT)
        (self.steam / "steamapps/appmanifest_1174180.acf").write_text('"AppState" { "installdir" "Red Dead Redemption 2" }')

    def test_a_vulkan_runtime_installer_is_left_to_run(self):
        self.rdr2()
        self.run_seed()
        reg = self.reg.read_text()
        self.assertNotIn("Vulkan111080", reg)
        self.assertIn('"RockstarLauncher"=dword:00000001', reg)
        self.assertNotIn("Vulkan111080", (self.reg.parent / ".droiddeck-redists").read_text())

    def test_a_vulkan_marker_an_earlier_seeding_wrote_comes_out_once(self):
        self.rdr2()
        marker = "HKEY_LOCAL_MACHINE\\\\Software\\\\Valve\\\\Steam\\\\Apps\\\\1174180\tVulkan111080"
        self.reg.write_text(
            "WINE REGISTRY Version 2\n"
            "\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\1174180] 1791729070\n#time=1dd598d29161f72\n"
            '"Other"=dword:00000001\n"Vulkan111080"=dword:00000001\n'
            "\n[Software\\\\Wow6432Node\\\\Valve\\\\Steam\\\\Apps\\\\1174180] 1791729070\n"
            '"Vulkan111080"=dword:00000001\n'
            "\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\22380] 1791311644\n"
            '"Vulkan111080"=dword:00000001\n')
        (self.reg.parent / ".droiddeck-redists").write_text(marker + "\n")
        os.utime(self.reg, (1_000_000_000, 1_000_000_000))
        self.run_seed()
        reg = self.reg.read_text()
        self.assertEqual(reg.count('"Vulkan111080"'), 1, "only the other app's value stays")
        self.assertIn('"Other"=dword:00000001', reg)
        self.assertNotIn("Vulkan111080", (self.reg.parent / ".droiddeck-redists").read_text())
        self.assertEqual(int(self.reg.stat().st_mtime), 1_000_000_000)
        with self.reg.open("a") as out:
            out.write('\n[Software\\\\Valve\\\\Steam\\\\Apps\\\\1174180] 1791730000\n"Vulkan111080"=dword:00000001\n')
        self.run_seed()
        self.assertEqual(self.reg.read_text().count('"Vulkan111080"'), 2)


if __name__ == "__main__":
    unittest.main()
