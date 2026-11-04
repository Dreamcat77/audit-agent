"""Create a new diagnostic-only archive, never include customer artifacts."""
import ast,hashlib,json,struct,tarfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent
DEST=ROOT.parents[3]/'deliverables'
names=('run.sh','collect.py','probe.py','report.py','FieldDiag.java','FieldDiag.class','vocabulary.json','README.txt','test_diagnostics.py','test_scan.py')
folder=DEST/'ScanDiag_v1';archive=DEST/'ScanDiag_v1.tar.gz'
assert not folder.exists() and not archive.exists(),'Preserve prior delivery; choose a new version'
folder.mkdir(parents=True)
for name in names:
    data=(ROOT/name).read_bytes()
    if not name.endswith('.class'):data=data.replace(b'\r\n',b'\n')
    if name.endswith('.py'):ast.parse(data.decode('utf-8'))
    (folder/name).write_bytes(data)
assert struct.unpack('>H',(folder/'FieldDiag.class').read_bytes()[6:8])[0]==61
manifest=''.join(hashlib.sha256((folder/name).read_bytes()).hexdigest()+'  '+name+'\n' for name in names)
(folder/'SHA256SUMS').write_bytes(manifest.encode())
with tarfile.open(archive,'w:gz') as tar:
    for name in (*names,'SHA256SUMS'):
        p=folder/name;info=tar.gettarinfo(str(p),'ScanDiag_v1/'+name);info.uid=info.gid=0;info.uname=info.gname='root';info.mode=0o755 if name.endswith('.sh') else 0o644
        with p.open('rb') as stream:tar.addfile(info,stream)
with tarfile.open(archive) as tar:
    assert sorted(tar.getnames())==sorted('ScanDiag_v1/'+name for name in (*names,'SHA256SUMS'))
    for line in manifest.splitlines():
        digest,name=line.split('  ');assert hashlib.sha256(tar.extractfile('ScanDiag_v1/'+name).read()).hexdigest()==digest
    for name in names:
        if not name.endswith('.class'):assert b'\r' not in tar.extractfile('ScanDiag_v1/'+name).read()
digest=hashlib.sha256(archive.read_bytes()).hexdigest()
(DEST/(archive.name+'.sha256')).write_bytes((digest+'  '+archive.name+'\n').encode())
print(json.dumps({'archive':str(archive),'bytes':archive.stat().st_size,'sha256':digest,'files':len(names)+1,'javaMajor':61,'inventory':'PASS','LF':'PASS','dataIncluded':False}))
