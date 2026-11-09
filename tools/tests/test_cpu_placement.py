"""droiddeck-cpu-placement: which cores games and Steam's own processes get, per chip (WL2)."""
import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-cpu-placement"))
GAME_ENV = runpy.run_path(str(BIN / "droiddeck-game-env"))


def per_mhz(ratio, ghz, fastest_ghz):
    """cpu_capacity of a core `ratio` times as fast per MHz as the fastest one (1024 at fastest_ghz)."""
    return round(1024 * ratio * ghz / fastest_ghz)


# (top clock GHz, capacity or None) per core. Clocks are the players' device.txt "Core ceilings" lines;
# capacities: MT6991 is the one we read on the S11 Ultra (502/880/1024), the others are what the kernel's
# capacity-dmips-mhz rule gives for those cores (in-order 0.30-0.57 of the prime per MHz, A720/A725 class
# 0.72-0.85, A715/X 0.9-0.97).
CHIPS = {
    # POCO X6 Pro : 4x A510 2.2, 3x A715 3.2, 1x A715 3.35; a MediaTek-style and a Qualcomm-style tree
    "D8300": [(2.2, per_mhz(0.32, 2.2, 3.35))] * 4 + [(3.2, per_mhz(0.97, 3.2, 3.35))] * 3 + [(3.35, 1024)],
    "D8300 (0.57 per MHz)": [(2.2, per_mhz(0.57, 2.2, 3.35))] * 4 + [(3.2, per_mhz(0.97, 3.2, 3.35))] * 3 + [(3.35, 1024)],
    # Dimensity 9300: 4x A720 2.0, 3x X4 2.85, 1x X4 3.25
    "D9300": [(2.0, per_mhz(0.8, 2.0, 3.25))] * 4 + [(2.85, per_mhz(0.97, 2.85, 3.25))] * 3 + [(3.25, 1024)],
    # Galaxy Tab S10+ (MT6989, Dimensity 9300+): 4x A720 2.0, 3x X4 2.85, 1x X4 3.4
    "S10+ D9300+": [(2.0, per_mhz(0.8, 2.0, 3.4))] * 4 + [(2.85, per_mhz(0.97, 2.85, 3.4))] * 3 + [(3.4, 1024)],
    # Galaxy Tab S11 Ultra (MT6991, Dimensity 9400+): read on the tablet
    "D9400 Ultra": [(2.4, 502)] * 4 + [(3.3, 880)] * 3 + [(3.73, 1024)],
    # Dimensity 9500 (MT6993): 4x C1-Pro 2.7, 3x C1-Premium 3.5, 1x C1-Ultra 4.21
    "D9500": [(2.7, per_mhz(0.72, 2.7, 4.21))] * 4 + [(3.5, per_mhz(0.9, 3.5, 4.21))] * 3 + [(4.21, 1024)],
    # Snapdragon 8 Gen 2: 3x A510 2.0, 4x A715/A710 2.8, 1x X3 3.2 (qcom tree: 1024 / 1792 / 1894 dmips)
    "8 Gen 2": [(2.0, per_mhz(1024 / 1894, 2.0, 3.2))] * 3 + [(2.8, per_mhz(1792 / 1894, 2.8, 3.2))] * 4 + [(3.2, 1024)],
    # Exynos 1380: 4x A55 2.0, 4x A78 2.4
    "Exynos 1380": [(2.0, per_mhz(0.4, 2.0, 2.4))] * 4 + [(2.4, 1024)] * 4,
    # Dimensity 1080 (MT6877, Galaxy A34): 6x A55 2.0, 2x A78 2.6
    "D1080 2+6": [(2.0, per_mhz(0.4, 2.0, 2.6))] * 6 + [(2.6, 1024)] * 2,
    # Snapdragon 8 Elite: 6x Oryon 3.53, 2x Oryon 4.32 (one core design)
    "8 Elite": [(3.53, per_mhz(1.0, 3.53, 4.32))] * 6 + [(4.32, 1024)] * 2,
    # A kernel without capacities, and one that gives every core 1024
    "D8300 no capacity": [(2.2, None)] * 4 + [(3.2, None)] * 3 + [(3.35, None)],
    "D8300 all 1024": [(2.2, 1024)] * 4 + [(3.2, 1024)] * 3 + [(3.35, 1024)],
    "one kind": [(2.0, 1024)] * 8,
}

