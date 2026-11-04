"""Read-only test-server inventory. Never print config values or document data."""
import hashlib
import json
from pathlib import Path
import platform
import subprocess

def run(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=20)
    return {"exit": result.returncode, "output": result.stdout.strip()[:3000]}

def fingerprint(path):
    path = Path(path)
    digest = hashlib.sha256()
    if path.is_file():
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    return {"present": path.is_file(), "sha256": digest.hexdigest() if path.is_file() else None}

release = Path("/opt/audit-agent/current").resolve()
assert release.parent == Path("/opt/audit-agent/releases")
services = {}
for name in ["audit-backend", "audit-ai", "nginx", "DmServiceAUDITDB", "audit-autocase-pilot-dm", "audit-autocase-pilot-backend", "audit-autocase-pilot-ai", "audit-autocase-pilot-web"]:
    services[name] = run(["systemctl", "show", name, "--property=ActiveState,SubState,MainPID"])
artifacts = {}
for path in [release / "backend/audit-backend-0.1.0-SNAPSHOT.jar", release / "frontend/dist/index.html",
             Path("/etc/audit-agent/backend.env"), release / "deploy/update-v10/backend.env",
             Path("/home/admin/audit_ai_runtime/audit-ai.env"), release / "deploy/update-v10/ai.env",
             Path("/etc/nginx/nginx.conf")]:
    artifacts[str(path)] = fingerprint(path)
output = {"system": platform.system(), "machine": platform.machine(), "release": str(release),
          "services": services, "artifacts": artifacts,
          "java": run(["/opt/java/current/bin/java", "--version"]),
          "python": run(["/opt/python/venvs/audit-ai/bin/python", "--version"]),
          "disk": run(["df", "-h", "/opt", "/home", "/var"]),
          "listening": run(["ss", "-H", "-ltn"]),
          "nginxSyntax": subprocess.run(["/usr/sbin/nginx", "-t"], capture_output=True, timeout=15).returncode,
          "readOnly": True, "businessDml": False, "configurationValuesExported": False}
print(json.dumps(output, ensure_ascii=False, indent=2))
