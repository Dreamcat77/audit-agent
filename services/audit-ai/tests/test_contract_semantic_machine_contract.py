from __future__ import annotations

import pytest

from audit_ai.model.contract_draft import (
    ProviderContractDraft,
    ProviderDraftFailureReason,
    ProviderDraftValidationFailure,
    parse_provider_contract_draft,
    provider_contract_json_schema,
)
from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.gateway import provider_compatible_json_schema
from audit_ai.model.router import ModelApplicationService, UnprocessableContractResult


def valid_payload() -> dict[str, object]:
    return {
        "summary": "简短、可验证的语义摘要。",
        "hints": [
            {
                "category": "CONTRACT_STRUCTURE",
                "attention": "INFO",
                "title": "结构信息",
                "description": "正文包含可定位的结构信息。",
                "problemEvidenceIds": [],
                "comparisonEvidenceIds": ["C:S1"],
                "unavailableMaterialCategory": None,
            }
        ],
    }


def test_schema_is_generated_from_the_strict_production_model() -> None:
    schema = provider_contract_json_schema()

    assert schema == ProviderContractDraft.model_json_schema(by_alias=True)
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {"summary", "hints"}
    hint = schema["$defs"]["ProviderHintDraft"]
    assert hint["additionalProperties"] is False
    assert set(hint["required"]) == {
        "category",
        "attention",
        "title",
        "description",
        "problemEvidenceIds",
        "comparisonEvidenceIds",
        "unavailableMaterialCategory",
    }
    property_names: set[str] = set()

    def collect_properties(node: object) -> None:
        if isinstance(node, dict):
            properties = node.get("properties")
            if isinstance(properties, dict):
                property_names.update(properties)
            for value in node.values():
                collect_properties(value)
        elif isinstance(node, list):
            for value in node:
                collect_properties(value)

    collect_properties(schema)
    for server_owned in (
        "fileId",
        "fileSha256",
        "parseResultId",
        "parseVersion",
        "canonicalTextSha256",
        "pageNumber",
        "paragraphIndex",
        "offset",
        "excerpt",
        "locator",
        "provider",
        "model",
    ):
        assert server_owned not in property_names


def test_transport_schema_removes_only_the_proven_unsupported_max_length() -> None:
    authority = provider_contract_json_schema()
    projected = provider_compatible_json_schema(authority)

    def count_key(value: object, key: str) -> int:
        if isinstance(value, dict):
            return (1 if key in value else 0) + sum(
                count_key(child, key) for child in value.values()
            )
        if isinstance(value, list):
            return sum(count_key(child, key) for child in value)
        return 0

    assert count_key(authority, "maxLength") == 3
    assert count_key(projected, "maxLength") == 0
    for retained in (
        "$defs",
        "$ref",
        "anyOf",
        "minLength",
        "maxItems",
        "additionalProperties",
    ):
        assert count_key(projected, retained) == count_key(authority, retained)
    assert count_key(authority, "maxLength") == 3


def test_transport_projection_does_not_weaken_runtime_string_length_validation() -> None:
    payload = valid_payload()
    payload["summary"] = "x" * 4001

    with pytest.raises(ProviderDraftValidationFailure) as caught:
        parse_provider_contract_draft(payload)

    assert caught.value.reason is ProviderDraftFailureReason.TYPE_MISMATCH


def test_strict_parser_accepts_only_the_minimal_machine_contract() -> None:
    draft = parse_provider_contract_draft(valid_payload())

    assert draft.hints[0].category == "CONTRACT_STRUCTURE"
    assert draft.hints[0].comparison_evidence_ids == ["C:S1"]


@pytest.mark.parametrize(
    ("mutation", "reason"),
    [
        (lambda payload: payload.pop("summary"), ProviderDraftFailureReason.MISSING_REQUIRED_FIELD),
        (
            lambda payload: payload["hints"][0].update(category="NOT_A_CATEGORY"),
            ProviderDraftFailureReason.ENUM_VALUE_INVALID,
        ),
        (
            lambda payload: payload["hints"][0].update(fileId="provider-owned"),
            ProviderDraftFailureReason.EXTRA_PROPERTY,
        ),
        (
            lambda payload: payload["hints"][0].update(problemEvidenceIds="C:S1"),
            ProviderDraftFailureReason.TYPE_MISMATCH,
        ),
    ],
)
def test_strict_parser_returns_a_finite_safe_failure_reason(mutation, reason) -> None:
    payload = valid_payload()
    mutation(payload)

    with pytest.raises(ProviderDraftValidationFailure) as caught:
        parse_provider_contract_draft(payload)

    assert caught.value.reason is reason
    assert str(caught.value) == "semantic provider draft failed strict validation"


def test_runtime_normalization_rejects_provider_extra_fields_before_compatibility_mapping() -> None:
    payload = valid_payload()
    payload["hints"][0]["fileId"] = "must-not-be-provider-owned"
    catalog = EvidenceCatalog(
        [
            EvidenceSegment(
                evidence_id="C:S1",
                file_id="contract",
                material_category="CONTRACT_BODY",
                source_start=0,
                source_end=4,
                exact_text="正文条款",
                citable=True,
            )
        ]
    )

    with pytest.raises(UnprocessableContractResult) as caught:
        ModelApplicationService._normalize_contract_result(
            payload,
            {"contract": "正文条款"},
            {"CONTRACT_BODY"},
            catalog,
            {"C:S1"},
            "A1",
            strict_machine_contract=True,
        )

    assert caught.value.safe_reason == "EXTRA_PROPERTY"
