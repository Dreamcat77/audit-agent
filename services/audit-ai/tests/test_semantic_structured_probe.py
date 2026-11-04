from __future__ import annotations

import json

import httpx
import pytest

from audit_ai.model.contract_draft import provider_contract_json_schema
from audit_ai.model.gateway import provider_compatible_json_schema
from audit_ai.model.structured_probe import (
    ProbeFixture,
    ProbeLevel,
    ProbeTransport,
    aggregate_observations,
    build_request,
    probe_schema,
    run_probe_once,
)


def fixture() -> ProbeFixture:
    return ProbeFixture(
        source="[evidenceId=C:S1;citable=true]\n可验证条款。",
        allowed_evidence_ids=frozenset({"C:S1"}),
        required_categories=("CONTRACT_STRUCTURE",),
    )


def test_level_four_schema_is_the_compatible_projection_of_the_machine_contract() -> None:
    assert probe_schema(ProbeLevel.LEVEL_4) == provider_compatible_json_schema(
        provider_contract_json_schema()
    )


def test_three_transports_keep_the_same_schema_and_generation_policy() -> None:
    schema = probe_schema(ProbeLevel.LEVEL_4)
    prompt = build_request(ProbeTransport.PROMPT_ONLY, ProbeLevel.LEVEL_4, fixture(), "m")
    compatible = build_request(
        ProbeTransport.OPENAI_COMPAT_STRUCTURED,
        ProbeLevel.LEVEL_4,
        fixture(),
        "m",
    )
    native = build_request(
        ProbeTransport.OLLAMA_NATIVE_STRUCTURED,
        ProbeLevel.LEVEL_4,
        fixture(),
        "m",
    )

    assert prompt.body["response_format"] == {"type": "json_object"}
    assert compatible.body["response_format"]["json_schema"]["schema"] == schema
    assert compatible.body["temperature"] == 0
    assert compatible.body["max_tokens"] == 4096
    assert native.body["format"] == schema
    assert native.body["options"] == {"temperature": 0, "num_predict": 4096}
    assert prompt.messages == compatible.messages == native.messages


def test_probe_observation_never_contains_prompt_source_or_raw_response() -> None:
    response_payload = {
        "category": "CONTRACT_STRUCTURE",
    }

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "m",
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {"content": json.dumps(response_payload)},
                    }
                ],
                "usage": {
                    "prompt_tokens": 2,
                    "completion_tokens": 3,
                    "total_tokens": 5,
                },
            },
        )

    observation = run_probe_once(
        base_url="http://provider.invalid",
        api_key="must-not-escape",
        model="m",
        transport=ProbeTransport.PROMPT_ONLY,
        level=ProbeLevel.LEVEL_1,
        fixture=fixture(),
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )

    assert observation.schema_valid is True
    safe = observation.as_safe_dict()
    serialized = json.dumps(safe)
    assert not {
        "prompt",
        "rawResponse",
        "requestBody",
        "responseBody",
        "documentText",
    }.intersection(safe)
    for forbidden in (
        "must-not-escape",
        "可验证条款",
        "rawResponse",
        "requestBody",
        "responseBody",
    ):
        assert forbidden not in serialized


@pytest.mark.parametrize(
    ("status", "reason"),
    [
        (400, "PROVIDER_400"),
        (401, "PROVIDER_401"),
        (403, "PROVIDER_403"),
        (404, "PROVIDER_404"),
        (405, "PROVIDER_405"),
        (409, "PROVIDER_409"),
        (413, "PROVIDER_413"),
        (415, "PROVIDER_415"),
        (422, "PROVIDER_422"),
        (418, "PROVIDER_4XX_OTHER"),
        (429, "PROVIDER_429"),
        (503, "PROVIDER_5XX"),
    ],
)
def test_provider_http_failure_is_classified_without_exposing_response_body(
    status: int, reason: str
) -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(status, text="sensitive provider body")

    observation = run_probe_once(
        base_url="http://provider.invalid",
        api_key="secret",
        model="m",
        transport=ProbeTransport.OPENAI_COMPAT_STRUCTURED,
        level=ProbeLevel.LEVEL_1,
        fixture=fixture(),
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )

    assert observation.failure_stage == "PROVIDER_TRANSPORT"
    assert observation.failure_reason_code == reason
    assert observation.transport_success is True
    assert observation.provider_http_success is False
    assert "sensitive" not in json.dumps(observation.as_safe_dict())


def test_schema_rejection_preserves_transport_and_parse_success() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "m",
                "choices": [
                    {"message": {"content": json.dumps({"category": "INVALID"})}}
                ],
            },
        )

    observation = run_probe_once(
        base_url="http://provider.invalid",
        api_key="secret",
        model="m",
        transport=ProbeTransport.OPENAI_COMPAT_STRUCTURED,
        level=ProbeLevel.LEVEL_1,
        fixture=fixture(),
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )

    assert observation.transport_success is True
    assert observation.provider_http_success is True
    assert observation.json_parse_valid is True
    assert observation.schema_valid is False
    assert observation.failure_stage == "TYPED_SCHEMA"


def test_level_three_only_requires_categories_its_schema_can_express() -> None:
    rich_fixture = ProbeFixture(
        source=fixture().source,
        allowed_evidence_ids=frozenset({"C:S1"}),
        required_categories=(
            "CONTRACT_STRUCTURE",
            "SUPPLIER_IDENTITY",
            "TERM_TERMINATION",
        ),
    )
    request = build_request(
        ProbeTransport.OPENAI_COMPAT_STRUCTURED,
        ProbeLevel.LEVEL_3,
        rich_fixture,
        "m",
    )
    assert "requiredCategories=CONTRACT_STRUCTURE,TERM_TERMINATION" in (
        request.messages[1]["content"]
    )

    payload = {
        "hints": [
            {
                "category": category,
                "attention": "INFO",
                "problemEvidenceIds": [],
                "comparisonEvidenceIds": ["C:S1"],
            }
            for category in ("CONTRACT_STRUCTURE", "TERM_TERMINATION")
        ]
    }
    observation = run_probe_once(
        base_url="http://provider.invalid",
        api_key="secret",
        model="m",
        transport=ProbeTransport.OPENAI_COMPAT_STRUCTURED,
        level=ProbeLevel.LEVEL_3,
        fixture=rich_fixture,
        client=httpx.Client(
            transport=httpx.MockTransport(
                lambda _: httpx.Response(
                    200,
                    json={
                        "model": "m",
                        "choices": [
                            {"message": {"content": json.dumps(payload)}}
                        ],
                    },
                )
            )
        ),
    )

    assert observation.completeness_valid is True
    assert observation.evidence_id_valid is True
    assert observation.business_validator_valid is True


def test_aggregate_contains_only_counts_rates_latency_and_safe_reasons() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(400, text="must never be retained")

    observation = run_probe_once(
        base_url="http://provider.invalid",
        api_key="secret",
        model="m",
        transport=ProbeTransport.OPENAI_COMPAT_STRUCTURED,
        level=ProbeLevel.LEVEL_1,
        fixture=fixture(),
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )

    aggregate = aggregate_observations([observation])

    assert aggregate["total"] == 1
    assert aggregate["pass"]["schema_valid"] == 0
    assert aggregate["failureCounts"] == {
        "PROVIDER_TRANSPORT:PROVIDER_400": 1
    }
    assert "must never be retained" not in json.dumps(aggregate)
