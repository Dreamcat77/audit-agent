"""Inspect effective test26 model route without exporting credentials or document content."""
import json, shlex, sys
import paramiko
config=json.loads(sys.stdin.readline())
ssh=paramiko.SSHClient();ssh.load_system_host_keys();ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
try:
    ssh.connect(config['host'],username=config['username'],password=config.pop('password'),timeout=15)
    code="""import subprocess,json,os,hashlib,socket,urllib.parse,urllib.request,urllib.error,time
from pathlib import Path
pid=int(subprocess.check_output(['systemctl','show','audit-ai.service','-p','MainPID','--value'],text=True).strip())
result={'runningAi':pid>0}
try:
 env=dict(x.split('=',1) for x in Path('/proc/'+str(pid)+'/environ').read_bytes().decode().split('\\0') if '=' in x)
 endpoint=urllib.parse.urlsplit(env.get('AUDIT_MODEL_BASE_URL',''))
 result.update(effectiveEnvironmentReadable=True,modelConfigured=bool(endpoint.hostname),endpointFingerprint=hashlib.sha256(env.get('AUDIT_MODEL_BASE_URL','').encode()).hexdigest()[:24],modelFingerprint=hashlib.sha256(env.get('AUDIT_MODEL_NAME','').encode()).hexdigest()[:24])
 if endpoint.hostname:
  try:
   s=socket.create_connection((endpoint.hostname,endpoint.port or (443 if endpoint.scheme=='https' else 80)),timeout=7);s.close();result['modelTcpReachable']=True
   req=urllib.request.Request(env['AUDIT_MODEL_BASE_URL'].rstrip('/')+'/chat/completions',data=json.dumps({'model':env.get('AUDIT_MODEL_NAME'),'messages':[{'role':'user','content':'Reply only OK.'}],'max_tokens':16,'stream':False}).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+env.get('AUDIT_MODEL_API_KEY','')})
   start=time.monotonic()
   try:
    response=urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=25);raw=response.read(1000000);payload=json.loads(raw)
    result.update(modelHttp=response.status,responseBytes=len(raw),hasChoices=isinstance(payload.get('choices'),list) and bool(payload['choices']),seconds=round(time.monotonic()-start,2))
   except Exception as error:result.update(modelProbeError=type(error).__name__,seconds=round(time.monotonic()-start,2))
  except OSError:result['modelTcpReachable']=False
except PermissionError:result['effectiveEnvironmentReadable']=False
print(json.dumps(result))
"""
    _,stdout,stderr=ssh.exec_command('python3 -c '+shlex.quote(code),timeout=30)
    data=stdout.read().decode();status=stdout.channel.recv_exit_status()
    if status:raise RuntimeError('read-only model probe failed')
    print(data.strip())
finally:ssh.close()
