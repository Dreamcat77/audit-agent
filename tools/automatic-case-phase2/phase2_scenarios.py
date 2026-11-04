"""Additional isolated acceptance scenarios; loaded on demand by the coordinator."""
import hashlib
import json
import time
import uuid
from pathlib import Path

BASE="http://127.0.0.1:19131/api/v1/procurement/cases"

def run(request, ctx):
    ctx["SSH"].get_transport().set_keepalive(15)
    evidence=ctx["EVIDENCE"]
    def get(case_id):
        for attempt in range(12):
            r=ctx["SESSION"].get(BASE+"/"+case_id,timeout=20)
            if r.status_code!=409:r.raise_for_status();return r.json()["data"]
            time.sleep(.2)
        raise RuntimeError("case remained busy after bounded HTTP retry")
    def upload(paths):
        session=ctx["SESSION"]
        token=session.get("http://127.0.0.1:19131/api/v1/auth/csrf",timeout=10).json()["data"]["token"]
        from contextlib import ExitStack
        with ExitStack() as stack:
            files=[("files",(p.name,stack.enter_context(p.open("rb")))) for p in paths]
            r=session.post(BASE,files=files,headers={"X-CSRF-TOKEN":token,"Idempotency-Key":str(uuid.uuid4())},timeout=90)
        r.raise_for_status();value=r.json();assert value["code"]==200,value
        return value["data"]
    def await_case(case_id,condition,seconds=180):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            value=get(case_id)
            if condition(value):return value
            time.sleep(.5)
        raise RuntimeError("isolated restart scenario timed out: "+case_id)
    test=request["test"]
    if test=="restart":
        completed=json.loads((evidence/"dm8-simulated-result.json").read_text(encoding="utf-8"))["data"]
        ctx["start_backend"]("simulated",scan_delay=600000)
        assert get(completed["id"])==completed
        # A real process restart with no schema/data edits. A long scan delay leaves the new case queued.
        paths=sorted(Path(ctx["CONFIG"]["fixtures"]).iterdir())
        queued=upload(paths);assert queued["status"]=="QUEUED"
        ctx["stop"]("backend")
        ctx["start_backend"]("simulated")
        recovered=await_case(queued["id"],lambda c:c["status"] not in ["QUEUED","RUNNING","UPLOADING"])
        assert recovered["status"]=="COMPLETED"
        assert len(recovered["report"]["offers"])==3
        # Terminate during a persisted CALLING checkpoint. Advance only the test-injected clock,
        # rather than waiting 30 minutes or editing a lease in DM8.
        gate=ctx["BACKUP"]/"never-open-model-gate"
        assert not gate.exists()
        ctx["start_backend"]("simulated",gate=gate)
        interrupted=upload([paths[0]])
        calling=await_case(interrupted["id"],lambda c:any(d["status"]=="CALLING" for d in c["documents"]))
        ctx["stop"]("backend")
        ctx["BASE_ENV"]["AUDIT_CASE_TEST_CLOCK_OFFSET_SECONDS"]="1860"
        try:
            ctx["start_backend"]("simulated")
            unknown=await_case(interrupted["id"],lambda c:c["status"] not in ["QUEUED","RUNNING","UPLOADING"])
            assert unknown["documents"][0]["status"]=="UNKNOWN"
            assert unknown["documents"][0]["errorCode"]=="EXECUTOR_LOST_EXTERNAL_OUTCOME_UNKNOWN"
            assert unknown["status"]!="COMPLETED"
        finally:ctx["BASE_ENV"].pop("AUDIT_CASE_TEST_CLOCK_OFFSET_SECONDS",None)
        result={"environment":"isolated real DM8, real HTTP, real Java process restarts, simulated semantic model",
                "completedReopenIdentical":True,"queuedBeforeRestart":queued,"queuedAfterRestart":recovered,
                "callingBeforeTermination":calling,"afterLeaseExpiryRestart":unknown,
                "leaseExpiryMethod":"test-only injected Clock offset +1860 seconds; no database edits",
                "externalCallReplayed":False}
        (evidence/"restart-recovery.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
        print("RESTART_PASS completed-reopen queued-auto-resume CALLING-UNKNOWN-no-replay",flush=True)
    elif test=="reopen":
        label=request["label"]
        original=json.loads((evidence/(label+"-result.json")).read_text(encoding="utf-8"))["data"]
        ctx["start_backend"](ctx["MODE"])
        reopened=get(original["id"]);assert reopened==original
        originals=[]
        paths={p.name:p for p in Path(ctx["CONFIG"]["fixtures"]).iterdir()}
        for doc in reopened["documents"]:
            response=ctx["SESSION"].get(BASE+"/"+reopened["id"]+"/files/"+doc["id"]+"/original",timeout=15)
            response.raise_for_status()
            expected=hashlib.sha256(paths[doc["name"]].read_bytes()).hexdigest()
            actual=hashlib.sha256(response.content).hexdigest();assert expected==actual
            originals.append({"file":doc["name"],"sha256":actual,"bytes":len(response.content)})
        (evidence/(label+"-reopen-originals.json")).write_text(json.dumps({"reopenIdentical":True,"case":reopened["id"],"originals":originals},ensure_ascii=False,indent=2),encoding="utf-8")
        print("REOPEN_ORIGINALS_PASS "+label,flush=True)
    elif test=="frontend":
        import subprocess
        ctx["stop"]("frontend")
        env=dict(ctx["os"].environ,AUDIT_VITE_API_PROXY_TARGET="http://127.0.0.1:19131",VITE_AUTH_MODE="real",VITE_API_BASE_URL="")
        log=(ctx["BACKUP"]/"vite.log").open("wb")
        p=subprocess.Popen(["node",str(ctx["ROOT"]/"frontend/node_modules/vite/bin/vite.js"),"--host","127.0.0.1","--port","19132","--strictPort"],cwd=ctx["ROOT"]/"frontend",env=env,stdout=log,stderr=subprocess.STDOUT)
        ctx["CHILDREN"]["frontend"]=p;ctx["wait_health"]("http://127.0.0.1:19132",p)
        print("FRONTEND_REAL_AUTH_READY",flush=True)
    elif test in ["schema","database"]:
        import subprocess
        env=dict(ctx["BASE_ENV"],AUDIT_DB_URL=ctx["BASE_ENV"]["AUDIT_CASE_TEST_DB_URL"],AUDIT_DB_USERNAME=ctx["STATE"]["application_user"],AUDIT_DB_PASSWORD=ctx["STATE"]["application_password"])
        java=str(ctx["JAVA"]);javac=str(ctx["JAVA"].with_name("javac.exe"))
        out=ctx["BACKUP"]/"schema-check-classes";out.mkdir(exist_ok=True)
        sources=[ctx["ROOT"]/"tools/automatic-case-phase2/AutoCaseSchemaCheck.java",ctx["ROOT"]/"tools/automatic-case-phase2/Phase2DatabaseEvidence.java"]
        subprocess.run([javac,"--release","17","-encoding","UTF-8","-cp",ctx["CP"],"-d",str(out)]+list(map(str,sources)),check=True,capture_output=True)
        cp=str(out)+ctx["os"].pathsep+ctx["CP"]
        def snapshot():
            p=subprocess.run([java,"-cp",cp,"Phase2DatabaseEvidence"],env=env,capture_output=True,text=True,encoding="utf-8")
            assert p.returncode==0,p.stderr
            return json.loads(p.stdout)
        before=snapshot()
        if test=="database":
            (evidence/"database-catalog-and-data.json").write_text(json.dumps(before,ensure_ascii=False,indent=2),encoding="utf-8")
            print("DATABASE_EVIDENCE_SAVED",flush=True);return
        p=subprocess.run([java,"-cp",cp,"AutoCaseSchemaCheck",str(ctx["ROOT"]/"db/dm8/PROCUREMENT__automatic_case_v1.sql"),"apply"],env=env,capture_output=True,text=True)
        (evidence/"schema-repeat-with-data.log").write_text(p.stdout+p.stderr,encoding="utf-8")
        assert p.returncode==0,p.stdout+p.stderr
        after=snapshot();assert before["tables"]==after["tables"]
        (evidence/"schema-repeat-data-integrity.json").write_text(json.dumps({"before":before["tables"],"after":after["tables"],"identical":True},indent=2),encoding="utf-8")
        print("SCHEMA_REPEAT_WITH_DATA_PASS",flush=True)
    elif test=="feature-off":
        original=json.loads((evidence/"dm8-actual-result.json").read_text(encoding="utf-8"))["data"]
        ctx["BASE_ENV"]["SPRING_APPLICATION_JSON"]=json.dumps({"audit":{"procurement":{"auto-case":{"enabled":False}}}})
        try:
            ctx["start_backend"]("actual")
            response=ctx["SESSION"].get(BASE,timeout=15);assert response.status_code==404
        finally:ctx["BASE_ENV"].pop("SPRING_APPLICATION_JSON",None)
        ctx["start_backend"]("actual")
        assert get(original["id"])==original
        (evidence/"feature-off-reopen.json").write_text(json.dumps({"disabledHttpStatus":404,"reenabledOriginalCaseIdentical":True,"scope":"isolated harness only; no existing service or production switch changed"},indent=2),encoding="utf-8")
        print("FEATURE_OFF_404_REOPEN_PASS",flush=True)
    elif test=="finalize":
        import shlex
        # Stop local writers first; the remote script checks the exact PID and ini path before SIGTERM.
        for name in list(ctx["CHILDREN"]):ctx["stop"](name)
        remote=ctx["REMOTE"]+"/remote_finalize.py"
        ctx["SFTP"].put(str(ctx["ROOT"]/"tools/automatic-case-phase2/remote_finalize.py"),remote)
        _,stdout,stderr=ctx["SSH"].exec_command("python3 "+shlex.quote(remote)+" "+shlex.quote(ctx["REMOTE"]))
        text=stdout.read().decode();code=stdout.channel.recv_exit_status()
        assert code==0,"isolated finalization failed: "+stderr.read().decode()[:300]
        result=json.loads(text)
        (evidence/"finalization.json").write_text(json.dumps(result,indent=2),encoding="utf-8")
        cookie=ctx["BACKUP"]/"curl-session.cookies"
        if cookie.exists():cookie.unlink()
        print("FINALIZATION_PASS "+json.dumps(result),flush=True)
    elif test=="collect":
        destination=ctx["BACKUP"]/"remote-evidence";destination.mkdir(exist_ok=True)
        names=ctx["SFTP"].listdir(ctx["REMOTE"])
        hashes=[]
        for name in names:
            if name.endswith((".log",".tsv")) or name in ["restore-data-validation.json","consistent-backup-status.json"]:
                target=destination/name;ctx["SFTP"].get(ctx["REMOTE"]+"/"+name,str(target))
                hashes.append({"file":name,"bytes":target.stat().st_size,"sha256":hashlib.sha256(target.read_bytes()).hexdigest()})
        (evidence/"protected-remote-evidence-manifest.json").write_text(json.dumps(hashes,indent=2),encoding="utf-8")
        print("PROTECTED_REMOTE_EVIDENCE_COLLECTED "+str(len(hashes)),flush=True)
    else:raise ValueError("unknown extended scenario")
