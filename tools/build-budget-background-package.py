"""Offline code-only update. No environment files, model settings or business data."""
from pathlib import Path
import argparse
import hashlib
import shutil
import subprocess
import tarfile

root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('dist',type=Path);p.add_argument('output',type=Path);p.add_argument('jdk',type=Path);a=p.parse_args()
name='AuditAgent_Budget_Background_20261010';package=a.output/name;package.mkdir(parents=True,exist_ok=False)
shutil.copytree(root/'scripts/offline/budget-background',package,dirs_exist_ok=True)
(package/'payload/backend').mkdir(parents=True);(package/'tools').mkdir()
shutil.copy2(root/'backend/target/audit-backend-0.1.0-SNAPSHOT.jar',package/'payload/backend')
shutil.copytree(a.dist,package/'payload/frontend/dist')
for tool in ('BudgetMaterialMigration','MaterialAsyncMigration'):
    source=root/f'db/dm8/tools/{tool}.java';shutil.copy2(source,package/'tools')
    subprocess.run([str(a.jdk/'bin/javac.exe'),'--release','17','-d',str(package/'tools'),str(source)],check=True)
shutil.copy2(root/'db/dm8/V051__budget_material_background.sql',package/'tools')
shutil.copy2(root/'docs/budget-background-materials.md',package/'README.md')
for file in package.rglob('*'):
    if file.suffix in ('.sh','.sql','.java','.md'):file.write_text(file.read_text(encoding='utf-8'),encoding='utf-8',newline='\n')
digest=lambda b:hashlib.sha256(b).hexdigest()
(package/'manifest.sha256').write_text(''.join(digest(file.read_bytes())+'  '+file.relative_to(package).as_posix()+'\n' for file in sorted(package.rglob('*')) if file.is_file()),encoding='utf-8',newline='\n')
archive=a.output/(name+'.tar.gz')
with tarfile.open(archive,'w:gz') as tar:
    for file in sorted(package.rglob('*')):
        if not file.is_file():continue
        info=tar.gettarinfo(str(file),name+'/'+file.relative_to(package).as_posix());info.uid=info.gid=0;info.uname=info.gname='root';info.mode=0o755 if file.suffix=='.sh' else 0o644
        with file.open('rb') as stream:tar.addfile(info,stream)
with tarfile.open(archive) as tar:
    for file in package.rglob('*'):
        if file.is_file():assert tar.extractfile(name+'/'+file.relative_to(package).as_posix()).read()==file.read_bytes()
hashvalue=digest(archive.read_bytes());(a.output/(archive.name+'.sha256')).write_text(hashvalue+'  '+archive.name+'\n',encoding='utf-8',newline='\n')
deploy=(package/'update.sh').read_text(encoding='utf-8').replace("[[ -f /etc/audit-agent/portable-install.marker ]] || die '离线安装尚未完成'\n",'')
(a.output/'update26.sh').write_text(deploy,encoding='utf-8',newline='\n')
print(archive);print('SHA256='+hashvalue);print('BYTES='+str(archive.stat().st_size))
