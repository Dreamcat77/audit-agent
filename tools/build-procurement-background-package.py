"""Package only verified application code; no env, database data or credentials."""
from pathlib import Path
import hashlib
import shutil
import subprocess
import sys
import tarfile

root = Path(__file__).resolve().parents[1]
if len(sys.argv) != 3:
    raise SystemExit('Usage: build-procurement-background-package.py <frontend-dist> <jdk17-directory>')
dist = Path(sys.argv[1]).resolve()
jdk = Path(sys.argv[2]).resolve()
name = 'AuditAgent_Procurement_Privacy_20261010'
out = root / '.runtime' / 'material-privacy-delivery-v1'
package = out / name
package.mkdir(parents=True, exist_ok=False)
shutil.copytree(root / 'scripts/offline/procurement-background', package, dirs_exist_ok=True)
(package / 'tools').mkdir()
(package / 'payload/backend').mkdir(parents=True)
shutil.copy2(root / 'backend/target/audit-backend-0.1.0-SNAPSHOT.jar', package / 'payload/backend')
shutil.copytree(dist, package / 'payload/frontend/dist')
for file in sorted((root / 'services/audit-ai/src/audit_ai').rglob('*.py')):
    target = package / 'payload' / file.relative_to(root)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(file, target)
for tool in ('MaterialAsyncMigration', 'MaterialOrderMigration'):
    source = root / f'db/dm8/tools/{tool}.java'
    shutil.copy2(source, package / 'tools')
    subprocess.run([str(jdk / 'bin/javac.exe'), '--release', '17', '-d', str(package / 'tools'), str(source)], check=True)
for migration in ('V048__procurement_material_order_100.sql', 'V050__procurement_material_async_queue.sql'):
    shutil.copy2(root / 'db/dm8' / migration, package / 'tools')
for file in ('scripts/collect-ai-diagnostics.sh', 'tools/ai-log-extract.py', 'docs/ai-local-model-diagnostics.md'):
    target = package / file
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(root / file, target)
# LF is required by the target shell and sha256sum's manifest parser.
for file in package.rglob('*'):
    if file.suffix in ('.sh', '.sql', '.java', '.md', '.txt'):
        with file.open('r', encoding='utf-8') as stream:
            content = stream.read()
        with file.open('w', encoding='utf-8', newline='\n') as stream:
            stream.write(content)
files = sorted(file for file in package.rglob('*') if file.is_file())
with (package / 'manifest.sha256').open('w', encoding='utf-8', newline='\n') as stream:
    for file in files:
        stream.write(hashlib.sha256(file.read_bytes()).hexdigest() + '  ' + file.relative_to(package).as_posix() + '\n')
archive = out / f'{name}.tar.gz'
with tarfile.open(archive, 'w:gz') as bundle:
    bundle.add(package, arcname=name)
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
with (out / f'{name}.tar.gz.sha256').open('w', encoding='utf-8', newline='\n') as stream:
    stream.write(digest + '  ' + archive.name + '\n')
print(str(archive))
print('SHA256=' + digest)