# (small cores, game cores, background cores)
EXPECTED = {
    "D8300": ({0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 2, 3}),
    "D8300 (0.57 per MHz)": ({0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 2, 3}),
    "D9300": (set(), set(), {0, 1, 2, 3}),
    "S10+ D9300+": (set(), set(), {0, 1, 2, 3}),
    "D9400 Ultra": (set(), set(), {0, 1, 2, 3}),
    "D9500": (set(), set(), {0, 1, 2, 3}),
    "8 Gen 2": ({0, 1, 2}, {3, 4, 5, 6, 7}, {0, 1, 2}),
    "Exynos 1380": ({0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 2, 3}),
    "D1080 2+6": ({0, 1, 2, 3, 4, 5}, set(), {0, 1, 2, 3, 4, 5}),
    "8 Elite": (set(), set(), {0, 1, 2, 3, 4, 5}),
    "D8300 no capacity": (set(), set(), {0, 1, 2, 3}),
    "D8300 all 1024": (set(), set(), {0, 1, 2, 3}),
    "one kind": (set(), set(), set()),
}


def fake_sysfs(root, cores):
    root = Path(root)
    (root / "online").write_text("0-%d\n" % (len(cores) - 1))
    for n, (ghz, capacity) in enumerate(cores):
        (root / ("cpu%d" % n) / "cpufreq").mkdir(parents=True)
        (root / ("cpu%d" % n) / "cpufreq/cpuinfo_max_freq").write_text("%d\n" % round(ghz * 1e6))
        if capacity is not None:
            (root / ("cpu%d" % n) / "cpu_capacity").write_text("%d\n" % capacity)
    return str(root)


class TopologyTest(unittest.TestCase):
    def test_each_chip_maps_to_its_cores(self):
        for chip, cores in CHIPS.items():
            with tempfile.TemporaryDirectory() as tmp:
                topology = MODULE["Topology"](root=fake_sysfs(tmp, cores))
                self.assertEqual((topology.little(), topology.game(), topology.slow()), EXPECTED[chip], chip)

    def test_lloyds_chip_keeps_a_margin_from_the_all_big_ones(self):
        # The small cores' speed per MHz against the fastest core's, for the chips above: in-order cores
        # stay under 0.6, the A720/C1-Pro class stays clearly over it.
        for chip in ("D9300", "S10+ D9300+", "D9400 Ultra", "D9500"):
            freq, cap = CHIPS[chip][0]
            ratio = (cap / freq) / (1024 / CHIPS[chip][-1][0])
            self.assertGreater(ratio, MODULE["SMALL_PER_MHZ"] + 0.1, chip)

    def test_lists_are_printed_the_way_taskset_reads_them(self):
        fmt = MODULE["fmt"]
        self.assertEqual(fmt({0, 1, 2, 3}), "0-3")
        self.assertEqual(fmt({4, 5, 6, 7, 0}), "0,4-7")
        self.assertEqual(fmt(set()), "")

    def test_the_switch(self):
        mode = MODULE["mode"]
        self.assertEqual(mode({}), {"game", "stack"})
        self.assertEqual(mode({"BL_AUTO_CORES": "0"}), set())
        self.assertEqual(mode({"BL_AUTO_CORES": "game"}), {"game"})
        self.assertEqual(mode({"BL_AUTO_CORES": "stack"}), {"stack"})
        self.assertEqual(mode({"BL_AUTO_CORES": "1"}), {"game", "stack"})

    def test_the_session_log_line_names_what_happens(self):
        with tempfile.TemporaryDirectory() as tmp:
            topology = MODULE["Topology"](root=fake_sysfs(tmp, CHIPS["D8300"]))
            line = MODULE["describe"](topology, {"game", "stack"})
            self.assertIn("small cores 0-3", line)
            self.assertIn("games on 4-7", line)
            self.assertIn("off", MODULE["describe"](topology, set()))
        with tempfile.TemporaryDirectory() as tmp:
            topology = MODULE["Topology"](root=fake_sysfs(tmp, CHIPS["D9400 Ultra"]))
            self.assertIn("no small cores", MODULE["describe"](topology, {"game", "stack"}))


