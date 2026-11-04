"""Exercise the production UI in actual Chrome 109 against the isolated services."""
import json
from pathlib import Path
import requests

root = Path(__file__).resolve().parents[2]
token = (root / ".test-runtime/contract-acceptance/control-token").read_text(encoding="utf-8")
spec = next(s for s in json.loads((root / ".test-runtime/contract-fixtures/golden.json").read_text(encoding="utf-8")) if s["id"] == "missing-fields")
response = requests.post("http://127.0.0.1:19147/command",
    headers={"Authorization": "Bearer " + token}, timeout=2400,
    json={"action": "browser", "paths": spec["paths"], "label": "browser109-notice-fixed",
          "production": True, "executable": str(root.parent / "browser-compat-test/chrome-win/chrome.exe")})
response.raise_for_status()
result = response.json()
if not result.get("completed"):
    raise RuntimeError(result.get("reason", "browser acceptance failed; inspect isolated coordinator"))
print("BROWSER109_LATEST_PRODUCTION_UPLOAD=PASS", flush=True)
