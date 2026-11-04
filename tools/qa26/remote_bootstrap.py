"""Bootstrap a new complete schema. Never connect to the original application DB."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import threading

job = Path(sys.argv[1]).resolve()
assert str(job).startswith(str(Path.home() / "audit-agent-backups/auto-case-phase2-"))
instance = json.loads((job / "isolated-instance.json").read_text())
assert instance["port"] == 15287 and instance["ini"] == str(job / "instance/AUTOCASE2/dm.ini")
tools = job / "contract-schema"
plan = json.loads(Path(sys.argv[2]).read_text())
assert plan["target"] == "EMPTY_QA26_FULL2_ON_AUTOCASE2_ONLY"
assert hashlib.sha256(json.dumps(plan["statements"]).encode()).hexdigest() == plan["digest"]
statefile = job / "qa26-full2-private.json"
app = "QA26_FULL2"
classpath = str(tools) + ":" + str(tools / "dmjdbc.jar")

def save(state):
    temp = statefile.with_suffix(".tmp")
    temp.write_text(json.dumps(state))
    temp.chmod(0o600)
    temp.replace(statefile)

def env(user, password):
    return dict(os.environ, AUDIT_DB_URL="jdbc:dm://127.0.0.1:15287", AUDIT_DB_USERNAME=user, AUDIT_DB_PASSWORD=password)

def query(user, password, statement):
    result = subprocess.run(["/opt/java/current/bin/java", "-cp", classpath, "IsolatedSql"], input=statement + "\n",
                            env=env(user, password), text=True, capture_output=True, timeout=40)
    assert result.returncode == 0, "ISOLATED_QUERY_FAILED"
    return result.stdout.strip()

os.umask(0o077)
if not statefile.exists():
    assert query("SYSDBA", instance["sysdba_password"], "SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME='QA26_FULL2'") == "0", "UNOWNED_SCHEMA_EXISTS"
    state = {"application_user": app, "application_password": "Qf9_" + secrets.token_hex(18), "port": 15287,
             "planDigest": plan["digest"], "ack": 0, "pending": "CREATE_USER"}
    save(state)
    for sql in ['CREATE USER ' + app + ' IDENTIFIED BY "' + state["application_password"] + '"', 'GRANT RESOURCE, PUBLIC TO ' + app]:
        query("SYSDBA", instance["sysdba_password"], sql)
    state["pending"] = None
    save(state)
state = json.loads(statefile.read_text())
assert state["port"] == 15287 and state["application_user"] == app and state["planDigest"] == plan["digest"]
assert state["pending"] is None, "UNCERTAIN_DDL_REQUIRES_INSPECTION_DO_NOT_REPLAY"
if state["ack"] == 0:
    assert query(app, state["application_password"], "SELECT COUNT(*) FROM USER_TABLES") == "0", "NONEMPTY_SCHEMA"
while state["ack"] < len(plan["statements"]):
    index = state["ack"]
    # One JDBC process per chunk keeps receipts conservative across process interruptions.
    chunk = plan["statements"][index:index + 100]
    state["pending"] = {"first": index + 1, "last": index + len(chunk)}
    save(state)
    process = subprocess.Popen(["/opt/java/current/bin/java", "-cp", classpath, "IsolatedSql"],
                               env=env(app, state["application_password"]), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True)
    def feed():
        try:
            process.stdin.write("\n".join(" ".join(s.splitlines()) for s in chunk) + "\n")
            process.stdin.close()
        except BrokenPipeError:
            pass
    threading.Thread(target=feed, daemon=True).start()
    for line in process.stdout:
        assert line.startswith("SQL_OK "), "DDL_FAILED_INSPECT_PENDING"
        state["ack"] += 1
        save(state)
    assert process.wait(timeout=80) == 0 and state["ack"] == index + len(chunk), "DDL_INCOMPLETE_DO_NOT_REPLAY"
    state["pending"] = None
    save(state)
    print("QA26_DDL_ACK=" + str(state["ack"]), flush=True)
print(json.dumps({"schema": app, "instance": "AUTOCASE2", "ddlAck": state["ack"], "originalDatabaseModified": False}))
