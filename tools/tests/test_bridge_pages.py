import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/cpp/waylandcomp/src'


class BridgePagesTest(unittest.TestCase):
    """The compositor's question to the MaliBridge server about a client's dma-buf (bridge_pages.c)."""

    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('cc'), 'requires a Linux C compiler')
    def test_lookup(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            lib = d / 'libvkbridge_jni.so'
            subprocess.run(['cc', '-std=c11', '-Wall', '-Werror', '-shared', '-fPIC', '-I', str(SRC),
                            str(ROOT / 'tools/tests/test_bridge_pages_server.c'), '-o', str(lib)], check=True)
            binary = d / 'bridge-pages-test'
            subprocess.run(['cc', '-std=c11', '-D_DEFAULT_SOURCE', '-Wall', '-Wextra', '-Werror', '-I', str(SRC),
                            f'-DBRIDGE_PAGES_LIB="{lib}"', str(ROOT / 'tools/tests/test_bridge_pages.c'),
                            str(SRC / 'bridge_pages.c'), '-ldl', '-pthread', '-o', str(binary)], check=True)
            run = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertIn('OK: 0 failures', run.stdout)
            absent = subprocess.run([str(binary), 'absent'], capture_output=True, text=True, timeout=10)
            self.assertEqual(absent.returncode, 0, absent.stdout + absent.stderr)


if __name__ == '__main__':
    unittest.main()
