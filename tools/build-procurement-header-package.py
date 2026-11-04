"""Build a font-only, version-guarded offline patch from known frontend dist trees."""
import argparse
import hashlib
import re
import tarfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("baselines", nargs="+", type=Path)
    args = parser.parse_args()
    package = args.output / "AuditAgent_Header_Font_20261010"
    package.mkdir(parents=True, exist_ok=False)
    fonts = REPO / "frontend/src/assets/fonts"
    font = (fonts / "audit-header-sc-500.woff2").read_bytes()
    font_name = f"header-sc-500-{digest(font)[:20]}.woff2"
    css = (REPO / "frontend/src/styles/procurement-table-header.css").read_text(encoding="utf-8")
    css = css.replace("../assets/fonts/audit-header-sc-500.woff2", font_name).encode()
    css_name = f"header-font-{digest(css)[:20]}.css"
    marker = (REPO / "scripts/offline/procurement-header/mark-table.js").read_bytes()
    js_name = f"header-mark-{digest(marker)[:20]}.js"
    addition = (f'<link rel="stylesheet" href="/assets/{css_name}">\n'
                f'<script defer src="/assets/{js_name}"></script>\n')
    seen = set()
    entries = []
    for baseline in args.baselines:
        original = (baseline / "index.html").read_bytes()
        if digest(original) in seen:
            continue
        seen.add(digest(original))
        html = original.decode("utf-8")
        assert html.count("</head>") == 1
        # Additive patch: original app code/style bytes are never replaced.
        patched = html.replace("</head>", addition + "</head>").encode()
        variant = f"r{len(seen)}"
        folder = package / "variants" / variant
        dist = folder / "dist"
        assets = dist / "assets"
        assets.mkdir(parents=True)
        (dist / "index.html").write_bytes(patched)
        for name, data in [(font_name, font), (css_name, css), (js_name, marker)]:
            (assets / name).write_bytes(data)
        refs = sorted(set(re.findall(r'(?:src|href)=["\'](/assets/[^"\']+)["\']', html)))
        assert refs, "Frontend asset references missing"
        hashes = []
        for ref in refs:
            assert ".." not in ref and "?" not in ref
            data = (baseline / ref.lstrip("/")).read_bytes()
            hashes.append(f"{digest(data)}  {ref.lstrip('/')}\n")
        (folder / "original-assets.sha256").write_text("".join(hashes), encoding="utf-8", newline="\n")
        entries.extend([f"{digest(original)} {variant}\n", f"{digest(patched)} {variant}\n"])
    (package / "entry-map.txt").write_text("".join(entries), encoding="utf-8", newline="\n")
    for name in ["update.sh", "rollback.sh"]:
        (package / name).write_bytes((REPO / "scripts/offline/procurement-header" / name).read_bytes())
    for name in ["OFL.txt", "SOURCE.md"]:
        (package / name).write_bytes((fonts / name).read_bytes())
    (package / "README.md").write_bytes((REPO / "docs/procurement-header-font-fix.md").read_bytes())
    manifest = "".join(f"{digest(p.read_bytes())}  {p.relative_to(package).as_posix()}\n"
                       for p in sorted(package.rglob("*")) if p.is_file())
    (package / "manifest.sha256").write_text(manifest, encoding="utf-8", newline="\n")
    for p in package.rglob("*"):
        if p.suffix == ".sh" or p.name.endswith(".sha256") or p.name == "entry-map.txt":
            assert b"\r" not in p.read_bytes(), f"Linux control file is not LF: {p}"
    archive = package.with_suffix(".tar.gz")
    with tarfile.open(archive, "w:gz") as tar:
        for p in sorted(package.rglob("*")):
            if not p.is_file():
                continue
            info = tar.gettarinfo(str(p), f"{package.name}/{p.relative_to(package).as_posix()}")
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            info.mode = 0o755 if p.suffix == ".sh" else 0o644
            with p.open("rb") as stream:
                tar.addfile(info, stream)
    with tarfile.open(archive) as tar:
        assert all(item.isfile() and ".." not in Path(item.name).parts for item in tar)
        for p in package.rglob("*"):
            if p.is_file():
                assert tar.extractfile(f"{package.name}/{p.relative_to(package).as_posix()}").read() == p.read_bytes()
    archive.with_suffix(archive.suffix + ".sha256").write_text(
        f"{digest(archive.read_bytes())}  {archive.name}\n", encoding="utf-8", newline="\n")
    print(f"FONT_ONLY_PATCH_VARIANTS={len(seen)}; ARCHIVE={archive}; BYTES={archive.stat().st_size}")


if __name__ == "__main__":
    main()
