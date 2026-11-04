from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import tarfile
from pathlib import Path


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


CSS = '''/* Cumulative Chrome 109 compatibility overlay. Keep ASCII on the original stacks. */
@font-face {
  font-family: "Audit UI CJK";
  src: url('FONT_FILE') format('woff2');
  font-weight: 400;
  font-style: normal;
  font-display: swap;
  unicode-range: U+0080-10FFFF;
}

.font-mono.uppercase.tracking-wider,
.procurement-demand-table > tbody > tr > td:nth-child(6) .font-mono,
.procurement-demand-table > tbody > tr > td:nth-child(7) .font-mono,
pre.whitespace-pre-wrap.select-text.text-xs,
.procurement-demand-table > tbody > tr > td:nth-child(4) > span.font-mono {
  font-family: "Audit UI CJK", ui-monospace, SFMono-Regular, Menlo, Monaco,
    Consolas, "Liberation Mono", "Courier New", monospace;
}
'''


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('output', type=Path)
    ap.add_argument('baseline', type=Path)
    args = ap.parse_args()
    repo = Path(__file__).resolve().parents[1]
    baseline = args.baseline.resolve()
    source_scripts = repo / 'scripts' / 'offline' / 'font-fix-v3'
    font = repo / 'frontend' / 'src' / 'assets' / 'fonts' / 'ui-cjk.woff2'
    out = args.output.resolve() / 'FontFix_v4'
    if out.exists():
        raise SystemExit(f'输出已存在：{out}')
    if not (baseline / 'index.html').is_file() or not font.is_file():
        raise SystemExit('基准前端或本地字体不存在')
    (out / 'payload' / 'assets').mkdir(parents=True)
    font_name = f'ui-cjk-{sha256(font)[:20]}.woff2'
    css_name = f'font-fix-cumulative-{hashlib.sha256(CSS.replace("FONT_FILE", font_name).encode()).hexdigest()[:20]}.css'
    (out / 'payload' / 'assets' / font_name).write_bytes(font.read_bytes())
    (out / 'payload' / 'assets' / css_name).write_text(CSS.replace('FONT_FILE', font_name), encoding='utf-8', newline='\n')
    original = (baseline / 'index.html').read_bytes()
    patched = original.replace(b'</head>', f'<link rel="stylesheet" href="/assets/{css_name}">\n</head>'.encode('ascii'))
    (out / 'payload' / 'index.html').write_bytes(patched)
    for name in ('update.sh', 'rollback.sh'):
        text = (source_scripts / name).read_text(encoding='utf-8')
        text = text.replace('FONT_FIX_V3', 'FONT_FIX_V4').replace('font-fix-v3', 'font-fix-v4').replace('FontFix_v3', 'FontFix_v4')
        text = text.replace('procurement-type.css', 'font-fix-cumulative.css').replace('采购类型列本地中文字体已更新', '累计 Chrome 109 中文字体修复已更新')
        text = text.replace('采购类型列字体补丁', '累计中文字体补丁').replace('采购类型列', '累计中文字体修复')
        text = text.replace('[[ -n "$expected" && "$actual" == "$expected" ]] || die',
                            'grep -q "^$actual  index.html$" "$package_dir/entry-map.txt" || die')
        (out / name).write_text(text, encoding='utf-8', newline='\n')
    readme = '''FontFix_v4：累计恢复 Chrome 109 中文字体修复

本包只追加一份本地字体样式，恢复早期统计/状态标签/材料预览修复，并保留采购类型列修复。不会替换 JS/CSS、后端、AI、数据库、Redis、Nginx 或业务数据。

执行：sha256sum -c FontFix_v4.tar.gz.sha256；tar -xzf FontFix_v4.tar.gz；cd FontFix_v4；sudo bash update.sh --check；sudo bash update.sh
成功后浏览器 Ctrl+F5，无需重启服务。入口或资源指纹不匹配时必须停止，不能绕过预检。
'''
    (out / 'README.txt').write_text(readme, encoding='utf-8', newline='\n')
    head = subprocess.run(['git', '-C', str(repo), 'rev-parse', 'HEAD'], check=True, capture_output=True, text=True).stdout.strip()
    (out / 'package-version.txt').write_text(f'PACKAGE=FontFix_v4\nAPP_COMMIT={head}\n', encoding='utf-8', newline='\n')
    known_entries = [sha256(baseline / 'index.html')]
    previous_v3 = repo / 'deliverables' / 'FontFix_v3' / 'payload' / 'index.html'
    if previous_v3.is_file():
        known_entries.append(sha256(previous_v3))
    (out / 'entry-map.txt').write_text(''.join(f'{digest}  index.html\n' for digest in known_entries), encoding='utf-8', newline='\n')
    assets = [' '.join((sha256(p), p.relative_to(baseline).as_posix())) for p in sorted((baseline / 'assets').rglob('*')) if p.is_file()]
    (out / 'original-assets.sha256').write_text('\n'.join(assets) + '\n', encoding='utf-8', newline='\n')
    files = sorted(p for p in out.rglob('*') if p.is_file())
    (out / 'manifest.sha256').write_text(''.join(f'{sha256(p)}  {p.relative_to(out).as_posix()}\n' for p in files), encoding='utf-8', newline='\n')
    archive = out.with_suffix('.tar.gz')
    with tarfile.open(archive, 'w:gz') as tar:
        for p in sorted(out.rglob('*')):
            if not p.is_file():
                continue
            info = tar.gettarinfo(str(p), arcname=p.relative_to(out).as_posix())
            info.uid = info.gid = 0
            info.uname = info.gname = 'root'
            info.mode = 0o755 if p.suffix == '.sh' else 0o644
            with p.open('rb') as f:
                tar.addfile(info, f)
    (archive.with_name(archive.name + '.sha256')).write_text(f'{sha256(archive)}  {archive.name}\n', encoding='utf-8', newline='\n')
    print(f'ARCHIVE={archive}\nSHA256={sha256(archive)}\nBYTES={archive.stat().st_size}')


if __name__ == '__main__':
    main()
