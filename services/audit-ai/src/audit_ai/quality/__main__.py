"""Offline runner: python -m audit_ai.quality --fixtures F --observations O --context C."""
import argparse
import json
from pathlib import Path
from .harness import evaluate
from .manifest import canonical_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("fixtures", "observations", "context"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    values = [json.loads(path.read_text(encoding="utf-8")) for path in (args.fixtures, args.observations, args.context)]
    print(canonical_json(evaluate(*values)))


if __name__ == "__main__":
    main()
