#!/usr/bin/env python3
"""Reject a build whose DroidDeck-Components catalogs are not the pinned snapshot (MaliBridge).

Checks app/src/main/resources/catalog-pins: every catalog the app reads is there with the sha256 pins.json records,
every asset a catalog names carries a sha256 (in the catalog or in pins.json), and no app source reads a catalog
from a branch of DroidDeck-Components (raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/...).
--online also asks GitHub that each pinned asset is still published with that digest, and says which mirrors exist.

  check_catalog_pins.py [--online]
"""
import json
import hashlib
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PINS = ROOT / 'app/src/main/resources/catalog-pins'
SOURCES = ROOT / 'app/src/main/java'
FILES = ['fex.json', 'linuxfs-ubuntu.json', 'components.json', 'desktop.json', 'steam-seed.json']
LIVE = re.compile(r'raw\.githubusercontent\.com/Droid-Deck/DroidDeck-Components/(?![0-9a-f]{40}/)[^"/]+/')
SHA = re.compile(r'^[0-9a-f]{64}$')


def sha_assets(pins):
    """(catalog, id, url, sha256) for every sha256-checked asset of the pinned catalogs."""
    j = lambda n: json.loads((pins / n).read_text())
    out = []
    for n in ['fex.json', 'linuxfs-ubuntu.json']:
        c = j(n)
        out.append((n, c['version'], c['url'], c['sha256']))
    for n in ['steam-seed.json', 'desktop.json']:
        for p in j(n)['packages']:
            for size, v in (p.get('pages') or {'': p}).items():
                out.append((n, p['id'] + (f'@{size}' if size else ''), v['url'], v.get('sha256', '')))
    return out


def component_urls(pins):
    for c in json.loads((pins / 'components.json').read_text())['components']:
        if c.get('snapshot'):
            yield c['name'], c['snapshot'], ''
        for s in c.get('steps', []):
            env = s.get('environment') or {}
            u = s.get('url') or env.get('url')
            if u and u.startswith('http'):
                yield c['name'], u, s.get('file_checksum') or env.get('file_checksum') or ''


def check(pins=PINS, sources=SOURCES):
    errors = []
    manifest = json.loads((pins / 'pins.json').read_text())
    if not re.fullmatch(r'[0-9a-f]{40}', manifest.get('commit', '')):
        errors.append('pins.json: no 40-hex commit')
    for n in FILES:
        f = pins / n
        if not f.is_file():
            errors.append(f'{n}: not pinned')
            continue
        if hashlib.sha256(f.read_bytes()).hexdigest() != manifest['files'].get(n):
            errors.append(f'{n}: sha256 differs from pins.json (edited by hand? use bump_catalog_pins.py)')
    for cat, ident, url, sha in sha_assets(pins):
        if not SHA.match(sha.lower()):
            errors.append(f'{cat} {ident}: no sha256 for {url}')
    assets = manifest.get('assets', {})
    for name, url, md5 in component_urls(pins):
        if url.startswith('https://github.com/Droid-Deck/DroidDeck-Components/') and not SHA.match(assets.get(url, '')):
            errors.append(f'components.json {name}: {url} has no pinned sha256')
    for path in sorted(sources.rglob('*')):
        if path.suffix in ('.kt', '.java') and LIVE.search(path.read_text(errors='replace')):
            errors.append(f'{path.relative_to(sources)}: reads a DroidDeck-Components catalog from a branch')
    return errors


def online(pins=PINS):
    errors, notes = [], []
    manifest = json.loads((pins / 'pins.json').read_text())
    cache = {}

    def digest(repo, tag, name):
        if (repo, tag) not in cache:
            try:
                with urllib.request.urlopen(f'https://api.github.com/repos/{repo}/releases/tags/{tag}', timeout=30) as r:
                    cache[(repo, tag)] = {a['name']: a.get('digest', '') for a in json.load(r)['assets']}
            except Exception as e:
                cache[(repo, tag)] = {'__error__': str(e)}
        return cache[(repo, tag)].get(name)

    rel = re.compile(r'^https://github\.com/([^/]+/[^/]+)/releases/download/([^/]+)/([^/?#]+)$')
    pairs = [(u, s) for _, _, u, s in sha_assets(pins)] + list(manifest.get('assets', {}).items())
    for url, sha in pairs:
        m = rel.match(url)
        if not m:
            continue
        have = digest(*m.groups())
        mirrors = manifest.get('mirrors', {}).get(url, [])
        if have != 'sha256:' + sha.lower():
            line = f'{url}: published digest {have} is not the pinned {sha[:12]}'
            alive = [x for x in mirrors if (lambda mm: mm and digest(*mm.groups()) == 'sha256:' + sha.lower())(rel.match(x))]
            (notes if alive else errors).append(line + (f' (mirror {alive[0]} has it)' if alive else ' and no mirror has it'))
        for x in mirrors:
            mm = rel.match(x)
            ok = mm and digest(*mm.groups()) == 'sha256:' + sha.lower()
            notes.append(f'mirror {"OK" if ok else "MISSING"}: {x}')
    for key, sha in manifest.get('nightlies', {}).items():
        tag, name = key.split('/', 1)
        have = digest('The412Banner/Nightlies', tag, name)
        if have != 'sha256:' + sha:
            errors.append(f'Nightlies {key}: published {have}, pinned {sha[:12]} (the app keeps the Proton\'s DXVK)')
    return errors, notes


def main(argv):
    errors = check()
    notes = []
    if '--online' in argv:
        e, notes = online()
        errors += e
    for n in notes:
        print('note:', n)
    for e in errors:
        print('ERROR:', e)
    if not errors:
        m = json.loads((PINS / 'pins.json').read_text())
        print(f'catalog pins OK: DroidDeck-Components {m["commit"][:12]} ({m["commitDate"]})')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
