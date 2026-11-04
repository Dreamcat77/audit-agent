"""Interactive local acceptance coordinator. Initial SSH secrets arrive over stdin, never files.

The only DB write target is the separately initialized instance through a local TCP proxy.
Existing test26 application services/configuration are never modified.
"""
import contextlib
import http.cookiejar
import json
import os
from pathlib import Path
import select
import secrets
import shlex
import socketserver
import socket
import subprocess
import sys
import threading
import time
import traceback
import paramiko
import requests

ROOT=Path(__file__).resolve().parents[2]
BACKEND=ROOT/"backend"
JAVA=Path(os.environ["JAVA_HOME"])/"bin/java.exe"
CONFIG=json.loads(os.environ.pop("AUDIT_PHASE2_COORDINATOR_CONFIG"))
BACKUP=Path((ROOT/"tools/automatic-case-phase2/local-backup-location.txt").read_text())
EVIDENCE=ROOT/"docs/automatic-procurement-phase2-20261027/evidence"
EVIDENCE.mkdir(parents=True,exist_ok=True)
SSH=paramiko.SSHClient();SSH.load_system_host_keys();SSH.set_missing_host_key_policy(paramiko.AutoAddPolicy())
SSH.connect(CONFIG["host"],username=CONFIG["username"],password=CONFIG["password"],timeout=15)
SSH.get_transport().set_keepalive(15)
CONFIG.pop("password")
SFTP=SSH.open_sftp()
REMOTE=(ROOT/"tools/automatic-case-phase2/remote-job-location.txt").read_text()
STATE=json.loads(SFTP.open(REMOTE+"/isolated-instance.json").read().decode())
class Tunnel(socketserver.BaseRequestHandler):
    def handle(self):
        channel=socket.create_connection((CONFIG["host"],STATE["port"]),timeout=10)
        try:
            while True:
                ready,_,_=select.select([self.request,channel],[],[],5)
                if self.request in ready:
                    data=self.request.recv(65536)
                    if not data:break
                    channel.sendall(data)
                if channel in ready:
                    data=channel.recv(65536)
                    if not data:break
                    self.request.sendall(data)
        finally:channel.close()
class Server(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
TUNNEL=Server(("127.0.0.1",15288),Tunnel)
threading.Thread(target=TUNNEL.serve_forever,daemon=True).start()
CP=os.pathsep.join([str(BACKEND/"target/test-classes"),str(BACKEND/"target/classes"),(BACKEND/"target/phase2-classpath.txt").read_text().strip()])
PASSWORD="Ac9_"+secrets.token_hex(16)
hashed=subprocess.run([str(JAVA),"-cp",CP,"com.fuquan.audit.security.tools.PasswordHashTool","--stdin"],input=PASSWORD+"\n",capture_output=True,text=True,check=True).stdout.strip()
BASE_ENV=dict(os.environ,AUDIT_CASE_TEST_DB_CONFIRM="ISOLATED_AUTOCASE2",AUDIT_CASE_TEST_DB_URL="jdbc:dm://127.0.0.1:15288",
    AUDIT_CASE_TEST_DB_USERNAME=STATE["application_user"],AUDIT_CASE_TEST_DB_PASSWORD=STATE["application_password"],
    AUDIT_LOCAL_ACCOUNT_USERNAME="phase2-auditor",AUDIT_LOCAL_ACCOUNT_PASSWORD_HASH=hashed,AUDIT_LOCAL_ACCOUNT_ROLES="AUDITOR",
    AUDIT_FILE_STORAGE_ROOT=str(BACKUP/"file-storage"),AUDIT_CORS_ALLOWED_ORIGINS="http://127.0.0.1:19132",
    AUDIT_CASE_FIXTURES=CONFIG["fixtures"])
CHILDREN={};MODE=None;SESSION=None

def wait_health(url,process):
    deadline=time.monotonic()+60
    while time.monotonic()<deadline:
        if process.poll() is not None:raise RuntimeError("child exited; inspect protected log")
        try:
            if requests.get(url,timeout=2).status_code==200:return
        except requests.RequestException:pass
        time.sleep(.5)
    raise RuntimeError("health timeout")

def stop(name):
    process=CHILDREN.pop(name,None)
    if process and process.poll() is None:
        process.terminate()
        try:process.wait(timeout=15)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)

