import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]


class WinFgScratchTest(unittest.TestCase):
    """Win-FG's per-size descriptor sets on a pool held to its sizes."""

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CXX', 'c++'))
        if compiler is None:
            raise unittest.SkipTest('A host C++ compiler is required for the Win-FG scratch test')
        candidates = [Path('/usr/include/vulkan'), Path('/opt/homebrew/include/vulkan')]
        for name in ('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'NDK_HOME'):
            if os.environ.get(name):
                candidates.extend(Path(os.environ[name]).glob('toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan'))
        for sdk in {Path(os.environ.get('ANDROID_HOME', '/nonexistent')),
                    Path(os.environ.get('ANDROID_SDK_ROOT', '/nonexistent')),
                    Path.home() / 'Library/Android/sdk', Path.home() / 'Android/Sdk'}:
            candidates.extend(sdk.glob('ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan'))
        headers = next((p for p in candidates if (p / 'vulkan.h').is_file() and (p / 'vk_layer.h').is_file()), None)
        if headers is None:
            raise unittest.SkipTest('Vulkan headers (with vk_layer.h) from the Android NDK or system SDK are required')
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-winfg-scratch-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        shutil.copytree(headers, folder / 'include/vulkan', ignore=shutil.ignore_patterns('*.hpp'))
        if (headers.parent / 'vk_video').is_dir():
            shutil.copytree(headers.parent / 'vk_video', folder / 'include/vk_video')
        cls.binary = folder / 'winfg-scratch'
        build = subprocess.run([compiler, '-std=c++17', '-O1', '-I' + str(folder / 'include'),
                                '-I' + str(REPO / 'app/src/main/cpp/framegen/winfg'),
                                str(REPO / 'tools/tests/winfg_scratch_harness.cpp'),
                                '-o', str(cls.binary)], capture_output=True, text=True)
        if build.returncode:
            raise RuntimeError(build.stderr)

    def check(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('PASS', result.stdout.strip())

    def test_resizes_free_the_last_size_sets(self):
        self.check('resize')

    def test_a_new_framegen_of_the_same_size_makes_its_own_sets(self):
        self.check('reinit')

    def test_a_pool_too_small_turns_generation_off_without_a_crash(self):
        self.check('exhausted')


if __name__ == '__main__':
    unittest.main()
