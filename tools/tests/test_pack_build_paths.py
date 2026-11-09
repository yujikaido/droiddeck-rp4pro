"""The audio and PipeWire packs the APK carries name no folder or user of the machine that built them.

v0.5.5.2/test12 shipped assets/pulseaudio.tzst with the builder's TMPDIR and checkout paths inside two PulseAudio
modules (pa_assert's __FILE__) and both packs with the builder's user name in every tar header.
"""
import io
import os
import re
import subprocess
import tarfile
import tempfile
import textwrap
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
SINK_BUILD = TOOLS / 'aaudio-sink/build.sh'
ASSEMBLE = TOOLS / 'pipewire/assemble.sh'
BUILD_LOCAL = TOOLS / 'build_local.sh'
RECIPE = TOOLS / 'components/recipes/audio.sh'

# A stand-in for the NDK's clang: the "module" it writes holds the source and header paths the way __FILE__ would,
# after the -ffile-prefix-map options it was given (first match, as clang applies them).
FAKE_CLANG = textwrap.dedent('''\
    #!/usr/bin/env python3
    import sys
    args = sys.argv[1:]
    maps = [a.split('=', 1)[1].split('=', 1) for a in args if a.startswith('-ffile-prefix-map=')]
    def mapped(path):
        for old, new in maps:
            if path == old or path.startswith(old.rstrip('/') + '/'):
                return new + path[len(old):]
        return path
    out = args[args.index('-o') + 1]
    sources = [a for a in args if a.endswith('.c')]
    includes = [a[2:] for a in args if a.startswith('-I')]
    names = [mapped(p) for p in sources] + [mapped(i + '/pulsecore/sink.h') for i in includes]
    with open(out, 'wb') as f:
        f.write(b'\\x7fELF' + b'\\0'.join(n.encode() for n in names))
    ''')
FAKE_NM = '#!/bin/sh\nfor s in pa__init pa__done pa__get_author pa__get_description pa__get_usage pa__get_version pa__load_once; do echo "0000 T $s"; done\n'
FAKE_READELF = '#!/bin/sh\necho " 0x0000000000000001 (NEEDED) Shared library: [libpulsecore-13.0.so]"\n'


class SinkModuleBuildPathsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.ndk = root / 'ndk'
        bin_dir = self.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
        bin_dir.mkdir(parents=True)
        for name, body in (('aarch64-linux-android26-clang', FAKE_CLANG), ('aarch64-linux-android28-clang', FAKE_CLANG),
                           ('llvm-strip', '#!/bin/sh\nexit 0\n'), ('llvm-nm', FAKE_NM), ('llvm-readelf', FAKE_READELF)):
            (bin_dir / name).write_text(body)
            (bin_dir / name).chmod(0o755)
        self.pa = root / 'builder-home/tmp/droiddeck-build.XYZ/pulseaudio-13.0'
        (self.pa / 'src/pulse').mkdir(parents=True)
        (self.pa / 'src/pulse/version.h.in').write_text('#define PA_MAJOR @PA_MAJOR@\n')
        self.out = root / 'out'
        self.env = dict(os.environ, NDK=str(self.ndk), TMPDIR=str(root / 'builder-home/tmp'), HOME=str(root / 'builder-home'))
        (root / 'builder-home/tmp').mkdir(parents=True, exist_ok=True)

    def build(self, env=None):
        return subprocess.run(['bash', str(SINK_BUILD), str(self.pa), str(self.out)], env=env or self.env,
                              capture_output=True, text=True, timeout=120)

    @unittest.skipUnless(os.uname().sysname == 'Linux' and os.uname().machine == 'x86_64', 'fake NDK is linux-x86_64')
    def test_modules_name_no_build_folder(self):
        result = self.build()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for module in ('module-aaudio-sink.so', 'module-directaudio-native-sink.so'):
            data = (self.out / module).read_bytes()
            for folder in (str(self.pa), str(REPO), self.env['HOME'], self.env['TMPDIR']):
                self.assertNotIn(folder.encode(), data, '%s names %s' % (module, folder))
            self.assertIn(b'/build/pulseaudio-13.0/src/pulsecore/sink.h', data)
            self.assertIn(b'/build/droiddeck/tools/aaudio-sink/', data)

    @unittest.skipUnless(os.uname().sysname == 'Linux' and os.uname().machine == 'x86_64', 'fake NDK is linux-x86_64')
    def test_a_module_that_still_names_a_build_folder_stops_the_build(self):
        # a compiler that ignores the maps (or a new path they miss) must not reach the APK silently
        clang = self.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang'
        clang.write_text(FAKE_CLANG.replace("a.startswith('-ffile-prefix-map=')", "False"))
        result = self.build()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('names the build folder', result.stdout + result.stderr)


class PackTarHeadersTest(unittest.TestCase):
    def test_pipewire_pack_headers_carry_no_user_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / 'pkgs'
            files = ['usr/bin/pipewire', 'usr/bin/wireplumber', 'usr/bin/wpctl', 'usr/lib/libpipewire-0.3.so.0',
                     'usr/lib/libwireplumber-0.5.so.0', 'usr/lib/pipewire-0.3/libpipewire-module-rt.so',
                     'usr/lib/pipewire-0.3/libpipewire-module-jack-tunnel.so', 'usr/lib/spa-0.2/libspa.so',
                     'usr/lib/wireplumber-0.5/libwireplumber-module-x.so', 'usr/share/pipewire/pipewire.conf',
                     'usr/share/pipewire/client.conf', 'usr/share/wireplumber/wireplumber.conf']
            files += ['usr/lib/spa-0.2/%s/libspa-%s.so' % (p, p) for p in ('support', 'control', 'videoconvert', 'v4l2')]
            files += ['usr/share/licenses/%s/LICENSE' % p for p in ('pipewire', 'libpipewire', 'wireplumber', 'libwireplumber')]
            for name in files:
                (src / name).parent.mkdir(parents=True, exist_ok=True)
                (src / name).write_text(name)
            out = Path(tmp) / 'droiddeck-pipewire.tzst'
            subprocess.run(['bash', str(ASSEMBLE), str(src), str(out)], check=True, timeout=120)
            raw = subprocess.run(['zstd', '-dc', str(out)], check=True, capture_output=True).stdout
            with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
                members = archive.getmembers()
            self.assertTrue(members)
            for member in members:
                self.assertEqual((member.uname, member.gname), ('', ''), member.name)
                self.assertEqual((member.uid, member.gid), (os.getuid(), os.getgid()), member.name)
            self.assertNotIn('./lib/pipewire-0.3/libpipewire-module-jack-tunnel.so', [m.name for m in members])

    def test_audio_bundle_repacks_without_user_names(self):
        for script in (BUILD_LOCAL, RECIPE):
            lines = [l for l in script.read_text().splitlines() if 'pulseaudio.tzst' in l and re.search(r'\btar\b[^|]*\s-c', l)]
            self.assertTrue(lines, script)
            for line in lines:
                self.assertIn('--numeric-owner', line, '%s: %s' % (script.name, line))


if __name__ == '__main__':
    unittest.main()
