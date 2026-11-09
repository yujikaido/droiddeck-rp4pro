#!/usr/bin/env python3
"""Pin the DroidDeck-Components catalogs this app reads to one commit (MaliBridge).

The app reads fex.json, linuxfs-ubuntu.json, components.json, desktop.json and steam-seed.json from the
copies under app/src/main/resources/catalog-pins/, never from the repository's main branch: a push there
reached every player untested (the Ubuntu runtime was repointed four times in 10-28..10-31). This tool is
the deliberate bump: it fetches the catalogs at a commit, records the sha256 of each file, adds a sha256 for
every DroidDeck-Components asset components.json names without one (from GitHub's asset digests) and the
digests of the DXVK packages the app picks by itself from The412Banner/Nightlies. After a bump, run
tools/release/check_catalog_pins.py --online and the Kotlin CatalogPin tests, and test the build on a device.

  bump_catalog_pins.py [--commit SHA] [--mirror-base URL] [--out DIR]

--mirror-base names a release (e.g. https://github.com/ASEbrahim/MaliBridge/releases/download/dd-components-4ba1759e)
that carries copies of the assets upstream replaces in place (the single `fex` release) or may drop (rc runtimes);
the app tries upstream first and the mirror second, each checked against the same sha256.
Network only; no auth (60 GitHub API calls an hour is enough).
"""
import argparse
import hashlib
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPO = 'Droid-Deck/DroidDeck-Components'
FILES = ['fex.json', 'linuxfs-ubuntu.json', 'components.json', 'desktop.json', 'steam-seed.json']
NIGHTLIES = 'The412Banner/Nightlies'
# The DXVK packages the app downloads without the player picking them (ComponentsManager.FORCED_PACKAGES,
# bridgeDxvk, known-games.json); each must match the digest pinned here.
AUTO_PACKAGES = ['Dxvk-Linux/dxvk-1.10.3-linux.wcp', 'Dxvk-Linux/dxvk-2.6.2-linux.wcp', 'Dxvk-Linux/dxvk-2.7.1-linux.wcp']
ASSET = re.compile(r'^https://github\.com/' + re.escape(REPO) + r'/releases/download/([^/]+)/([^/?#]+)$')


def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'MaliBridge-catalog-pin'})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def api(path):
    return json.loads(get('https://api.github.com/' + path))


def release_assets(repo, tag, cache):
    key = (repo, tag)
    if key not in cache:
        cache[key] = {a['name']: a for a in api(f'repos/{repo}/releases/tags/{tag}')['assets']}
    return cache[key]


def component_urls(components):
    for c in components['components']:
        if c.get('snapshot'):
            yield c['snapshot']
        for s in c.get('steps', []):
            u = s.get('url') or (s.get('environment') or {}).get('url')
            if u:
                yield u


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument('--commit')
    p.add_argument('--mirror-base')
    p.add_argument('--out', default=str(ROOT / 'app/src/main/resources/catalog-pins'))
    a = p.parse_args(argv)
    commit = a.commit or api(f'repos/{REPO}/commits/main')['sha']
    info = api(f'repos/{REPO}/commits/{commit}')
    commit = info['sha']
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    texts = {}
    for name in FILES:
        texts[name] = get(f'https://raw.githubusercontent.com/{REPO}/{commit}/{name}')
        json.loads(texts[name])
    cache = {}
    assets = {}
    components = json.loads(texts['components.json'])
    for url in sorted(set(component_urls(components))):
        m = ASSET.match(url)
        if not m:
            continue
        asset = release_assets(REPO, m.group(1), cache).get(m.group(2))
        digest = (asset or {}).get('digest') or ''
        if not digest.startswith('sha256:'):
            sys.exit(f'{url}: no GitHub sha256 digest; cannot pin it')
        assets[url] = digest.removeprefix('sha256:')
    nightlies = {}
    for key in AUTO_PACKAGES:
        tag, name = key.split('/', 1)
        asset = release_assets(NIGHTLIES, tag, cache).get(name)
        digest = (asset or {}).get('digest') or ''
        if not digest.startswith('sha256:'):
            sys.exit(f'{NIGHTLIES} {key}: missing or no digest')
        nightlies[key] = digest.removeprefix('sha256:')
    mirrors = {}
    if a.mirror_base:
        base = a.mirror_base.rstrip('/')
        fex = json.loads(texts['fex.json'])
        mirrors[fex['url']] = [f'{base}/fex-{fex["version"]}.tzst']
        rt = json.loads(texts['linuxfs-ubuntu.json'])
        mirrors[rt['url']] = [f'{base}/{rt["url"].rsplit("/", 1)[1]}']
    for name, body in texts.items():
        (out / name).write_bytes(body)
    manifest = {
        'schema': 1,
        'repo': REPO,
        'commit': commit,
        'commitDate': info['commit']['committer']['date'],
        'commitTitle': info['commit']['message'].splitlines()[0],
        'files': {n: hashlib.sha256(b).hexdigest() for n, b in sorted(texts.items())},
        'assets': assets,
        'nightlies': nightlies,
        'mirrors': mirrors,
    }
    (out / 'pins.json').write_text(json.dumps(manifest, indent=1, sort_keys=False) + '\n')
    print(f'pinned {REPO} {commit[:12]} ({manifest["commitDate"]}): {len(texts)} catalogs, {len(assets)} asset digests, '
          f'{len(nightlies)} auto packages, {len(mirrors)} mirrored assets -> {out}')


if __name__ == '__main__':
    main()
