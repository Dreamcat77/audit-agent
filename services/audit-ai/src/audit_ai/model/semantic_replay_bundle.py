"""Explicitly approved synthetic-only local capture, separate from DM8 truth.

No automatic retention, cleanup, upload or latest-input fallback. The caller
supplies an independently attested frozen identity, not provider-owned data.
"""
from contextlib import ExitStack
import base64
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from unittest.mock import patch
from uuid import UUID,uuid4

from audit_ai.errors import AuditAiError

MAX_CANDIDATE_BYTES=1048576


def canonical(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode('utf-8')


def digest(value):return hashlib.sha256(canonical(value)).hexdigest()


def refused(code='SYNTHETIC_REPLAY_IDENTITY_UNPROVEN'):
    raise AuditAiError(409,code,code)


def _safe(value):
    if type(value)is dict:
        for key,child in value.items():
            if key in {'promptTokens','completionTokens','totalTokens'}:
                if (type(child)is int and child>=0) or child=='UNKNOWN':continue
                return False
            if type(key)is not str or re.search(r'(?i)reasoning|thinking|thought|^analysis$|scratchpad|prompt|authorization|credential|password|secret|api.?key|^token$|http.?headers',key):return False
            if not _safe(child):return False
    elif type(value)is list:
        return all(_safe(child) for child in value)
    elif type(value)is str:
        return not re.search(r'(?i)<\s*(?:think|analysis)|\[(?:analysis|thinking)\]|思考过程|chain.of.thought|\bBearer\s+\S+|\bsk-[A-Za-z0-9_-]{16,}',value)
    return value is None or type(value)in {str,bool,int,float,list,dict}


def _business_shape(value):
    """Retention, not acceptance: known fields may have invalid scalar values.

    Never retain an unknown container in order to diagnose it later. A refusal
    keeps the whole sample NOT_REPLAYABLE; it does not strip extra fields.
    """
    def scalar(v):return v is None or type(v) in {str,int,float,bool}
    def fields(v,allowed,children):
        return type(v)is dict and not set(v)-allowed and all(
            children.get(k,scalar)(child) for k,child in v.items())
    def rows(v,check):return scalar(v) or type(v)is list and all(check(row) for row in v)
    scope=lambda v:scalar(v) or fields(v,{'topic','responsibleParty','trigger'}, {})
    refs=lambda v:rows(v,scalar)
    hint=lambda v:scalar(v) or fields(v,{'category','attention','title','description','claimKind',
        'obligationScope','problemEvidenceIds','comparisonEvidenceIds','unavailableMaterialCategory'},
        {'obligationScope':scope,'problemEvidenceIds':refs,'comparisonEvidenceIds':refs})
    decision=lambda v:scalar(v) or fields(v,{'issueId','findings','reason'},
        {'findings':lambda v:rows(v,hint)})
    # This approved generation uses only the business envelope. The small
    # legacy shape remains for native-parser coordinate regression fixtures.
    if type(value)is not dict:return False
    if 'protocol' in value or 'decisions' in value or 'additionalFindings' in value:
        return fields(value,{'protocol','summary','decisions','additionalFindings'},
            {'decisions':lambda v:rows(v,decision),'additionalFindings':lambda v:rows(v,hint)})
    return fields(value,{'summary','hints','resolutions'},
        {'hints':lambda v:rows(v,hint),'resolutions':lambda v:v==[]})


def _identity(value):
    try:
        if (type(value)is not dict or set(value)!={'syntheticOnly','projectId','runId','frozenInputFingerprint',
                'files','allowedStages','policyFingerprint'} or value['syntheticOnly'] is not True
            or type(value['projectId'])is not str or not value['projectId']
            or not re.fullmatch('[a-f0-9]{64}',value['frozenInputFingerprint'])
            or not re.fullmatch('[a-f0-9]{64}',value['policyFingerprint'])
            or not value['files'] or len(value['files'])>100
            or set(value['allowedStages'])-{'ANALYZE','GROUP','FINAL','FINAL_DIAGNOSTIC','REPAIR'}):refused()
        UUID(value['runId']);seen=set()
        for file in value['files']:
            if set(file)!={'fileId','parseResultId','sha256'} or not re.fullmatch('[a-f0-9]{64}',file['sha256']):refused()
            UUID(file['fileId']);UUID(file['parseResultId'])
            if file['fileId'] in seen:refused()
            seen.add(file['fileId'])
        if not _safe(value):refused()
        return deepcopy(value)
    except (ValueError,TypeError,KeyError):refused()


def _acl(root,initialize):
    if os.name!='nt':refused('SYNTHETIC_REPLAY_WINDOWS_ACL_REQUIRED')
    # Fixed script and data-only environment path; no string-built shell path.
    script=r'''
$ErrorActionPreference='Stop'
$target=$env:AUDIT_REPLAY_ACL_PATH
$operator=[System.Security.Principal.WindowsIdentity]::GetCurrent().User
$system=[System.Security.Principal.SecurityIdentifier]::new('S-1-5-18')
if($env:AUDIT_REPLAY_ACL_INITIALIZE -eq '1'){
 $security=[System.Security.AccessControl.DirectorySecurity]::new()
 $security.SetAccessRuleProtection($true,$false)
 $security.SetOwner($operator)
 foreach($sid in @($operator,$system)){
  $rule=[System.Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
  $security.AddAccessRule($rule)
 }
 Set-Acl -LiteralPath $target -AclObject $security
}
$actual=Get-Acl -LiteralPath $target
$rules=@($actual.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier]))
$allowed=@($operator.Value,$system.Value)
$bad=@($rules | Where-Object {$_.IdentityReference.Value -notin $allowed -or $_.IsInherited -or $_.AccessControlType -ne 'Allow' -or ($_.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::FullControl) -ne [System.Security.AccessControl.FileSystemRights]::FullControl})
if(-not $actual.AreAccessRulesProtected -or $bad.Count -ne 0 -or $rules.Count -ne 2 -or $actual.GetOwner([System.Security.Principal.SecurityIdentifier]).Value -ne $operator.Value){throw 'REPLAY_ACL_UNPROVEN'}
Write-Output 'REPLAY_ACL_VERIFIED'
'''
    env=dict(os.environ,AUDIT_REPLAY_ACL_PATH=str(root),AUDIT_REPLAY_ACL_INITIALIZE='1' if initialize else '0')
    result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-EncodedCommand',
        base64.b64encode(script.encode('utf-16-le')).decode('ascii')],env=env,stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,timeout=20,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    if result.returncode or result.stdout.strip()!=b'REPLAY_ACL_VERIFIED':refused('SYNTHETIC_REPLAY_ACL_UNPROVEN')


