"""Read disposable QA credentials over pinned SSH and pass them to Node in memory."""
import argparse
import getpass
import json
import hashlib
from pathlib import Path
import subprocess
import time
import urllib.request
import paramiko

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("host")
parser.add_argument("user")
parser.add_argument("mode", choices=["inspect", "accept"])
parser.add_argument("--revision", choices=["r1", "r2", "r3", "r4", "r5"], default="r1")
args = parser.parse_args()
client = paramiko.SSHClient()
client.load_system_host_keys()
client.load_host_keys(str(Path.home() / ".ssh/known_hosts"))
client.set_missing_host_key_policy(paramiko.RejectPolicy())
password = getpass.getpass("SSH password (memory only): ")
try:
    client.connect(args.host, username=args.user, password=password, allow_agent=False, look_for_keys=False, timeout=15)
    password = None
    with client.open_sftp() as sftp:
        if args.revision in {'r4', 'r5'}:
            # Wait for the exact UI candidate; never test the old running release
            # and accidentally label that as verification of a pending update.
            expected = hashlib.sha256((ROOT / 'frontend/dist/index.html').read_bytes()).digest()
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            deadline = time.monotonic() + 1800
            print('QA26_BROWSER_WAITING_FOR_R4', flush=True)
            while True:
                try:
                    with opener.open('http://' + args.host + ':18082/', timeout=5) as response:
                        ready = response.status == 200 and hashlib.sha256(response.read()).digest() == expected
                    for name in ('procurement-normal-r3', 'contract-normal-service-r3'):
                        sftp.stat('/home/admin/qa26-public-evidence/' + name + '-verification.json')
                    if ready:
                        break
                except OSError:
                    pass
                assert time.monotonic() < deadline, 'R4_UI_NOT_READY_WITHIN_BOUNDED_WAIT'
                time.sleep(10)
            print('QA26_BROWSER_R4_READY', flush=True)
        with sftp.open("/home/admin/audit-agent-backups/qa26-stage2-20261027/qa26-access-private.json") as f:
            account = json.loads(f.read())["primary"]
        ids = {}
        for module, label in (("procurement", "normal"), ("contract", "normal-service")):
            suffix = "-r3" if args.revision in {"r3", "r4", "r5"} else ""
            directory = "/home/admin/qa26-public-evidence/" if suffix else "/home/admin/audit-agent-backups/qa26-stage2-20261027/results/"
            with sftp.open(directory + module + "-" + label + suffix + "-verification.json") as f:
                result=json.loads(f.read())
                assert not result["failures"]
                ids[module + "CaseId"] = result["caseId"]
    config = dict(account, base="http://" + args.host + ":18082", mode=args.mode, revision=args.revision,
                  executable="E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe")
    config.update(ids)
    result = subprocess.run(["node", str(ROOT / "tools/qa26/browser.mjs")], input=json.dumps(config), text=True, cwd=ROOT)
    raise SystemExit(result.returncode)
finally:
    client.close()
