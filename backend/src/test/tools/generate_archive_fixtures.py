"""One-time generator for fixed hostile ZIP test resources (Python 3.14 zipfile)."""
from pathlib import Path
import stat
import zipfile

OUTPUT = Path(__file__).parents[1] / "resources" / "archive-fixtures"
OUTPUT.mkdir(parents=True, exist_ok=True)

def write_zip(path: Path, info: zipfile.ZipInfo, payload: bytes, compression=zipfile.ZIP_STORED):
    with zipfile.ZipFile(path, "w", compression=compression) as archive:
        archive.writestr(info, payload, compress_type=compression)

ordinary = OUTPUT / "truncated.zip"
write_zip(ordinary, zipfile.ZipInfo("truncated.txt"), b"content")
ordinary.write_bytes(ordinary.read_bytes()[:-8])

crc = OUTPUT / "crc-mismatch.zip"
write_zip(crc, zipfile.ZipInfo("crc.txt"), b"expected")
crc.write_bytes(crc.read_bytes().replace(b"expected", b"fxpected", 1))

link = zipfile.ZipInfo("link")
link.create_system = 3
link.external_attr = (stat.S_IFLNK | 0o777) << 16
write_zip(OUTPUT / "symlink.zip", link, b"target.txt")

pipe = zipfile.ZipInfo("pipe")
pipe.create_system = 3
pipe.external_attr = (stat.S_IFIFO | 0o644) << 16
write_zip(OUTPUT / "non-regular.zip", pipe, b"x")

large = zipfile.ZipInfo("large.txt")
write_zip(OUTPUT / "high-ratio.zip", large, bytes(1024 * 1024), zipfile.ZIP_DEFLATED)
