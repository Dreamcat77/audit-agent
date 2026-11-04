"""Interactive isolated contract acceptance. Secrets are memory-only, never evidence.

Only CONTRACT_AUTO_APP in the pre-existing independent AUTOCASE2 instance is writable.
The normal test26 services, database and configuration are not changed by this tool.
"""
import contextlib
import json
import os
from pathlib import Path
import secrets
import select
import shlex
import socketserver
import subprocess
import sys
import threading
import time
import base64
import http.server
import socket
from urllib.parse import urlsplit, urlunsplit

import paramiko
import requests
from bridge_http_body import read_http_body

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / ".test-runtime/contract-acceptance"
PRIVATE.mkdir(parents=True, exist_ok=True)
EVIDENCE = ROOT / "docs/contract-automation/evidence"
JAVA = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
CONFIG = json.loads(os.environ.pop("AUDIT_CONTRACT_COORDINATOR_CONFIG"))
SSH = paramiko.SSHClient()
SSH.load_system_host_keys()
SSH.set_missing_host_key_policy(paramiko.RejectPolicy())
SSH.connect(CONFIG["host"], username=CONFIG["username"], password=CONFIG.pop("password"), timeout=15)
SSH.get_transport().set_keepalive(15)
SFTP = SSH.open_sftp()
REMOTE = (ROOT / "tools/automatic-case-phase2/remote-job-location.txt").read_text().strip()
assert REMOTE.startswith("/home/admin/audit-agent-backups/auto-case-phase2-")
INSTANCE = json.loads(SFTP.open(REMOTE + "/isolated-instance.json").read().decode())
STATE_FILE = "contract-isolated-batch.json" if CONFIG.get("batchSchema") else "contract-isolated.json"
STATE = json.loads(SFTP.open(REMOTE + "/contract-schema/" + STATE_FILE).read().decode())
assert INSTANCE["port"] == STATE["port"] == 15287
assert INSTANCE["ini"] == REMOTE + "/instance/AUTOCASE2/dm.ini"
assert STATE["application_user"] == ("CONTRACT_CASE_QA" if CONFIG.get("batchSchema") else "CONTRACT_AUTO_APP")
CP = os.pathsep.join([str(ROOT / "backend/target/test-classes"), str(ROOT / "backend/target/classes"),
                       (ROOT / "backend/target/contract-classpath.txt").read_text().strip()])
PASSWORD = "Ct9_" + secrets.token_hex(16)
hashed = subprocess.run([str(JAVA), "-cp", CP, "com.fuquan.audit.security.tools.PasswordHashTool", "--stdin"],
                        input=PASSWORD + "\n", capture_output=True, text=True, check=True).stdout.strip()
ACTOR = "9a7b5b49-0597-45f4-81a2-000000000001"
CHILDREN = {}
SESSION = None
BASE = "http://127.0.0.1:19141"


class Tunnel(socketserver.BaseRequestHandler):
    def handle(self):
        channel = socket.create_connection((CONFIG["host"], 15287), timeout=10)
        try:
            while True:
                ready, _, _ = select.select([self.request, channel], [], [], 10)
                for source, target in [(self.request, channel), (channel, self.request)]:
                    if source in ready:
                        data = source.recv(65536)
                        if not data:
                            return
                        target.sendall(data)
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
            # A test child closing its JDBC connection during controlled shutdown is expected.
            return
        finally:
            channel.close()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 32


TUNNEL = Server(("127.0.0.1", 15288), Tunnel)
TUNNEL.remote_target = ("127.0.0.1", 15287)
threading.Thread(target=TUNNEL.serve_forever, daemon=True).start()
MODEL_TUNNEL = None
MODEL_ENDPOINT = None
OCR_TUNNEL = None

