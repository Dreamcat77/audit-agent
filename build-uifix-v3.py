from hashlib import sha256
from pathlib import Path
import shutil
import tarfile

worktree = Path(__file__).resolve().parent
repository = worktree.parents[1]
source = worktree / "frontend" / "dist-uifix-v3"
package = repository / "deliverables" / "UIFix_v3"
package_source = worktree / "deliverables" / "UIFix_v3"
compat = repository / ".runtime" / "uifix-v2-contract-dist-20261028" / "assets"
fontfix = worktree / "deliverables" / "FontFix_v4-final" / "FontFix_v4" / "payload" / "assets"
archive = repository / "deliverables" / "UIFix_v3c.tar.gz"


def digest(path: Path) -> str:
    return sha256(path.read_bytes()).hexdigest()


package.mkdir(parents=True, exist_ok=True)
for name in ("package-version.txt", "README.txt", "update.sh"):
    shutil.copy2(package_source / name, package / name)

assets = package / "payload" / "assets" / "uifix-v3"
assets.mkdir(parents=True, exist_ok=True)
for old in assets.iterdir():
    if old.is_file():
        old.unlink()

for path in sorted((source / "assets" / "uifix-v3").iterdir()):
    if path.is_file():
        shutil.copy2(path, assets / path.name)
for name in (
    "chrome109-colors-1f18e6629fc9c95f12a6.css",
    "chrome109-currentcolor-fdbf6e1dfa1c39dca627.js",
):
    shutil.copy2(compat / name, assets / name)
font_css = (fontfix / "font-fix-cumulative-fe6f8ee386ad94109f04.css").read_text(encoding="utf-8")
font_css = font_css.replace("ui-cjk-5bf611f708444e807ade.woff2", "ui-cjk-C8g_JgdG.woff2")
font_css_bytes = font_css.encode("utf-8")
font_css_name = f"font-fix-cumulative-{sha256(font_css_bytes).hexdigest()[:20]}.css"
(assets / font_css_name).write_bytes(font_css_bytes)

index = (source / "index.html").read_text(encoding="utf-8")
if "assets/uifix-v3/index-" not in index:
    raise SystemExit("Vite build is missing the unique UIFix_v3 asset namespace")
css_links = (
    '<link rel="stylesheet" href="/assets/uifix-v3/chrome109-colors-1f18e6629fc9c95f12a6.css">\n'
    f'<link rel="stylesheet" href="/assets/uifix-v3/{font_css_name}">\n'
)
compat_script = '<script defer src="/assets/uifix-v3/chrome109-currentcolor-fdbf6e1dfa1c39dca627.js"></script>\n'
if "</head>" not in index or "</body>" not in index:
    raise SystemExit("Unexpected HTML entry structure")
index = index.replace("</head>", css_links + "</head>", 1)
index = index.replace("</body>", compat_script + "</body>", 1)
(package / "payload" / "index.html").write_text(index, encoding="utf-8", newline="\n")

installed = []
for path in sorted(assets.iterdir()):
    if path.is_file():
        relative = f"assets/uifix-v3/{path.name}"
        installed.append(f"{digest(path)}  {relative}")
text = "\n".join(installed) + "\n"
(package / "installed-assets.sha256").write_text(text, encoding="ascii", newline="\n")
(package / "new-assets.sha256").write_text(text, encoding="ascii", newline="\n")
for obsolete in ("entry-map.txt", "original-assets.sha256", "rollback.sh"):
    (package / obsolete).unlink(missing_ok=True)

manifest_paths = sorted(path for path in package.rglob("*")
                        if path.is_file() and path.name != "manifest.sha256")
manifest = "".join(f"{digest(path)}  {path.relative_to(package).as_posix()}\n"
                   for path in manifest_paths)
(package / "manifest.sha256").write_text(manifest, encoding="ascii", newline="\n")

with tarfile.open(archive, "w:gz") as tar:
    for path in sorted(package.rglob("*")):
        info = tar.gettarinfo(str(path), arcname=path.relative_to(package).as_posix())
        info.uid = info.gid = 0
        info.uname = info.gname = "root"
        info.mode = 0o755 if path.is_dir() or path.suffix == ".sh" else 0o644
        if path.is_dir():
            tar.addfile(info)
        else:
            with path.open("rb") as stream:
                tar.addfile(info, stream)

archive_hash = digest(archive)
archive.with_name(archive.name + ".sha256").write_text(
    f"{archive_hash}  {archive.name}\n", encoding="ascii", newline="\n")
print(f"ARCHIVE={archive}")
print(f"SHA256={archive_hash}")
print(f"BYTES={archive.stat().st_size}")
print(f"FILES={len(manifest_paths)}")
