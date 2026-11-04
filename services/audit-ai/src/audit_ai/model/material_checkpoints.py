"""Disposable validated extraction cache. Never a business record or raw-text store."""
from collections import OrderedDict
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import threading
import time


def digest(value):
    return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()


class MaterialCheckpoints:
    MAX_ENTRIES=4096
    MAX_BYTES=32*1024*1024
    MAX_RECORD_BYTES=1024*1024

    def __init__(self, root=None, ttl=900, clock=time.time):
        self.root=Path(root) if root else None
        self.ttl=ttl;self.clock=clock
        self.memory=OrderedDict();self.lock=threading.RLock()
        if self.root:
            if not self.root.is_absolute():
                raise ValueError('checkpoint root must be absolute')
            for part in [*reversed(self.root.parents), self.root]:
                if part.is_symlink():
                    raise ValueError('checkpoint root must not contain symlinks')
            self.root.mkdir(mode=0o700,exist_ok=True)
            info=self.root.stat()
            if os.name=='posix' and (info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode)&0o077):
                raise ValueError('checkpoint root must be private to the service account')

    def _name(self, operation, key):
        if not re.fullmatch('[a-f0-9]{64}',operation) or not re.fullmatch('[a-f0-9]{64}',key):
            raise ValueError('invalid checkpoint identity')
        return operation+'-'+key+'.json'

    def _files(self):
        return [p for p in self.root.iterdir() if re.fullmatch(r'[a-f0-9]{64}-[a-f0-9]{64}\.json',p.name)]

    def get(self, operation, key):
        name=self._name(operation,key)
        with self.lock:
            try:
                if self.root:
                    path=self.root/name
                    if path.is_symlink():return None
                    flags=os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_BINARY',0)
                    with os.fdopen(os.open(path,flags),'rb') as stream:
                        raw=stream.read(self.MAX_RECORD_BYTES+1)
                    if len(raw)>self.MAX_RECORD_BYTES:return None
                    record=json.loads(raw)
                else:
                    record=self.memory.get(name)
                if (not isinstance(record,dict) or record.get('identity')!=name
                        or record.get('digest')!=digest(record.get('value'))
                        or not isinstance(record.get('created'),(int,float))
                        or not 0<=self.clock()-record['created']<self.ttl):
                    if self.root:
                        try:(self.root/name).unlink()
                        except OSError:pass
                    else:self.memory.pop(name,None)
                    return None
                return record.get('value')
            except (OSError,ValueError):
                # A missing/corrupt cache is UNKNOWN, requiring a fresh model call.
                return None

    def put(self, operation, key, value):
        name=self._name(operation,key)
        record={'identity':name,'created':self.clock(),'value':value,'digest':digest(value)}
        raw=json.dumps(record,ensure_ascii=False).encode()
        if len(raw)>self.MAX_RECORD_BYTES:return False
        with self.lock:
            if not self.root:
                for old in list(self.memory):
                    if self.clock()-self.memory[old]['created']>=self.ttl:self.memory.pop(old,None)
                self.memory[name]=record
                while (len(self.memory)>self.MAX_ENTRIES or
                       sum(len(json.dumps(v,ensure_ascii=False).encode()) for v in self.memory.values())>self.MAX_BYTES):
                    self.memory.popitem(last=False)
                return True
            temporary=None
            try:
                entries=[]
                for path in self._files():
                    info=path.lstat()
                    if stat.S_ISREG(info.st_mode):
                        if self.clock()-info.st_mtime>=self.ttl:path.unlink()
                        else:entries.append((info.st_mtime,info.st_size,path))
                entries.sort()
                size=sum(e[1] for e in entries)
                while entries and (len(entries)>=self.MAX_ENTRIES or size+len(raw)>self.MAX_BYTES):
                    _,amount,path=entries.pop(0);path.unlink();size-=amount
                fd,temporary=tempfile.mkstemp(prefix='.material-',dir=self.root)
                with os.fdopen(fd,'wb') as stream:
                    stream.write(raw);stream.flush();os.fsync(stream.fileno())
                os.replace(temporary,self.root/name)
                return True
            except OSError:
                return False
            finally:
                if temporary:
                    try:os.unlink(temporary)
                    except OSError:pass

    def discard(self, operation):
        with self.lock:
            if self.root:
                try:files=self._files()
                except OSError:return
                for path in files:
                    if path.name.startswith(operation+'-'):
                        try:path.unlink()
                        except OSError:pass
            else:
                for name in list(self.memory):
                    if name.startswith(operation+'-'):del self.memory[name]