# Test26 sshd disallows TCP forwarding. An exec-only, memory-only HTTP bridge
# lets the actual provider see the test server's source address without changes
# to sshd, firewall, application services, proxy configuration or provider tokens.
BRIDGE = """import sys,json,base64,urllib.request,urllib.error
r=json.loads(sys.stdin.buffer.readline())
q=urllib.request.Request(r['url'],data=base64.b64decode(r['body']),headers=r['headers'],method='POST')
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
try:
 p=opener.open(q,timeout=r.get('timeout',155)); data=p.read(r.get('outputLimit',2000000)); status=p.status; content=p.headers.get('Content-Type','application/json')
except urllib.error.HTTPError as p:
 data=p.read(2000000); status=p.code; content=p.headers.get('Content-Type','application/json')
except Exception:
 data=b'{"error":"TEST_BRIDGE_TRANSPORT_FAILED"}'; status=502; content='application/json'
print(json.dumps({'status':status,'contentType':content,'body':base64.b64encode(data).decode()}))
"""


class ModelBridge(http.server.BaseHTTPRequestHandler):
    target_endpoint = None
    input_limit = 2000000
    remote_timeout = 155
    output_limit = 2000000
    def log_message(self, *args):
        pass

    def do_POST(self):
        try:
            body = read_http_body(self.rfile, self.headers, self.input_limit)
            endpoint = urlsplit(self.target_endpoint or MODEL_ENDPOINT)
            target = urlunsplit((endpoint.scheme, endpoint.netloc, self.path, "", ""))
            header = {key: value for key, value in self.headers.items() if key.lower() not in {"host", "connection", "content-length", "transfer-encoding", "expect"}}
            stdin, stdout, stderr = SSH.exec_command("python3 -c " + shlex.quote(BRIDGE), timeout=self.remote_timeout + 10)
            stdin.write(json.dumps({"url": target, "headers": header, "body": base64.b64encode(body).decode(), "timeout": self.remote_timeout, "outputLimit": self.output_limit}) + "\n")
            stdin.flush()
            stdin.channel.shutdown_write()
            result = json.loads(stdout.read().decode())
            if stdout.channel.recv_exit_status() != 0:
                raise RuntimeError("test bridge execution failed")
            data = base64.b64decode(result["body"])
            self.send_response(result["status"])
            self.send_header("Content-Type", result["contentType"])
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception:
            self.send_error(502, "Test bridge unavailable")


class OcrBridge(ModelBridge):
    # Test transport only: the existing real OCR service is loopback-bound on 26.
    # No firewall/sshd/production bind address is changed for isolated acceptance.
    target_endpoint = "http://127.0.0.1:8001"
    input_limit = 32 * 1024 * 1024
    remote_timeout = 600
    output_limit = 16 * 1024 * 1024


def wait_health(url, process):
    until = time.monotonic() + 100
    while time.monotonic() < until:
        if process.poll() is not None:
            raise RuntimeError("isolated child exited; inspect protected local log")
        try:
            if requests.get(url, timeout=3).status_code == 200:
                return
        except requests.RequestException:
            pass
        time.sleep(.5)
    raise RuntimeError("isolated child health timeout")


def stop(name):
    process = CHILDREN.pop(name, None)
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def start_ai():
    global MODEL_TUNNEL, MODEL_ENDPOINT, OCR_TUNNEL
    stop("ai")
    env = dict(os.environ, PYTHONPATH=str(ROOT / "services/audit-ai/src"), PYTHONIOENCODING="utf-8")
    for path in ["/home/admin/audit_ai_runtime/audit-ai.env", "/opt/audit-agent/current/deploy/update-v10/ai.env"]:
        for line in SFTP.open(path).read().decode().splitlines():
            line = line.strip().removeprefix("export ")
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key.startswith("AUDIT_"):
                env[key] = " ".join(shlex.split(value, comments=False))
    for key in list(env):
        if key.startswith("AUDIT_") and any(part in key for part in ["ROOT", "DIRECTORY", "PATH"]):
            env.pop(key)
    env["AUDIT_MATERIAL_CHECKPOINT_ROOT"] = str(PRIVATE / "checkpoints")
    endpoint = urlsplit(env["AUDIT_MODEL_BASE_URL"])
    if endpoint.scheme in {"http", "https"}:
        MODEL_ENDPOINT = env["AUDIT_MODEL_BASE_URL"]
        if MODEL_TUNNEL is None:
            MODEL_TUNNEL = http.server.ThreadingHTTPServer(("127.0.0.1", 19145), ModelBridge)
            threading.Thread(target=MODEL_TUNNEL.serve_forever, daemon=True).start()
        env["AUDIT_MODEL_BASE_URL"] = urlunsplit(("http", "127.0.0.1:19145", endpoint.path, endpoint.query, ""))
        print("MODEL_TRANSPORT=SSH_FROM_TEST26 actual_provider=true", flush=True)
    log = (PRIVATE / "actual-ai.log").open("wb")
    process = subprocess.Popen([sys.executable, "-m", "uvicorn", "audit_ai.main:app", "--host", "127.0.0.1", "--port", "19143"],
                               cwd=ROOT / "services/audit-ai", env=env, stdout=log, stderr=subprocess.STDOUT)
    CHILDREN["ai"] = process
    wait_health("http://127.0.0.1:19143/health", process)
    if OCR_TUNNEL is None:
        OCR_TUNNEL = http.server.ThreadingHTTPServer(("127.0.0.1", 19146), OcrBridge)
        threading.Thread(target=OCR_TUNNEL.serve_forever, daemon=True).start()
    print("ACTUAL_AI_READY", flush=True)


