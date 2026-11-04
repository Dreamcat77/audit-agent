"""Frontend update using the existing verified atomic entry/rollback scripts."""
import argparse
import hashlib
import re
import shutil
import tarfile
from pathlib import Path

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('dist',type=Path)
parser.add_argument('output',type=Path)
parser.add_argument('baselines',nargs='+',type=Path)
parser.add_argument('--name',default='AuditAgent_Procurement_Simple_20261010')
parser.add_argument('--readme',type=Path,default=root/'docs/procurement-simple-materials.md')
args=parser.parse_args()
name=args.name
assert re.fullmatch(r'[A-Za-z0-9_-]+',name)
package=args.output/name
package.mkdir(parents=True,exist_ok=False)
digest=lambda data:hashlib.sha256(data).hexdigest()
entries=[]
def add_variant(variant,baseline):
    folder=package/'variants'/variant
    shutil.copytree(args.dist,folder/'dist')
    html=(baseline/'index.html').read_bytes()
    refs=sorted(set(re.findall(r'(?:src|href)=["\'](/assets/[^"\']+)["\']',html.decode())))
    assert refs
    lines=[]
    for ref in refs:
        assert '..' not in ref and '?' not in ref
        lines.append(digest((baseline/ref.lstrip('/')).read_bytes())+'  '+ref.lstrip('/')+'\n')
    (folder/'original-assets.sha256').write_text(''.join(lines),encoding='utf-8',newline='\n')
    entries.append(digest(html)+' '+variant+'\n')
for i,baseline in enumerate(args.baselines,1):
    add_variant(f'r{i}',baseline)
# A repeated update verifies its own current assets, regardless of the original baseline.
add_variant(f'r{len(args.baselines)+1}',args.dist)
assert len({line.split()[0] for line in entries})==len(entries)
(package/'entry-map.txt').write_text(''.join(entries),encoding='utf-8',newline='\n')
for file in ('update.sh','rollback.sh'):
    script=(root/'scripts/offline/procurement-header'/file).read_text(encoding='utf-8')
    script=script.replace('header-font-20261010','material-simple-20261010').replace('.index-header-','.index-material-simple-')
    script=script.replace('采购表头离线中文字体及HTTP检查通过。请Ctrl+F5刷新。','材料界面更新及HTTP检查通过。请Ctrl+F5刷新。')
    script=script.replace('原页面JS/CSS、采购上传行为、Nginx、后端、AI配置和业务数据保留；未重启服务。','更新前端界面；Nginx、后端、AI配置和业务数据保留，未重启服务。')
    script=script.replace('表头补丁已撤销。','前端界面更新已撤销。')
    (package/file).write_text(script,encoding='utf-8',newline='\n')
readme=args.readme.read_text(encoding='utf-8')
(package/'README.md').write_text(readme,encoding='utf-8',newline='\n')
manifest=''.join(digest(file.read_bytes())+'  '+file.relative_to(package).as_posix()+'\n'
                 for file in sorted(package.rglob('*')) if file.is_file())
(package/'manifest.sha256').write_text(manifest,encoding='utf-8',newline='\n')
archive=args.output/(name+'.tar.gz')
with tarfile.open(archive,'w:gz') as tar:
    for file in sorted(package.rglob('*')):
        if not file.is_file():continue
        info=tar.gettarinfo(str(file),name+'/'+file.relative_to(package).as_posix())
        info.uid=info.gid=0;info.uname=info.gname='root';info.mode=0o755 if file.suffix=='.sh' else 0o644
        with file.open('rb') as source:tar.addfile(info,source)
with tarfile.open(archive) as tar:
    assert all(item.isfile() and '..' not in Path(item.name).parts for item in tar)
    for file in package.rglob('*'):
        if file.is_file():assert tar.extractfile(name+'/'+file.relative_to(package).as_posix()).read()==file.read_bytes()
checksum=digest(archive.read_bytes())
(args.output/(archive.name+'.sha256')).write_text(checksum+'  '+archive.name+'\n',encoding='utf-8',newline='\n')
# Test server uses the same layout but predates the offline marker. Its adaptation is not delivered.
deploy=(package/'update.sh').read_text(encoding='utf-8')
deploy=deploy.replace('[[ -f "${install_root%/}/etc/audit-agent/portable-install.marker" ]] || die \'离线安装尚未完成\'\n','')
(args.output/'update26.sh').write_text(deploy,encoding='utf-8',newline='\n')
print(archive)
print('SHA256='+checksum)
print('BYTES='+str(archive.stat().st_size))
