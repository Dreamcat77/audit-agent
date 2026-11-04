"""Patch only the pinned v10 deployment checker. No DB, process or environment access."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tempfile

BASE_MANIFEST = '00438ef8a5fd048e9d508fcdb5ae283a4cf29e3cb2322d3ad4fea7f0834539bf'
ALLOWED = {
    'tools/CustomerContractSchema.java', 'tools/CustomerContractSchema.class',
    'tools/CustomerContractSchemaTest.java', 'tools/CustomerContractSchemaTest.class',
    'tools/SemanticBudgetExpression.java', 'tools/SemanticBudgetExpression.class',
    'tools/SemanticBudgetExpression$Node.class',
}

def need(test, code):
    if not test:
        raise RuntimeError(code)

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def inventory(root, filename='manifest.sha256'):
    result = {}
    for line in (root / filename).read_text(encoding='utf-8').splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})  ([A-Za-z0-9_.$/-]+)', line)
        need(match is not None, 'MANIFEST_FORMAT')
        sha, name = match.groups()
        path = PurePosixPath(name)
        need(not path.is_absolute() and '..' not in path.parts and name not in result, 'MANIFEST_PATH')
        result[name] = sha
    return result

def verify(root, expected):
    need(not any(p.is_symlink() for p in root.rglob('*')), 'SYMLINK')
    for name, sha in expected.items():
        path = root / name
        need(path.is_file() and digest(path) == sha, 'TARGET_HASH')
    for prefix in ('tools', 'db', 'payload', 'scripts'):
        for path in (root / prefix).rglob('*'):
            need(not path.is_file() or path.relative_to(root).as_posix() in expected, 'UNREGISTERED_CODE')
    for path in root.iterdir():
        need(not (path.is_file() and path.suffix in ('.sh', '.py', '.json')) or path.name in expected, 'UNREGISTERED_ROOT_CODE')

def install(patch, target):
    need(target.is_dir() and not target.is_symlink(), 'TARGET_DIRECTORY')
    target = target.resolve()
    patch = patch.resolve()
    need(target != patch and not target.is_relative_to(patch) and not patch.is_relative_to(target), 'PATCH_MUST_BE_SEPARATE')
    contents = inventory(patch)
    for name, sha in contents.items():
        need((patch/name).is_file() and not (patch/name).is_symlink() and digest(patch/name) == sha, 'PATCH_HASH')
    files = {p.relative_to(patch/'replacement').as_posix() for p in (patch/'replacement').rglob('*') if p.is_file()}
    need(files == ALLOWED, 'PATCH_SCOPE')
    expected = inventory(patch, 'target-manifest.sha256')
    current_manifest = digest(target/'manifest.sha256')
    if current_manifest == digest(patch/'target-manifest.sha256'):
        verify(target, expected)
        print('PATCH=ALREADY_APPLIED version=v10.1')
        return
    need(current_manifest == BASE_MANIFEST, 'ONLY_ORIGINAL_V10_SUPPORTED')
    original = inventory(target)
    verify(target, original)
    need(set(expected) == set(original) | ALLOWED, 'NEW_MANIFEST_SCOPE')
    for name, sha in original.items():
        if name not in ALLOWED and name != 'VERSION.txt':
            need(expected[name] == sha, 'UNCHANGED_PAYLOAD_REQUIRED')
    for name in ALLOWED:
        path = patch/'replacement'/name
        need(expected[name] == digest(path), 'REPLACEMENT_HASH')
        if path.suffix == '.class':
            need(path.read_bytes()[:8] == bytes.fromhex('cafebabe0000003d'), 'JAVA17_REQUIRED')
    need(expected['VERSION.txt'] == digest(patch/'target-VERSION.txt'), 'VERSION_HASH')
    replacements = {name: patch/'replacement'/name for name in sorted(ALLOWED)}
    replacements['VERSION.txt'] = patch/'target-VERSION.txt'
    replacements['manifest.sha256'] = patch/'target-manifest.sha256'
    backup = Path(tempfile.mkdtemp(prefix='.schema-fix-v1.', dir=target))
    os.chmod(backup, 0o700)
    existed = {name: (target/name).is_file() for name in replacements}
    for name, present in existed.items():
        if present:
            destination = backup/'original'/name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target/name, destination)
    (backup/'scope.json').write_text(json.dumps(existed, indent=2)+'\n', encoding='utf-8')
    # Stage every file before changing any original byte.
    for name, source in replacements.items():
        staged = backup/'staged'/name
        staged.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, staged)
        os.chmod(staged, 0o644)
    installed = []
    try:
        for name in replacements:
            os.replace(backup/'staged'/name, target/name)
            installed.append(name)
        verify(target, expected)
    except BaseException:
        for name in reversed(installed):
            if existed[name]:
                shutil.copy2(backup/'original'/name, target/name)
            else:
                (target/name).unlink()
        print('PATCH_RESTORE=PASS backup='+str(backup))
        raise
    print('PATCH=PASS version=v10.1 JAVA17=PASS APP_PAYLOAD_UNCHANGED=YES')
    print('备份目录：'+str(backup))
    print('未连接数据库、未改服务配置、未重启服务。请在原更新目录先运行 sudo bash update.sh --check。')

if __name__ == '__main__':
    try:
        need(len(sys.argv) == 2, 'ARGUMENTS')
        install(Path(__file__).resolve().parent, Path(sys.argv[1]))
    except Exception as failure:
        reason = str(failure) if isinstance(failure, RuntimeError) and re.fullmatch('[A-Z0-9_]+', str(failure)) else 'PATCH_IO_ERROR'
        print('PATCH_BLOCKED reasonCode='+reason)
        raise SystemExit(1)
