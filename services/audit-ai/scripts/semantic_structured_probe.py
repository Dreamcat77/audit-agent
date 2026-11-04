"""Run aggregate-only Semantic transport qualification over stdin fixture rows.

Input rows are never echoed or persisted. Each row is:
MATERIAL_CATEGORY|FILE_ID|BASE64_UTF8_REDACTED_TEXT
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys

import httpx

from audit_ai.model.evidence import EvidenceSource, segment_evidence_sources
from audit_ai.model.structured_probe import (
    ProbeFixture,
    ProbeLevel,
    ProbeTransport,
    aggregate_observations,
    run_probe_once,
)


def _required(name: str) -> str:
    value = os.getenv(name)
    if value is None or not value.strip():
        raise RuntimeError(f"{name} is required")
    return value.strip()


def _fixture() -> ProbeFixture:
    sources: list[EvidenceSource] = []
    for raw in sys.stdin:
        category, file_id, encoded = raw.rstrip("\n").split("|", 2)
        sources.append(
            EvidenceSource(
                file_id=file_id,
                material_category=category,
                text=base64.b64decode(encoded).decode("utf-8"),
            )
        )
    if not sources:
        raise RuntimeError("probe fixture is empty")
    catalog = segment_evidence_sources(sources, max_segment_chars=800)
    selected = []
    length = 0
    seen_categories: set[str] = set()
    for segment in catalog.segments:
        if not segment.citable:
            continue
        block = (
            f"[evidenceId={segment.evidence_id};fileId={segment.file_id};"
            f"category={segment.material_category};citable=true]\n"
            f"{segment.exact_text}"
        )
        if length + len(block) > 6000 and segment.material_category in seen_categories:
            continue
        if length + len(block) > 10000:
            break
        selected.append((segment, block))
        seen_categories.add(segment.material_category)
        length += len(block)
    if not selected:
        raise RuntimeError("probe fixture has no citable segment")
    required = (
        (
            "CONTRACT_STRUCTURE",
            "PROJECT_IDENTITY",
            "SUPPLIER_IDENTITY",
            "DELIVERY_TERMS",
            "SERVICE_WARRANTY",
            "SCOPE_AND_SUBCONTRACTING",
            "TERM_TERMINATION",
        )
        if "CONTRACT_BODY" in seen_categories
        else ("OTHER_TEXT",)
    )
    return ProbeFixture(
        source="\n\n".join(block for _, block in selected),
        allowed_evidence_ids=frozenset(
            segment.evidence_id for segment, _ in selected
        ),
        required_categories=required,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", choices=[item.value for item in ProbeTransport], required=True)
    parser.add_argument("--level", choices=[item.value for item in ProbeLevel], action="append")
    parser.add_argument("--iterations", type=int, default=20)
    args = parser.parse_args()
    if not 1 <= args.iterations <= 20:
        raise RuntimeError("iterations must be between 1 and 20")
    base_url = _required("PROBE_OLLAMA_BASE_URL").rstrip("/")
    model = _required("AUDIT_MODEL_NAME")
    api_key = _required("AUDIT_MODEL_API_KEY")
    fixture = _fixture()
    headers = {"Authorization": f"Bearer {api_key}"}
    transport = ProbeTransport(args.transport)
    with httpx.Client(timeout=httpx.Timeout(180.0, connect=5.0)) as client:
        version = client.get(f"{base_url}/api/version", headers=headers)
        version.raise_for_status()
        version_body = version.json()
        shown = client.post(
            f"{base_url}/api/show", headers=headers, json={"model": model}
        )
        shown.raise_for_status()
        show_body = shown.json()
        tags = client.get(f"{base_url}/api/tags", headers=headers)
        tags.raise_for_status()
        tags_body = tags.json()
        exact_model_match = (
            isinstance(tags_body, dict)
            and isinstance(tags_body.get("models"), list)
            and any(
                isinstance(item, dict) and item.get("name") == model
                for item in tags_body["models"]
            )
            and isinstance(show_body, dict)
            and show_body.get("details") is not None
        )
        provider_version = (
            version_body.get("version")
            if isinstance(version_body, dict)
            else None
        )
        if not isinstance(provider_version, str) or not provider_version.strip():
            raise RuntimeError("provider identity could not be verified")
        if not exact_model_match:
            raise RuntimeError("exact model identity could not be verified")
        print(json.dumps({
            "provider": "OLLAMA_PRIVATE",
            "version": provider_version,
            "model": model,
            "modelIdentityMatched": exact_model_match,
            "transport": transport.value,
            "iterationsPerLevel": args.iterations,
        }, separators=(",", ":")))
        levels = [ProbeLevel(value) for value in args.level] if args.level else list(ProbeLevel)
        for level in levels:
            observations = [
                run_probe_once(
                    base_url=base_url,
                    api_key=api_key,
                    model=model,
                    transport=transport,
                    level=level,
                    fixture=fixture,
                    client=client,
                )
                for _ in range(args.iterations)
            ]
            print(json.dumps({
                "transport": transport.value,
                "level": level.value,
                **aggregate_observations(observations),
            }, separators=(",", ":"), sort_keys=True))


if __name__ == "__main__":
    main()