def start_backend(mode="simulated",gate=None,scan_delay=None):
    global MODE,SESSION
    stop("backend");MODE=mode
    env=dict(BASE_ENV,AUDIT_CASE_TEST_MODEL_MODE=mode,AUDIT_CASE_REAL_AI_URL="http://127.0.0.1:19121")
    if gate:env["AUDIT_CASE_TEST_MODEL_GATE"]=str(gate)
    args=[str(JAVA),"-cp",CP,"com.fuquan.audit.procurement.autocase.AutomaticCaseDm8Harness"]
    if scan_delay:args.append("--audit.procurement.auto-case.scan-delay-ms="+str(scan_delay))
    log=(BACKUP/("harness-"+mode+"-"+str(int(time.time()))+".log")).open("wb")
    process=subprocess.Popen(args,cwd=BACKEND,env=env,stdout=log,stderr=subprocess.STDOUT)
    CHILDREN["backend"]=process;wait_health("http://127.0.0.1:19131/api/v1/health",process)
    SESSION=requests.Session()
    csrf=SESSION.get("http://127.0.0.1:19131/api/v1/auth/csrf",timeout=5).json()["data"]["token"]
    response=SESSION.post("http://127.0.0.1:19131/api/v1/auth/login",json={"username":"phase2-auditor","password":PASSWORD},headers={"X-CSRF-TOKEN":csrf},timeout=10)
    response.raise_for_status()
    print("BACKEND_READY mode="+mode+" pid="+str(process.pid),flush=True)

def start_ai():
    env=dict(os.environ,PYTHONPATH=str(ROOT/"services/audit-ai/src"),PYTHONIOENCODING="utf-8")
    for path in ["/home/admin/audit_ai_runtime/audit-ai.env","/opt/audit-agent/current/deploy/update-v10/ai.env"]:
        for line in SFTP.open(path).read().decode().splitlines():
            line=line.strip()
            if not line or line.startswith("#") or "=" not in line:continue
            if line.startswith("export "):line=line[7:]
            key,value=line.split("=",1);env[key]=" ".join(shlex.split(value,comments=False))
    # Isolate checkpoints/storage rather than using any server paths from env.
    for key in list(env):
        if key.startswith("AUDIT_AI_") and any(x in key for x in ["ROOT","DIRECTORY","PATH"]):env.pop(key)
    env["AUDIT_MATERIAL_CHECKPOINT_ROOT"]=str(BACKUP/"actual-model-checkpoints")
    python=Path(r"E:\audit-agent-new\services\audit-ai\.venv-local\Scripts\python.exe")
    log=(BACKUP/"actual-ai.log").open("wb")
    process=subprocess.Popen([str(python),"-m","uvicorn","audit_ai.main:app","--host","127.0.0.1","--port","19121"],cwd=ROOT/"services/audit-ai",env=env,stdout=log,stderr=subprocess.STDOUT)
    CHILDREN["ai"]=process;wait_health("http://127.0.0.1:19121/health",process)
    print("ACTUAL_AI_READY pid="+str(process.pid),flush=True)

def scenario(label):
    csrf=SESSION.get("http://127.0.0.1:19131/api/v1/auth/csrf",timeout=5).json()["data"]["token"]
    cookiefile=BACKUP/"curl-session.cookies"
    jar=http.cookiejar.MozillaCookieJar(str(cookiefile))
    for cookie in SESSION.cookies:
        value=__import__("copy").copy(cookie)
        # Netscape curl requires numeric expiry; Python writes an empty field for session cookies.
        if value.expires is None:value.expires=0
        jar.set_cookie(value)
    jar.save(ignore_discard=True,ignore_expires=True)
    operation=secrets.token_hex(16);operation=str(__import__("uuid").UUID(operation))
    args=["curl.exe","-sS","--max-time","90","-b",str(cookiefile),"-H","X-CSRF-TOKEN: "+csrf,"-H","Idempotency-Key: "+operation]
    paths=sorted(Path(CONFIG["fixtures"]).iterdir());assert len(paths)==12
    for p in paths:args.extend(["-F","files=@"+str(p)])
    args.append("http://127.0.0.1:19131/api/v1/procurement/cases")
    process=subprocess.run(args,capture_output=True,timeout=100,check=True)
    uploaded=json.loads(process.stdout.decode("utf-8"));assert uploaded["code"]==200,uploaded
    case_id=uploaded["data"]["id"]
    (EVIDENCE/(label+"-upload.json")).write_text(json.dumps(uploaded,ensure_ascii=False,indent=2),encoding="utf-8")
    print("UPLOADED mode="+MODE+" case="+case_id,flush=True)
    start=time.monotonic();last=None;deadline=start+1200
    while time.monotonic()<deadline:
        response=SESSION.get("http://127.0.0.1:19131/api/v1/procurement/cases/"+case_id,timeout=20)
        if response.status_code!=200:time.sleep(1);continue
        result=response.json();case=result["data"]
        progress=(case["status"],tuple((d["status"],d["factCount"]) for d in case["documents"]))
        if progress!=last:
            print("PROGRESS "+json.dumps({"case":case_id,"state":case["status"],"extracted":sum(d["status"]=="EXTRACTED" for d in case["documents"]),"seconds":round(time.monotonic()-start)}),flush=True);last=progress
        if case["status"] not in ["UPLOADING","QUEUED","RUNNING"]:break
        time.sleep(1)
    else:raise RuntimeError("case timeout; preserve state and do not resubmit")
    (EVIDENCE/(label+"-result.json")).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    report=case["report"]
    assert case["status"]=="COMPLETED",case["status"]
    assert report["budget"]==222600 and report["awardAmount"]==212000
    assert sorted(o["amount"] for o in report["offers"])==[212000,216400,219800]
    assert {o["supplier"] for o in report["offers"]}=={"澄川星河办公设备有限公司","澄川明川数字设备有限公司","澄川启衡信息设备有限公司"}
    assert all(len(o["lines"])==3 and o["lineValidation"]=="VERIFIED" for o in report["offers"])
    assert report["calculation"]["averageQuote"]==216066.67
    assert not any(q["overBudget"] for q in report["calculation"]["quotes"])
    evidence=[]
    for offer in report["offers"]:
        response=SESSION.get("http://127.0.0.1:19131/api/v1/procurement/cases/"+case_id+"/files/"+offer["documentId"]+"/evidence",timeout=10)
        response.raise_for_status();evidence.append(response.json())
    (EVIDENCE/(label+"-evidence.json")).write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding="utf-8")
    recovered=SESSION.get("http://127.0.0.1:19131/api/v1/procurement/cases/operations/"+operation,timeout=10).json()
    assert recovered["data"]["id"]==case_id
    print("SCENARIO_PASS "+json.dumps({"label":label,"mode":MODE,"case":case_id,"seconds":round(time.monotonic()-start),"repository":"real DM8"}),flush=True)
    return case_id

