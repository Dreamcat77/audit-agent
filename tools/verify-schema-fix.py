"""Extract and validate the final flat patch archive, then run adversarial artifact tests."""
from pathlib import Path, PurePosixPath
import hashlib
import importlib.util
import subprocess
import sys
import tarfile
import tempfile

root = Path(__file__).resolve().parents[1]
archive = Path(sys.argv[1]).resolve()
expected = archive.with_name(archive.name+'.sha256').read_text(encoding='utf-8').split()[0]
assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected
with tempfile.TemporaryDirectory(prefix='schema-final-archive-', dir=root/'.runtime') as temporary:
    destination = Path(temporary)
    with tarfile.open(archive, 'r:gz') as tar:
        names = set()
        for member in tar.getmembers():
            path = PurePosixPath(member.name)
            assert not path.is_absolute() and '..' not in path.parts
            assert not member.issym() and not member.islnk()
            assert member.isdir() or member.isfile()
            assert member.name not in names
            names.add(member.name)
        tar.extractall(destination, filter='data')
    assert (destination/'apply.sh').is_file(), 'archive must be flat'
    spec = importlib.util.spec_from_file_location('final_patch', destination/'patch.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    for name, sha in module.inventory(destination).items():
        assert module.digest(destination/name) == sha
    classes = 0
    for file in destination.rglob('*'):
        if not file.is_file():
            continue
        if file.suffix in {'.java', '.py', '.sh', '.txt', '.sha256'}:
            assert b'\r' not in file.read_bytes(), 'LF required'
        if file.suffix == '.class':
            assert file.read_bytes()[:8] == bytes.fromhex('cafebabe0000003d')
            classes += 1
    assert classes == 4
    print('FINAL_ARCHIVE_FLAT_MANIFEST_LF_JAVA17=PASS', flush=True)
    subprocess.run([sys.executable, str(root/'tools/test-schema-fix.py'), str(destination)], check=True)
    java = Path('C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin/java.exe')
    classpath = str(destination/'replacement/tools')+';'+str(root/'.runtime/v10-1-check-a/build-runtime/BOOT-INF/lib/*')
    subprocess.run([str(java), '-cp', classpath, 'CustomerContractSchemaTest', str(root/'.runtime/v10-1-check-b/schema.json')], check=True)
    bash = Path('C:/Users/29339/AppData/Local/GitHubDesktop/app-3.5.12/resources/app/git/usr/bin/sh.exe')
    subprocess.run([str(bash), '-n', str(destination/'apply.sh')], check=True)
print('FINAL_ARCHIVE_ALL_CHECKS=PASS SHA256='+expected)
