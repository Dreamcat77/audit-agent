"""Synthetic-only contract auto-promotion acceptance for the deployed 26 release."""
import io
import json
import sys
import time
import uuid
import zipfile
from xml.sax.saxutils import escape

import requests


auth = json.loads(sys.stdin.readline())
client = requests.Session()
base = "http://127.0.0.1/api/v1"


def csrf():
    token = client.get(base + "/auth/csrf", timeout=30).json()["data"]
    client.headers[token["headerName"]] = token["token"]


def call(method, path, **kwargs):
    response = client.request(method, base + path, timeout=kwargs.pop("timeout", 1800), **kwargs)
    assert response.status_code in (200, 202), f"HTTP_{response.status_code}_{path}"
    body = response.json()
    assert body["code"] == 200, f"BUSINESS_{path}"
    return body["data"]


def docx(lines):
    body = (
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        "<w:body>"
        + "".join("<w:p><w:r><w:t>" + escape(line) + "</w:t></w:r></w:p>" for line in lines)
        + "</w:body></w:document>"
    )
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(
            "[Content_Types].xml",
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
            "</Types>",
        )
        archive.writestr(
            "_rels/.rels",
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
            "</Relationships>",
        )
        archive.writestr("word/document.xml", body)
    return out.getvalue()


csrf()
call("POST", "/auth/login", json={"username": auth["username"], "password": auth["password"]})
csrf()
project = call("POST", "/projects", headers={"Idempotency-Key": "synthetic-project-" + uuid.uuid4().hex})
project_id = project["id"]
scenario = sys.argv[1] if len(sys.argv)>1 else 'automatic'
suffix = uuid.uuid4().hex[:10]
project_no = 'SYN-PROJECT-'+suffix
if scenario == 'conflict': call(
    "PATCH",
    f"/projects/{project_id}",
    headers={"Idempotency-Key": "synthetic-metadata-" + uuid.uuid4().hex},
    json={"expectedVersion": project["versionNo"], "name": "2026年办公设备更新项目", "projectNo": project_no+'-OTHER', "category": "GOODS"},
)
lines = [
    "本材料全部为合成测试内容，不含甲方业务数据。",
    "合同编号：SYN-CONTRACT-"+suffix,
    "合同名称：办公设备采购及维护服务合同",
    "合同类型：货物采购合同",
    "项目名称：2026年办公设备更新项目",
    "项目编号："+project_no,
    "甲方：合成测试机关",
    "甲方统一社会信用代码：91110000SYNTHETIC01",
    "乙方：合成测试供应商有限公司",
    "乙方统一社会信用代码：91110000SYNTHETIC02",
    "合同金额：人民币壹拾贰万元整（120000.00元）",
    "中标金额：人民币壹拾贰万元整（120000.00元）",
    "中标供应商：合成测试供应商有限公司",
    "采购内容：台式计算机20台、显示器20台及安装调试服务。",
    "合同签订日期：2026年10月26日。",
    "履约期限：合同签订后30个日历日内完成交付安装。",
    "验收方式：甲方按照合同约定的数量、规格和性能进行验收，验收合格后出具验收记录。",
    "付款方式：验收合格后30日内支付合同价款的100%。",
    "质量保证：自验收合格之日起提供三年质保服务。",
    "违约责任：乙方逾期交付的，每逾期一日按合同金额的0.05%支付违约金。",
    "争议解决：协商不成的，向甲方所在地人民法院提起诉讼。",
    "本合同一式四份，甲乙双方签字盖章后生效。",
]
content = docx(lines)
filename = "合成完整合同-自动晋级-20261026.docx"
accepted = call(
    "POST",
    f"/projects/{project_id}/file-jobs",
    headers={"Idempotency-Key": "synthetic-file-" + uuid.uuid4().hex},
    files={"file": (filename, content, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")},
)
file_id = accepted["fileId"]
deadline = time.monotonic() + 1800
while True:
    detail = call("GET", f"/projects/{project_id}", timeout=180)
    file = next(item for item in detail.get("files", []) if item.get("id") == file_id)
    if file.get("parseStatus") in ("PARSED", "FAILED", "UNKNOWN"):
        break
    assert time.monotonic() < deadline, "PARSE_TIMEOUT"
    time.sleep(5)
assert file.get("parseStatus") == "PARSED", "PARSE_" + str(file.get("parseStatus"))
if scenario == 'conflict':
    # Classification and linking follow parsing; require the durable job to
    # finish before asserting that no contract was created.
    while True:
        state=call('GET',f'/projects/{project_id}/files/{file_id}/processing')
        if state['status'] in ('SUCCEEDED','FAILED','UNKNOWN'):break
        assert time.monotonic()<deadline,'CONFLICT_JOB_TIMEOUT'
        time.sleep(2)
    assert state['status']=='SUCCEEDED','CONFLICT_JOB_'+state['status']
    detail=call('GET',f'/projects/{project_id}')
    file=next(f for f in detail['files'] if f['id']==file_id)
    assert not detail.get('associatedContracts'),'CROSS_PROJECT_AUTO_PROMOTION'
    assert file.get('effectiveIdentityStatus')=='NOT_SAME_PROJECT','CONFLICT_IDENTITY_NOT_PROVEN'
    print(json.dumps({'result':'PASS','scenario':'conflict','projectId':project_id,'fileId':file_id,'jobStatus':state['status'],'identityStatus':file.get('effectiveIdentityStatus'),'contractCount':0}),flush=True)
    sys.exit(0)
deadline = time.monotonic() + 1800
while True:
    detail = call("GET", f"/projects/{project_id}", timeout=180)
    contracts = detail.get("associatedContracts") or []
    candidates = call("GET", f"/projects/{project_id}/contract-candidates", timeout=180)
    if contracts or (candidates and any(c.get("promotionState") == "DRAFT" for c in candidates)):
        break
    assert time.monotonic() < deadline, "AUTO_PROMOTION_TIMEOUT"
    time.sleep(5)
assert contracts, {"candidateCount": len(candidates), "fileStates": [
    {"parseStatus": f.get("parseStatus"), "role": f.get("effectiveRole"),
     "identity": f.get("effectiveIdentityStatus"),
     "progressionStage": (f.get("progression") or {}).get("stage")}
    for f in detail.get("files") or []
]}
contract = contracts[0]
files = [item for item in detail.get("files", []) if item.get("parseStatus") == "PARSED"]
run = call(
    "POST",
    f"/projects/{project_id}/contracts/{contract['contractId']}/review-runs",
    headers={"Idempotency-Key": "synthetic-review-" + uuid.uuid4().hex},
    json={"expectedContractVersion": contract["versionNo"], "materialFileIds": [item["id"] for item in files]},
)
run_id = run["runId"]
deadline = time.monotonic() + 1800
while True:
    current = call("GET", f"/projects/{project_id}/contracts/{contract['contractId']}/review-runs/{run_id}", timeout=180)
    if current.get("status") in ("COMPLETED", "FAILED", "UNKNOWN"):
        break
    assert time.monotonic() < deadline, "REVIEW_RUN_TIMEOUT"
    time.sleep(5)
assert current.get("status") == "COMPLETED", current.get("status")
assert len(current.get("steps") or []) == 5
assert all(step.get("status") == "COMPLETED" for step in current["steps"])
reopened = call("GET", f"/projects/{project_id}", timeout=180)
assert len(reopened.get("associatedContracts") or []) == 1
print(json.dumps({
    "result": "PASS",
    "projectId": project_id,
    "fileId": file_id,
    "contractId": contract["contractId"],
    "runId": run_id,
    "parseStatus": file.get("parseStatus"),
    "autoPromotion": True,
    "reviewRunStatus": current.get("status"),
    "stepCount": len(current["steps"]),
    "reopenedContractCount": len(reopened.get("associatedContracts") or []),
}, ensure_ascii=False), flush=True)