def command(request):
    action=request["action"]
    if action=="extended":
        __import__("runpy").run_path(str(ROOT/"tools/automatic-case-phase2/phase2_scenarios.py"))["run"](request,globals())
    elif action=="start":start_backend(request.get("mode","simulated"),request.get("gate"),request.get("scanDelay"))
    elif action=="ai":start_ai()
    elif action=="scenario":scenario(request.get("label",MODE))
    elif action=="jdbc":
        env=dict(BASE_ENV,JAVA_HOME=os.environ["JAVA_HOME"])
        log=EVIDENCE/"real-dm8-junit.log"
        with log.open("wb") as output:
            p=subprocess.run([str(BACKEND/"mvnw.cmd"),"-Dtest=Dm8AutoCaseRealIntegrationTest","test"],cwd=BACKEND,env=env,stdout=output,stderr=subprocess.STDOUT)
        print("JDBC_TEST_EXIT="+str(p.returncode),flush=True)
    elif action=="frontend":
        env=dict(os.environ,AUDIT_VITE_API_PROXY_TARGET="http://127.0.0.1:19131")
        log=(BACKUP/"vite.log").open("wb")
        p=subprocess.Popen(["node",str(ROOT/"frontend/node_modules/vite/bin/vite.js"),"--host","127.0.0.1","--port","19132","--strictPort"],cwd=ROOT/"frontend",env=env,stdout=log,stderr=subprocess.STDOUT)
        CHILDREN["frontend"]=p;wait_health("http://127.0.0.1:19132",p);print("FRONTEND_READY",flush=True)
    elif action=="browser":
        data={"username":"phase2-auditor","password":PASSWORD,"fixtures":CONFIG["fixtures"],"evidence":str(EVIDENCE),"action":request.get("test","inspect")}
        p=subprocess.run(["node",str(ROOT/"tools/automatic-case-phase2/browser-acceptance.mjs")],input=json.dumps(data),capture_output=True,text=True,encoding="utf-8",timeout=1300)
        print(p.stdout,flush=True);print(p.stderr[-1500:],flush=True);print("BROWSER_EXIT="+str(p.returncode),flush=True)
    elif action=="status":print(json.dumps({"mode":MODE,"children":{k:{"pid":v.pid,"exit":v.poll()} for k,v in CHILDREN.items()}}),flush=True)
    elif action=="stop":return False
    else:raise ValueError("unknown action")
    return True

print("COORDINATOR_READY",flush=True)
try:
    inbox=BACKUP/"coordinator-commands.jsonl"
    inbox.touch(exist_ok=True)
    running=True
    with inbox.open("r",encoding="utf-8") as commands:
      commands.seek(0,2)
      while running:
        line=commands.readline()
        if not line:
            time.sleep(.5);continue
        try:
            if not command(json.loads(line)):running=False
        except Exception as error:
            print("ACTION_FAILED type="+type(error).__name__+" message="+str(error)[:600],flush=True)
finally:
    for name in list(CHILDREN):stop(name)
    TUNNEL.shutdown();TUNNEL.server_close();SSH.close()
    print("COORDINATOR_STOPPED",flush=True)
