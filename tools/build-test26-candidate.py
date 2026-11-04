"""Assemble a test-server candidate from verified outputs, with exact source fingerprints."""
import ast
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    output = Path(sys.argv[1]).resolve()
    dist = Path(sys.argv[2]).resolve()
    output.mkdir(parents=True, exist_ok=False)
    previous = ROOT / '.runtime/rotation-all-r9'
    shutil.copytree(previous / 'tools', output / 'tools',
                    ignore=shutil.ignore_patterns('*.class', '__pycache__'))
    shutil.copytree(previous / 'db', output / 'db')
    for filename in ('ContractAutomaticAuthorityMigration.java', 'BudgetBackgroundRunner.java',
                     'test-procurement-intake-review26.py', 'test-contract-rotation-application.py',
                     'test-contract-result-quality.py', 'test-office-formats26.py'):
        shutil.copy2(ROOT / 'tools' / filename, output / 'tools' / filename)
    for filename in ('CustomerContractSchema.java', 'SemanticBudgetExpression.java'):
        shutil.copy2(ROOT / 'scripts/offline/customer-v10' / filename, output / 'tools' / filename)
    shutil.copy2(ROOT / 'db/dm8/CONTRACT__contract_system_auto_authority.sql', output / 'db')
    shutil.copy2(ROOT / 'db/dm8/CONTRACT__project_file_processing_job.sql', output / 'db')
    for label in ('release.txt', 'jar.sha256', 'index.sha256'):
        shutil.copy2(ROOT / '.runtime' / ('test26-previous-' + label), output / ('previous-' + label))
    (output / 'payload/backend').mkdir(parents=True)
    shutil.copy2(ROOT / 'backend/target/audit-backend-0.1.0-SNAPSHOT.jar', output / 'payload/backend')
    shutil.copytree(dist, output / 'payload/frontend/dist')
    for source in (ROOT / 'services/audit-ai/src').rglob('*.py'):
        ast.parse(source.read_text(encoding='utf-8'))
        dest = output / 'payload' / source.relative_to(ROOT)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, dest)
    deployment = (output / 'tools/deploy.sh').read_text(encoding='utf-8')
    deployment = deployment.replace('rotation-all-20261022', 'auto-intake-20261026')
    deployment = deployment.replace('BUDGET_PROCUREMENT_DEPLOY', 'THREE_MODULE_DEPLOY')
    anchor = 'if [[ ${1:-} == --check ]]; then'
    deployment = deployment.replace(anchor,
        'guard ContractAutomaticAuthorityMigration check "$package/db/CONTRACT__contract_system_auto_authority.sql" "$package/auto-schema-journal"\n' + anchor)
    deployment = deployment.replace('# V057 already deployed; this release performs no schema mutation.',
        'guard ContractAutomaticAuthorityMigration apply "$package/db/CONTRACT__contract_system_auto_authority.sql" "$backup/auto-schema-journal"\n'
        'guard ContractAutomaticAuthorityMigration check "$package/db/CONTRACT__contract_system_auto_authority.sql" "$backup/auto-schema-journal"')
    # The original deployment gate covers budget/procurement. Include contract tasks
    # before stopping services, so this candidate cannot interrupt a live formal review.
    deployment = deployment.replace('guard MaterialDeploymentReadyCheck', 'guard ThreeModuleDeploymentReadyCheck')
    (output / 'tools/deploy.sh').write_text(deployment, encoding='utf-8', newline='\n')
    shutil.copy2(ROOT / 'tools/ThreeModuleDeploymentReadyCheck.java', output / 'tools')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    diff = subprocess.check_output(['git', 'diff', 'HEAD'], cwd=ROOT)
    (output / 'VERSION.txt').write_text('release=auto-intake-20261026\nbaseCommit=' + commit
        + '\nworkingTree=DIRTY_CANDIDATE\ntrackedDiffSha256=' + hashlib.sha256(diff).hexdigest() + '\n', encoding='utf-8')
    source_files = []
    for folder in ('backend/src', 'services/audit-ai/src', 'frontend/src', 'db/dm8'):
        source_files.extend(p for p in (ROOT / folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    (output / 'source.sha256').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  '
        + p.relative_to(ROOT).as_posix() + '\n' for p in sorted(source_files)), encoding='utf-8', newline='\n')
    for p in output.rglob('*'):
        if p.is_file() and p.suffix in ('.sh', '.java', '.sql', '.py', '.txt'):
            p.write_text(p.read_text(encoding='utf-8'), encoding='utf-8', newline='\n')
    files = sorted(p for p in output.rglob('*') if p.is_file())
    (output / 'manifest.sha256').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  '
        + p.relative_to(output).as_posix() + '\n' for p in files), encoding='utf-8', newline='\n')
    archive = output.with_suffix('.tar.gz')
    assert not archive.exists()
    with tarfile.open(archive, 'w:gz') as bundle:
        for p in sorted(output.rglob('*')):
            bundle.add(p, arcname=p.relative_to(output).as_posix(), recursive=False)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_name(archive.name + '.sha256').write_text(digest + '  ' + archive.name + '\n', encoding='utf-8')
    print('TEST_CANDIDATE=' + str(archive) + '\nSHA256=' + digest)


if __name__ == '__main__':
    main()
