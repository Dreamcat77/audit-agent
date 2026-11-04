"""Build a pinned v10 checker-only repair, retaining every application payload byte."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import shutil
import tarfile

ROOT=Path(__file__).resolve().parents[1]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    ap=argparse.ArgumentParser();ap.add_argument('compiled',type=Path);ap.add_argument('output',type=Path);args=ap.parse_args()
    original=ROOT/'.runtime/Audit_Update_v10_release4';output=args.output.resolve()
    output.mkdir(parents=True,exist_ok=False)
    source=ROOT/'scripts/offline/schema-fix-v1'
    for file in source.iterdir():
        if file.is_file():shutil.copy2(file,output/file.name)
    spec=importlib.util.spec_from_file_location('schema_patch',source/'patch.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    assert sha(original/'manifest.sha256')==module.BASE_MANIFEST
    new=module.inventory(original)
    for name in sorted(module.ALLOWED):
        path=args.compiled/name;dest=output/'replacement'/name;dest.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(path,dest)
        if dest.suffix=='.java':
            dest.write_text(dest.read_text(encoding='utf-8'),encoding='utf-8',newline='\n')
        new[name]=sha(dest)
    version=(original/'VERSION.txt').read_text(encoding='utf-8').replace('PACKAGE_VERSION=v10\n','PACKAGE_VERSION=v10.1\n')
    (output/'target-VERSION.txt').write_text(version,encoding='utf-8',newline='\n');new['VERSION.txt']=sha(output/'target-VERSION.txt')
    assert 'PACKAGE_VERSION=v10.1\n' in version
    (output/'target-manifest.sha256').write_text(''.join(value+'  '+name+'\n' for name,value in sorted(new.items())),encoding='utf-8',newline='\n')
    for path in output.rglob('*'):
        if path.is_file() and path.suffix in ('.sh','.py','.txt','.java'):
            path.write_text(path.read_text(encoding='utf-8'),encoding='utf-8',newline='\n')
    paths=sorted(path for path in output.rglob('*') if path.is_file())
    (output/'manifest.sha256').write_text(''.join(sha(path)+'  '+path.relative_to(output).as_posix()+'\n' for path in paths),encoding='utf-8',newline='\n')
    archive=output.with_suffix('.tar.gz');assert not archive.exists()
    with tarfile.open(archive,'w:gz') as tar:
        for path in sorted(output.rglob('*')):
            info=tar.gettarinfo(str(path),path.relative_to(output).as_posix());info.uid=info.gid=0;info.uname=info.gname='root';info.mode=0o755 if path.is_dir() or path.suffix=='.sh' else 0o644
            if path.is_dir():tar.addfile(info)
            else:
                with path.open('rb') as stream:tar.addfile(info,stream)
    archive.with_name(archive.name+'.sha256').write_text(sha(archive)+'  '+archive.name+'\n',encoding='utf-8',newline='\n')
    print('ARCHIVE='+str(archive)+' BYTES='+str(archive.stat().st_size)+' SHA256='+sha(archive))
if __name__=='__main__':main()
