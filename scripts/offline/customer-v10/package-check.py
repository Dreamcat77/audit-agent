"""Verify code inventory before executing it; paths must remain inside the package."""
import hashlib
from pathlib import Path, PurePosixPath
import re
import sys

root=Path(sys.argv[1]).resolve(); expected={}
for line in (root/'manifest.sha256').read_text(encoding='utf-8').splitlines():
    match=re.fullmatch(r'([a-f0-9]{64})  ([A-Za-z0-9_.$/-]+)',line)
    if not match:raise SystemExit('MANIFEST_FORMAT')
    digest,name=match.groups();parts=PurePosixPath(name)
    if parts.is_absolute() or '..' in parts.parts or name in expected:raise SystemExit('MANIFEST_PATH')
    if parts.name in {'.env','backend.env','audit-ai.env','id_rsa','id_ed25519'} or parts.suffix.lower() in {'.pem','.key'}:raise SystemExit('PRIVATE_FILE_IN_PACKAGE')
    path=root/name
    if not path.is_file() or path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise SystemExit('MANIFEST_HASH')
    expected[name]=digest
for prefix in ('payload','tools','scripts','db'):
    for path in (root/prefix).rglob('*'):
        if path.is_file() and path.relative_to(root).as_posix() not in expected:raise SystemExit('UNREGISTERED_CODE')
for path in root.iterdir():
    if path.is_file() and path.suffix in ('.sh','.py','.json') and path.name not in expected:raise SystemExit('UNREGISTERED_SCRIPT')
print('PACKAGE_INVENTORY=PASS')
