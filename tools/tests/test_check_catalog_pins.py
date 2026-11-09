"""MaliBridge: the DroidDeck-Components catalogs the app reads are the pinned snapshot, never a branch of that repo."""
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_catalog_pins", ROOT / "tools/release/check_catalog_pins.py")
pins = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pins)


class CheckCatalogPinsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.pins = self.tmp / "catalog-pins"
        shutil.copytree(pins.PINS, self.pins)
        self.src = self.tmp / "java"
        self.src.mkdir()
        (self.src / "Ok.kt").write_text('val x = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/fex/fex.tzst"\n')

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_this_build_is_pinned(self):
        self.assertEqual([], pins.check())

    def test_every_catalog_the_app_reads_is_pinned(self):
        self.assertEqual(set(pins.FILES), set(json.loads((pins.PINS / "pins.json").read_text())["files"]))

    def test_a_catalog_edited_after_the_bump_is_refused(self):
        f = self.pins / "linuxfs-ubuntu.json"
        f.write_text(f.read_text().replace("ubuntu-r1-rc4", "ubuntu-r1-rc5"))
        self.assertTrue(any("linuxfs-ubuntu.json: sha256 differs" in e for e in pins.check(self.pins, self.src)))

    def test_a_source_reading_main_is_refused(self):
        (self.src / "Live.kt").write_text('const val U = "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/fex.json"\n')
        self.assertEqual(["Live.kt: reads a DroidDeck-Components catalog from a branch"], pins.check(self.pins, self.src))

    def test_a_source_reading_a_commit_is_allowed(self):
        (self.src / "Sha.kt").write_text('const val U = "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/'
                                         '4ba1759e3f284bf8780ea4ed4b3478afea7cc757/fex.json"\n')
        self.assertEqual([], pins.check(self.pins, self.src))

    def test_a_component_asset_without_a_digest_is_refused(self):
        m = json.loads((self.pins / "pins.json").read_text())
        url = sorted(m["assets"])[0]
        del m["assets"][url]
        (self.pins / "pins.json").write_text(json.dumps(m))
        self.assertTrue(any(url in e and "no pinned sha256" in e for e in pins.check(self.pins, self.src)))

    def test_the_auto_dxvk_packages_are_pinned(self):
        m = json.loads((pins.PINS / "pins.json").read_text())
        for f in ["dxvk-1.10.3-linux.wcp", "dxvk-2.6.2-linux.wcp", "dxvk-2.7.1-linux.wcp"]:
            self.assertRegex(m["nightlies"].get("Dxvk-Linux/" + f, ""), "^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