class FakeAffinity:
    def __init__(self, masks):
        self.masks = masks
        self.calls = []

    def get(self, tid):
        return set(self.masks[tid])

    def set(self, tid, cores):
        self.calls.append((tid, set(cores)))
        self.masks[tid] = set(cores)


def fake_proc(root, processes):
    """processes: pid -> (argv, [tids])"""
    for pid, (argv, tids) in processes.items():
        folder = Path(root) / str(pid)
        (folder / "task").mkdir(parents=True)
        (folder / "cmdline").write_bytes(b"".join(a.encode() + b"\0" for a in argv))
        for tid in tids:
            (folder / "task" / str(tid)).mkdir()
    return Path(root)


class StackHoldTest(unittest.TestCase):
    PROCESSES = {
        100: (["/root/.local/share/Steam/ubuntu12_64/steamrtarm64/steam", "-gamepadui"], [100, 101]),
        200: (["/s/FEX", "/root/.local/share/Steam/ubuntu12_64/steamwebhelper", "--type=renderer"], [200, 201]),
        300: (["gamescope", "-w", "1280"], [300]),
        400: (["/usr/bin/Xwayland", ":1"], [400]),
        500: (["pipewire"], [500]),
        600: (["C:\\Games\\re7.exe"], [600, 601]),
        700: (["/usr/bin/wineserver"], [700]),
        800: (["/s/reaper", "SteamLaunch", "AppId=418370"], [800]),
    }

    def run_focus(self, focus_values):
        every = set(range(8))
        with tempfile.TemporaryDirectory() as tmp:
            proc = fake_proc(Path(tmp) / "proc", self.PROCESSES)
            masks = {tid: set(every) for _, tids in self.PROCESSES.values() for tid in tids}
            masks[101] = {0, 1, 2, 3, 4, 5}  # a thread with a mask of its own
            affinity = FakeAffinity(masks)
            holder = MODULE["StackHolder"]({0, 1, 2, 3}, proc=proc, affinity=affinity, own=1)
            focus = Path(tmp) / "focus.json"
            ticks = iter(range(1000))
            governor = MODULE["Governor"](focus, holder, clock=lambda: next(ticks) * 100.0)
            states = []
            for n, app in enumerate(focus_values):
                focus.write_text(json.dumps({"focusedApp": app}))
                os.utime(focus, ns=(n + 1, n + 1))
                governor.tick()
                states.append({tid: frozenset(m) for tid, m in affinity.masks.items()})
            return states, affinity

    def test_steam_gamescope_and_xwayland_go_to_the_small_cores_while_a_game_has_focus(self):
        states, _ = self.run_focus([418370])
        held = states[0]
        for tid in (100, 101, 200, 201, 300, 400, 500):
            self.assertEqual(held[tid], {0, 1, 2, 3}, tid)
        for tid in (600, 601, 700, 800):  # the game, wineserver and Steam's launch wrapper are left alone
            self.assertEqual(held[tid], set(range(8)), tid)

    def test_steams_ui_in_front_gives_every_thread_its_own_mask_back(self):
        for back in (769, 0, None):
            states, _ = self.run_focus([418370, back])
            released = states[1]
            self.assertEqual(released[100], set(range(8)))
            self.assertEqual(released[101], {0, 1, 2, 3, 4, 5}, back)
            self.assertEqual(released[300], set(range(8)))

    def test_nothing_is_touched_while_steam_has_focus(self):
        _, affinity = self.run_focus([769, 0])
        self.assertEqual(affinity.calls, [])


