"""Build a separate pilot release from the verified dirty worktree, never HEAD."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import zipfile
import argparse

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / 'docs/automatic-procurement-deploy26-20261027'
BUILD = ROOT / '.deploy26-private/build'


def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    global BUILD
    parser = argparse.ArgumentParser()
    parser.add_argument('--revision', choices=['v1', 'v2', 'v3', 'v4', 'v5', 'v6', 'v7'], default='v1')
    revision = parser.parse_args().revision
    if revision != 'v1': BUILD = ROOT / ('.deploy26-private/build-' + revision)
    assert not BUILD.exists(), 'inspect previous package staging instead of overwriting'
    BUILD.mkdir(parents=True)
    stage = BUILD / 'release'; stage.mkdir()
    jar = ROOT / 'backend/target-deploy26/audit-backend-0.1.0-SNAPSHOT.jar'
    assert json.loads((DOC / 'evidence/targeted-backend-outcome.json').read_text())['exitCode'] == 0
    assert json.loads((DOC / 'evidence/backend-package-outcome.json').read_text())['exitCode'] == 0
    (stage / 'backend').mkdir(); shutil.copy2(jar, stage / 'backend/audit-backend.jar')
    shutil.copytree(ROOT / 'frontend/dist-autocase-pilot', stage / 'frontend')
    shutil.copytree(ROOT / 'services/audit-ai/src', stage / 'services/audit-ai/src', ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    for name in ['pyproject.toml', 'requirements-runtime.txt', 'requirements-test.txt']:
        shutil.copy2(ROOT / 'services/audit-ai' / name, stage / 'services/audit-ai' / name)
    tools = stage / 'tools'; tools.mkdir()
    shutil.copy2(ROOT / 'tools/automatic-case-deploy26/remote_deploy.py', tools / 'remote_deploy.py')
    for name, src in {
        'AutoCaseSchemaCheck.java': 'tools/automatic-case-phase2/AutoCaseSchemaCheck.java',
        'CustomerContractSchema.java': 'scripts/offline/customer-v10/CustomerContractSchema.java',
        'SemanticBudgetExpression.java': 'scripts/offline/customer-v10/SemanticBudgetExpression.java',
        'PilotAdmin.java': 'tools/automatic-case-deploy26/PilotAdmin.java',
        'PilotReadOnlyCheck.java': 'tools/automatic-case-deploy26/PilotReadOnlyCheck.java',
        'MetadataSnapshot.java': 'tools/automatic-case-deploy26/MetadataSnapshot.java',
        'PROCUREMENT__automatic_case_v1.sql': 'db/dm8/PROCUREMENT__automatic_case_v1.sql',
        'pilot-support-schema.json': 'docs/automatic-procurement-deploy26-20261027/evidence/pilot-support-schema.json',
    }.items(): shutil.copy2(ROOT / src, tools / name)
    shutil.copy2(ROOT / 'tools/automatic-case-deploy26/final_evidence.py', tools / 'final_evidence.py')
    libs = BUILD / 'compile-libs'; libs.mkdir()
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libs / Path(name).name).write_bytes(z.read(name))
    classes = tools / 'classes'; classes.mkdir()
    java_home = Path(os.environ['JAVA_HOME'])
    classpath = str(ROOT / 'backend/target-deploy26/classes') + os.pathsep + str(libs / '*')
    args = [str(java_home / 'bin/javac.exe'), '--release', '17', '-encoding', 'UTF-8', '-cp', classpath,
            '-d', str(classes)] + [str(p) for p in tools.glob('*.java')]
    r = subprocess.run(args, capture_output=True)
    (DOC / 'evidence/checker-compile.log').write_bytes(r.stdout + r.stderr)
    assert r.returncode == 0, 'checker compilation failed; inspect log'
    for p in classes.glob('*.class'):
        assert int.from_bytes(p.read_bytes()[6:8], 'big') == 61
    source = json.loads((ROOT / 'docs/automatic-procurement-phase3-20261027/evidence/phase3-source-manifest.json').read_text(encoding='utf-8'))
    for f in source['changedSourceFiles']:
        assert sha(ROOT / f['path']) == f['after'], 'phase-three source drift: ' + f['path']
    manifest = {'version': 'autocase-stage3-pilot-20261027', 'git': source['git'],
                'builtFromVerifiedWorktree': True, 'javaClassMajor': 61, 'files': []}
    for p in sorted(stage.rglob('*')):
        if p.is_file(): manifest['files'].append({'path': p.relative_to(stage).as_posix(), 'bytes': p.stat().st_size, 'sha256': sha(p)})
    (stage / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    tarpath = ROOT / ('deliverables/Autocase_Stage3_Pilot_20261027' + ('' if revision == 'v1' else '_' + revision) + '.tar.gz')
    assert not tarpath.exists(), 'existing package requires inspection'
    with tarfile.open(tarpath, 'w:gz') as tar:
        for p in sorted(stage.iterdir()): tar.add(p, arcname=p.name)
    # Verify all final archive members against the inner manifest without extracting secrets.
    with tarfile.open(tarpath) as tar:
        for item in manifest['files']:
            data = tar.extractfile(item['path']).read()
            assert len(data) == item['bytes'] and hashlib.sha256(data).hexdigest() == item['sha256']
    result = {'package': str(tarpath), 'sha256': sha(tarpath), 'bytes': tarpath.stat().st_size,
              'innerFilesVerified': len(manifest['files']), 'checkerClassMajor': 61,
              'backendJarSha256': sha(jar), 'verifiedPhase3SourceUnchanged': True}
    (DOC / ('evidence/package-manifest' + ('' if revision == 'v1' else '-' + revision) + '.json')).write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__': main()