def login():
    global SESSION
    SESSION = requests.Session()
    token = SESSION.get(BASE + "/api/v1/auth/csrf", timeout=10).json()["data"]["token"]
    response = SESSION.post(BASE + "/api/v1/auth/login", json={"username": "contract-acceptance", "password": PASSWORD},
                            headers={"X-CSRF-TOKEN": token}, timeout=15)
    response.raise_for_status()


def start_backend(mode="actual", clock_offset=0, scan_delay=250):
    stop("backend")
    state = json.loads(SFTP.open(REMOTE + "/contract-schema/" + STATE_FILE).read().decode())
    plan = json.loads(SFTP.open(REMOTE + "/contract-schema/base-plan.json").read().decode())
    assert state["ack"] == len(plan["statements"]), "isolated schema bootstrap is not complete"
    env = dict(os.environ, AUDIT_CONTRACT_TEST_DB_CONFIRM="ISOLATED_AUTOCASE2_CONTRACT",
               AUDIT_CONTRACT_TEST_DB_URL="jdbc:dm://127.0.0.1:15288", AUDIT_CONTRACT_TEST_DB_USERNAME=STATE["application_user"],
               AUDIT_CONTRACT_TEST_DB_PASSWORD=STATE["application_password"], AUDIT_LOCAL_ACCOUNT_USERNAME="contract-acceptance",
               AUDIT_LOCAL_ACCOUNT_ID=ACTOR, AUDIT_LOCAL_ACCOUNT_PASSWORD_HASH=hashed, AUDIT_LOCAL_ACCOUNT_ROLES="AUDITOR",
               AUDIT_FILE_STORAGE_ROOT=str(PRIVATE / "file-storage"), AUDIT_CORS_ALLOWED_ORIGINS="http://127.0.0.1:19144",
               AUDIT_CONTRACT_TEST_MODEL_MODE=mode, AUDIT_CONTRACT_TEST_AI_URL="http://127.0.0.1:19143",
               AUDIT_CONTRACT_TEST_OCR_URL=CONFIG.get("ocrUrl", "http://127.0.0.1:19146"),
               AUDIT_CONTRACT_TEST_CLOCK_OFFSET=str(clock_offset))
    log = (PRIVATE / ("backend-" + mode + "-" + str(int(time.time())) + ".log")).open("wb")
    process = subprocess.Popen([str(JAVA), "-cp", CP, "com.fuquan.audit.contract.application.analysis.ContractAnalysisDm8Harness",
                                "--audit.contract.analysis.enabled=true", "--audit.contract.file-processing.worker-enabled=true",
                                "--audit.contract.analysis.scan-delay-ms=" + str(scan_delay)],
                               cwd=ROOT / "backend", env=env, stdout=log, stderr=subprocess.STDOUT)
    CHILDREN["backend"] = process
    wait_health(BASE + "/api/v1/health", process)
    login()
    print("ISOLATED_BACKEND_READY mode=" + mode, flush=True)


