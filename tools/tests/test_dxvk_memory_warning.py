"""droiddeck-game-env: one session log line when the game's dxvk.conf caps DXVK's memory far below the
phone's (GTA V folder: dxgi.maxDeviceMemory = 512 and dxgi.maxSharedMemory = 512 on an 11 GB
POCO X6 Pro). The player's file is never changed. /proc/meminfo is
never read from this PC: every test passes its own."""
import io
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-game-env"))
LOW = MODULE["low_dxvk_memory"]

GTA_CONF = """# Winlator-style settings that came with the game folder
dxgi.nvapiHack = True
dxgi.maxDeviceMemory = 512
dxgi.maxSharedMemory = 512
dxvk.maxFrameLatency = 1
"""


class LowDxvkMemoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.game = self.root / "Grand Theft Auto V"
        self.game.mkdir()
        self.exe = self.game / "GTAVLauncher.exe"
        self.exe.write_text("")
        self.command = ["/opt/proton", "waitforexitandrun", str(self.exe)]
        self.cwd = os.getcwd()
        os.chdir(self.root)  # not the game's folder: the file is found next to the game

    def tearDown(self):
        os.chdir(self.cwd)
        self.tmp.cleanup()

    def meminfo(self, gb):
        path = self.root / "meminfo"
        path.write_text("MemTotal:       %d kB\nMemFree:         1000 kB\n" % int(gb * 1024 * 1024))
        return str(path)

    def conf(self, text, name="dxvk.conf"):
        path = self.game / name
        path.write_text(text)
        return path

    def test_gtas_folder_on_an_11_gb_phone_names_the_file_and_both_values(self):
        path = self.conf(GTA_CONF)
        before = (path.read_bytes(), path.stat().st_mtime_ns)
        line = LOW(self.command, {}, self.meminfo(11.1))
        self.assertIn(str(path), line)
        self.assertIn("dxgi.maxDeviceMemory = 512 and dxgi.maxSharedMemory = 512 (MB)", line)
        self.assertIn("11.1 GB", line)
        self.assertIn("The file is left as it is; 2048 or more there is worth a try", line)
        self.assertNotIn("DXVK_CONFIG", line)
        self.assertEqual((path.read_bytes(), path.stat().st_mtime_ns), before)  # the player's file, untouched

    def test_only_phones_of_8_gb_or_more(self):
        self.conf(GTA_CONF)
        self.assertIsNotNone(LOW(self.command, {}, self.meminfo(7.4)))  # an "8 GB" phone
        self.assertIsNone(LOW(self.command, {}, self.meminfo(5.6)))     # a 6 GB one: 512 may be on purpose
        self.assertIsNone(LOW(self.command, {}, str(self.root / "no-meminfo")))

    def test_values_of_1024_or_more_or_none_say_nothing(self):
        for text in ("dxgi.maxDeviceMemory = 1024\n", "dxgi.maxDeviceMemory = 4096\ndxgi.maxSharedMemory = 2048\n",
                     "dxvk.maxFrameLatency = 1\n", "# dxgi.maxDeviceMemory = 512\n", "dxgi.maxDeviceMemory = 0\n",
                     "dxgi.maxDeviceMemory = lots\n"):
            self.conf(text)
            self.assertIsNone(LOW(self.command, {}, self.meminfo(11.1)), text)
        (self.game / "dxvk.conf").unlink()
        self.assertIsNone(LOW(self.command, {}, self.meminfo(11.1)))

    def test_one_low_value_is_named_alone(self):
        self.conf("dxgi.maxDeviceMemory = 4096\ndxgi.maxSharedMemory = 256\n")
        line = LOW(self.command, {}, self.meminfo(11.1))
        self.assertIn("sets dxgi.maxSharedMemory = 256 (MB)", line)
        self.assertNotIn("maxDeviceMemory", line)

    def test_file_name_in_any_case_and_dxvk_config_file_wins(self):
        upper = self.conf(GTA_CONF, "DXVK.CONF")
        self.assertIn(str(upper), LOW(self.command, {}, self.meminfo(11.1)))
        other = self.root / "elsewhere.conf"
        other.write_text("dxgi.maxDeviceMemory = 8192\n")
        self.assertIsNone(LOW(self.command, {"DXVK_CONFIG_FILE": str(other)}, self.meminfo(11.1)))
        other.write_text("dxgi.maxDeviceMemory = 300\n")
        self.assertIn(str(other), LOW(self.command, {"DXVK_CONFIG_FILE": str(other)}, self.meminfo(11.1)))
        self.assertIsNone(LOW(self.command, {"DXVK_CONFIG_FILE": str(self.root / "missing.conf")}, self.meminfo(11.1)))

    def test_sections_for_other_programs_do_not_count(self):
        self.conf("[othergame.exe]\ndxgi.maxDeviceMemory = 512\n")
        self.assertIsNone(LOW(self.command, {}, self.meminfo(11.1)))
        self.conf("[gtavlauncher.exe]\ndxgi.maxDeviceMemory = 512\n")
        self.assertIsNotNone(LOW(self.command, {}, self.meminfo(11.1)))

    def test_dxvk_config_setting_the_same_value_is_said(self):
        self.conf(GTA_CONF)
        line = LOW(self.command, {"DXVK_CONFIG": "dxgi.maxDeviceMemory = 4096"}, self.meminfo(11.1))
        self.assertIn("(DXVK_CONFIG sets dxgi.maxDeviceMemory too, and DXVK takes that one)", line)

    def test_a_run_mode_command_is_read_too(self):
        self.conf(GTA_CONF)
        command = ["/usr/local/bin/droiddeck-proton-run", "--app", "2970816652", "--dir", str(self.game), "--", str(self.exe)]
        self.assertIsNotNone(LOW(command, {}, self.meminfo(11.1)))

    def test_the_launch_logs_the_line_and_goes_on_when_the_check_fails(self):
        self.conf(GTA_CONF)
        calls = []
        g = MODULE["main"].__globals__
        for broken in (False, True):
            err = io.StringIO()
            low = (lambda command, env: 1 / 0) if broken else (lambda command, env: LOW(command, env, self.meminfo(11.1)))
            with mock.patch.dict(os.environ, {}, clear=True), mock.patch("sys.argv", ["droiddeck-game-env"] + self.command), \
                    mock.patch("sys.stderr", err), mock.patch("runpy.run_path", side_effect=FileNotFoundError("x")), \
                    mock.patch("os.execvpe", side_effect=lambda *args: calls.append(args)), \
                    mock.patch.dict(g, low_dxvk_memory=low, load_sync=lambda: None,
                                    select_sync=lambda command, env, sync: (command, env)):
                g["main"]()
            if broken:
                self.assertIn("droiddeck-game-env: dxvk.conf not checked (division by zero)", err.getvalue())
            else:
                self.assertIn("droiddeck-game-env: %s sets dxgi.maxDeviceMemory = 512" % (self.game / "dxvk.conf"), err.getvalue())
        self.assertEqual(len(calls), 2)  # the game started both times
        self.assertEqual(calls[0][1], self.command)


if __name__ == "__main__":
    unittest.main()
