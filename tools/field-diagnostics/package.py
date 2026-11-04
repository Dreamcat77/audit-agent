"""Build a small diagnostic-only delivery. Never include collected customer/test data."""
import hashlib
import json
from pathlib import Path
import struct
import tarfile

ROOT=Path(__file__).resolve().parent
DEST=ROOT.parents[3]/'deliverables'
DEST.mkdir(exist_ok=True)
folder=DEST/'FieldDiag_v1';folder.mkdir(exist_ok=True)
names=('run.sh','collect.py','probe.py','FieldDiag.java','FieldDiag.class','vocabulary.json','README.txt','test_diagnostics.py')
for name in names:
    content=(ROOT/name).read_bytes()
    if name.endswith(('.sh','.py','.java','.json','.txt')):content=content.replace(b'\r\n',b'\n')
    (folder/name).write_bytes(content)
assert struct.unpack('>H',(folder/'FieldDiag.class').read_bytes()[6:8])[0]==61
manifest=''.join(hashlib.sha256((folder/n).read_bytes()).hexdigest()+'  '+n+'\n' for n in names)
(folder/'SHA256SUMS').write_bytes(manifest.encode('ascii'))
path=DEST/'FieldDiag_v1.tar.gz'
with tarfile.open(path,'w:gz') as tar:
    for name in (*names,'SHA256SUMS'):tar.add(folder/name,arcname='FieldDiag_v1/'+name)
with tarfile.open(path) as tar:
    assert sorted(tar.getnames())==sorted('FieldDiag_v1/'+n for n in (*names,'SHA256SUMS'))
    for line in manifest.splitlines():
        digest,name=line.split('  ');assert hashlib.sha256(tar.extractfile('FieldDiag_v1/'+name).read()).hexdigest()==digest
    for name in (*names,'SHA256SUMS'):
        if not name.endswith('.class'):assert b'\r\n' not in tar.extractfile('FieldDiag_v1/'+name).read()
digest=hashlib.sha256(path.read_bytes()).hexdigest()
(DEST/(path.name+'.sha256')).write_bytes((digest+'  '+path.name+'\n').encode('ascii'))
print(json.dumps({'package':str(path),'sha256':digest,'files':len(names)+1,'javaMajor':61,'diagnosticOnly':True}))
