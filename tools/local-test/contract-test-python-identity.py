"""Read-only source/interpreter identity; does not construct OCR/model services."""
import importlib.util
import os
from pathlib import Path
import sys

expected = Path(os.environ["CONTRACT_TEST_PYTHON_SOURCE"]).resolve()
actual = importlib.util.find_spec("audit_ai")
if sys.version_info[:2] != (3, 11) or actual is None:
    raise SystemExit("PYTHON_IDENTITY_BLOCKED")
locations = list(actual.submodule_search_locations or [])
if len(locations) != 1 or Path(locations[0]).resolve() != expected / "audit_ai":
    raise SystemExit("PYTHON_SOURCE_BLOCKED")
print("PYTHON_IDENTITY=3.11_TARGET_SOURCE_VERIFIED")
