"""Prepare a reviewable Git allowlist and scan its contents without printing secrets.

Does not stage or commit. Excludes binaries, archives, raw reports and runtime data.
"""
from pathlib import Path
import collections
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / '.test-runtime/delivery'
OUT.mkdir(parents=True, exist_ok=True)
PREFIXES = ('backend/src/', 'frontend/src/', 'services/audit-ai/src/', 'services/audit-ai/tests/',
            'db/dm8/', 'scripts/offline/', 'tools/',
            'tools/automatic-case-phase2/', 'tools/automatic-case-phase3/', 'tools/automatic-case-deploy26/',
            'docs/contract-automation/', 'docs/qa-26/', 'docs/automatic-procurement-', 'docs/delivery/')
EXACT = {'.gitignore', 'DEPLOYMENT-MEMORY.md', 'tools/build-customer-v10.py',
         'tools/test-budget-material-risk26.py', 'tools/test-budget-material-split26.py',
         'tools/test-office-formats26.py', 'tools/remote-acceptance-session.py',
         'frontend/pilot.html', 'frontend/vite.pilot.config.ts', 'docs/runbook/customer-offline-deployment.md'}
REJECT_SUFFIX = {'.log', '.class', '.pyc', '.jar', '.xlsx', '.zip', '.gz', '.tar', '.pdf', '.docx', '.png', '.jpg'}
PATTERNS = {
    'private-key': re.compile(r'-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----'),
    'customer-address': re.compile(r'\b38\.4\.\d{1,3}\.\d{1,3}\b'),
    'literal-model-key': re.compile(r'\bsk-[A-Za-z0-9_-]{20,}'),
    'literal-bearer': re.compile(r'(?i)bearer\s+[A-Za-z0-9_=-]{24,}'),
    'private-field-value': re.compile(r'(?i)["\'](?:AUDIT_DB_PASSWORD|AUDIT_REDIS_PASSWORD|api_key|ssh_password|password)["\']\s*:\s*["\'][^"\'${\s][^"\']{7,}["\']'),
}

files = set()
for args in (['git', 'diff', '--name-only', '-z', 'HEAD'], ['git', 'ls-files', '--others', '--exclude-standard', '-z']):
    files.update(subprocess.check_output(args, cwd=ROOT).decode('utf-8').split('\0'))
selected, excluded, findings = [], [], []
for name in sorted(files - {''}):
    path = ROOT / name
    allowed = name in EXACT or name.startswith(PREFIXES)
    if name.startswith('docs/') and not (path.suffix == '.md' or name.startswith('docs/qa-26/evidence/') and path.suffix == '.json'):
        allowed = False
    if not allowed or not path.is_file() or path.suffix.lower() in REJECT_SUFFIX or '__pycache__' in path.parts or '/raw/' in name or '-raw.' in name or '-private.' in name:
        excluded.append(name); continue
    try:
        text = path.read_text(encoding='utf-8')
    except UnicodeError:
        excluded.append(name); continue
    for code, pattern in PATTERNS.items():
        for match in pattern.finditer(text):
            findings.append({'file': name, 'line': text[:match.start()].count('\n') + 1, 'kind': code})
    selected.append(name)
(OUT / 'source-allowlist.txt').write_text(''.join(n + '\n' for n in selected), encoding='utf-8', newline='\n')
(OUT / 'excluded-files.json').write_text(json.dumps(excluded, ensure_ascii=False, indent=2), encoding='utf-8')
report = {'files': len(selected), 'byRoot': dict(collections.Counter(p.split('/')[0] for p in selected)),
          'excluded': len(excluded), 'findings': findings, 'secretsPrinted': False, 'staged': False}
(OUT / 'source-scan.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False))
