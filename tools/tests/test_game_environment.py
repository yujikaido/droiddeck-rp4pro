import hashlib
import json
import os
import shutil
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-game-env"))
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))
STATE = tempfile.TemporaryDirectory()
COMPAT["main"].__globals__["COMPAT_DIR"] = STATE.name


def mapping_of(text):
    tokens = COMPAT["tokenize"](text)
    return COMPAT["read_mapping"](tokens, COMPAT["mapping_block"](tokens))


def config_text(entries):
    mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % (app, name, "75" if app == "0" else "250") for app, name in entries)
    return '"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping


class ReadConfigMappingTest(unittest.TestCase):
    def test_missing_parent_or_unbalanced_vdf_is_not_treated_as_absent_choices(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.vdf"
            config.write_text('"InstallConfigStore" { "Software" { "Other" {} } }')
            self.assertIsNone(COMPAT["read_config_mapping"](str(config)))
            config.write_text('"InstallConfigStore" { "Software" { "Valve" { "Steam" {} }')
            self.assertIsNone(COMPAT["read_config_mapping"](str(config)))

    def test_valid_steam_parent_without_compat_mapping_has_no_per_app_choices(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.vdf"
            config.write_text('"InstallConfigStore" { "Software" { "Valve" { "Steam" {} } } }')
            self.assertEqual(COMPAT["read_config_mapping"](str(config)), {})


class GameEnvironmentTest(unittest.TestCase):
    def test_directaudio_capture_follows_the_session_microphone_setting(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            probe = home / "probe"
            probe.write_text("#!" + sys.executable + "\nimport os\nprint(os.environ.get('BANNER_AUDIO_DIRECT_MIC', 'unset'))\n")
            probe.chmod(0o755)
            config = home / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            for enabled, override, expected in (("1", None, "1"), ("0", "1", "0"), ("1", "0", "0")):
                with self.subTest(enabled=enabled, override=override):
                    profile = {} if override is None else {
                        "BANNER_AUDIO_DIRECT_MIC": override, "BL_MIC_ENABLED": override,
                    }
                    config.write_text(json.dumps({"version": 1, "games": {"42": profile}}))
                    env = dict(os.environ, HOME=tmp, BL_MIC_ENABLED=enabled,
                               BANNER_AUDIO_DIRECT_MIC="1", STEAM_COMPAT_DATA_PATH="/compatdata/42")
                    result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"),
                                             str(probe), "waitforexitandrun"],
                                            env=env, text=True, capture_output=True, check=True)
                    self.assertEqual(result.stdout.strip(), expected)

    def test_the_install_script_evaluator_gets_its_scripts_marked_first(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            script = root / "legacycompat/evaluatorscript_409710.vdf"
            script.parent.mkdir(parents=True)
            script.write_text("{}")
            calls = []
            run = MODULE["mark_install_scripts"].__globals__["subprocess"].run
            MODULE["mark_install_scripts"].__globals__["subprocess"].run = lambda args, **kw: calls.append((args, kw["env"]))
            try:
                env = {"STEAM_COMPAT_CLIENT_INSTALL_PATH": str(root)}
                MODULE["mark_install_scripts"](["/proton", "run", str(root / "legacycompat/iscriptevaluator.exe"), "legacycompat\\evaluatorscript_409710.vdf"], env)
                MODULE["mark_install_scripts"](["/proton", "waitforexitandrun", "/game/Game.exe"], env)
            finally:
                MODULE["mark_install_scripts"].__globals__["subprocess"].run = run
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0][0][1], str(root))
            self.assertEqual(calls[0][1]["DROIDDECK_SEED_SCRIPTS"], str(script))

    def test_the_service_helper_gets_the_games_install_scripts_marked_first(self):
        # Just Cause 2: legacycompat\\steamservice.exe /runfunc runs the install steps with no script
        # on its command line and no install path for Proton.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "Steam"
            sd = Path(tmp) / "sd"
            game = sd / "steamapps/common/Just Cause 2"
            (game / "_CommonRedist/vcredist/2005").mkdir(parents=True)
            (game / "installscript.vdf").write_text("{}")
            (game / "_CommonRedist/vcredist/2005/installscript.vdf").write_text("{}")
            (root / "steamapps").mkdir(parents=True)
            (root / "steamapps/libraryfolders.vdf").write_text('"libraryfolders" { "1" { "path" "%s" } }' % sd)
            (sd / "steamapps/appmanifest_8190.acf").write_text('"AppState" { "appid" "8190" "installdir" "Just Cause 2" }')
            calls = []
            module = MODULE["mark_install_scripts"].__globals__
            run = module["subprocess"].run
            module["subprocess"].run = lambda args, **kw: calls.append((args, kw["env"]))
            try:
                helper = ["/proton", "run", "Z:\\root\\.local\\share\\Steam\\legacycompat\\steamservice.exe",
                          "/runfunc", "2528692275", "1", "6959", "32768"]
                env = {"STEAM_COMPAT_CLIENT_INSTALL_PATH": str(root), "STEAM_COMPAT_INSTALL_PATH": "",
                       "STEAM_COMPAT_DATA_PATH": str(sd / "steamapps/compatdata/8190")}
                with mock.patch("sys.stderr"):
                    MODULE["mark_install_scripts"](helper, env)
                    MODULE["mark_install_scripts"](helper[:3], env)
                    MODULE["mark_install_scripts"](helper, dict(env, STEAM_COMPAT_DATA_PATH=str(sd / "steamapps/compatdata/9")))
            finally:
                module["subprocess"].run = run
            self.assertEqual(len(calls), 2)
            self.assertEqual(calls[0][0][1], str(root))
            self.assertEqual(calls[0][1]["DROIDDECK_SEED_SCRIPTS"].split("\n"), [
                str(game / "_CommonRedist/vcredist/2005/installscript.vdf"), str(game / "installscript.vdf")])
            self.assertEqual(calls[1][1]["DROIDDECK_SEED_SCRIPTS"], "")

    def test_the_mali_bridge_caps_the_vkd3d_feature_level(self):
        bridge = {"BL_VKBRIDGE": "1", "VKD3D_FEATURE_LEVEL": "12_2"}
        self.assertIn("capped", MODULE["bridge_feature_level"](bridge))
        self.assertEqual(bridge["VKD3D_FEATURE_LEVEL"], "12_0")
        for env in ({"BL_VKBRIDGE": "1", "VKD3D_FEATURE_LEVEL": "11_1"}, {"VKD3D_FEATURE_LEVEL": "12_2"}, {"BL_VKBRIDGE": "1"}):
            before = dict(env)
            self.assertIsNone(MODULE["bridge_feature_level"](env))
            self.assertEqual(env, before)

    def test_profile_precedence_and_unset(self):
        env = {"KEEP": "inherited", "REMOVE": "inherited", "CUSTOM": "launch option"}
        config = {"version": 1, "shared": {"CUSTOM": "shared"}, "games": {"42": {"REMOVE": None, "CUSTOM": "game", "EMPTY": ""}}}
        self.assertEqual(MODULE["apply_config"](env, config, "42"), {"KEEP": "inherited", "CUSTOM": "game", "EMPTY": ""})
        self.assertEqual(env["REMOVE"], "inherited")
        self.assertEqual(MODULE["apply_config"](env, config, "43")["CUSTOM"], "shared")

    def test_component_profiles_override_general_and_restore_it_for_the_next_game(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            home = root / "home"
            component_dir = home / ".local/share/droiddeck-components"
            base = root / "proton"
            target = base / "files/lib/wine/dxvk/aarch64-windows/d3d11.dll"
            leftover = target.with_name("d3d8.dll")
            component_dir.mkdir(parents=True)
            target.parent.mkdir(parents=True)
            (base / "version").write_text("1 build-1\n")
            general = root / "general.dll"
            game = root / "game.dll"
            general.write_text("general")
            game.write_text("game")
            target.write_text("old")
            leftover.write_text("old")
            digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
            (component_dir / "desired.tsv").write_text(
                "*\tproton\tbuild-1\tdxvk\tfiles/lib/wine/dxvk/aarch64-windows/d3d11.dll\t%s\t%s\n"
                "42\tproton\tbuild-1\tdxvk\tfiles/lib/wine/dxvk/aarch64-windows/d3d11.dll\t%s\t%s\n"
                "42\tproton\tbuild-1\tdxvk\tfiles/lib/wine/dxvk/aarch64-windows/d3d8.dll\t-\t-\n"
                % (digest(general), general, digest(game), game)
            )
            script = COMPAT["BL_COMPONENTS_SETUP"] + '\nverb=waitforexitandrun; bl_components "$1"\n'
            env = dict(os.environ, HOME=str(home), STEAM_COMPAT_DATA_PATH="/steam/compatdata/42")
            subprocess.run(["bash", "-c", script, "test", str(base)], env=env, check=True)
            self.assertEqual(target.read_text(), "game")
            self.assertFalse(leftover.exists())

            env["STEAM_COMPAT_DATA_PATH"] = "/steam/compatdata/43"
            subprocess.run(["bash", "-c", script, "test", str(base)], env=env, check=True)
            self.assertEqual(target.read_text(), "general")

    def test_component_scope_matches_environment_scope_for_shortcut_ids(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            component_dir = root / ".local/share/droiddeck-components"
            base = root / "proton"
            target = base / "files/lib/wine/dxvk/aarch64-windows/d3d11.dll"
            component_dir.mkdir(parents=True)
            target.parent.mkdir(parents=True)
            (base / "version").write_text("1 build-1\n")
            rows = []
            for scope in ("*", "42", "4294967295", "2147483648"):
                source = root / ("default" if scope == "*" else scope)
                source.write_text(scope)
                rows.append("%s\tproton\tbuild-1\tdxvk\t%s\t%s\t%s\n" % (
                    scope, target.relative_to(base),
                    hashlib.sha256(source.read_bytes()).hexdigest(), source,
                ))
            (component_dir / "desired.tsv").write_text("".join(rows))
            script = COMPAT["BL_COMPONENTS_SETUP"] + '\nverb=waitforexitandrun; bl_components "$1"\n'
            for value in (
                "42", "00042", "43", "-1", "-0001", "4294967295", "-2147483648",
                "2147483648", "-1///", "0", "-0", "000", "-2147483649", "4294967296",
                "999999999999999999999999999", "not-an-id", "1+1", "",
            ):
                with self.subTest(prefix=value):
                    env = dict(os.environ, HOME=str(root), STEAM_COMPAT_DATA_PATH="/steam/compatdata/" + value)
                    target.write_text("untouched")
                    subprocess.run(["bash", "-c", script, "test", str(base)], env=env, check=True, capture_output=True)
                    scope = MODULE["game_id"](env)
                    expected = "untouched" if scope is None else scope if scope in {"42", "4294967295", "2147483648"} else "*"
                    self.assertEqual(target.read_text(), expected)

    def test_engine_fixes_follow_the_games_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            game = Path(tmp) / "Godot Game"
            (game / "data_game_windows_x86_64").mkdir(parents=True)
            for name in ("Game.exe", "Game.pck", "libsentry.windows.release.x86_64.dll", "data_game_windows_x86_64/coreclr.dll"):
                (game / name).touch()
            env, extra, notes = MODULE["engine_fixes"](str(game / "Game.exe"), [])
            self.assertEqual(env["FEX_TSOENABLED"], "1")
            self.assertEqual(env["FEX_MULTIBLOCK"], "0")
            self.assertEqual(env["WINEDLLOVERRIDES"], "libsentry.windows.release.x86_64=d")
            self.assertEqual(extra, ["--rendering-driver", "vulkan"])
            self.assertEqual(MODULE["engine_fixes"](str(game / "Game.exe"), ["--rendering-driver", "opengl3"])[1], [])
            other = Path(tmp) / "Other"
            other.mkdir()
            (other / "Other.exe").touch()
            self.assertEqual(MODULE["engine_fixes"](str(other / "Other.exe"), []), ({}, [], []))

    def make_game(self, tmp, exe, *names):
        folder = Path(tmp) / Path(exe).stem
        folder.mkdir()
        for name in (exe,) + names:
            path = folder / name
            if name.endswith("_Data"):
                path.mkdir()
            else:
                path.touch()
        return str(folder / exe)

    def test_re_engine_keeps_tso_at_every_level_but_off(self):
        with tempfile.TemporaryDirectory() as tmp:
            exe = self.make_game(tmp, "re2.exe", "re_chunk_000.pak")
            for level in ("on", "fast"):
                env, _, notes = MODULE["engine_fixes"](exe, [], level)
                self.assertEqual(env, {"FEX_TSOENABLED": "1", "FEX_HALFBARRIERTSOENABLED": "1"}, level)
                self.assertEqual(len(notes), 1)
            self.assertEqual(MODULE["engine_fixes"](exe, [], "off"), ({}, [], []))
            self.assertEqual(MODULE["engine_fixes"](exe, [])[0]["FEX_TSOENABLED"], "1")

    def test_speed_presets_only_when_asked_for(self):
        with tempfile.TemporaryDirectory() as tmp:
            games = [self.make_game(tmp, "TheForest.exe", "TheForest_Data"),
                     self.make_game(tmp, "OuterWilds.exe", "UnityPlayer.dll"),
                     self.make_game(tmp, "sekiro.exe"),
                     self.make_game(tmp, "DarkSoulsIII.exe"),
                     self.make_game(tmp, "Astro-Win64-Shipping.exe")]
            for exe in games:
                self.assertEqual(MODULE["engine_fixes"](exe, [], "on"), ({}, [], []), exe)
                env, _, notes = MODULE["engine_fixes"](exe, [], "fast")
                self.assertEqual(env["FEX_TSOENABLED"], "0", exe)
                self.assertIn("experimental", notes[0])
            other = self.make_game(tmp, "Other.exe")
            self.assertEqual(MODULE["engine_fixes"](other, [], "fast"), ({}, [], []))

    def test_safety_fixes_win_over_speed_ones(self):
        with tempfile.TemporaryDirectory() as tmp:
            unity_re = self.make_game(tmp, "Odd.exe", "UnityPlayer.dll", "re_chunk_000.pak")
            self.assertEqual(MODULE["engine_fixes"](unity_re, [], "fast")[0]["FEX_TSOENABLED"], "1")
            unity_net = self.make_game(tmp, "Net.exe", "Net_Data", "coreclr.dll")
            env = MODULE["engine_fixes"](unity_net, [], "fast")[0]
            self.assertEqual((env["FEX_TSOENABLED"], env["FEX_MULTIBLOCK"]), ("1", "0"))
            # .NET full TSO predates the switch and stays when it is off.
            self.assertEqual(MODULE["engine_fixes"](unity_net, [], "off")[0]["FEX_TSOENABLED"], "1")

    def test_cpu_preset_level_follows_game_then_shared_then_session(self):
        level = MODULE["cpu_preset_level"]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "game-environment.json"
            self.assertEqual(level({}, "42", path), "on")
            self.assertEqual(level({"DROIDDECK_CPU_PRESETS": "FAST"}, "42", path), "fast")
            self.assertEqual(level({"DROIDDECK_CPU_PRESETS": "bogus"}, "42", path), "on")
            self.assertEqual(level({"DROIDDECK_CPU_PRESETS": "0"}, "42", path), "off")
            path.write_text(json.dumps({"version": 1, "shared": {"DROIDDECK_CPU_PRESETS": "off"},
                                        "games": {"42": {"DROIDDECK_CPU_PRESETS": "fast"}}}))
            self.assertEqual(level({}, "42", path), "fast")
            self.assertEqual(level({}, "7", path), "off")
            path.write_text(json.dumps({"version": 1, "games": {"7": {"DROIDDECK_CPU_PRESETS": None}}}))
            self.assertEqual(level({"DROIDDECK_CPU_PRESETS": "fast"}, "7", path), "on")
            path.write_text("{broken")
            self.assertEqual(level({"DROIDDECK_CPU_PRESETS": "fast"}, "42", path), "fast")

    def test_the_game_profile_still_beats_the_re_engine_fix(self):
        config = {"version": 1, "shared": {"FEX_TSOENABLED": "0"}, "games": {"42": {"FEX_TSOENABLED": "0"}}}
        fixes = {"FEX_TSOENABLED": "1", "FEX_HALFBARRIERTSOENABLED": "1"}
        self.assertEqual(MODULE["apply_config"]({}, config, "42", fixes)["FEX_TSOENABLED"], "0")
        self.assertEqual(MODULE["apply_config"]({}, config, "7", fixes)["FEX_TSOENABLED"], "1")

    def test_a_new_fix_only_needs_registering(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "Game.exe").touch()
            (Path(tmp) / "engine.dll").touch()
            check = lambda game: ({"ENGINE": "1"}, ["--flag"], "engine") if "engine.dll" in game.files else None
            MODULE["FIXES"].append(check)
            try:
                self.assertEqual(MODULE["engine_fixes"](str(Path(tmp) / "Game.exe"), []), ({"ENGINE": "1"}, ["--flag"], ["engine"]))
            finally:
                MODULE["FIXES"].remove(check)

    def test_engine_fixes_sit_between_shared_and_game_profiles(self):
        env = {"WINEDLLOVERRIDES": "dxgi=n"}
        fixes = {"FEX_MULTIBLOCK": "0", "WINEDLLOVERRIDES": "libsentry=d"}
        config = {"version": 1, "shared": {"FEX_MULTIBLOCK": "1"}, "games": {"42": {"FEX_TSOENABLED": "0"}}}
        result = MODULE["apply_config"](env, config, "42", {**fixes, "FEX_TSOENABLED": "1"})
        self.assertEqual(result["FEX_MULTIBLOCK"], "0")
        self.assertEqual(result["FEX_TSOENABLED"], "0")
        self.assertEqual(result["WINEDLLOVERRIDES"], "dxgi=n;libsentry=d")

    def test_hpl_games_get_ati_fragment_shader_hidden_added_to_the_players_override(self):
        hide = "-GL_ATI_fragment_shader"
        with tempfile.TemporaryDirectory() as tmp:
            soma = self.make_game(tmp, "Soma.exe")
            for app in ("282140", "57300", "239200"):
                env, extra, notes = MODULE["engine_fixes"](soma, [], "on", app)
                self.assertEqual((env, extra), ({"MESA_EXTENSION_OVERRIDE": hide}, []), app)
                self.assertIn("GL_ATI_fragment_shader hidden", notes[0])
                self.assertEqual(MODULE["engine_fixes"](soma, [], "off", app)[0], env)
            # Only those games: the same exe under another id, or no id, gets nothing.
            self.assertEqual(MODULE["engine_fixes"](soma, [], "on", "42"), ({}, [], []))
            self.assertEqual(MODULE["engine_fixes"](soma, []), ({}, [], []))
        fixes = {"MESA_EXTENSION_OVERRIDE": hide}
        apply = MODULE["apply_config"]
        self.assertEqual(apply({}, {"version": 1}, "282140", fixes)["MESA_EXTENSION_OVERRIDE"], hide)
        # The player's value, from launch options, the shared profile or the game's, is kept and added to.
        own = {"MESA_EXTENSION_OVERRIDE": "+GL_EXT_foo"}
        self.assertEqual(apply(own, {"version": 1}, "282140", fixes)["MESA_EXTENSION_OVERRIDE"], "+GL_EXT_foo " + hide)
        shared = {"version": 1, "shared": {"MESA_EXTENSION_OVERRIDE": "-GL_ARB_bar"}}
        self.assertEqual(apply(own, shared, "282140", fixes)["MESA_EXTENSION_OVERRIDE"], "-GL_ARB_bar " + hide)
        game = {"version": 1, "games": {"282140": {"MESA_EXTENSION_OVERRIDE": "  +GL_EXT_a   -GL_EXT_b "}}}
        self.assertEqual(apply({}, game, "282140", fixes)["MESA_EXTENSION_OVERRIDE"], "+GL_EXT_a -GL_EXT_b " + hide)
        # A player who names the extension themselves keeps their choice.
        mine = {"MESA_EXTENSION_OVERRIDE": "+GL_ATI_fragment_shader"}
        self.assertEqual(apply(mine, {"version": 1}, "282140", fixes)["MESA_EXTENSION_OVERRIDE"], "+GL_ATI_fragment_shader")
        # Another game is untouched.
        self.assertNotIn("MESA_EXTENSION_OVERRIDE", apply({}, {"version": 1}, "42", {}))

    def test_launch_hides_ati_fragment_shader_for_soma_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            probe = home / "fake-proton"
            probe.write_text("#!/usr/bin/python3\nimport json, os\nprint(json.dumps(os.environ.get('MESA_EXTENSION_OVERRIDE')))\n")
            probe.chmod(0o755)
            exe = self.make_game(tmp, "Soma.exe")
            base = {**os.environ, "HOME": tmp}
            base.pop("MESA_EXTENSION_OVERRIDE", None)

            def run(app, **extra):
                out = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun", exe],
                                     env={**base, "STEAM_COMPAT_DATA_PATH": "/compatdata/" + app, **extra},
                                     text=True, capture_output=True, check=True)
                return json.loads(out.stdout.strip().splitlines()[-1])

            self.assertEqual(run("282140"), "-GL_ATI_fragment_shader")
            self.assertEqual(run("282140", MESA_EXTENSION_OVERRIDE="+GL_EXT_x"), "+GL_EXT_x -GL_ATI_fragment_shader")
            self.assertIsNone(run("42"))

    def test_stray_gets_dxvk_tiler_mode_off_added_to_the_players_dxvk_config(self):
        off = "dxvk.tilerMode=False"
        with tempfile.TemporaryDirectory() as tmp:
            stray = self.make_game(tmp, "Stray.exe")
            env, extra, notes = MODULE["engine_fixes"](stray, [], "on", "1332010", True)
            self.assertEqual((env, extra), ({"DXVK_CONFIG": off}, []))
            self.assertIn("tiler mode off", notes[0])
            self.assertEqual(MODULE["engine_fixes"](stray, [], "off", "1332010", True)[0], env)
            # Only Stray, and only on the Mali bridge: Dark Souls III (374320) and other ids get nothing.
            for app, bridge in (("1332010", False), ("374320", True), ("42", True), (None, True)):
                self.assertEqual(MODULE["engine_fixes"](stray, [], "on", app, bridge), ({}, [], []), (app, bridge))
        fixes = {"DXVK_CONFIG": off}
        apply = MODULE["apply_config"]
        self.assertEqual(apply({}, {"version": 1}, "1332010", fixes)["DXVK_CONFIG"], off)
        # The player's options, from launch options, the shared profile or the game's, are kept and added to.
        own = {"DXVK_CONFIG": "dxvk.maxFrameRate = 30; "}
        self.assertEqual(apply(own, {"version": 1}, "1332010", fixes)["DXVK_CONFIG"], "dxvk.maxFrameRate = 30; " + off)
        shared = {"version": 1, "shared": {"DXVK_CONFIG": "dxvk.tearFree = True"}}
        self.assertEqual(apply(own, shared, "1332010", fixes)["DXVK_CONFIG"], "dxvk.tearFree = True; " + off)
        game = {"version": 1, "games": {"1332010": {"DXVK_CONFIG": "dxgi.maxFrameLatency = 1;dxvk.hud = fps"}}}
        self.assertEqual(apply({}, game, "1332010", fixes)["DXVK_CONFIG"],
                         "dxgi.maxFrameLatency = 1;dxvk.hud = fps; " + off)
        # A player who sets tiler mode themselves keeps their choice (DXVK lets a later entry win).
        for mine in ("dxvk.tilerMode = True", "dxvk.maxFrameRate=30;dxvk.tilerMode=True"):
            self.assertEqual(apply({"DXVK_CONFIG": mine}, {"version": 1}, "1332010", fixes)["DXVK_CONFIG"], mine)
        # Texture filtering still comes after.
        filtering = {"version": 1, "dxvkConfig": "d3d11.samplerAnisotropy = 16"}
        self.assertEqual(apply(own, filtering, "1332010", fixes)["DXVK_CONFIG"],
                         "dxvk.maxFrameRate = 30; " + off + "; d3d11.samplerAnisotropy = 16")
        # Another game is untouched.
        self.assertNotIn("DXVK_CONFIG", apply({}, {"version": 1}, "374320", {}))

    def test_launch_turns_dxvk_tiler_mode_off_for_stray_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            probe = Path(tmp) / "fake-proton"
            probe.write_text("#!/usr/bin/python3\nimport json, os\nprint(json.dumps(os.environ.get('DXVK_CONFIG')))\n")
            probe.chmod(0o755)
            exe = self.make_game(tmp, "Stray.exe")
            base = {**os.environ, "HOME": tmp, "BL_VKBRIDGE": "1"}
            base.pop("DXVK_CONFIG", None)

            def run(app, **extra):
                out = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun", exe],
                                     env={**base, "STEAM_COMPAT_DATA_PATH": "/compatdata/" + app, **extra},
                                     text=True, capture_output=True, check=True)
                return json.loads(out.stdout.strip().splitlines()[-1])

            self.assertEqual(run("1332010"), "dxvk.tilerMode=False")
            self.assertEqual(run("1332010", DXVK_CONFIG="dxvk.hud=fps"), "dxvk.hud=fps; dxvk.tilerMode=False")
            self.assertEqual(run("1332010", DXVK_CONFIG="dxvk.tilerMode=True"), "dxvk.tilerMode=True")
            self.assertIsNone(run("1332010", BL_VKBRIDGE="0"))
            self.assertIsNone(run("374320"))

    def test_the_session_log_says_what_the_tiler_fix_did(self):
        # rc9 item 20: with the player's own dxvk.tilerMode the log said "tiler mode off" though
        # nothing was added. The line now says the player's value was kept.
        with tempfile.TemporaryDirectory() as tmp:
            probe = Path(tmp) / "fake-proton"
            probe.write_text("#!/usr/bin/python3\nimport json, os\nprint(json.dumps(os.environ.get('DXVK_CONFIG')))\n")
            probe.chmod(0o755)
            exe = self.make_game(tmp, "Stray.exe")
            base = {**os.environ, "HOME": tmp, "BL_VKBRIDGE": "1", "STEAM_COMPAT_DATA_PATH": "/compatdata/1332010"}
            base.pop("DXVK_CONFIG", None)

            def run(**extra):
                out = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun", exe],
                                     env={**base, **extra}, text=True, capture_output=True, check=True)
                notes = [l for l in out.stderr.splitlines() if "tiler mode" in l]
                return json.loads(out.stdout.strip().splitlines()[-1]), notes

            off = "droiddeck-game-env: Stray: DXVK tiler mode off on the Mali bridge, so the GPU doesn't wait on the render thread"
            kept = "droiddeck-game-env: Stray: DXVK tiler mode left as the player set it in DXVK_CONFIG (nothing added)"
            self.assertEqual(run(), ("dxvk.tilerMode=False", [off]))
            self.assertEqual(run(DXVK_CONFIG="dxgi.maxFrameLatency=1"), ("dxgi.maxFrameLatency=1; dxvk.tilerMode=False", [off]))
            self.assertEqual(run(DXVK_CONFIG="dxvk.tilerMode=True"), ("dxvk.tilerMode=True", [kept]))
            self.assertEqual(run(DXVK_CONFIG="dxvk.hud=fps; dxvk.tilerMode = False"), ("dxvk.hud=fps; dxvk.tilerMode = False", [kept]))
            # From the game's own profile too, not only launch options.
            config = Path(tmp) / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            config.write_text(json.dumps({"version": 1, "games": {"1332010": {"DXVK_CONFIG": "dxvk.tilerMode=True"}}}))
            self.assertEqual(run(), ("dxvk.tilerMode=True", [kept]))
            config.unlink()
            # Not on the bridge: no fix, no line.
            self.assertEqual(run(BL_VKBRIDGE="0"), (None, []))

    def test_the_kept_line_needs_every_added_name_already_set(self):
        note = MODULE["Note"]("added", {"DXVK_CONFIG"}, "kept")
        self.assertEqual(note.line(set()), "added")
        self.assertEqual(note.line({"DXVK_CONFIG"}), "kept")
        self.assertEqual(note.line({"MESA_EXTENSION_OVERRIDE"}), "added")
        # A fix without a kept line, or one that sets a plain variable, always says its own line.
        self.assertEqual(MODULE["Note"]("set", {"DXVK_CONFIG"}).line({"DXVK_CONFIG"}), "set")
        self.assertEqual(MODULE["Note"]("set", {"WINE_X"}, "kept").line({"WINE_X"}), "set")
        # SOMA: the player's own GL_ATI_fragment_shader choice is said, not "hidden".
        kept = set()
        out = MODULE["apply_config"]({"MESA_EXTENSION_OVERRIDE": "+GL_ATI_fragment_shader"}, {"version": 1}, "282140",
                                     {"MESA_EXTENSION_OVERRIDE": "-GL_ATI_fragment_shader"}, kept)
        self.assertEqual((out["MESA_EXTENSION_OVERRIDE"], kept), ("+GL_ATI_fragment_shader", {"MESA_EXTENSION_OVERRIDE"}))
        kept = set()
        MODULE["apply_config"]({}, {"version": 1}, "282140", {"MESA_EXTENSION_OVERRIDE": "-GL_ATI_fragment_shader"}, kept)
        self.assertEqual(kept, set())

    def test_invalid_configuration_is_atomic(self):
        env = {"ORIGINAL": "unchanged"}
        for entries in ({"A": "ok", "BAD=KEY": "x"}, {"A": "bad\0value"}, {"A": 1}):
            with self.assertRaises(ValueError):
                MODULE["apply_config"](env, {"version": 1, "shared": entries}, "42")
            self.assertEqual(env, {"ORIGINAL": "unchanged"})

    def test_texture_filtering_is_appended_to_dxvk_config(self):
        options = "d3d9.samplerAnisotropy = 16; d3d11.samplerAnisotropy = 16"
        config = {"version": 1, "shared": {}, "games": {}, "dxvkConfig": options}
        self.assertEqual(MODULE["apply_config"]({}, config, "42")["DXVK_CONFIG"], options)
        own = {"DXVK_CONFIG": "dxvk.maxFrameRate = 60; "}
        self.assertEqual(MODULE["apply_config"](own, config, "42")["DXVK_CONFIG"], "dxvk.maxFrameRate = 60; " + options)
        shared = {"version": 1, "shared": {"DXVK_CONFIG": "dxvk.tearFree = True"}, "games": {}, "dxvkConfig": options}
        self.assertEqual(MODULE["apply_config"]({}, shared, "42")["DXVK_CONFIG"], "dxvk.tearFree = True; " + options)
        self.assertNotIn("DXVK_CONFIG", MODULE["apply_config"]({}, {**config, "dxvkConfig": ""}, "42"))
        for bad in (1, "a\0b", "x" * 8193):
            with self.assertRaises(ValueError):
                MODULE["apply_config"]({}, {**config, "dxvkConfig": bad}, "42")

    def test_game_texture_filtering_overrides_default_without_leaking(self):
        default = "d3d9.samplerAnisotropy = 16"
        game = "d3d9.samplerAnisotropy = 4"
        config = {
            "version": 1,
            "shared": {},
            "games": {},
            "dxvkConfig": default,
            "dxvkConfigGames": {"42": game, "43": ""},
        }
        self.assertEqual(MODULE["apply_config"]({}, config, "42")["DXVK_CONFIG"], game)
        self.assertNotIn("DXVK_CONFIG", MODULE["apply_config"]({}, config, "43"))
        self.assertEqual(MODULE["apply_config"]({}, config, "44")["DXVK_CONFIG"], default)
        with self.assertRaises(ValueError):
            MODULE["apply_config"]({}, {**config, "dxvkConfigGames": []}, "42")

    def test_game_ids_and_probes(self):
        for prefix in ("", "/compatdata/0", "/compatdata/0-123", "/compatdata/nope", "/compatdata/4294967296"):
            self.assertIsNone(MODULE["game_id"]({"STEAM_COMPAT_DATA_PATH": prefix}))
        self.assertEqual(MODULE["game_id"]({"STEAM_COMPAT_DATA_PATH": "/a/compatdata/42/"}), "42")
        self.assertEqual(MODULE["game_id"]({"STEAM_COMPAT_DATA_PATH": "/a/compatdata/-1"}), "4294967295")

    def test_launch_reads_updates_preserves_argv_and_does_not_execute_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            config = home / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            probe = home / "fake-proton"
            probe.write_text("#!/usr/bin/python3\nimport json, os, sys\nprint(json.dumps([os.environ.get('CUSTOM'), sys.argv[1:]]))\n")
            probe.chmod(0o755)
            env = {**os.environ, "HOME": tmp, "STEAM_COMPAT_DATA_PATH": "/compatdata/42"}
            for value in ("first value", "$(touch " + str(home / "injected") + "); 'literal'=value"):
                config.write_text(json.dumps({"version": 1, "shared": {"CUSTOM": value}}))
                result = subprocess.check_output([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun", "path with spaces", "a=b"], env=env, text=True)
                self.assertEqual(json.loads(result), [value, ["waitforexitandrun", "path with spaces", "a=b"]])
            self.assertFalse((home / "injected").exists())
            for verb, prefix in (("run", "/compatdata/42"), ("waitforexitandrun", "/compatdata/0")):
                output = subprocess.check_output([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), verb], env={**env, "STEAM_COMPAT_DATA_PATH": prefix, "CUSTOM": "original"}, text=True)
                self.assertEqual(json.loads(output)[0], "original")
            config.write_text("{broken")
            result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun"], env={**env, "CUSTOM": "original"}, text=True, capture_output=True, check=True)
            self.assertEqual(json.loads(result.stdout)[0], "original")

    def test_generated_valve_and_third_party_launchers_apply_configuration(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            steam = home / "Steam"
            tools = steam / "compatibilitytools.d"
            depot = steam / "steamapps/common" / COMPAT["SOURCES"][0]
            (depot / "files/bin-arm64").mkdir(parents=True)
            extra = tools / "custom-proton"
            extra.mkdir(parents=True)
            for base in (depot, extra):
                proton = base / "proton"
                proton.write_text("#!/usr/bin/python3\nimport json, os, sys\nprint(json.dumps([os.environ['CUSTOM'], sys.argv[1:]]))\nsys.exit(7)\n")
                proton.chmod(0o755)
            (extra / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/proton %verb%" }')
            COMPAT["build_tool"](str(tools / COMPAT["TOOL"]), str(depot))
            COMPAT["adopt_extras"](str(tools))
            config = home / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            config.write_text(json.dumps({"version": 1, "shared": {"CUSTOM": "shared"}, "games": {"42": {"CUSTOM": "specific"}}}))
            wrappers = (tools / COMPAT["TOOL"] / COMPAT["LAUNCHER"], extra / COMPAT["EXTRA_WRAPPER"])
            for wrapper in wrappers:
                wrapper.write_text(wrapper.read_text().replace("/usr/local/bin/droiddeck-game-env", str(BIN / "droiddeck-game-env")))
                result = subprocess.run([str(wrapper), "waitforexitandrun", "game with spaces.exe"],
                    env={"PATH": os.defpath, "HOME": tmp, "STEAM_COMPAT_DATA_PATH": "/compatdata/42", "STEAM_COMPAT_CLIENT_INSTALL_PATH": str(steam)},
                    text=True, capture_output=True)
                self.assertEqual(result.returncode, 7, result.stderr)
                self.assertEqual(json.loads(result.stdout), ["specific", ["waitforexitandrun", "game with spaces.exe"]])

    def test_default_is_compatible_tool_and_labels_mark_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            label = home / "label"
            label.write_text("Compatible\n")
            COMPAT["display_name"].__globals__["LABEL_FILE"] = str(label)
            tools = home / "compatibilitytools.d"
            extra = tools / "proton-cachyos-11"
            extra.mkdir(parents=True)
            (extra / "proton").write_text("")
            (extra / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/proton %verb%" }')
            vdf = tools / COMPAT["TOOL"] / "compatibilitytool.vdf"
            COMPAT["build_tool"](str(tools / COMPAT["TOOL"]), None)
            self.assertIn('"display_name" "Proton ARM64 (Compatible)"', vdf.read_text())
            for source in COMPAT["SOURCES"]:
                COMPAT["build_tool"](str(tools / COMPAT["TOOL"]), str(home / "steamapps/common" / source))
                self.assertIn('"display_name" "%s ARM64 (Compatible)"' % source.replace(" (ARM64)", ""), vdf.read_text())
            protect = COMPAT["adopt_extras"](str(tools))
            self.assertIn('"display_name" "proton-cachyos-11 (Compatible)"', (extra / "compatibilitytool.vdf").read_text())
            config = home / "config.vdf"
            mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % entry for entry in (
                ("0", "GE-Proton10-25", "75"), ("42", "proton_experimental_arm64", "250"), ("43", "proton-cachyos-11", "250")))
            config.write_text('"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping)
            COMPAT["register_default"](str(config), ["44"], protect=protect)
            tokens = COMPAT["tokenize"](config.read_text())
            names = {tokens[i - 1].strip('"'): tokens[i + 2].strip('"') for i in range(1, len(tokens) - 2) if tokens[i] == "{" and tokens[i + 1] == '"name"'}
            self.assertEqual(names, {"0": COMPAT["TOOL"], "42": COMPAT["TOOL"], "43": "proton-cachyos-11", "44": COMPAT["TOOL"]})

    def test_existing_install_moves_to_new_tool_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / "label").write_text("Compatible\n")
            COMPAT["main"].__globals__["LABEL_FILE"] = str(home / "label")
            steam = home / "Steam"
            depot = steam / "steamapps/common" / COMPAT["SOURCES"][0]
            (depot / "files/bin-arm64").mkdir(parents=True)
            (steam / "steamapps/appmanifest_42.acf").write_text("")
            legacy = steam / "compatibilitytools.d" / COMPAT["LEGACY_TOOL"]
            legacy.mkdir(parents=True)
            for name in COMPAT["OWN_FILES"]:
                (legacy / name).write_text("old")
            (steam / "config").mkdir()
            config = steam / "config/config.vdf"
            mapping = "".join('"%s" { "name" "%s" "config" "" "priority" "%s" }' % (app, COMPAT["LEGACY_TOOL"], priority)
                              for app, priority in (("0", "75"), ("42", "250")))
            config.write_text('"InstallConfigStore" { "Software" { "Valve" { "Steam" { "CompatToolMapping" { %s } } } } }' % mapping)
            argv = sys.argv
            try:
                sys.argv = ["steam-compatibility", str(steam)]
                COMPAT["main"]()
                migrated = config.read_text()
                COMPAT["main"]()
            finally:
                sys.argv = argv
            self.assertFalse(legacy.exists())
            self.assertIn('"display_name" "Proton Experimental ARM64 (Compatible)"',
                          (steam / "compatibilitytools.d" / COMPAT["TOOL"] / "compatibilitytool.vdf").read_text())
            self.assertNotIn(COMPAT["LEGACY_TOOL"], migrated)
            self.assertEqual(migrated.count('"%s"' % COMPAT["TOOL"]), 2)
            self.assertEqual(config.read_text(), migrated)

    def test_launchers_and_adopted_protons_from_before_the_rename_move_over(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / "label").write_text("Compatible\n")
            COMPAT["main"].__globals__["LABEL_FILE"] = str(home / "label")
            tools = home / "compatibilitytools.d"
            own = tools / COMPAT["TOOL"]
            own.mkdir(parents=True)
            for name in ("toolmanifest.vdf", "compatibilitytool.vdf", COMPAT["LEGACY_LAUNCHER"]):
                (own / name).write_text("old")
            spare = tools / COMPAT["TOOL_11"]
            spare.mkdir()
            for name in ("toolmanifest.vdf", "compatibilitytool.vdf", COMPAT["LEGACY_LAUNCHER"]):
                (spare / name).write_text("old")
            extra = tools / "GE-Proton11-7"
            extra.mkdir()
            (extra / "proton").write_text("")
            (extra / "toolmanifest.vdf").write_text('"manifest" { "commandline" "/%s %%verb%%" }' % COMPAT["LEGACY_EXTRA_WRAPPER"])
            (extra / ("toolmanifest.vdf" + COMPAT["LEGACY_ORIGINAL_SUFFIX"])).write_text('"manifest" { "commandline" "/proton %verb%" }')
            (extra / COMPAT["LEGACY_EXTRA_WRAPPER"]).write_text("old")
            COMPAT["build_tool"](str(own), None)
            COMPAT["remove_tool"](str(spare))
            self.assertEqual(COMPAT["adopt_extras"](str(tools)), {"GE-Proton11-7": "GE-Proton11-7"})
            self.assertEqual(sorted(os.listdir(own)), sorted(COMPAT["OWN_FILES"]))
            self.assertFalse(spare.exists())
            self.assertEqual(sorted(os.listdir(extra)), sorted(["proton", "toolmanifest.vdf", "compatibilitytool.vdf",
                                                                 COMPAT["EXTRA_WRAPPER"], "toolmanifest.vdf" + COMPAT["ORIGINAL_SUFFIX"]]))
            self.assertIn("/%s %%verb%%" % COMPAT["EXTRA_WRAPPER"], (extra / "toolmanifest.vdf").read_text())
            self.assertIn('"/proton %verb%"', (extra / ("toolmanifest.vdf" + COMPAT["ORIGINAL_SUFFIX"])).read_text())
            self.assertIn('"$here/proton"', (extra / COMPAT["EXTRA_WRAPPER"]).read_text())
            self.assertIn("GE-Proton11-7", [entry["name"] for entry in COMPAT["tool_catalog"](str(home), None)])

    def test_droiddeck_proton_is_adopted_and_labelled_like_any_other_extra(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / "label").write_text("Compatible\n")
            COMPAT["display_name"].__globals__["LABEL_FILE"] = str(home / "label")
            tools = home / "compatibilitytools.d"
            extra = tools / "DroidDeck-Proton 11-7.5"
            extra.mkdir(parents=True)
            (extra / "proton").write_text("")
            (extra / "toolmanifest.vdf").write_text('"manifest" { "version" "2" "commandline" "/proton %verb%" "require_tool_appid" "4185400" }')
            (extra / "compatibilitytool.vdf").write_text(
                '"compatibilitytools" { "compat_tools" { "DroidDeck-Proton-11-7.5" { "install_path" "." '
                '"display_name" "DroidDeck-Proton 11-7.5" "from_oslist" "windows" "to_oslist" "linux" } } }')
            COMPAT["build_tool"](str(tools / COMPAT["TOOL"]), None)
            protect = COMPAT["adopt_extras"](str(tools))
            self.assertEqual(protect, {"DroidDeck-Proton-11-7.5": "DroidDeck-Proton 11-7.5"})
            compat = (extra / "compatibilitytool.vdf").read_text()
            self.assertIn('"DroidDeck-Proton-11-7.5"', compat)
            self.assertIn('"display_name" "DroidDeck-Proton 11-7.5 (Compatible)"', compat)
            self.assertNotIn("require_tool_appid", (extra / "toolmanifest.vdf").read_text())
            self.assertIn('"$here/proton"', (extra / COMPAT["EXTRA_WRAPPER"]).read_text())
            catalog = COMPAT["tool_catalog"](str(home), None)
            self.assertIn({"name": "DroidDeck-Proton-11-7.5", "display": "DroidDeck-Proton 11-7.5 (Compatible)",
                           "dir": "DroidDeck-Proton 11-7.5", "valve": False}, catalog)
            config = home / "config.vdf"
            config.write_text(config_text([("0", COMPAT["TOOL"]), ("42", "DroidDeck-Proton-11-7.5")]))
            COMPAT["register_default"](str(config), ["42", "43"], protect=protect)
            self.assertEqual(mapping_of(config.read_text()), {"0": COMPAT["TOOL"], "42": "DroidDeck-Proton-11-7.5", "43": COMPAT["TOOL"]})
            changes, _ = COMPAT["plan_mapping"]({"0": COMPAT["TOOL"], "42": "DroidDeck-Proton-11-7.5"}, ["42"], [COMPAT["TOOL"]], COMPAT["TOOL"], {})
            self.assertEqual(changes, {"42": COMPAT["TOOL"]})

    def test_both_proton_wrappers_call_environment_launcher(self):
        for script in (COMPAT["LAUNCHER_SH"], COMPAT["EXTRA_WRAPPER_SH"] % "proton"):
            subprocess.run(["bash", "-n"], input=script, text=True, check=True)
            self.assertIn('bl_run ${BL_TASKSET:-} /usr/local/bin/droiddeck-game-env', script)

    def test_wrappers_preload_the_session_library_before_the_input_shim(self):
        overlay = "/root/.local/share/Steam/ubuntu12_64/gameoverlayrenderer.so"
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("libblsession.so", "libfakeinput.so"):
                (Path(tmp) / name).write_bytes(b"")
            for script in (COMPAT["LAUNCHER_SH"], COMPAT["EXTRA_WRAPPER_SH"] % "proton"):
                start = script.index("for fake in ")
                block = script[start:script.index("\ndone\n", start) + 6].replace("/usr/local/lib/", tmp + "/")
                for inherited, expected in (
                        (None, ["libblsession.so", "libfakeinput.so"]),
                        (overlay, ["libblsession.so", "libfakeinput.so", overlay]),
                        (tmp + "/libfakeinput.so:" + overlay, ["libblsession.so", "libfakeinput.so", overlay]),
                        (tmp + "/libblsession.so:" + tmp + "/libfakeinput.so", ["libblsession.so", "libfakeinput.so"])):
                    env = {"PATH": os.defpath}
                    if inherited is not None:
                        env["LD_PRELOAD"] = inherited
                    out = subprocess.run(["bash", "-c", block + 'printf %s "$LD_PRELOAD"'], env=env,
                                         capture_output=True, text=True, check=True).stdout
                    self.assertEqual([entry.replace(tmp + "/", "") for entry in out.split(":")], expected, inherited)


class ProtonDefaultTest(unittest.TestCase):
    TOOL = COMPAT["TOOL"]
    CATALOG = [
        {"name": COMPAT["TOOL"], "display": "Proton Experimental ARM64", "dir": "Proton Experimental (ARM64)", "valve": True},
        {"name": COMPAT["TOOL_11"], "display": "Proton 11.0 ARM64", "dir": "Proton 11.0 (ARM64)", "valve": True},
        {"name": "GE-Proton11-7", "display": "GE-Proton11-7", "dir": "GE-Proton11-7", "valve": False},
    ]
    NAMES = [t["name"] for t in CATALOG]

    def plan(self, current, installed, default, auto):
        return COMPAT["plan_mapping"](current, installed, self.NAMES, default, auto)

    def test_new_installs_and_valve_picks_follow_the_default(self):
        changes, auto = self.plan({"0": self.TOOL, "10": "proton_experimental", "11": "proton_11_arm64", "12": "GE-Proton11-7", "13": "steamlinuxruntime"},
                                  ["10", "11", "12", "13", "14"], "GE-Proton11-7", {})
        self.assertEqual(changes, {"0": "GE-Proton11-7", "10": "GE-Proton11-7", "11": COMPAT["TOOL_11"], "14": "GE-Proton11-7"})
        self.assertEqual(auto, {"10": "GE-Proton11-7", "14": "GE-Proton11-7"})

    def test_windows_only_titles_still_follow_the_default(self):
        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL}, ["42"], self.NAMES, self.TOOL, {})
        self.assertEqual(changes, {"42": self.TOOL})
        self.assertEqual(auto, {"42": self.TOOL})

    def test_native_arm64_default_clears_only_our_auto_mapping(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.vdf"
            config.write_text(config_text([("0", self.TOOL), ("42", self.TOOL)]))
            (Path(tmp) / "state.json").write_text(json.dumps({
                "default": self.TOOL,
                "auto": {"42": self.TOOL},
            }))
            globals_ = COMPAT["register_default"].__globals__
            original_native_targets = globals_["native_targets"]
            globals_["native_targets"] = lambda _root, _apps: {"42": None}
            try:
                COMPAT["register_default"](
                    str(config), ["42"], catalog=self.CATALOG, directory=tmp)
                first = config.read_bytes()
                self.assertEqual(mapping_of(first.decode()), {"0": self.TOOL})
                self.assertEqual(COMPAT["tokenize"](first.decode()),
                                 COMPAT["tokenize"](config_text([("0", self.TOOL)])))
                self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["auto"], {})

                COMPAT["register_default"](
                    str(config), ["42"], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(config.read_bytes(), first)
            finally:
                globals_["native_targets"] = original_native_targets

    def test_saved_native_choice_survives_valve_pick_without_appinfo(self):
        app = "452280"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            steam = root / "Steam"
            config = steam / "config/config.vdf"
            config.parent.mkdir(parents=True)
            (steam / "steamapps").mkdir()
            (steam / "steamapps" / ("appmanifest_%s.acf" % app)).write_text("")
            config.write_text(config_text([("0", self.TOOL)]))
            (root / "state.json").write_text(json.dumps({
                "default": self.TOOL,
                "auto": {},
                "native": [app],
            }))

            globals_ = COMPAT["register_default"].__globals__
            original_native_targets = globals_["native_targets"]
            globals_["native_targets"] = lambda _root, _apps: {}
            try:
                COMPAT["register_default"](
                    str(config), [], catalog=self.CATALOG, directory=tmp, picks=[app])
                self.assertEqual(mapping_of(config.read_text()), {"0": self.TOOL})
                state = json.loads((root / "state.json").read_text())
                self.assertEqual(state["auto"], {})
                self.assertEqual(state["native"], [app])
            finally:
                globals_["native_targets"] = original_native_targets

    def test_shortcuts_scanner_keeps_native_choice_for_unrelated_app_list(self):
        app, added_shortcut = "452280", "3537741571"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            steam = root / "Steam"
            config = steam / "config/config.vdf"
            config.parent.mkdir(parents=True)
            (steam / "steamapps").mkdir()
            (steam / "steamapps" / ("appmanifest_%s.acf" % app)).write_text("")
            config.write_text(config_text([("0", self.TOOL)]))
            (root / "state.json").write_text(json.dumps({
                "default": self.TOOL,
                "auto": {},
                "native": [app],
            }))

            globals_ = COMPAT["register_default"].__globals__
            original_native_targets = globals_["native_targets"]
            globals_["native_targets"] = lambda _root, wanted: {
                app: COMPAT["NATIVE_CHOICE"]
            } if app in wanted else {}
            try:
                COMPAT["register_default"](
                    str(config), [app], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(json.loads((root / "state.json").read_text())["native"], [app])

                COMPAT["register_default"](
                    str(config), [added_shortcut], catalog=self.CATALOG, directory=tmp)
                state = json.loads((root / "state.json").read_text())
                self.assertEqual(state["native"], [app])
                self.assertEqual(state["auto"], {added_shortcut: self.TOOL})
                self.assertEqual(mapping_of(config.read_text()), {
                    "0": self.TOOL,
                    added_shortcut: self.TOOL,
                })
            finally:
                globals_["native_targets"] = original_native_targets

    def test_factorio_preserves_an_observed_native_choice_despite_ambiguous_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            steam = Path(tmp) / "Steam"
            config = steam / "config/config.vdf"
            config.parent.mkdir(parents=True)
            config.write_text(config_text([("0", self.TOOL)]))
            state = {"default": self.TOOL, "auto": {}}
            (Path(tmp) / "state.json").write_text(json.dumps(state))

            globals_ = COMPAT["native_targets"].__globals__
            original_platforms = globals_["appinfo_platforms"]
            globals_["appinfo_platforms"] = lambda _path, wanted: {
                "427520": ({"linux", "macos", "windows"}, "", "game")
            } if "427520" in wanted else {}
            try:
                native = COMPAT["native_targets"](str(steam), ["427520"])
                self.assertIs(native["427520"], COMPAT["NATIVE_CHOICE"])

                changes, auto = COMPAT["plan_mapping"](
                    {"0": self.TOOL}, ["427520"], self.NAMES, self.TOOL, {}, native)
                self.assertEqual(changes, {"427520": self.TOOL})
                self.assertEqual(auto, {"427520": self.TOOL})

                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(mapping_of(config.read_text()), {"0": self.TOOL, "427520": self.TOOL})

                state["auto"] = {"427520": self.TOOL}
                (Path(tmp) / "state.json").write_text(json.dumps(state))
                config.write_text(config_text([("0", self.TOOL)]))
                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                first = config.read_bytes()
                self.assertEqual(mapping_of(first.decode()), {"0": self.TOOL})
                self.assertEqual(COMPAT["tokenize"](first.decode()),
                                 COMPAT["tokenize"](config_text([("0", self.TOOL)])))
                self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["auto"], {})
                self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["native"], ["427520"])

                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(config.read_bytes(), first)

                config.write_text(config_text([("0", self.TOOL), ("427520", "")]))
                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                native_choice = config.read_bytes()
                self.assertEqual(mapping_of(native_choice.decode()), {"0": self.TOOL, "427520": ""})
                self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["auto"], {})
                self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["native"], ["427520"])
                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(config.read_bytes(), native_choice)

                for choice in (COMPAT["FEX_TOOL"], COMPAT["TOOL_11"], "GE-Proton11-7"):
                    config.write_text(config_text([("0", self.TOOL), ("427520", choice)]))
                    COMPAT["register_default"](
                        str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                    self.assertEqual(mapping_of(config.read_text()), {"0": self.TOOL, "427520": choice})
                    self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["native"], [])

                state = json.loads((Path(tmp) / "state.json").read_text())
                state["native"] = ["427520"]
                state["auto"] = {}
                (Path(tmp) / "state.json").write_text(json.dumps(state))
                config.write_text(config_text([("0", self.TOOL), ("427520", "proton_experimental")]))
                COMPAT["register_default"](
                    str(config), ["427520"], catalog=self.CATALOG, directory=tmp)
                self.assertEqual(mapping_of(config.read_text()), {"0": self.TOOL, "427520": self.TOOL})
                saved = json.loads((Path(tmp) / "state.json").read_text())
                self.assertEqual(saved["native"], [])
                self.assertEqual(saved["auto"], {"427520": self.TOOL})
            finally:
                globals_["appinfo_platforms"] = original_platforms

    def test_native_arm64_preserves_a_proton_mapping_without_managed_state(self):
        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL, "42": self.TOOL}, ["42"], self.NAMES, self.TOOL,
            {"42": self.TOOL}, {"42": None}, managed=())
        self.assertEqual(changes, {})
        self.assertEqual(auto, {})

    def test_native_arm64_keeps_a_chosen_valve_proton_through_its_wrapper(self):
        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL, "42": "proton_experimental"}, ["42"], self.NAMES,
            self.TOOL, {}, {"42": None})
        self.assertEqual(changes, {"42": self.TOOL})
        self.assertEqual(auto, {})

        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL, "42": self.TOOL}, ["42"], self.NAMES,
            self.TOOL, {}, {"42": None})
        self.assertEqual(changes, {})
        self.assertEqual(auto, {})

    def test_native_arm64_keeps_explicit_fex_and_proton_profiles(self):
        fex = COMPAT["FEX_TOOL"]
        current = {
            "0": self.TOOL,
            "42": COMPAT["TOOL_11"],
            "43": "GE-Proton11-7",
            "44": "steamlinuxruntime_arm64",
            "45": fex,
            "46": "",
        }
        changes, auto = COMPAT["plan_mapping"](
            current, ["42", "43", "44", "45", "46"], self.NAMES, self.TOOL, {},
            {app: None for app in ("42", "43", "44", "45", "46")},
            overrides={"46": "GE-Proton11-7"},
        )
        self.assertEqual(changes, {"44": None, "46": "GE-Proton11-7"})
        self.assertEqual(auto, {})

    def test_titles_following_the_default_move_with_it_and_picks_stay(self):
        current = {"0": self.TOOL, "10": self.TOOL, "11": "GE-Proton11-7", "12": self.TOOL}
        auto = {"10": self.TOOL, "11": self.TOOL, "12": self.TOOL}
        changes, auto = self.plan(current, ["10", "11", "12"], COMPAT["TOOL_11"], auto)
        self.assertEqual(changes, {"0": COMPAT["TOOL_11"], "10": COMPAT["TOOL_11"], "12": COMPAT["TOOL_11"]})
        self.assertEqual(auto, {"10": COMPAT["TOOL_11"], "12": COMPAT["TOOL_11"]})

    def test_a_removed_tool_falls_back_to_the_default(self):
        changes, auto = self.plan({"0": self.TOOL, "10": "GE-Proton10-1", "11": "droiddeck-proton-12-arm64"}, ["10", "11"], self.TOOL, {"10": "GE-Proton10-1"})
        self.assertEqual(changes, {"10": self.TOOL, "11": self.TOOL})
        self.assertEqual(auto, {"10": self.TOOL, "11": self.TOOL})

    def test_valve_arm64_protons_move_to_droiddeck_builds_and_stay_there(self):
        current = {"0": "GE-Proton11-7", "10": "proton_11-arm64", "11": "proton-experimental-arm64",
                   "12": "proton_11_arm64", "13": "proton_experimental_arm64", "14": "proton-stable",
                   "15": "GE-Proton11-7", "16": COMPAT["TOOL_11"], "17": self.TOOL}
        changes, auto = self.plan(current, [str(a) for a in range(10, 18)], "GE-Proton11-7", {})
        self.assertEqual(changes, {"10": COMPAT["TOOL_11"], "11": self.TOOL, "12": COMPAT["TOOL_11"],
                                   "13": self.TOOL, "14": "GE-Proton11-7"})
        self.assertEqual(auto, {"14": "GE-Proton11-7"})
        current.update(changes)
        changes, auto = self.plan(current, [str(a) for a in range(10, 18)], COMPAT["TOOL_11"], auto)
        self.assertEqual(changes, {"0": COMPAT["TOOL_11"], "14": COMPAT["TOOL_11"]})

    def test_valve_proton_11_arm64_without_droiddeck_build_falls_back_to_the_default(self):
        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL, "10": "proton_11-arm64"}, ["10"], [self.TOOL], self.TOOL, {})
        self.assertEqual(changes, {"10": self.TOOL})
        self.assertEqual(auto, {"10": self.TOOL})

    def test_a_linux_runtime_becomes_the_titles_own_linux_launch(self):
        fex = COMPAT["FEX_TOOL"]
        native = {"10": None, "11": fex, "12": COMPAT["NATIVE_CHOICE"]}
        current = {"0": self.TOOL, "10": "steamlinuxruntime_arm64", "11": "steamlinuxruntime_sniper",
                   "12": "SteamLinuxRuntime", "13": "steamlinuxruntime"}
        changes, auto = COMPAT["plan_mapping"](current, ["10", "11", "12", "13"], self.NAMES, self.TOOL, {}, native)
        # 13 has no appinfo to go by: what it needs is unknown, so its pick stays.
        self.assertEqual(changes, {"10": None, "11": fex, "12": fex})
        self.assertEqual(auto, {})
        current.update({"10": "", "11": fex, "12": fex})
        changes, _ = COMPAT["plan_mapping"](current, ["10", "11", "12", "13"], self.NAMES, self.TOOL, auto, native)
        self.assertEqual(changes, {})

    def test_config_vdf_maps_a_valve_arm64_proton_to_droiddeck_without_the_live_client(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.vdf"
            config.write_text(config_text([("0", self.TOOL), ("42", "proton_11-arm64"), ("43", "GE-Proton11-7")]))
            globals_ = COMPAT["register_default"].__globals__
            original_native_targets = globals_["native_targets"]
            globals_["native_targets"] = lambda _root, _apps: {}
            try:
                COMPAT["register_default"](str(config), ["42", "43"], catalog=self.CATALOG, directory=tmp)
            finally:
                globals_["native_targets"] = original_native_targets
            self.assertEqual(mapping_of(config.read_text()), {"0": self.TOOL, "42": COMPAT["TOOL_11"], "43": "GE-Proton11-7"})
            state = json.loads((Path(tmp) / "state.json").read_text())
            self.assertEqual(state["auto"], {})

    def test_native_choice_gives_way_where_the_client_would_start_a_valve_proton(self):
        native = {"10": COMPAT["NATIVE_CHOICE"], "11": COMPAT["NATIVE_CHOICE"], "12": COMPAT["NATIVE_CHOICE"]}
        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL}, ["10", "11", "12"], self.NAMES, self.TOOL, {}, native,
            native_choices=["10", "11", "12"], valve={"10": "proton-stable", "11": "native"})
        self.assertEqual(changes, {"10": self.TOOL})
        self.assertIs(auto["11"], COMPAT["NATIVE_CHOICE"])
        self.assertIs(auto["12"], COMPAT["NATIVE_CHOICE"])
        self.assertEqual(auto["10"], self.TOOL)

    def test_uninstalled_titles_are_forgotten(self):
        changes, auto = self.plan({"0": self.TOOL}, [], self.TOOL, {"10": self.TOOL})
        self.assertEqual((changes, auto), ({}, {}))

    def test_per_game_proton_override_and_inherit_follow_steam_mappings(self):
        current = {"0": self.TOOL, "10": self.TOOL, "11": "GE-Proton11-7", "12": self.TOOL}
        overrides = {"10": "GE-Proton11-7", "11": None}
        changes, auto = COMPAT["plan_mapping"](
            current, ["10", "11", "12"], self.NAMES, COMPAT["TOOL_11"],
            {"10": self.TOOL, "12": self.TOOL}, overrides=overrides)
        self.assertEqual(changes, {"0": COMPAT["TOOL_11"], "10": "GE-Proton11-7", "11": COMPAT["TOOL_11"], "12": COMPAT["TOOL_11"]})
        self.assertEqual(auto, {"11": COMPAT["TOOL_11"], "12": COMPAT["TOOL_11"]})

    def test_a_known_games_suggestion_never_beats_a_pick_in_the_clients_menu_or_the_app(self):
        current = {"0": self.TOOL, "10": self.TOOL, "11": "GE-Proton11-7", "12": self.TOOL}
        suggested = {"10": COMPAT["TOOL_11"], "11": COMPAT["TOOL_11"], "12": COMPAT["TOOL_11"], "13": COMPAT["TOOL_11"]}
        changes, auto = COMPAT["plan_mapping"](
            current, ["10", "11", "12", "13"], self.NAMES, self.TOOL, {"10": self.TOOL},
            overrides={"12": "GE-Proton11-7"}, suggested=suggested)
        # 10 followed the default and 13 is new: both take the suggestion. 11 is the owner's pick in
        # the client's menu and 12 the owner's pick in the app: both stay.
        self.assertEqual(changes, {"10": COMPAT["TOOL_11"], "12": "GE-Proton11-7", "13": COMPAT["TOOL_11"]})
        self.assertEqual(auto, {"10": COMPAT["TOOL_11"], "13": COMPAT["TOOL_11"]})
        # The owner then picks another tool in the client's menu: it stays.
        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL, "10": "GE-Proton11-7", "13": COMPAT["TOOL_11"]}, ["10", "13"], self.NAMES, self.TOOL,
            auto, suggested=suggested)
        self.assertEqual(changes, {})
        self.assertEqual(auto, {"13": COMPAT["TOOL_11"]})
        # The suggestion is withdrawn: the title it moved goes back to the default.
        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL, "13": COMPAT["TOOL_11"]}, ["13"], self.NAMES, self.TOOL, auto)
        self.assertEqual(changes, {"13": self.TOOL})

    def test_a_suggested_tool_that_is_not_there_leaves_the_default(self):
        changes, auto = COMPAT["plan_mapping"](
            {"0": self.TOOL}, ["10"], [self.TOOL], self.TOOL, {}, suggested={"10": COMPAT["TOOL_11"]})
        self.assertEqual(changes, {"10": self.TOOL})

    def test_known_games_file_resolves_to_tools(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "known-games.json").write_text(json.dumps({"version": 1, "games": {
                "10": {"valve": True, "dir": "Proton 11.0 (ARM64)"}, "11": {"valve": False, "dir": "missing"}, "12": None}}))
            self.assertEqual(COMPAT["suggested_games"](self.CATALOG, directory=tmp), {"10": COMPAT["TOOL_11"]})
            self.assertEqual(COMPAT["suggested_games"](self.CATALOG, [self.TOOL], directory=tmp), {})
            Path(tmp, "known-games.json").write_text(json.dumps({"version": 2, "games": {}}))
            self.assertEqual(COMPAT["suggested_games"](self.CATALOG, directory=tmp), {})

    def test_per_game_requests_resolve_identity_and_defer_a_new_live_tool(self):
        requests = {
            "10": {"valve": False, "dir": "GE-Proton11-7"},
            "11": None,
            "12": {"valve": True, "dir": "Proton 11.0 (ARM64)"},
            "0": None,
        }
        self.assertEqual(
            COMPAT["requested_games"](requests, self.CATALOG),
            {"10": "GE-Proton11-7", "11": None, "12": COMPAT["TOOL_11"]},
        )
        self.assertEqual(
            COMPAT["requested_games"](requests, self.CATALOG, [self.TOOL, COMPAT["TOOL_11"]]),
            {"10": COMPAT["DEFERRED_GAME_TOOL"], "11": None, "12": COMPAT["TOOL_11"]},
        )

    def test_missing_per_game_proton_is_preserved_during_default_registration(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.vdf"
            config.write_text(config_text([
                ("0", self.TOOL),
                ("10", "GE-Proton9-1"),
                ("11", self.TOOL),
            ]))
            (Path(tmp) / "games.json").write_text(json.dumps({
                "version": 1,
                "games": {
                    "10": {"valve": False, "dir": "GE-Proton9-1"},
                },
            }))

            COMPAT["register_default"](
                str(config), ["10", "11"], default=COMPAT["TOOL_11"],
                catalog=self.CATALOG, directory=tmp,
            )

            self.assertEqual(mapping_of(config.read_text()), {
                "0": COMPAT["TOOL_11"],
                "10": "GE-Proton9-1",
                "11": COMPAT["TOOL_11"],
            })

    def test_the_last_side_to_change_wins(self):
        state = {"default": self.TOOL, "applied": 5}
        default, state = COMPAT["choose_default"]("GE-Proton11-7", state, {"seq": 5, "dir": "Proton 11.0 (ARM64)", "valve": True}, self.CATALOG, now=100)
        self.assertEqual((default, state["source"], state["seq"]), ("GE-Proton11-7", "steam", 100))
        default, state = COMPAT["choose_default"]("GE-Proton11-7", state, {"seq": 6, "dir": "Proton 11.0 (ARM64)", "valve": True}, self.CATALOG, now=200)
        self.assertEqual((default, state["source"], state["seq"], state["applied"]), (COMPAT["TOOL_11"], "app", 200, 6))
        default, state = COMPAT["choose_default"]("GE-Proton11-7", state, {"seq": 6}, self.CATALOG, adopt_steam=False, now=300)
        self.assertEqual((default, state["seq"]), (COMPAT["TOOL_11"], 200))
        default, state = COMPAT["choose_default"]("proton_experimental", state, {"seq": 6}, self.CATALOG, now=300)
        self.assertEqual(default, COMPAT["TOOL_11"])

    def test_a_request_for_a_missing_tool_is_dropped(self):
        default, state = COMPAT["choose_default"](self.TOOL, {"default": self.TOOL}, {"seq": 9, "dir": "GE-Proton9-1", "valve": False}, self.CATALOG, now=1)
        self.assertEqual((default, state["applied"]), (self.TOOL, 9))
        default, state = COMPAT["choose_default"]("GE-Proton11-7", state, {"seq": 9, "dir": "GE-Proton9-1", "valve": False}, self.CATALOG, now=2)
        self.assertEqual(default, "GE-Proton11-7")

    def test_settle_before_the_client_starts(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / "config.vdf"
            config.write_text(config_text([("0", self.TOOL), ("10", "proton_experimental"), ("11", "GE-Proton11-7")]))
            (Path(tmp) / "request.json").write_text(json.dumps({"seq": 3, "dir": "Proton 11.0 (ARM64)", "valve": True}))
            default = COMPAT["register_default"](str(config), ["10", "11", "12"], catalog=self.CATALOG, directory=tmp, settle=True)
            self.assertEqual(default, COMPAT["TOOL_11"])
            self.assertEqual(mapping_of(config.read_text()), {"0": COMPAT["TOOL_11"], "10": COMPAT["TOOL_11"], "11": "GE-Proton11-7", "12": COMPAT["TOOL_11"]})
            state = json.loads((Path(tmp) / "state.json").read_text())
            self.assertEqual((state["applied"], state["source"], state["tools"]), (3, "app", self.CATALOG))
            config.write_text(config_text([("0", "GE-Proton11-7"), ("10", COMPAT["TOOL_11"]), ("11", "GE-Proton11-7"), ("12", COMPAT["TOOL_11"])]))
            default = COMPAT["register_default"](str(config), ["10", "11", "12"], catalog=self.CATALOG, directory=tmp, settle=True)
            self.assertEqual(default, "GE-Proton11-7")
            self.assertEqual(mapping_of(config.read_text())["10"], "GE-Proton11-7")
            self.assertEqual(json.loads((Path(tmp) / "state.json").read_text())["source"], "steam")
            config.write_text(config_text([("0", "proton_experimental")]))
            default = COMPAT["register_default"](str(config), ["10"], catalog=self.CATALOG, directory=tmp)
            self.assertEqual(mapping_of(config.read_text()), {"0": "GE-Proton11-7", "10": "GE-Proton11-7"})

    def test_the_shortcut_writer_keeps_the_chosen_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            steam = Path(tmp) / "Steam"
            for name in COMPAT["SOURCES"]:
                (steam / "steamapps/common" / name / "files/bin-arm64").mkdir(parents=True)
            (steam / "config").mkdir()
            config = steam / "config/config.vdf"
            config.write_text(config_text([("0", COMPAT["TOOL_11"]), ("10", COMPAT["TOOL_11"]), ("3044416433", COMPAT["TOOL_11"])]))
            (Path(tmp) / "state.json").write_text(json.dumps({"default": COMPAT["TOOL_11"], "auto": {"10": COMPAT["TOOL_11"], "3044416433": COMPAT["TOOL_11"]}}))
            COMPAT["register_default"](str(config), apps=["3044416433", "3212965118"], protect={}, directory=tmp)
            self.assertEqual(mapping_of(config.read_text()), {"0": COMPAT["TOOL_11"], "10": COMPAT["TOOL_11"], "3044416433": COMPAT["TOOL_11"], "3212965118": COMPAT["TOOL_11"]})

    def test_each_valve_depot_gets_its_own_tool(self):
        with tempfile.TemporaryDirectory() as tmp:
            steam = Path(tmp) / "Steam"
            for name in COMPAT["SOURCES"]:
                (steam / "steamapps/common" / name / "files/bin-arm64").mkdir(parents=True)
            self.assertEqual(COMPAT["find_source"](str(steam), COMPAT["OWN_TOOLS"][1][1]), str(steam / "steamapps/common/Proton 11.0 (ARM64)"))
            self.assertEqual(COMPAT["find_source"](str(steam)), str(steam / "steamapps/common/Proton Experimental (ARM64)"))
            self.assertEqual([(t["name"], t["dir"]) for t in COMPAT["tool_catalog"](str(steam), {})],
                             [(COMPAT["TOOL"], "Proton Experimental (ARM64)"), (COMPAT["TOOL_11"], "Proton 11.0 (ARM64)")])
            for name, sources in COMPAT["OWN_TOOLS"]:
                COMPAT["build_tool"](str(steam / "compatibilitytools.d" / name), COMPAT["find_source"](str(steam), sources), sources, name)
            self.assertEqual(COMPAT["adopt_extras"](str(steam / "compatibilitytools.d")), {})
            self.assertEqual(sorted(os.listdir(steam / "compatibilitytools.d" / COMPAT["TOOL_11"])), sorted(COMPAT["OWN_FILES"]))
            launcher = COMPAT["launcher_sh"](COMPAT["OWN_TOOLS"][1][1])
            self.assertIn('for name in "Proton 11.0 (ARM64)"; do', launcher)
            self.assertIn("${major%%.*}", launcher)



class DirectAudioPrefixTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.tool = base / "tool"
        (self.tool / "files/bin-arm64").mkdir(parents=True)
        wine = self.tool / "files/bin-arm64/wine"
        wine.write_text("#!/bin/sh\necho wine-11.0-4c8f2e1 '(Staging)'\n")
        wine.chmod(0o755)
        (self.tool / "files/lib/wine/aarch64-unix").mkdir(parents=True)
        self.winepulse = self.tool / "files/lib/wine/aarch64-unix/winepulse.so"
        self.winepulse.write_bytes(b"\x7fELF classic wine 11 pulse driver")
        self.sets = base / "directaudio"
        for set_name in ("linux-wine11", "linux-wine11-systhread"):
            for arch in ("aarch64-windows", "i386-windows", "aarch64-unix"):
                (self.sets / set_name / "lib/wine" / arch).mkdir(parents=True)
            (self.sets / set_name / "lib/wine/aarch64-windows/winedirectaudio.drv").write_bytes(b"arm64x-" + set_name.encode())
            (self.sets / set_name / "lib/wine/i386-windows/winedirectaudio.drv").write_bytes(b"i386")
        self.audio = self.sets / "linux-wine11"
        self.compat = base / "compat"
        self.windows = self.compat / "pfx/drive_c/windows"
        for folder in ("system32", "syswow64"):
            (self.windows / folder).mkdir(parents=True)
        self.reg = self.compat / "pfx/user.reg"

    def run_setup(self):
        script = COMPAT["BL_DIRECTAUDIO_SETUP"] + '\nbl_directaudio "$1"\necho "WINEDLLPATH=$WINEDLLPATH"\necho "WINE_AUDIO_DRIVER=${WINE_AUDIO_DRIVER:-}"\n'
        env = dict(os.environ, BL_DIRECTAUDIO=str(self.sets), STEAM_COMPAT_DATA_PATH=str(self.compat))
        env.pop("WINEDLLPATH", None)
        env.pop("WINE_AUDIO_DRIVER", None)
        return subprocess.run(["bash", "-c", script, "bash", str(self.tool)], env=env, capture_output=True, text=True, check=True)

    def audio_values(self):
        found, current = [], None
        for line in self.reg.read_text().splitlines():
            if line.startswith("["):
                current = line.rsplit("] ", 1)[0] + "]"
            elif line.startswith('"Audio"='):
                found.append((current, line))
        return found

    def link(self, folder):
        path = self.windows / folder / "winedirectaudio.drv"
        return os.readlink(path) if path.is_symlink() else None

    def test_the_driver_is_placed_and_selected_the_way_wine_reads_it(self):
        self.reg.write_text("WINE REGISTRY Version 2\n;; All keys relative to \\\\User\\\\S-1-5-21-0-0-0-1000\n\n#arch=win64\n")
        result = self.run_setup()
        self.assertIn("DirectAudio selected", result.stderr)
        self.assertIn("WINEDLLPATH=%s/lib/wine" % self.audio, result.stdout)
        self.assertIn("WINE_AUDIO_DRIVER=directaudio,pulse\n", result.stdout)
        self.assertEqual(self.link("system32"), str(self.audio / "lib/wine/aarch64-windows/winedirectaudio.drv"))
        self.assertEqual(self.link("syswow64"), str(self.audio / "lib/wine/i386-windows/winedirectaudio.drv"))
        self.assertEqual((self.windows / "system32/winedirectaudio.drv").read_bytes(), b"arm64x-linux-wine11")
        self.assertEqual(self.audio_values(), [("[Software\\\\Wine\\\\Drivers]", '"Audio"="directaudio,pulse"')])
        before = self.reg.read_text()
        self.assertNotIn("DirectAudio selected", self.run_setup().stderr)
        self.assertEqual(self.reg.read_text(), before)
        self.assertEqual(sorted(os.listdir(self.windows / "system32")), ["winedirectaudio.drv"])

    def test_without_a_prefix_it_waits_for_the_next_launch(self):
        result = self.run_setup()
        self.assertIn("takes effect the next time this game starts", result.stderr)
        self.assertEqual(self.link("system32"), None)

    def test_the_system_thread_set_is_picked_from_winepulse_not_the_version(self):
        self.reg.write_text("WINE REGISTRY Version 2\n\n#arch=win64\n")
        self.winepulse.write_bytes(b"\x7fELF cachyos pulse driver PsCreateSystemThread NtSetInformationThread")
        result = self.run_setup()
        systhread = self.sets / "linux-wine11-systhread"
        self.assertIn("DirectAudio ready (Wine 11, linux-wine11-systhread)", result.stderr)
        self.assertIn("WINEDLLPATH=%s/lib/wine" % systhread, result.stdout)
        self.assertEqual(self.link("system32"), str(systhread / "lib/wine/aarch64-windows/winedirectaudio.drv"))
        self.assertEqual((self.windows / "system32/winedirectaudio.drv").read_bytes(), b"arm64x-linux-wine11-systhread")

    def test_no_set_for_the_interface_leaves_protons_own_audio(self):
        self.reg.write_text("WINE REGISTRY Version 2\n\n#arch=win64\n")
        self.winepulse.write_bytes(b"\x7fELF PsCreateSystemThread")
        shutil.rmtree(self.sets / "linux-wine11-systhread")
        result = self.run_setup()
        self.assertIn("no driver set for the linux-wine11-systhread interface", result.stderr)
        self.assertIn("WINEDLLPATH=\n", result.stdout)
        self.assertIn("WINE_AUDIO_DRIVER=\n", result.stdout)
        self.assertFalse((self.windows / "system32/winedirectaudio.drv").exists())

    def test_older_selections_are_upgraded(self):
        self.reg.write_text('WINE REGISTRY Version 2\n\n[SoftwareWineDrivers] 1790995967\n#time=1dd52e24550e980\n"Audio"="directaudio"\n'
                            '\n[Software\\\\Wine\\\\Drivers] 1790995986\n#time=1dd52e250b31ec4\n"Audio"="directaudio"\n')
        self.assertIn("DirectAudio selected", self.run_setup().stderr)
        self.assertEqual(self.audio_values(), [("[SoftwareWineDrivers]", '"Audio"="directaudio"'),
                                               ("[Software\\\\Wine\\\\Drivers]", '"Audio"="directaudio"'),
                                               ("[Software\\\\Wine\\\\Drivers]", '"Audio"="directaudio,pulse"')])
        self.assertNotIn("DirectAudio selected", self.run_setup().stderr)

    def test_a_copied_driver_is_replaced_by_the_staged_one(self):
        self.reg.write_text("WINE REGISTRY Version 2\n")
        (self.windows / "system32/winedirectaudio.drv").write_bytes(b"stale copy")
        self.run_setup()
        self.assertEqual(self.link("system32"), str(self.audio / "lib/wine/aarch64-windows/winedirectaudio.drv"))

    def test_nothing_is_selected_without_the_driver_in_the_prefix(self):
        self.reg.write_text("WINE REGISTRY Version 2\n")
        shutil.rmtree(self.windows)
        self.assertIn("could not be placed", self.run_setup().stderr)
        self.assertEqual(self.audio_values(), [])
        (self.windows / "system32").mkdir(parents=True)
        (self.audio / "lib/wine/aarch64-windows/winedirectaudio.drv").unlink()
        self.assertIn("could not be placed", self.run_setup().stderr)
        self.assertEqual(self.audio_values(), [])

    def test_a_new_prefix_waits_for_the_next_launch(self):
        shutil.rmtree(self.compat / "pfx")
        self.assertIn("no prefix yet", self.run_setup().stderr)
        self.assertFalse(self.reg.exists())

    def test_another_wine_major_is_left_alone(self):
        (self.tool / "files/bin-arm64/wine").write_text("#!/bin/sh\necho wine-10.0\n")
        self.reg.write_text("WINE REGISTRY Version 2\n")
        result = self.run_setup()
        self.assertIn("left off", result.stderr)
        self.assertIn("WINEDLLPATH=\n", result.stdout)
        self.assertEqual(self.audio_values(), [])
        self.assertIsNone(self.link("system32"))


if __name__ == "__main__":
    unittest.main()


class StartWithoutSteamTest(unittest.TestCase):
    def test_start_without_steam_uses_umu_or_runinprefix_and_only_when_asked(self):
        with tempfile.TemporaryDirectory() as tmp:
            ge = Path(tmp) / "ge/proton"
            ge.parent.mkdir()
            ge.write_text('if "UMU_ID" in os.environ:\n    argv = [wine, "c:\\\\windows\\\\system32\\\\umu.exe"]\n')
            valve = Path(tmp) / "valve/proton"
            valve.parent.mkdir()
            valve.write_text('argv = [wine, "c:\\\\windows\\\\system32\\\\steam.exe"]\n')
            start = MODULE["start_without_steam"]
            game = [str(ge), "waitforexitandrun", "/games/Game.exe", "-windowed"]
            self.assertEqual(start(game, {}, "3384071994"), (game, {}))
            on = {"DROIDDECK_NO_STEAM": "1"}
            self.assertEqual(start(game, on, "3384071994"), (game, dict(on, UMU_ID="0")))
            self.assertEqual(start(game, on, "570940")[1]["UMU_ID"], "570940")
            self.assertEqual(start(game, dict(on, UMU_ID="umu-1"), "570940")[1]["UMU_ID"], "umu-1")
            plain = [str(valve)] + game[1:]
            self.assertEqual(start(plain, on, "570940"), ([str(valve), "runinprefix", "/games/Game.exe", "-windowed"], on))
            for other in ([str(ge), "run", "/x.exe"], [str(ge), "waitforexitandrun"], [str(valve), "getcompatpath", "/x"]):
                self.assertEqual(start(other, on, "570940"), (other, on))


class ForceSsbsTest(unittest.TestCase):
    def test_libssbs_is_preloaded_only_when_asked_and_only_into_arm64_proton(self):
        with tempfile.TemporaryDirectory() as tmp:
            arm = Path(tmp) / "arm"
            (arm / "files/lib/wine/aarch64-unix").mkdir(parents=True)
            x86 = Path(tmp) / "x86"
            (x86 / "files/lib/wine/x86_64-unix").mkdir(parents=True)
            lib = Path(tmp) / "libssbs.so"
            lib.write_bytes(b"\x7fELF")
            g = MODULE["force_ssbs"].__globals__
            old = g["SSBS_LIB"]
            g["SSBS_LIB"] = str(lib)
            try:
                env = {"LD_PRELOAD": "/usr/local/lib/libblsession.so"}
                self.assertEqual(MODULE["force_ssbs"]([str(arm / "proton"), "waitforexitandrun"], env), env)
                on = dict(env, DROIDDECK_FORCE_SSBS="1")
                got = MODULE["force_ssbs"]([str(arm / "proton"), "waitforexitandrun"], on)
                self.assertEqual(got["LD_PRELOAD"], "/usr/local/lib/libblsession.so:" + str(lib))
                self.assertEqual(MODULE["force_ssbs"]([str(arm / "proton")], got)["LD_PRELOAD"], got["LD_PRELOAD"])
                self.assertEqual(MODULE["force_ssbs"]([str(x86 / "proton")], on), on)
            finally:
                g["SSBS_LIB"] = old
