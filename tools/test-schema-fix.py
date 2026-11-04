"""Adversarial tests against the actual minimal patch and pinned original release."""
from pathlib import Path
from unittest.mock import patch as mock_patch
import contextlib
import importlib.util
import io
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PATCH = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location('schema_patch', PATCH/'patch.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
ORIGINAL = ROOT/'.runtime/Audit_Update_v10_release4'

def snapshot(root):
    files = module.inventory(ORIGINAL)
    names = set(files) | module.ALLOWED | {'manifest.sha256'}
    return {name: module.digest(root/name) if (root/name).is_file() else None for name in names}

def blocked(patch, target, code):
    before = snapshot(target)
    try:
        module.install(patch, target)
    except RuntimeError as error:
        assert str(error) == code, (str(error), code)
    else:
        raise AssertionError('blocked patch accepted')
    assert snapshot(target) == before, 'blocked patch changed files'

with tempfile.TemporaryDirectory(prefix='schema-fix-tests-', dir=ROOT/'.runtime') as temporary:
    work = Path(temporary)
    def fresh(name):
        return Path(shutil.copytree(ORIGINAL, work/name))

    target = fresh('success')
    before = snapshot(target)
    (target/'update-v10.example.log').write_text('local log retained', encoding='utf-8')
    with contextlib.redirect_stdout(io.StringIO()):
        module.install(PATCH, target)
    module.verify(target, module.inventory(target))
    subprocess.run([sys.executable, '-I', str(target/'package-check.py'), str(target)], check=True)
    after = snapshot(target)
    assert all(after[name] == sha for name, sha in before.items()
               if name not in module.ALLOWED and name not in ('VERSION.txt', 'manifest.sha256'))
    assert (target/'update-v10.example.log').read_text(encoding='utf-8') == 'local log retained'
    assert len(list(target.glob('.schema-fix-v1.*'))) == 1
    with contextlib.redirect_stdout(io.StringIO()) as output:
        module.install(PATCH, target)
    assert 'ALREADY_APPLIED' in output.getvalue()
    assert snapshot(target) == after
    assert len(list(target.glob('.schema-fix-v1.*'))) == 1
    print('PATCH_SUCCESS_IDEMPOTENCE_UNCHANGED_APPLICATION=PASS')

    target = fresh('target-tamper')
    (target/'update-core.sh').write_bytes((target/'update-core.sh').read_bytes()+b'\n')
    blocked(PATCH, target, 'TARGET_HASH')
    print('PATCH_TARGET_TAMPER_BLOCKED=PASS')

    target = fresh('unexpected-code')
    (target/'tools/unregistered.py').write_text('pass\n', encoding='utf-8')
    blocked(PATCH, target, 'UNREGISTERED_CODE')
    print('PATCH_UNREGISTERED_CODE_BLOCKED=PASS')

    target = fresh('unknown-manifest')
    (target/'manifest.sha256').write_bytes((target/'manifest.sha256').read_bytes()+b'\n')
    blocked(PATCH, target, 'ONLY_ORIGINAL_V10_SUPPORTED')
    print('PATCH_UNKNOWN_MANIFEST_BLOCKED=PASS')

    changed_patch = Path(shutil.copytree(PATCH, work/'tampered-patch'))
    (changed_patch/'replacement/tools/SemanticBudgetExpression.class').write_bytes(b'broken')
    target = fresh('patch-tamper')
    blocked(changed_patch, target, 'PATCH_HASH')
    print('PATCH_REPLACEMENT_TAMPER_BLOCKED=PASS')

    target = fresh('write-failure')
    before = snapshot(target)
    original_replace = module.os.replace
    calls = [0]
    def interrupt(source, destination):
        calls[0] += 1
        if calls[0] == 6:
            raise OSError('injected write failure')
        return original_replace(source, destination)
    with mock_patch.object(module.os, 'replace', interrupt), contextlib.redirect_stdout(io.StringIO()) as output:
        try:
            module.install(PATCH, target)
        except OSError:
            pass
        else:
            raise AssertionError('write failure not injected')
    assert 'PATCH_RESTORE=PASS' in output.getvalue()
    assert snapshot(target) == before, 'rollback failed'
    module.verify(target, module.inventory(target))
    assert len(list(target.glob('.schema-fix-v1.*'))) == 1
    print('PATCH_MID_WRITE_FAILURE_FULL_RESTORE=PASS')

print('SCHEMA_FIX_ARTIFACT_REGRESSION=PASS scenarios=6')
