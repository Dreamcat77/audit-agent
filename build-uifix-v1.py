from hashlib import sha256
from pathlib import Path
import shutil
import tarfile

root = Path(__file__).resolve().parent
worktree = root
repo = root.parents[1]
package = repo / 'deliverables' / 'UIFix_v1'
templates = worktree / 'deliverables' / 'UIFix_v1'
current = worktree / 'frontend' / 'dist'
baseline = root / '.runtime' / 'uifix-v1-baseline' / 'payload' / 'frontend' / 'dist'
archive = repo / 'deliverables' / 'UIFix_v1.tar.gz'

def digest(path: Path) -> str:
    return sha256(path.read_bytes()).hexdigest()

if digest(baseline / 'index.html') != '1324e3d7f3b3f87e50e02889935023c3e34742b0fd592752f9cedb20f85494e9':
    raise SystemExit('The supplied baseline is not Update_v12a')
if '本次处理说明'.encode() in (current / 'assets' / 'index-DORjTvAQ.js').read_bytes():
    raise SystemExit('Built JS still contains the removed processing note')
(package / 'payload' / 'assets').mkdir(parents=True, exist_ok=True)
for name in ('update.sh', 'rollback.sh', 'README.txt', 'package-version.txt'):
    shutil.copy2(templates / name, package / name)

new_html = (current / 'index.html').read_bytes()
if b'index-DORjTvAQ.js' not in new_html or b'index-DtlhX-YH.css' not in new_html:
    raise SystemExit('Unexpected production entry resources')
for name in ('index-DORjTvAQ.js', 'index-DtlhX-YH.css'):
    shutil.copy2(current / 'assets' / name, package / 'payload' / 'assets' / name)
shutil.copy2(current / 'index.html', package / 'payload' / 'index.html')

(package / 'entry-map.txt').write_text(f'{digest(baseline / "index.html")}  index.html\n', encoding='ascii', newline='\n')
originals = []
for path in sorted((baseline / 'assets').rglob('*')):
    if path.is_file():
        originals.append(f'{digest(path)}  {path.relative_to(baseline).as_posix()}')
(package / 'original-assets.sha256').write_text('\n'.join(originals) + '\n', encoding='ascii', newline='\n')
installed = [f'{digest(package / "payload" / "assets" / name)}  assets/{name}'
             for name in ('index-DORjTvAQ.js', 'index-DtlhX-YH.css')]
(package / 'installed-assets.sha256').write_text('\n'.join(installed) + '\n', encoding='ascii', newline='\n')

files = sorted(p for p in package.rglob('*') if p.is_file() and p.name != 'manifest.sha256')
manifest = ''.join(f'{digest(p)}  {p.relative_to(package).as_posix()}\n' for p in files)
(package / 'manifest.sha256').write_text(manifest, encoding='ascii', newline='\n')

with tarfile.open(archive, 'w:gz') as tar:
    for path in sorted(package.rglob('*')):
        info = tar.gettarinfo(str(path), arcname=path.relative_to(package).as_posix())
        info.uid = info.gid = 0
        info.uname = info.gname = 'root'
        info.mode = 0o755 if path.is_dir() or path.suffix == '.sh' else 0o644
        if path.is_dir():
            tar.addfile(info)
        else:
            with path.open('rb') as stream:
                tar.addfile(info, stream)
archive_hash = digest(archive)
archive.with_name(archive.name + '.sha256').write_text(f'{archive_hash}  {archive.name}\n', encoding='ascii', newline='\n')
print(f'ARCHIVE={archive}')
print(f'SHA256={archive_hash}')
print(f'BYTES={archive.stat().st_size}')
print(f'FILES={len(files)}')
