"""Install all three modules in a separate test26 release/schema/storage/session namespace."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import pwd
import secrets
import shlex
import socket
import subprocess
import tarfile
import time
import uuid
import zipfile
from preserve_ocr_models import preserve

JAVA = "/opt/java/current/bin/java"
PYTHON = "/opt/python/venvs/audit-ai/bin/python"
CONFIG = Path("/etc/audit-qa26")
DATA = Path("/var/lib/audit-qa26")
LOG = Path("/var/log/audit-qa26")
RUN = Path("/run/audit-qa26")
UNITS = ["audit-qa26-ai", "audit-qa26-backend", "audit-qa26-web"]

def sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def load_env(paths):
    result = {}
    for path in paths:
        for line in Path(path).read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("export "):
                line = line[7:]
            key, value = line.split("=", 1)
            result[key] = " ".join(shlex.split(value, comments=False))
    return result

def write_env(path, values):
    with path.open("x") as f:
        for key, raw in values.items():
            value = str(raw)
            assert "\n" not in value and "\r" not in value
            f.write(key + '="' + value.replace("\\", "\\\\").replace('"', '\\"') + '"\n')
    path.chmod(0o600)

def run(args, name, env=None):
    result = subprocess.run(args, capture_output=True, env=env, timeout=150)
    (LOG / (name + ".log")).write_bytes(result.stdout + result.stderr)
    assert result.returncode == 0, name + "_FAILED_INSPECT_PRIVATE_LOG"
    print(name + "=PASS", flush=True)
    return result.stdout.decode("utf-8", "replace")

def original_unchanged(backup):
    manifest = json.loads((backup / "original-manifest-private.json").read_text())
    assert str(Path("/opt/audit-agent/current").resolve()) == manifest["release"]
    for name, item in manifest["files"].items():
        assert sha(Path("/") / name) == item["sha256"], "ORIGINAL_FILE_CHANGED"
    return manifest

def unit(command, workdir, environment="", extra=""):
    return ("[Unit]\nDescription=Isolated three-module QA26\nAfter=network.target audit-autocase-pilot-dm.service\n"
            "[Service]\nType=simple\nUser=admin\nGroup=admin\nUMask=0077\nNoNewPrivileges=true\n"
            "WorkingDirectory=" + str(workdir) + "\n" + environment + "ExecStart=" + command + "\n"
            "Restart=on-failure\nRestartSec=5\nTimeoutStopSec=50\n" + extra + "[Install]\nWantedBy=multi-user.target\n")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("package")
    parser.add_argument("sha256")
    parser.add_argument("--backup", required=True)
    parser.add_argument("--job", required=True)
    parser.add_argument("--allow-cidr", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    assert os.geteuid() == 0
    backup = Path(args.backup).resolve()
    baseline = original_unchanged(backup)
    job = Path(args.job).resolve()
    assert str(job).startswith("/home/admin/audit-agent-backups/auto-case-phase2-")
    state = json.loads((job / "qa26-full2-private.json").read_text())
    plan = json.loads(Path("/home/admin/qa26-schema-plan-v2.json").read_text())
    assert state["application_user"] == "QA26_FULL2" and state["port"] == 15287
    assert state["ack"] == len(plan["statements"]) and state["pending"] is None and state["planDigest"] == plan["digest"]
    archive = Path(args.package)
    assert sha(archive) == args.sha256
    with tarfile.open(archive) as tar:
        manifest = json.load(tar.extractfile("manifest.json"))
    version = manifest["version"]
    assert version.startswith("qa26-20261027-") and version.replace("-", "").isalnum()
    release = Path("/opt/audit-agent/pilots") / version
    assert not release.exists() and not CONFIG.exists(), "EXISTING_CANDIDATE_REQUIRES_CONTROLLED_UPGRADE"
    for port in (19151, 19153, 18082):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", port))
    owner = pwd.getpwnam("admin")
    for path in (release, CONFIG, DATA, LOG, RUN):
        path.mkdir(parents=True, exist_ok=False)
        os.chown(path, owner.pw_uid, owner.pw_gid)
        path.chmod(0o750 if path == release else 0o700)
    with tarfile.open(archive) as tar:
        for member in tar:
            path = (release / member.name).resolve()
            assert path.is_relative_to(release) and member.isfile() and not member.issym() and not member.islnk()
            path.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as src, path.open("xb") as dst:
                while chunk := src.read(1024 * 1024):
                    dst.write(chunk)
    for item in manifest["files"]:
        assert sha(release / item["path"]) == item["sha256"]
    # Application source packages do not replace the installed offline weights.
    print("OCR_MODEL_FILES_PRESERVED=" + str(preserve(Path(baseline["release"]), release)), flush=True)
    runtime = release / "tool-runtime"
    with zipfile.ZipFile(release / "backend/audit-backend.jar") as jar:
        for name in jar.namelist():
            if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                dst = runtime / "lib" / Path(name).name
            elif name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                dst = runtime / "classes" / name[len("BOOT-INF/classes/"):]
            else:
                continue
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(jar.read(name))
    cp = str(release / "tools/classes") + ":" + str(runtime / "classes") + ":" + str(runtime / "lib/*")
    dm = dict(os.environ, AUDIT_DB_URL="jdbc:dm://127.0.0.1:15287", AUDIT_DB_USERNAME="QA26_FULL2",
              AUDIT_DB_PASSWORD=state["application_password"], AUDIT_CASE_TEST_DB_CONFIRM="ISOLATED_AUTOCASE2")
    run([JAVA, "-cp", cp, "ContractAnalysisSchema", "check", str(release / "tools/analysis.sql")], "contract-schema-repeat", dm)
    run([JAVA, "-cp", cp, "Qa26CaseSchemaCheck", str(release / "tools/procurement.sql")], "procurement-schema-repeat", dm)
    run([JAVA, "-cp", cp, "ContractAnalysisSchemaTest"], "equivalence-and-drift-negative", dm)
    access = {}
    for prefix, username in (("PRIMARY", "qa26_auditor"), ("SECONDARY", "qa26_boundary")):
        access[prefix.lower()] = {"id": str(uuid.uuid4()), "username": username, "password": "Q9_" + secrets.token_urlsafe(28)}
        for key, value in access[prefix.lower()].items():
            dm["AUDIT_PILOT_" + prefix + "_" + key.upper()] = value
    private = backup / "qa26-access-private.json"
    private.write_text(json.dumps(access))
    private.chmod(0o600)
    os.chown(private, owner.pw_uid, owner.pw_gid)
    run([JAVA, "-cp", cp, "Qa26Admin", "bootstrap"], "real-auth-and-builtin-rules", dm)
    active = Path(baseline["release"])
    source = load_env(["/etc/audit-agent/backend.env", active / "deploy/update-v10/backend.env"])
    run([JAVA, "-cp", cp, "MetadataSnapshot", str(backup / "original-schema-before.tsv")], "original-schema-readonly", dict(os.environ, **source))
    source.update({key: dm[key] for key in ("AUDIT_DB_URL", "AUDIT_DB_USERNAME", "AUDIT_DB_PASSWORD")})
    source.update(AUDIT_AI_BASE_URL="http://127.0.0.1:19153", AUDIT_OCR_BASE_URL="http://127.0.0.1:19153",
                  AUDIT_SESSION_REDIS_NAMESPACE="audit-qa26:session:v1", AUDIT_CACHE_KEY_PREFIX="audit-qa26:cache:",
                  AUDIT_SESSION_COOKIE_SECURE="false", AUDIT_FILE_STORAGE_ROOT=str(DATA / "storage"),
                  AUDIT_PROCUREMENT_MATERIAL_WORKER_ENABLED="true", AUDIT_BUDGET_MATERIAL_WORKER_ENABLED="true",
                  AUDIT_CONTRACT_FILE_PROCESSING_WORKER_ENABLED="true", AUDIT_CONTRACT_ANALYSIS_ENABLED="true",
                  AUDIT_OBSERVABILITY_FLOW_DEBUG_ENABLED="false", AUDIT_SESSION_TIMEOUT="60m",
                  AUDIT_MULTIPART_MAX_FILE_SIZE="500MB", AUDIT_MULTIPART_MAX_REQUEST_SIZE="502MB")
    for key in list(source):
        if key.startswith("AUDIT_LOCAL_ACCOUNT_"):
            del source[key]
    write_env(CONFIG / "backend.env", source)
    ai = load_env(["/home/admin/audit_ai_runtime/audit-ai.env", active / "deploy/update-v10/ai.env"])
    ai.update(AUDIT_CASE_MODEL_READ_TIMEOUT_SECONDS="150", AUDIT_CASE_OUTPUT_TOKENS="4096",
              AUDIT_MATERIAL_CHECKPOINT_ROOT=str(DATA / "ai-checkpoints"), AUDIT_AI_TEMP_ROOT=str(DATA / "ai-temp"),
              PYTHONUNBUFFERED="1", PYTHONDONTWRITEBYTECODE="1")
    write_env(CONFIG / "ai.env", ai)
    for directory in ("storage", "ai-checkpoints", "ai-temp"):
        path = DATA / directory
        path.mkdir()
        os.chown(path, owner.pw_uid, owner.pw_gid)
    network = str(ipaddress.ip_network(args.allow_cidr, strict=True))
    nginx = '''pid __RUN__/nginx.pid;
error_log __LOG__/nginx-error.log warn;
events { worker_connections 256; }
http {
 include /etc/nginx/mime.types;
 default_type application/octet-stream;
 access_log __LOG__/nginx-access.log;
 client_body_temp_path __RUN__/client_body;
 proxy_temp_path __RUN__/proxy;
 fastcgi_temp_path __RUN__/fastcgi;
 uwsgi_temp_path __RUN__/uwsgi;
 scgi_temp_path __RUN__/scgi;
 server {
  listen 18082;
  server_name _;
  allow 127.0.0.1;
  allow __NETWORK__;
  deny all;
  root __ROOT__;
  index index.html;
  client_max_body_size 502m;
  add_header X-Frame-Options DENY always;
  add_header X-Content-Type-Options nosniff always;
  location /api/ {
   proxy_pass http://127.0.0.1:19151;
   proxy_set_header Host $http_host;
   proxy_set_header X-Forwarded-Proto http;
   proxy_set_header X-Real-IP $remote_addr;
   proxy_read_timeout 1900s;
  }
  location / { try_files $uri $uri/ /index.html; }
 }
}
'''.replace("__RUN__", str(RUN)).replace("__LOG__", str(LOG)).replace("__NETWORK__", network).replace("__ROOT__", str(release / "frontend"))
    (CONFIG / "nginx.conf").write_text(nginx)
    os.chown(CONFIG / "nginx.conf", owner.pw_uid, owner.pw_gid)
    (CONFIG / "nginx.conf").chmod(0o600)
    for directory in ("client_body", "proxy", "fastcgi", "uwsgi", "scgi"):
        path = RUN / directory
        path.mkdir()
        os.chown(path, owner.pw_uid, owner.pw_gid)
    commands = [unit(PYTHON + " -m uvicorn audit_ai.main:app --app-dir " + str(release / "services/audit-ai/src") + " --host 127.0.0.1 --port 19153 --workers 1", release / "services/audit-ai", "EnvironmentFile=" + str(CONFIG / "ai.env") + "\n"),
                unit(JAVA + " -jar " + str(release / "backend/audit-backend.jar") + " --spring.profiles.active=dev29 --server.address=127.0.0.1 --server.port=19151 --server.servlet.session.cookie.name=AUDITQA26 --audit.procurement.auto-case.enabled=true", release / "backend", "EnvironmentFile=" + str(CONFIG / "backend.env") + "\n"),
                unit('/usr/sbin/nginx -e ' + str(LOG / "nginx-error.log") + ' -c ' + str(CONFIG / "nginx.conf") + ' -g "daemon off;"', release,
                     extra="RuntimeDirectory=audit-qa26\nRuntimeDirectoryMode=0700\n")]
    for name, content in zip(UNITS, commands):
        with (Path("/etc/systemd/system") / (name + ".service")).open("x") as f:
            f.write(content)
    for path in release.rglob("*"):
        path.chmod(0o755 if path.is_dir() else 0o644)
    run(["/usr/sbin/nginx", "-t", "-e", str(LOG / "nginx-error.log"), "-c", str(CONFIG / "nginx.conf")], "independent-nginx")
    for path in LOG.glob("*"):
        os.chown(path, owner.pw_uid, owner.pw_gid)
    run(["systemctl", "daemon-reload"], "register-independent-units")
    run(["systemctl", "start"] + UNITS[:2], "start-independent-apps")
    original_unchanged(backup)
    summary = {"release": str(release), "version": version, "packageSha256": args.sha256,
               "schema": "QA26_FULL2", "instance": "AUTOCASE2", "webPort": 18082, "backendPort": 19151, "aiPort": 19153,
               "originalFilesUnchanged": True, "originalDatabaseDdlOrDml": False, "webPublished": False}
    (backup / "qa26-deployment.json").write_text(json.dumps(summary, indent=2))
    os.chown(backup / "qa26-deployment.json", owner.pw_uid, owner.pw_gid)
    print(json.dumps(summary), flush=True)

if __name__ == "__main__":
    main()
