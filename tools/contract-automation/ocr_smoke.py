"""Read one explicitly synthetic scan through the existing real OCR service."""
import json
from pathlib import Path
import sys
import time
import requests

root = Path(__file__).resolve().parents[2]
source = root / ".test-runtime/contract-fixtures/rotated-scan/倒置扫描服务合同.pdf"
started = time.monotonic()
with source.open("rb") as stream:
    response = requests.post("http://127.0.0.1:19146/ai/v1/ocr/extract",
                             files={"file": (source.name, stream, "application/pdf")}, timeout=620)
response.raise_for_status()
data = response.json()
assert data["code"] == 200 and data["data"]["text"], "real OCR produced no text"
text = data["data"]["text"]
private = root / ".test-runtime/ocr/contract-inverted-smoke.json"
private.parent.mkdir(parents=True, exist_ok=True)
private.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
expected = ["86000", "合同", "验收", "付款", "违约"]
result = {"synthetic": True, "realOcr": True, "seconds": round(time.monotonic()-started, 2),
          "chars": len(text), "pages": len(data["data"]["pages"]),
          "missingExpectedTokens": [token for token in expected if token not in text],
          "contractModelE2E": "NOT_EXECUTED_BY_THIS_SMOKE"}
(root / "docs/contract-automation/evidence/p5-inverted-ocr-smoke.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result), flush=True)
sys.exit(bool(result["missingExpectedTokens"]))