def call(method, path, **kwargs):
    headers = kwargs.pop("headers", {})
    if method != "GET":
        token = SESSION.get(BASE + "/api/v1/auth/csrf", timeout=10).json()["data"]["token"]
        headers["X-CSRF-TOKEN"] = token
    response = SESSION.request(method, BASE + path, headers=headers, timeout=120, **kwargs)
    if response.status_code >= 400:
        raise RuntimeError("HTTP " + str(response.status_code) + " path=" + path + " response=" + response.text[:600])
    return response.json()["data"]


def upload(paths, label):
    import uuid
    key = str(uuid.uuid4())
    receipt = call("POST", "/api/v1/contract-analysis-cases/intakes", json={"fileCount": len(paths)}, headers={"Idempotency-Key": key})
    project = receipt["projectId"]
    for path in paths:
        path = Path(path)
        content_types = {".docx":"application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".pdf":"application/pdf", ".zip":"application/zip", ".doc":"application/msword"}
        with path.open("rb") as stream:
            call("POST", "/api/v1/projects/" + project + "/file-jobs", files={"file": (path.name, stream, content_types.get(path.suffix.lower(), "application/octet-stream"))}, headers={"Idempotency-Key": str(uuid.uuid4())})
    (EVIDENCE / (label + "-receipt.json")).write_text(json.dumps({"receipt": receipt, "key": key}, indent=2), encoding="utf-8")
    print("UPLOADED " + json.dumps({"label": label, "case": receipt["caseId"]}), flush=True)
    return receipt


def watch(case_id, label, timeout=1000):
    until = time.monotonic() + timeout
    last = None
    while time.monotonic() < until:
        case = call("GET", "/api/v1/contract-analysis-cases/" + case_id)
        summary = (case["status"], len(case["steps"]), sum(s["status"] in ["COMPLETED", "PARTIAL"] for s in case["steps"]))
        if summary != last:
            print("PROGRESS " + json.dumps({"label": label, "status": summary[0], "steps": summary[1], "available": summary[2]}), flush=True)
            last = summary
        if case["status"] not in ["QUEUED", "RUNNING"]:
            report = call("GET", "/api/v1/contract-analysis-cases/" + case_id + "/report")
            for suffix, value in [("case", case), ("report", report)]:
                (EVIDENCE / (label + "-" + suffix + ".json")).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
            print("TERMINAL " + json.dumps({"label": label, "case": case_id, "status": case["status"], "facts": sum(len(d["facts"]) for d in report["documents"]), "clauses": sum(len(d["clauses"]) for d in report["documents"]), "risks": sum(f["level"]=="WARNING" for f in report["findings"]), "followUps":sum(f["level"]!="WARNING" for f in report["findings"])}), flush=True)
            return report
        time.sleep(1)
    raise RuntimeError("case still nonterminal; preserve identity and inspect, never resubmit blindly")


def command(value):
    action = value["action"]
    if action == "probe":
        state = json.loads(SFTP.open(REMOTE + "/contract-schema/" + STATE_FILE).read().decode())
        plan = json.loads(SFTP.open(REMOTE + "/contract-schema/base-plan.json").read().decode())
        print(json.dumps({"isolatedAck": state["ack"], "isolatedStatements": len(plan["statements"]), "originalServicesModified": False}), flush=True)
    elif action == "start":
        start_ai()
        start_backend(value.get("mode", "actual"))
    elif action == "ai":
        start_ai()
    elif action == "backend":
        start_backend(value.get("mode", "actual"))
    elif action == "restart":
        start_backend(value.get("mode", "actual"), value.get("clockOffset", 0), value.get("scanDelay", 250))
    elif action == "upload":
        upload(value["paths"], value["label"])
    elif action == "watch":
        watch(value["caseId"], value["label"], value.get("timeout", 1000))
    elif action == "scenario":
        from verify_report import verify
        spec=next(s for s in json.loads((ROOT/".test-runtime/contract-fixtures/golden.json").read_text(encoding="utf-8")) if s["id"]==value["scenario"])
        receipt=upload(spec["paths"],"e2e-"+spec["id"])
        report=watch(receipt["caseId"],"e2e-"+spec["id"],2000)
        case=call("GET", "/api/v1/contract-analysis-cases/"+receipt["caseId"])
        texts={}
        for source in case["sources"]:
            if source["status"]=="READY":
                texts[source["fileId"]]=call("GET",f"/api/v1/contract-analysis-cases/{case['id']}/sources/{source['fileId']}/text")["text"]
        result=verify(spec,case,report,texts)
        (EVIDENCE/("e2e-"+spec["id"]+"-verification.json")).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
        print("GOLDEN_RESULT "+json.dumps(result,ensure_ascii=False),flush=True)
    elif action == "browser":
        payload={"username":"contract-acceptance","password":PASSWORD,"caseId":value.get("caseId"),"paths":value.get("paths"),"label":value.get("label","browser"),"evidence":str(EVIDENCE),"production":value.get("production",False),"executable":value.get("executable")}
        result=subprocess.run(["node",str(ROOT/"tools/contract-automation/browser_acceptance.mjs")],input=json.dumps(payload),capture_output=True,text=True,encoding="utf-8",timeout=2100)
        print(result.stdout.strip(),flush=True)
        if result.returncode:raise RuntimeError("browser acceptance failed: "+result.stderr[-1500:])
    elif action == "platform":
        stop("backend")
        env=dict(os.environ,AUDIT_CONTRACT_TEST_DB_CONFIRM="ISOLATED_AUTOCASE2_CONTRACT",
            AUDIT_CONTRACT_TEST_DB_URL="jdbc:dm://127.0.0.1:15288",AUDIT_CONTRACT_TEST_DB_USERNAME=STATE["application_user"],
            AUDIT_CONTRACT_TEST_DB_PASSWORD=STATE["application_password"])
        try:
            result=subprocess.run([str(JAVA),"-cp",CP,"com.fuquan.audit.contract.application.analysis.ContractAnalysisDm8PlatformProbe"],env=env,capture_output=True,text=True,timeout=100)
            (EVIDENCE/"p5-dm8-platform-probe.log").write_text(result.stdout+result.stderr,encoding="utf-8")
            if result.returncode:raise RuntimeError("isolated DM8 platform probe failed; inspect evidence")
            print(result.stdout.strip(),flush=True)
        finally:
            start_backend("actual")
    elif action == "call":
        result = call(value.get("method", "GET"), value["path"], **value.get("arguments", {}))
        if value.get("output"):
            (EVIDENCE / value["output"]).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        else:
            print(json.dumps(result, ensure_ascii=False), flush=True)
    elif action == "stop":
        return False
    else:
        raise ValueError("unknown coordinator action")
    return True


print("CONTRACT_ACCEPTANCE_COORDINATOR_READY", flush=True)
CONTROL_LOCK = threading.Lock()
CONTROL_TOKEN = secrets.token_urlsafe(32)
(PRIVATE / "control-token").write_text(CONTROL_TOKEN, encoding="utf-8")


class Control(http.server.BaseHTTPRequestHandler):
    """Loopback-only test control, so a lost terminal cannot orphan acceptance."""
    def log_message(self, *args):
        pass

    def do_POST(self):
        if self.path != "/command" or not secrets.compare_digest(self.headers.get("Authorization", ""), "Bearer " + CONTROL_TOKEN):
            self.send_error(403)
            return
        try:
            value = json.loads(read_http_body(self.rfile, self.headers, 32768))
            if value.get("action") == "stop":
                raise ValueError("stop through the owning terminal")
            with CONTROL_LOCK:
                command(value)
            result = {"completed": True}
        except Exception as exc:
            result = {"completed": False, "errorType": type(exc).__name__, "reason": str(exc)[:1000]}
        data = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


CONTROL = http.server.ThreadingHTTPServer(("127.0.0.1", 19147), Control)
threading.Thread(target=CONTROL.serve_forever, daemon=True).start()
try:
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            if not command(json.loads(line)):
                break
            print("COMMAND_DONE", flush=True)
        except Exception as exc:
            print("COMMAND_FAILED " + type(exc).__name__ + " " + str(exc)[:1000], flush=True)
finally:
    CONTROL.shutdown()
    for name in list(CHILDREN):
        with contextlib.suppress(Exception):
            stop(name)
    TUNNEL.shutdown()
    if MODEL_TUNNEL is not None:
        MODEL_TUNNEL.shutdown()
    if OCR_TUNNEL is not None:
        OCR_TUNNEL.shutdown()
    SFTP.close()
    SSH.close()
