"""Reproduce automatic procurement admission restrictions using existing synthetic files."""
import hashlib
import json
from pathlib import Path
import uuid
from importlib.util import module_from_spec, spec_from_file_location

spec=spec_from_file_location("qa26", "/home/admin/qa26-acceptance.py")
qa=module_from_spec(spec);spec.loader.exec_module(qa)
paths=[qa.FIXTURES / "budget/excel/预算申报及批复.xlsx",
       Path("/home/admin/audit-soak-20261021.lwAmqs/fixtures/sample-large.docx")]
results=[]
for path in paths:
    assert path.is_file() and path.name in {"预算申报及批复.xlsx","sample-large.docx"}
    operation=qa.identity("procurement-limit-" + path.suffix[1:], {"key":str(uuid.uuid4())})["key"]
    existing=qa.CLIENT.call("GET","/api/v1/procurement/cases/operations/"+operation)
    if existing:
        result={"caseId":existing["id"],"status":existing["status"],"reopened":True}
    else:
        with path.open("rb") as stream:
            response=qa.CLIENT.session.post(qa.BASE+"/api/v1/procurement/cases",headers={"Idempotency-Key":operation},
                     files={"files":(path.name,stream,"application/octet-stream")},timeout=190)
        body=response.json()
        result={"http":response.status_code,"code":body["code"],"caseId":(body.get("data") or {}).get("id")}
    result.update(name=path.name,bytes=path.stat().st_size,synthetic=True)
    results.append(result)
qa.save("procurement-admission-probe",results)
print(json.dumps(results),flush=True)
