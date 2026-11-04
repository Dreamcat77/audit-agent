"""Check scripts copied byte-for-byte from the sealed final package on Linux."""
import ast
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

archive = Path(sys.argv[1])
expected = sys.argv[2]
assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected
counts = {'shell': 0, 'python': 0}
with tempfile.TemporaryDirectory(prefix='audit-v11-syntax-') as temporary:
    root = Path(temporary)
    with tarfile.open(archive) as bundle:
        manifest = json.load(bundle.extractfile('syntax-manifest.json'))
        assert set(bundle.getnames()) == set(manifest) | {'syntax-manifest.json'}
        for member in bundle:
            if member.name == 'syntax-manifest.json':
                continue
            target = (root / member.name).resolve()
            assert target.is_relative_to(root) and member.isfile()
            data = bundle.extractfile(member).read()
            assert hashlib.sha256(data).hexdigest() == manifest[member.name]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            if target.suffix == '.sh':
                result = subprocess.run(['bash', '-n', str(target)], capture_output=True)
                assert result.returncode == 0, 'FINAL_PACKAGE_SHELL_SYNTAX_FAILED_' + member.name
                counts['shell'] += 1
            elif target.suffix == '.py':
                ast.parse(data, filename=member.name)
                counts['python'] += 1
assert counts['shell'] and counts['python']
result = {'source': 'sealed-v11-final-unpack', 'linuxBashSyntax': counts['shell'],
          'pythonAstParsed': counts['python'], 'allSubsetHashesVerified': True,
          'arm64Execution': 'NOT_VERIFIED', 'failures': []}
path = Path('/home/admin/audit-agent-backups/qa26-stage2-20261027/results/package-v11-syntax-verification.json')
path.write_text(json.dumps(result, indent=2))
print(json.dumps(result))
