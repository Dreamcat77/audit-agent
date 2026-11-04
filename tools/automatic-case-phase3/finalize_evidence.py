"""Hash final source changes against the protected pre-phase-three backup. Never restores or clears."""
from pathlib import Path
import difflib
import hashlib
import json

root=Path(__file__).resolve().parents[2]
backup=Path((root.parent/'phase3-backup-location.txt').read_text(encoding='utf-8-sig').strip())
ev=root/'docs/automatic-procurement-phase3-20261027/evidence'
before=json.loads((backup/'source-manifest.json').read_text(encoding='utf-8-sig'))
lookup={x['path']:x for x in before['files']}
prefixes=('backend/src/','frontend/src/','services/audit-ai/src/','services/audit-ai/tests/','db/dm8/','tools/automatic-case-phase3/')
paths={p for p in lookup if p.startswith(prefixes)}
for prefix in prefixes:
    parent=root/prefix
    if parent.exists():
        paths.update(p.relative_to(root).as_posix() for p in parent.rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix not in {'.pyc','.class'})
changed=[];diff=[];untouched=0
for rel in sorted(paths):
    p=root/rel;old=lookup.get(rel)
    if not p.exists():
        if old:raise RuntimeError('preexisting source file was deleted: '+rel)
        continue
    data=p.read_bytes();sha=hashlib.sha256(data).hexdigest()
    if old and sha==old['sha256']:untouched+=1;continue
    changed.append({'path':rel,'change':'MODIFIED' if old else 'ADDED','before':old['sha256'] if old else None,'after':sha,'bytes':len(data)})
    old_text=(backup/'source'/rel).read_text(encoding='utf-8-sig') if old else ''
    diff.extend(difflib.unified_diff(old_text.splitlines(True),data.decode('utf-8-sig').splitlines(True),fromfile='phase2/'+rel,tofile='phase3/'+rel))
assert not any(x['path'].startswith('db/dm8/') for x in changed)
expected={x['path'] for x in changed if x['change']=='MODIFIED'}
unexpected=[];preserved=0
for rel,old in lookup.items():
    if rel in expected:continue
    p=root/rel
    if not p.exists():unexpected.append({'path':rel,'reason':'MISSING'});continue
    h=hashlib.sha256()
    with p.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    if h.hexdigest()!=old['sha256']:unexpected.append({'path':rel,'reason':'CHANGED_OUTSIDE_PHASE3_SOURCE'})
    else:preserved+=1
manifest={'git':before['git'],'backup':str(backup),'phase2SourceFilesMatchAtStart':before['phase2SourceFilesMatch'],
    'unchangedSourceFiles':untouched,'changedSourceFiles':changed,'databaseDdlChanged':False,
    'preservedBaselineFilesOutsideModifiedSource':preserved,'unexpectedBaselineChanges':unexpected}
(ev/'phase3-source-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
(ev/'phase3-vs-phase2-source.diff').write_text(''.join(diff),encoding='utf-8')
assert not unexpected,'unexpected preexisting-file modifications; inspect protected evidence'
print(json.dumps({'changedSourceFiles':len(changed),'preservedBaselineFiles':preserved,'unexpectedBaselineChanges':len(unexpected),'ddlChanged':False}))