def _no_reparse(path):
    for node in (path,*path.parents):
        if node.exists() and (node.is_symlink() or getattr(node.stat(),'st_file_attributes',0)&0x400):refused()


class SyntheticReplayStore:
    def __init__(self,root,authority,*,workspace):
        self.authority=_identity(authority);self.workspace=Path(workspace).absolute();self.root=Path(root).absolute()
        _no_reparse(self.root)
        if not self.root.is_relative_to(self.workspace) or self.root==self.workspace:refused()
        relative=(self.root/'candidate.json').relative_to(self.workspace).as_posix()
        ignored=subprocess.run(['git','check-ignore','--no-index','--quiet',relative],cwd=self.workspace,
            stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10,
            creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        if ignored.returncode:refused('SYNTHETIC_REPLAY_NOT_IGNORED')
        new=not self.root.exists()
        if new:self.root.mkdir(parents=True,exist_ok=False)
        _acl(self.root,new)

    def _directory(self,bundle):
        path=Path(bundle['directory']).absolute();_no_reparse(path)
        if path.parent!=self.root:refused()
        _acl(self.root,False)
        return path

    @staticmethod
    def _save(path,value):
        with path.open('xb') as stream:
            stream.write(canonical(value));stream.flush();os.fsync(stream.fileno())

    def capture(self,payload,*,authority,stage,operation_fingerprint,input_references,source_hashes,original_metadata=None):
        if _identity(authority)!=self.authority or stage not in self.authority['allowedStages']:refused()
        if not re.fullmatch('[a-f0-9]{64}',operation_fingerprint):refused()
        if not _safe(input_references) or not _safe(source_hashes) or not _safe(original_metadata):refused()
        _acl(self.root,False)
        directory=self.root/str(uuid4());directory.mkdir(exist_ok=False)
        manifest={'version':1,'authority':self.authority,'authoritySha256':digest(self.authority),
            'stage':stage,'operationFingerprint':operation_fingerprint,'inputReferences':input_references,
            'sourceHashes':source_hashes,'originalResponseMetadata':original_metadata,
            'canonicalCandidateIsNotOriginalResponseBytes':True,'state':'NOT_REPLAYABLE'}
        try:
            data=canonical(payload)
            if (type(payload)is dict and len(data)<=MAX_CANDIDATE_BYTES and _safe(payload) and _business_shape(payload)
                    and type(input_references)is list and input_references and type(source_hashes)is dict and source_hashes):
                self._save(directory/'candidate.json',payload)
                manifest.update(state='REPLAYABLE',candidateSha256=hashlib.sha256(data).hexdigest(),canonicalCandidateBytes=len(data))
        except (ValueError,TypeError,UnicodeError,RecursionError):pass
        self._save(directory/'manifest.json',manifest)
        return {'directory':str(directory),'manifestSha256':digest(manifest),'state':manifest['state']}

    def validate(self,bundle,observed):
        path=self._directory(bundle)
        if type(observed)is not dict or not _safe(observed):refused()
        self._save(path/'validation.json',{'manifestSha256':bundle['manifestSha256'],'observation':observed})

    def replay(self,bundle,*,authority,source_hashes,input_references,native_validator):
        path=self._directory(bundle)
        try:
            manifest=json.loads((path/'manifest.json').read_bytes())
            validation=json.loads((path/'validation.json').read_bytes())
            data=(path/'candidate.json').read_bytes();payload=json.loads(data)
            if (digest(manifest)!=bundle['manifestSha256'] or manifest['state']!='REPLAYABLE'
                or _identity(authority)!=manifest['authority'] or authority!=self.authority
                or source_hashes!=manifest['sourceHashes'] or input_references!=manifest['inputReferences']
                or hashlib.sha256(data).hexdigest()!=manifest['candidateSha256']
                or canonical(payload)!=data or len(data)>MAX_CANDIDATE_BYTES or not _safe(payload) or not _business_shape(payload)
                or validation['manifestSha256']!=bundle['manifestSha256']):refused()
        except (OSError,KeyError,ValueError,TypeError):refused('SYNTHETIC_REPLAY_NOT_REPLAYABLE')
        def deny(*args,**kwargs):refused('NO_OUTBOUND_ALLOWED')
        # Standalone process only: even an accidental native network/subprocess
        # path is refused, not a fake successful provider or business write.
        with ExitStack() as stack:
            for target in ('socket.socket','socket.create_connection','subprocess.Popen'):
                stack.enter_context(patch(target,deny))
            observed=native_validator(deepcopy(payload))
        if observed!=validation['observation']:refused('SYNTHETIC_REPLAY_VALIDATION_CHANGED')
        return observed
