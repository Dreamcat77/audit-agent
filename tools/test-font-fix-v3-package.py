from __future__ import annotations

import hashlib
import sys
import tarfile
from pathlib import Path, PurePosixPath


archive = Path(sys.argv[1])
package_dir = archive.parent / 'FontFix_v3'
manifest_lines = (package_dir / 'manifest.sha256').read_text(encoding='utf-8').splitlines()
for line in manifest_lines:
    expected, relative = line.split('  ', 1)
    path = package_dir / relative
    assert path.is_file(), relative
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, relative

with tarfile.open(archive, 'r:gz') as tar:
    members = tar.getmembers()
    assert all(member.isfile() and not member.issym() and not member.islnk() for member in members)
    assert all(not PurePosixPath(member.name).is_absolute() and '..' not in PurePosixPath(member.name).parts for member in members)
    assert all(not member.name.startswith('FontFix_v3/') for member in members), 'Archive must be flat'
    assert set(tar.getnames()) == {line.split('  ', 1)[1] for line in manifest_lines} | {'manifest.sha256'}

entry = (package_dir / 'payload' / 'index.html').read_bytes()
assert entry.count(b'procurement-type-') == 1
css_path = next((package_dir / 'payload' / 'assets').glob('procurement-type-*.css'))
css = css_path.read_text(encoding='utf-8')
assert '.procurement-demand-table > tbody > tr > td:nth-child(4) > span.font-mono' in css
assert '@font-face' in css and 'unicode-range: U+0080-10FFFF' in css
assert 'http://' not in css and 'https://' not in css
assert (package_dir / 'entry-map.txt').read_text(encoding='utf-8').split()[1] == 'index.html'
print(f'PACKAGE_MANIFEST=PASS; FLAT_TAR=PASS; SAFE_PATHS=PASS; TARGET_SELECTOR=PASS; FILES={len(manifest_lines)}')
