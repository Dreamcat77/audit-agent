"""Synthetic-only real-provider checks; never print environment or provider bodies."""
import hashlib
import json
import logging
import sys
import time
import uuid
from dataclasses import replace, fields
from concurrent.futures import ThreadPoolExecutor

from fastapi.testclient import TestClient
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import OpenAiCompatibleModelGateway


def main():
    settings = Settings.from_env()
    if any(f.name == "material_checkpoint_root" for f in fields(settings)):
        settings = replace(settings, material_checkpoint_root=None)
    gateway = OpenAiCompatibleModelGateway(settings)
    print(json.dumps({"modelName": settings.model_name, "configured": bool(settings.model_name and settings.model_api_key and settings.model_base_url)}), flush=True)
    class DiagnosticGateway:
        def __getattr__(self, name):
            return getattr(gateway, name)
        def extract_material_fields(self, *args, **kwargs):
            try:
                return gateway.extract_material_fields(*args, **kwargs)
            except Exception as error:
                print(json.dumps({"gatewayFailureClass": type(error).__name__, "reasonCode": getattr(error, "reason", None), "retryable": getattr(error, "retryable", None)}), flush=True)
                raise
    app = create_app(settings, model_gateway=DiagnosticGateway())
    logging.disable(logging.CRITICAL)
    cases = [
        ("budget-uppercase", "BUDGET", "项目名称：仿真设备项目；科目名称：办公设备；本次申请人民币壹拾贰万叁仟肆佰伍拾陆元柒角捌分，经研究核准壹拾万元整。",
         {"declaredAmount": "壹拾贰万叁仟肆佰伍拾陆元柒角捌分", "approvalAmount": "壹拾万元整"}),
        ("budget-narrative", "BUDGET", "项目名称：仿真设备项目；科目名称：设备；设备本次申请9000元，经研究核准5000元。",
         {"declaredAmount": "9000元", "approvalAmount": "5000元"}),
        ("procurement-uppercase", "PROCUREMENT", "项目名称：仿真办公设备采购；项目编号：SYN-2026；预算金额：人民币壹拾贰万叁仟肆佰伍拾陆元柒角捌分；需求说明：配备办公终端并提供三年维护。",
         {"budgetAmount": "壹拾贰万叁仟肆佰伍拾陆元柒角捌分"}),
        ("procurement-dual-mismatch", "PROCUREMENT", "项目名称：仿真不一致采购；预算金额：120000元（大写：壹拾万元整）；采购方式：公开招标。",
         {}),
    ]
    if "--large" in sys.argv:
        for i in range(12):
            kind = "BUDGET" if i < 6 else "PROCUREMENT"
            paragraphs = [f"仿真附录第{p}节：本材料用于软件测试，设备应具备稳定运行和验收记录，相关人员需核对材料依据。" * 18 for p in range(12)]
            paragraphs.insert(6, "项目名称：仿真大材料；科目名称：设备；申报金额：壹拾贰万元整；批复金额：壹拾万元整。" if kind == "BUDGET" else "项目名称：仿真大采购；预算金额：壹拾贰万元整；采购方式：公开招标；需求说明：交付设备并提供三年维护。")
            cases.append((f"large-{i+1}", kind, paragraphs, {"declaredAmount" if kind == "BUDGET" else "budgetAmount": "壹拾贰万元整"}))
    failed = 0
    with TestClient(app) as client:
        def run_case(case):
            name, kind, source, expected = case
            texts = source if isinstance(source, list) else [source]
            body = {"businessType": kind, "materialId": str(uuid.uuid4()),
                    "sha256": hashlib.sha256("\n".join(texts).encode()).hexdigest(),
                    "segments": [{"locator": f"synthetic/paragraph:{i+1}", "text": text} for i, text in enumerate(texts)]}
            start = time.monotonic()
            try:
                response = client.post("/ai/v1/materials/extract-fields", json=body)
                result = response.json()
                data = result.get("data") or {}
                facts = [f for row in data.get("rows", []) for f in row.get("facts", [])]
                values = {field: [f["value"] for f in facts if f["field"] == field] for field in expected}
                # Exact financial substrings must survive; a currency prefix is allowed.
                passed = response.status_code == 200 and all(any(value in actual for actual in values[field]) for field, value in expected.items())
                print(json.dumps({"case": name, "httpStatus": response.status_code, "code": result.get("code"),
                                  "model": data.get("model"), "chars": sum(map(len, texts)), "facts": len(facts),
                                  "expectedValuesPresent": passed, "amountValues": values,
                                  "elapsedSeconds": round(time.monotonic()-start, 2)}, ensure_ascii=False), flush=True)
                return not passed
            except Exception as error:
                print(json.dumps({"case": name, "errorClass": type(error).__name__}), flush=True)
                return True
        if '--parallel' in sys.argv:
            with ThreadPoolExecutor(max_workers=2) as workers:
                failed = sum(workers.map(run_case, cases))
        else:
            failed = sum(run_case(case) for case in cases)
    print(json.dumps({"cases": len(cases), "failed": failed}), flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
