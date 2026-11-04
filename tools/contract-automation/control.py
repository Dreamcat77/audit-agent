"""Authenticated local acceptance control; no credentials enter evidence or stdout."""
import json
from pathlib import Path
import sys
import requests

root = Path(__file__).resolve().parents[2]
action = sys.argv[1]
payload = {"action": action}
if len(sys.argv) > 2:
    payload[{"scenario": "scenario", "backend": "mode", "restart": "mode"}.get(action, "caseId")] = sys.argv[2]
token = (root / ".test-runtime/contract-acceptance/control-token").read_text(encoding="utf-8")
response = requests.post("http://127.0.0.1:19147/command", json=payload,
                         headers={"Authorization": "Bearer " + token}, timeout=2400)
response.raise_for_status()
result = response.json()
print(json.dumps(result, ensure_ascii=False), flush=True)
sys.exit(not result.get("completed"))