class GameCoresTest(unittest.TestCase):
    def apply(self, chip, env, affinity):
        calls = []
        os_mod = GAME_ENV["os"]
        with tempfile.TemporaryDirectory() as tmp:
            root = fake_sysfs(tmp, CHIPS[chip])
            module = dict(MODULE, Topology=lambda: MODULE["Topology"](root=root))
            env = dict({"STEAM_COMPAT_DATA_PATH": "/steam/compatdata/418370"}, **env)
            with mock.patch.object(os_mod, "sched_getaffinity", return_value=set(affinity), create=True), \
                    mock.patch.object(os_mod, "sched_setaffinity", side_effect=lambda pid, cores: calls.append(set(cores)),
                                      create=True):
                GAME_ENV["auto_game_cores"](env, module)
        return calls

    def test_lloyds_game_runs_on_the_big_cores(self):
        self.assertEqual(self.apply("D8300", {}, range(8)), [{4, 5, 6, 7}])
        self.assertEqual(self.apply("8 Gen 2", {}, range(8)), [{3, 4, 5, 6, 7}])

    def test_all_big_chips_are_left_as_they_are(self):
        for chip in ("D9300", "S10+ D9300+", "D9400 Ultra", "D9500", "8 Elite", "D8300 no capacity", "one kind"):
            self.assertEqual(self.apply(chip, {}, range(8)), [], chip)

    def test_too_few_big_cores_keep_every_core(self):
        self.assertEqual(self.apply("D1080 2+6", {}, range(8)), [])

    def test_the_players_and_the_games_picks_win(self):
        for env in ({"BL_AUTO_CORES": "0"}, {"BL_AUTO_CORES": "stack"}, {"DROIDDECK_GAME_CORES": "0-7"},
                    {"BL_GAME_CPUS": "0-7"}, {"WINE_CPU_TOPOLOGY": "8:0,1,2,3,4,5,6,7"}, {"PROTON_CPU_TOPOLOGY": "4:0,1,2,3"},
                    {"STEAM_COMPAT_DATA_PATH": ""}):
            self.assertEqual(self.apply("D8300", env, range(8)), [], env)

    def test_a_game_started_from_a_held_steam_thread_gets_its_cores(self):
        self.assertEqual(self.apply("D8300", {}, {0, 1, 2, 3}), [{4, 5, 6, 7}])
        self.assertEqual(self.apply("D8300", {"BL_AUTO_CORES": "stack"}, {0, 1, 2, 3}), [set(range(8))])
        self.assertEqual(self.apply("D8300", {"WINE_CPU_TOPOLOGY": "4:0,1,2,3"}, {0, 1}), [set(range(8))])

    def test_windows_then_sees_the_big_cores_fastest_first(self):
        os_mod = GAME_ENV["os"]
        env = {"STEAM_COMPAT_DATA_PATH": "/steam/compatdata/418370"}
        speeds = {c: cap for c, (_, cap) in enumerate(CHIPS["D8300"])}
        with mock.patch.dict(GAME_ENV["cpu_topology"].__globals__, {"online_cores": lambda: set(range(8))}), \
                mock.patch.dict(GAME_ENV["fastest_first"].__globals__, {"core_speeds": lambda cores: speeds}), \
                mock.patch.object(os_mod, "sched_getaffinity", return_value={4, 5, 6, 7}, create=True):
            GAME_ENV["cpu_topology"](env, ())
        self.assertEqual(env["WINE_CPU_TOPOLOGY"], "4:7,4,5,6")


if __name__ == "__main__":
    unittest.main()
