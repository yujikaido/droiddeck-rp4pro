"""Reject an APK that pins no add-on pack for proot or DirectAudio, or that pins an Xbox, GOG, itch.io or hand-tracking pack
other than the published one in tools/packs/<name>/release.env.

A build that pins no proot or DirectAudio pack cannot install the Linux runtime on a device that has
never had one ("Cannot download proot" / "Cannot download DirectAudio", RUNTIME_INSTALL_FAILED): the app
downloads each pack by the SHA-256 the APK pins. Upgrades over an older install still work, because the
packs are already on the device, which is how a build made with DROIDDECK_SKIP_PACKS=1 passed its tests.
The packs must be built (tools/packs/build.sh app/build/packs) and their digests must be inside the APK.

  check_pack_pins.py <apk> [packs-dir] [--published]    --published also asks GitHub for each pack's release asset
"""
import hashlib
import sys
import urllib.request
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
REQUIRED = ['proot', 'directaudio']
# Packs a build made outside Droid-Deck's CI pins by their published digest whatever it built (app/build.gradle): the local
# bytes differ from CI's, and every MaliBridge APK up to v0.5.6-test6 asked for an unpublished Xbox pack (404).
PUBLISHED_ONLY = ['xbox', 'comet', 'butler', 'tracking']
REPO = 'Droid-Deck/DroidDeck-Components'


def pins(names=None):
    """The published packs a release must pin: {name: sha256} from tools/packs/<name>/release.env."""
    result = {}
    for name in names or REQUIRED + PUBLISHED_ONLY:
        env = ROOT / 'tools/packs' / name / 'release.env'
        if not env.is_file():
            continue
        for line in env.read_text().splitlines():
            if line.startswith(name.upper() + '_PACK_SHA256='):
                result[name] = line.split('=', 1)[1].strip()
    return result


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def published(name, sha):
    url = f'https://github.com/{REPO}/releases/download/pack-{name}-{sha[:16]}/{name}.tar.zst'
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=30):
            return True
    except Exception:
        return False


def check(apk, packs=ROOT / 'app/build/packs', online=False, expected=None):
    errors = []
    expected = expected or pins()
    with ZipFile(apk) as package:
        dex = b''.join(package.read(n) for n in package.namelist() if n.startswith('classes') and n.endswith('.dex'))
    for name in REQUIRED:
        pack = Path(packs) / (name + '.tar.zst')
        if not pack.is_file():
            errors.append(f'{name}: no pack built at {pack} (a DROIDDECK_SKIP_PACKS=1 build?); the APK pins none')
            continue
        sha = digest(pack)
        if sha != expected[name]:
            errors.append(f'{name}: the pack built is {sha[:16]}, the published one to pin is {expected[name][:16]} '
                          f'(tools/packs/{name}/release.env)')
        elif sha.encode() not in dex:
            errors.append(f'{name}: the APK does not pin the pack {sha}')
        elif online and not published(name, sha):
            errors.append(f'{name}: pack-{name}-{sha[:16]} is not published on {REPO}; a fresh install cannot download it')
    for name in PUBLISHED_ONLY:
        sha = expected.get(name)
        if not sha:
            errors.append(f'{name}: tools/packs/{name}/release.env pins no published pack; the APK would pin one built here')
        elif sha.encode() not in dex:
            errors.append(f'{name}: the APK does not pin the published pack {sha[:16]} (tools/packs/{name}/release.env)')
        elif online and not published(name, sha):
            errors.append(f'{name}: pack-{name}-{sha[:16]} is not published on {REPO}; a fresh install cannot download it')
    return errors


if __name__ == '__main__':
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    problems = check(args[0], args[1] if len(args) > 1 else ROOT / 'app/build/packs', '--published' in sys.argv)
    for problem in problems:
        print('pack pins: ' + problem, file=sys.stderr)
    sys.exit(1 if problems else 0)
