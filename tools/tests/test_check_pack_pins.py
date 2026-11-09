import hashlib
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'release'))
import check_pack_pins  # noqa: E402


class PackPinsTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.packs = self.dir / 'packs'
        self.packs.mkdir()
        self.shas = {}
        for name in check_pack_pins.REQUIRED:
            data = (name * 10).encode()
            (self.packs / (name + '.tar.zst')).write_bytes(data)
            self.shas[name] = hashlib.sha256(data).hexdigest()
        for name in check_pack_pins.PUBLISHED_ONLY:  # published by CI; what this PC built does not matter
            (self.packs / (name + '.tar.zst')).write_bytes(b'built here ' + name.encode())
            self.shas[name] = hashlib.sha256(b'published ' + name.encode()).hexdigest()

    def apk(self, pinned):
        path = self.dir / 'a.apk'
        with ZipFile(path, 'w') as z:
            z.writestr('classes.dex', b'\0'.join(self.shas[n].encode() for n in pinned) + b'\0other')
        return path

    def test_both_packs_pinned(self):
        self.assertEqual([], check_pack_pins.check(self.apk(['proot', 'directaudio'] + check_pack_pins.PUBLISHED_ONLY), self.packs, expected=self.shas))

    def test_a_build_without_packs_fails(self):  # DROIDDECK_SKIP_PACKS=1: nothing built, nothing pinned
        errors = check_pack_pins.check(self.apk(check_pack_pins.PUBLISHED_ONLY), self.dir / 'none', expected=self.shas)
        self.assertEqual(2, len(errors))
        self.assertIn('SKIP_PACKS', errors[0])

    def test_a_pack_the_apk_does_not_pin_fails(self):
        errors = check_pack_pins.check(self.apk(['proot'] + check_pack_pins.PUBLISHED_ONLY), self.packs, expected=self.shas)
        self.assertEqual(1, len(errors))
        self.assertIn('directaudio', errors[0])

    def test_a_pack_other_than_the_published_one_fails(self):
        errors = check_pack_pins.check(self.apk(['proot', 'directaudio'] + check_pack_pins.PUBLISHED_ONLY), self.packs,
                                       expected=dict(self.shas, proot='0' * 64))
        self.assertEqual(1, len(errors))
        self.assertIn('published one to pin', errors[0])

    def test_the_repo_pins_the_working_packs(self):  # proot 5a2b7284 fails with the Ubuntu runtime
        pinned = check_pack_pins.pins()
        self.assertTrue(pinned['proot'].startswith('172bb48b00150b42'))
        self.assertTrue(pinned['directaudio'].startswith('fd65f0c1e68234ca'))
        # published by Droid-Deck CI from the same sources (11-04: the APKs pinned 94a6db89..., never published, 404)
        self.assertTrue(pinned['xbox'].startswith('59420a9886121345'))
        self.assertTrue(pinned['comet'].startswith('fdc66ee6ae44ccdb'))
        self.assertTrue(pinned['butler'].startswith('82e7f5f5d8fcde40'))
        self.assertTrue(pinned['tracking'].startswith('6b033308a3357594'))

    def test_an_apk_that_pins_a_locally_built_xbox_pack_fails(self):  # every MaliBridge APK v0.5.5.1..v0.5.6-test6
        local = hashlib.sha256(b'built here xbox').hexdigest()
        shas = dict(self.shas)
        self.shas['xbox'] = local
        apk = self.apk(['proot', 'directaudio'] + check_pack_pins.PUBLISHED_ONLY)
        errors = check_pack_pins.check(apk, self.packs, expected=shas)
        self.assertEqual(1, len(errors))
        self.assertIn('xbox', errors[0])
        self.assertIn('published pack', errors[0])


if __name__ == '__main__':
    unittest.main()
