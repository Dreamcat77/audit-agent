"""Finish the current contract stage sequentially, preserving actual verdicts."""
import json
from pathlib import Path
import subprocess
import sys
import requests

root=Path(__file__).resolve().parents[2]
evidence=root/"docs/contract-automation/evidence"
token=(root/".test-runtime/contract-acceptance/control-token").read_text(encoding="utf-8")
def command(action,**kw):
    response=requests.post("http://127.0.0.1:19147/command",headers={"Authorization":"Bearer "+token},json={"action":action,**kw},timeout=2300)
    response.raise_for_status()
    result=response.json()
    if not result.get("completed"):raise RuntimeError(result.get("reason","acceptance command failed"))
    print("COMMAND_COMPLETED "+action+" "+kw.get("scenario",kw.get("mode","")),flush=True)
command("ai")
command("backend",mode="actual")
verdicts=[]
for scenario in ("valid-amendment-clear","partial-file"):
    command("scenario",scenario=scenario)
    result=json.loads((evidence/("e2e-"+scenario+"-verification.json")).read_text(encoding="utf-8"))
    verdicts.append(result)
    print(json.dumps(result,ensure_ascii=False),flush=True)
faults=subprocess.run([sys.executable,str(root/"tools/contract-automation/fault_acceptance.py")],cwd=root)
if faults.returncode:raise RuntimeError("fault injection verification failed; preserve results")
spec=next(s for s in json.loads((root/".test-runtime/contract-fixtures/golden.json").read_text(encoding="utf-8")) if s["id"]=="missing-fields")
command("browser",paths=spec["paths"],label="browser109-final-upload",production=True,
        executable=str(root.parents[0]/"browser-compat-test/chrome-win/chrome.exe"))
if any(r["failures"] for r in verdicts):raise RuntimeError("Golden assertions failed; inspect verification, do not mark the stage complete")
print("ISOLATED_CONTRACT_FINAL_ACCEPTANCE=PASS",flush=True)
