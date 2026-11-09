from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin/droiddeck-session'


def function_source(name):
    source = SCRIPT.read_text()
    start = source.index('%s() {' % name)
    end = source.index('\n        }', start)
    return source[start:end + len('\n        }')]


def run_bash(script, env=None):
    return subprocess.run(['bash', '-c', script], capture_output=True, text=True, timeout=10, env=env)


class ClientZramFocusTest(unittest.TestCase):
    def test_the_hidden_epic_launcher_is_compressed_with_the_client(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = Path(tmp)

            def task(pid, argv, env=()):
                folder = proc / str(pid)
                folder.mkdir()
                (folder / 'cmdline').write_bytes(b''.join(arg.encode() + b'\0' for arg in argv))
                (folder / 'environ').write_bytes(b''.join(item.encode() + b'\0' for item in env))

            fex = '/s/FEX-Emu/usr/bin/FEX'
            task(5000010, ['/s/steamrtarm64/steam', '-gamepadui'])
            task(5000011, [fex, '/e/usr/bin/EpicGamesLauncher'], ['HOME=/root', 'DROIDDECK_EPIC_HIDDEN=1'])
            task(5000012, [fex, './/lib/webkit2gtk-4.1/WebKitWebProcess', '4'], ['DROIDDECK_EPIC_HIDDEN=1', 'LANG=C'])
            task(5000013, [fex, '/e/usr/bin/EpicGamesLauncher'], ['HOME=/root'])
            task(5000014, ['/e/WebKitWebProcess', '4'], ['DROIDDECK_EPIC_HIDDEN=1'])
            task(5000015, [fex, '/e/usr/bin/EpicGamesLauncher'], ['DROIDDECK_EPIC_HIDDEN=10'])
            task(5000016, ['/s/reaper', 'SteamLaunch', 'AppId=413150', '--', 'game'])
            source = function_source('bl_zram_scan').replace('/proc/[0-9]*', tmp + '/[0-9]*')
            result = run_bash(source + '\nbl_zram_scan\necho "${_bl_pids[*]}|${_bl_others[*]}|$_bl_game|$_bl_apps"')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual('5000010 5000011 5000012|5000013 5000014 5000015|1| 413150', result.stdout.strip())

    def scan_fake(self, tasks):
        with tempfile.TemporaryDirectory() as tmp:
            for pid, argv in tasks:
                self.write_cmd(Path(tmp), pid, argv)
            source = function_source('bl_zram_scan').replace('/proc/[0-9]*', tmp + '/[0-9]*')
            return run_bash(source + '\nbl_zram_scan\necho "${_bl_pids[*]}|${_bl_others[*]}|$_bl_game"')

    def test_battlenet_is_compressed_with_the_client_while_one_of_its_games_runs(self):
        launcher = [
            (6000010, ['/s/steamrtarm64/steam', '-gamepadui']),
            (6000011, ['/s/reaper', 'SteamLaunch', 'AppId=2899116412', '--', 'droiddeck-battlenet']),
            (6000012, ['C:\\Program Files (x86)\\Battle.net\\Battle.net.exe', '--exec=launch S1']),
            (6000013, ['C:\\Program Files (x86)\\Battle.net\\Battle.net.15511\\Battle.net Helper.exe', '--type=renderer']),
            (6000014, ['C:\\ProgramData\\Battle.net\\Agent\\Agent.9324\\Agent.exe', '--internalclienttools']),
            (6000015, ['C:\\windows\\system32\\services.exe']),
            (6000016, ['C:\\windows\\system32\\winedevice.exe', '-uid']),
        ]
        game = [(6000017, ['S:\\Games\\StarCraft\\x86\\StarCraft.exe', '-launch', '-uid', 's1'])]
        before = self.scan_fake(launcher)
        during = self.scan_fake(launcher + game)
        for result in (before, during):
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('', result.stderr)
        self.assertEqual('6000010|6000015 6000016 6000012 6000013 6000014|1', before.stdout.strip())
        self.assertEqual('6000010 6000012 6000013 6000014|6000015 6000016 6000017|1', during.stdout.strip())

    def test_a_game_without_a_battlenet_uid_leaves_battlenet_alone(self):
        result = self.scan_fake([
            (6000020, ['C:\\Program Files (x86)\\Battle.net\\Battle.net.exe']),
            (6000021, ['D:\\Games\\Other\\Game.exe', '-windowed']),
            (6000022, ['/usr/bin/wine', 'game.exe', '-uid']),
        ])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('|6000021 6000022 6000020|0', result.stdout.strip())

    def test_refocus_restarts_the_delay_and_game_exit_restores_a_packed_client(self):
        source = SCRIPT.read_text()
        start = source.index('          if [ "$_bl_game" = 1 ] && bl_game_focused; then')
        stop = source.index('          nap 5', start)
        body = source[start:stop]
        harness = '''
_bl_since=-1 _bl_since_apps= _bl_packed=-1 _bl_apps=" 413150"
_bl_pids=(123)
bl_game_focused() { [ "$focused" = 1 ]; }
bl_over_cap() { return 1; }
bl_zram_send() { echo "send:$1"; }
''' + function_source('bl_zram_out') + '''
tick() {
  SECONDS=$1 _bl_game=$2 focused=$3
''' + body + '''
}
tick 0 1 1
tick 5 1 0
tick 30 1 1
echo "refocus:$_bl_packed"
tick 49 1 1
tick 50 1 1
tick 55 1 0
tick 60 0 0
'''
        result = run_bash(harness)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual(['refocus:-1', 'send:2', 'send:0'], result.stdout.splitlines())

    def monitor_harness(self):
        source = SCRIPT.read_text()
        start = source.index('          bl_zram_scan\n          if bl_cap_due; then')
        stop = source.index('          nap 5', start)
        return '''
_bl_since=-1 _bl_since_apps= _bl_packed=-1 _bl_measured=-1 _bl_unpack=0 _bl_capped=-1 _bl_backoff=30
_bl_apps=" 413150"
_bl_pids=(123)
step() { :; }
bl_zram_scan() { :; }
bl_game_focused() { [ "$focused" = 1 ]; }
bl_cap_due() { [ "$cap_due" = 1 ]; }
bl_over_cap() { if [ "${1:-}" = bridge ]; then [ "$over_cap" = 1 ] || [ "${server_over:-0}" = 1 ]; else [ "$over_cap" = 1 ]; fi; }
bl_server_note() { :; }
bl_zram_send() { echo "send:$1:$3"; }
''' + function_source('bl_zram_out') + '''
tick() {
  SECONDS=$1 _bl_game=$2 focused=$3 over_cap=$4 cap_due=$5
''' + source[start:stop] + '\n}\n'

    def test_client_toggle_still_disables_automatic_compression(self):
        result = run_bash(self.monitor_harness() + '''
BL_ZRAM_CLIENT=0
tick 0 1 1 0 0
tick 25 1 1 0 0
tick 30 0 0 0 0
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual('', result.stdout)

    def test_a_new_game_restarts_the_compression_delay(self):
        result = run_bash(self.monitor_harness() + '''
tick 0 1 1 0 0
_bl_apps=" 413150 1245620"
tick 15 1 1 0 0
tick 20 1 1 0 0
echo "starting:$_bl_packed"
tick 34 1 1 0 0
tick 35 1 1 0 0
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual(['starting:-1', 'send:2:client'], result.stdout.splitlines())

    def test_ram_cap_can_still_compress_an_unfocused_client(self):
        result = run_bash(self.monitor_harness() + '''
BL_ZRAM_CLIENT=0 BL_RAM_CAP_MB=1024
bl_zram_usage() { _bl_rss=$((768 * 1024)); }
tick 0 1 0 1 1
echo "cap:$_bl_packed:$_bl_since:$_bl_backoff"
tick 5 0 0 0 0
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        # In a game the client's page-out is the gentle one (2); the game's own processes get one pass (1).
        self.assertEqual(['send:2:client', 'send:1:session', 'cap:0:-1:60', 'send:0:client'], result.stdout.splitlines())

    def test_the_bridge_server_counts_for_the_client_but_the_game_is_not_paged_out_for_it(self):
        result = run_bash(self.monitor_harness() + '''
BL_ZRAM_CLIENT=0 BL_RAM_CAP_MB=1024 server_over=1
bl_zram_usage() { _bl_rss=$((768 * 1024)); }
tick 0 1 0 0 1
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual(['send:2:client'], result.stdout.splitlines())

    def test_the_bridge_server_is_found_only_on_the_bridge(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = Path(tmp)
            for pid, argv in ((5000020, ['/data/app/x/lib/arm64/libvkbridge_server.so', '--socket', 's']),
                              (5000021, ['/s/steamrtarm64/steam'])):
                (proc / str(pid)).mkdir()
                (proc / str(pid) / 'cmdline').write_bytes(b''.join(a.encode() + b'\0' for a in argv))
            source = function_source('bl_zram_scan').replace('/proc/[0-9]*', tmp + '/[0-9]*')
            on = run_bash('BL_VKBRIDGE=1\n' + source + '\nbl_zram_scan\necho "${_bl_server[*]}|${_bl_pids[*]}|${_bl_others[*]}"')
            off = run_bash(source + '\nbl_zram_scan\necho "${_bl_server[*]}|${_bl_pids[*]}|${_bl_others[*]}"')
        self.assertEqual('', on.stderr)
        self.assertEqual('5000020|5000021|', on.stdout.strip())
        self.assertEqual('|5000021|', off.stdout.strip())

    def test_over_cap_adds_the_bridge_server_only_when_asked(self):
        source = function_source('bl_over_cap')
        result = run_bash('''
_bl_ram_cap=1000 _bl_pids=(1) _bl_session=(2) _bl_server=(3)
bl_session_others() { :; }
bl_zram_usage() { _bl_rss=$(( $# * 400 )); }
''' + source + '''
bl_over_cap bridge && echo bridge-over
bl_over_cap || echo guest-under
''')
        self.assertEqual('', result.stderr)
        self.assertEqual(['bridge-over', 'guest-under'], result.stdout.splitlines())

    def test_game_exit_defers_restore_until_the_session_is_under_the_ram_cap(self):
        result = run_bash(self.monitor_harness() + '''
tick 0 1 1 0 0
tick 20 1 1 0 0
tick 25 1 0 0 0
tick 30 0 0 1 0
echo "deferred:$_bl_unpack:$_bl_packed"
tick 35 0 0 0 1
echo "restored:$_bl_unpack"
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        self.assertEqual(['send:2:client', 'deferred:1:-1', 'send:0:client', 'restored:0'], result.stdout.splitlines())

    def test_scan_keeps_the_running_game_and_ignores_steams_own_reaper(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.write_cmd(root, 10, ['/usr/lib/steam/reaper', 'SteamLaunch', 'AppId=769', '--'])
        self.write_cmd(root, 20, ['/usr/bin/FEX', '/usr/lib/steam/reaper', 'SteamLaunch', 'AppId=1245620', '--'])
        self.write_cmd(root, 30, ['/home/deck/.local/share/Steam/steamrtarm64/steam'])
        self.write_cmd(root, 40, ['/usr/bin/wine', 'game.exe'])
        self.write_cmd(root, 50, ['/usr/bin/FEXInterpreter', '/usr/bin/steamwebhelper'])
        scan = function_source('bl_zram_scan').replace('for _d in /proc/[0-9]*', 'for _d in "$FAKE"/[0-9]*')
        result = run_bash('FAKE=%s\n%s\nbl_zram_scan\necho "game=$_bl_game"\necho "apps=${_bl_apps# }"\necho "pids=${_bl_pids[*]}"\necho "others=${_bl_others[*]}"' % (root, scan))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['game=1', 'apps=1245620', 'pids=30 50', 'others=40'], result.stdout.splitlines())

    def test_focus_must_be_the_running_game(self):
        focus = function_source('bl_game_focused')
        script = '''
%s
check() {
  BL_DESKTOP=$1 BL_LAUNCH_DIR=$2 _bl_apps=$3
  if bl_game_focused; then echo yes; else echo no; fi
}
check "" "$dir" " 413150"
check "" "$dir" " 1245620"
printf '%%s\\n' '{"focusedApp": 999}' > "$dir/agent/focus.json"
check "" "$dir" " 413150"
printf '%%s\\n' '{"focusedApp": 769}' > "$dir/agent/focus.json"
check "" "$dir" " 413150"
rm -f "$dir/agent/focus.json"
check 1 "$dir" " 413150"
check "" "" " 413150"
''' % focus
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        launch = Path(temp.name)
        (launch / 'agent').mkdir()
        (launch / 'agent' / 'focus.json').write_text('{"t": 1, "focusedApp": 413150}\n')
        result = run_bash('dir=%s\n%s' % (launch, script))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['yes', 'no', 'no', 'no', 'yes', 'yes'], result.stdout.splitlines())

    @staticmethod
    def write_cmd(root, pid, args):
        proc = root / str(pid)
        proc.mkdir()
        (proc / 'cmdline').write_bytes(b'\0'.join(arg.encode() for arg in args) + b'\0')


class GentleSwitchTest(unittest.TestCase):
    def test_bl_zram_gentle_0_brings_back_the_single_pass(self):
        source = function_source('bl_zram_out')
        result = run_bash(source + """
_bl_game=1; bl_zram_out
_bl_game=0; bl_zram_out
BL_ZRAM_GENTLE=0 _bl_game=1; bl_zram_out
""")
        self.assertEqual('', result.stderr)
        self.assertEqual(['2', '1', '1'], result.stdout.split())


class GentlePassTest(unittest.TestCase):
    """bl_zram_gentle: SIGURG again every 0.2 s until each process's ack says done (zram.c mode 2)."""

    def run_gentle(self, script):
        with tempfile.TemporaryDirectory() as tmp:
            source = function_source('bl_zram_gentle').replace('/tmp/.bl-zram', tmp + '/ctl')
            harness = """
ctl=%s/ctl
printf '2 pass=1 cpus=0-3 frames=' > "$ctl"
sleep 30 & a=$!
sleep 30 & b=$!
signals=()
nap() { SECONDS=$((SECONDS + 1)); }
""" % tmp + source + script + """
builtin kill -9 $a $b 2>/dev/null
"""
            return run_bash(harness)

    def test_each_process_is_signalled_until_its_pass_is_done(self):
        result = self.run_gentle("""
kill() {
  [ "$1" = -URG ] || { builtin kill "$@"; return; }
  shift
  local p
  for p in "$@"; do
    signals+=("$p")
    n=0; for s in "${signals[@]}"; do [ "$s" = "$p" ] && n=$((n + 1)); done
    : > "$ctl.ack.$p"
    [ "$p" = "$a" ] && [ "$n" = 3 ] && echo "1 3 1 30" > "$ctl.ack.$p"
    [ "$p" = "$b" ] && [ "$n" = 5 ] && echo "1 5 0 50" > "$ctl.ack.$p"
  done
}
bl_zram_gentle $a $b
echo "${#signals[@]}|$_bl_gentle"
ls "$ctl".ack.* 2>/dev/null | wc -l
""")
        self.assertEqual('', result.stderr)
        lines = result.stdout.splitlines()
        self.assertEqual("8|in 5 s, 8 slices (80 ms of work, 1 ended early for the game's frames)", lines[0])
        self.assertEqual('0', lines[1].strip())  # the acks are cleared for the next pass

    def test_a_suspend_writing_its_own_pass_stops_the_loop(self):
        result = self.run_gentle("""
kill() {
  [ "$1" = -URG ] || { builtin kill "$@"; return; }
  shift
  for p in "$@"; do signals+=("$p"); : > "$ctl.ack.$p"; done
  printf 1 > "$ctl"
}
bl_zram_gentle $a $b
echo "${#signals[@]}|$_bl_gentle"
""")
        self.assertEqual('', result.stderr)
        self.assertEqual("2|in 1 s, 0 slices (0 ms of work, 0 ended early for the game's frames), 2 processes not finished "
                         "(they go on at the next pass)", result.stdout.strip())

    def test_the_loop_ends_after_two_minutes(self):
        result = self.run_gentle("""
kill() {
  [ "$1" = -URG ] || { builtin kill "$@"; return; }
  shift
  for p in "$@"; do signals+=("$p"); : > "$ctl.ack.$p"; done
}
bl_zram_gentle $a
echo "${#signals[@]}|$_bl_gentle"
""")
        self.assertEqual('', result.stderr)
        self.assertEqual("120|in 120 s, 0 slices (0 ms of work, 0 ended early for the game's frames), 1 processes not finished "
                         "(they go on at the next pass)", result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
