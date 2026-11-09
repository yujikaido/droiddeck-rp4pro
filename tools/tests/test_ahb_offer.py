import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/cpp/waylandcomp/src'


class AhbOfferTest(unittest.TestCase):
    """banner_ahb_v1 version 3: an offered AHardwareBuffer pairs only with the wl_buffer that is its own
    dma-buf (same inode, any fd), laid out as it was allocated."""

    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('cc'), 'requires a Linux C compiler')
    def test_matching_rules(self):
        with tempfile.TemporaryDirectory() as tmp:
            binary = pathlib.Path(tmp) / 'ahb-offer-test'
            subprocess.run([
                'cc', '-std=c11', '-D_GNU_SOURCE', '-Wall', '-Wextra', '-Werror', '-I', str(SRC),
                str(ROOT / 'tools/tests/test_ahb_offer.c'), str(SRC / 'ahb_offer.c'), '-o', str(binary),
            ], check=True)
            out = subprocess.run([str(binary)], check=True, timeout=10, capture_output=True, text=True)
            self.assertIn('OK: ahb_offer', out.stdout)


if __name__ == '__main__':
    unittest.main()
