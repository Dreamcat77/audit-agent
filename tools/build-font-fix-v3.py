from __future__ import annotations

import argparse
import hashlib
import subprocess
import shutil
import tarfile
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    parser.add_argument('baseline', type=Path, help='已部署 v10 frontend/dist 目录')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    baseline = args.baseline.resolve()
    baseline_entry = baseline / 'index.html'
    source_font = repo / 'frontend' / 'src' / 'assets' / 'fonts' / 'ui-cjk.woff2'
    source_scripts = repo / 'scripts' / 'offline' / 'font-fix-v3'
    if not baseline_entry.is_file():
        raise SystemExit(f'基准入口不存在：{baseline_entry}')
    if not source_font.is_file():
        raise SystemExit(f'本地中文字体不存在：{source_font}')
    package = args.output.resolve() / 'FontFix_v3'
    if package.exists():
        raise SystemExit(f'输出已存在：{package}')
    (package / 'payload' / 'assets').mkdir(parents=True)
    font_name = f'ui-cjk-{sha256(source_font)[:20]}.woff2'
    css_source = source_scripts / 'procurement-type.css'
    css_bytes = css_source.read_bytes().replace(b'ui-cjk.woff2', font_name.encode('ascii'))
    css_name = f'procurement-type-{hashlib.sha256(css_bytes).hexdigest()[:20]}.css'
    (package / 'payload' / 'assets' / css_name).write_bytes(css_bytes)
    shutil.copy2(source_font, package / 'payload' / 'assets' / font_name)
    original_index = baseline_entry.read_bytes()
    if original_index.count(b'</head>') != 1:
        raise SystemExit('基准入口 head 结束标签数量不正确')
    linked_index = original_index.replace(
        b'</head>', f'<link rel="stylesheet" href="/assets/{css_name}">\n</head>'.encode('ascii'))
    (package / 'payload' / 'index.html').write_bytes(linked_index)
    for name in ('update.sh', 'rollback.sh', 'README.txt'):
        shutil.copy2(source_scripts / name, package / name)
    git_head = subprocess.run(
        ['git', '-C', str(repo), 'rev-parse', 'HEAD'], check=True,
        capture_output=True, text=True).stdout.strip()
    (package / 'package-version.txt').write_text(
        f'PACKAGE=FontFix_v3\nAPP_COMMIT={git_head}\n', encoding='utf-8', newline='\n')
    (package / 'entry-map.txt').write_text(
        f'{sha256(baseline_entry)}  index.html\n', encoding='utf-8', newline='\n')
    original_assets = []
    for file in sorted((baseline / 'assets').rglob('*')):
        if file.is_file():
            relative = file.relative_to(baseline).as_posix()
            original_assets.append(f'{sha256(file)}  {relative}')
    (package / 'original-assets.sha256').write_text(
        '\n'.join(original_assets) + '\n', encoding='utf-8', newline='\n')
    for path in package.rglob('*'):
        if path.is_file() and path.suffix in ('.sh', '.txt', '.sha256'):
            path.write_bytes(path.read_bytes().replace(b'\r\n', b'\n'))
    files = sorted(path for path in package.rglob('*') if path.is_file())
    manifest = '\n'.join(f'{sha256(path)}  {path.relative_to(package).as_posix()}' for path in files)
    (package / 'manifest.sha256').write_text(manifest + '\n', encoding='utf-8', newline='\n')
    archive = package.with_suffix('.tar.gz')
    with tarfile.open(archive, 'w:gz') as tar:
        for path in sorted(package.rglob('*')):
            if not path.is_file():
                continue
            relative = path.relative_to(package)
            info = tar.gettarinfo(str(path), arcname=relative.as_posix())
            info.uid = info.gid = 0
            info.uname = info.gname = 'root'
            info.mode = 0o755 if path.suffix == '.sh' else 0o644
            with path.open('rb') as stream:
                tar.addfile(info, stream)
    (archive.with_name(archive.name + '.sha256')).write_text(
        f'{sha256(archive)}  {archive.name}\n', encoding='utf-8', newline='\n')
    print(f'ARCHIVE={archive}')
    print(f'SHA256={sha256(archive)}')
    print(f'BYTES={archive.stat().st_size}')


if __name__ == '__main__':
    main()
