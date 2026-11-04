"""Compact provider rows must retain the public v2 fact/evidence contract."""

from __future__ import annotations

from copy import deepcopy
import hashlib
import json

import pytest
from fastapi.testclient import TestClient

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.contract_fact import ContractFactRequestV2, ContractFactServiceV2
from audit_ai.model.gateway import ModelCompletion


def digest(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def compact_row(index: int) -> dict:
    return {
        "evidenceId": f"evidence-{index}", "name": "共享维保服务",
        "quantity": "2", "unitPrice": "100.00", "declaredAmount": "200.00",
        "confidence": 0.95,
    }


def body_for(indices: range) -> dict:
    catalog = []
    offset = 0
    for index in indices:
        text = f"QA-{index:03d}\t共享维保服务\t2\t100.00\t200.00\n"
        catalog.append({
            "evidenceId": f"evidence-{index}", "sourceLocator": f"row:{index}",
            "text": text, "textSha256": digest(text),
            "startOffset": offset, "endOffset": offset + len(text),
            "rowIdentity": "row:" + digest(f"physical-row-{index}"),
            "lineItemColumns": ["rowId", "name", "quantity", "unitPrice", "declaredAmount"],
        })
        offset += len(text)
    return {
        "projectId": "00000000-0000-0000-0000-000000000100",
        "fileId": "00000000-0000-0000-0000-000000000101",
        "fileSha256": "a" * 64,
        "parseResultId": "00000000-0000-0000-0000-000000000102",
        "parseVersion": 3, "canonicalTextSha256": "b" * 64,
        "scope": "LINE_ITEMS", "evidenceCatalog": catalog,
    }


class Provider:
    def __init__(self, payload):
        self.payload = payload
        self.operations = []

    def extract_contract_facts(self, operation):
        self.operations.append(operation)
        return ModelCompletion(
            payload=self.payload, model="synthetic-fact-model", duration_ms=1,
            revision="synthetic-revision", finish_reason="STOP",
            prompt_tokens=100, completion_tokens=100, total_tokens=200,
        )


def extract(body, payload):
    gateway = Provider(payload)
    service = ContractFactServiceV2(Settings(
        contract_fact_extraction_enabled=True,
        model_base_url="http://synthetic.invalid/v1",
        model_name="synthetic-fact-model",
    ), gateway)
    response = service.extract(ContractFactRequestV2.model_validate(body), "compact-offline-test")
    return response.data, gateway


def legacy_row(index: int, *, group="model-row") -> list[dict]:
    return [
        {
            "factType": fact, "groupKey": group, "evidenceId": f"evidence-{index}",
            "valueText": value if fact == "LINE_ITEM_NAME" else None,
            "valueDecimal": None if fact == "LINE_ITEM_NAME" else value,
            "contextCode": "CONTRACT_LINE_ITEM", "confidence": 0.95,
        }
        for fact, value in (
            ("LINE_ITEM_NAME", "共享维保服务"), ("LINE_ITEM_QUANTITY", "2"),
            ("LINE_ITEM_UNIT_PRICE", "100.00"), ("LINE_ITEM_DECLARED_AMOUNT", "200.00"),
        )
    ]


@pytest.mark.parametrize("count", [12, 48])
def test_compact_twelve_and_forty_eight_rows_retain_tail_and_distinct_same_names(count):
    # Catches dropped tail rows, repeated-name merging, and changed v2 identities.
    all_candidates = []
    for start in range(1, count + 1, 12):
        indices = range(start, min(start + 12, count + 1))
        body = body_for(indices)
        result, _ = extract(body, {"candidates": [], "lineItems": [compact_row(i) for i in indices]})
        legacy, _ = extract(body, {"candidates": [row for i in indices for row in legacy_row(i)]})
        assert result.batch_status == "PARTIAL"  # Never a completeness claim.
        assert result.rejections == []
        assert result.candidates == legacy.candidates
        all_candidates.extend(result.candidates)
    assert len(all_candidates) == count * 4
    assert len({row.group_key for row in all_candidates}) == count
    assert len({row.candidate_key for row in all_candidates}) == count * 4
    assert {row.segment_id for row in all_candidates[-4:]} == {f"evidence-{count}"}
    assert [row.fact_type for row in all_candidates[-4:]] == [
        "LINE_ITEM_NAME", "LINE_ITEM_QUANTITY", "LINE_ITEM_UNIT_PRICE", "LINE_ITEM_DECLARED_AMOUNT",
    ]


def test_optional_compact_group_alias_cannot_change_server_owned_line_identity():
    body = body_for(range(1, 2))
    first, _ = extract(body, {"candidates": [], "lineItems": [compact_row(1)]})
    other, _ = extract(body, {"candidates": [], "lineItems": [{**compact_row(1), "groupKey": "other-alias"}]})
    assert first.candidates == other.candidates
    assert len(first.candidates) == 4


@pytest.mark.parametrize("field", ["name", "quantity", "unitPrice", "declaredAmount"])
def test_missing_compact_cell_fails_entire_batch_instead_of_publishing_three_fields(field):
    row = compact_row(2)
    row.pop(field)
    result, gateway = extract(body_for(range(1, 3)), {"candidates": [], "lineItems": [compact_row(1), row]})
    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert result.rejections
    assert {row.reason_code for row in result.rejections} == {"INVALID_COMPACT_ROW"}
    assert len(gateway.operations) == 1


@pytest.mark.parametrize("value", [-1, "-1", 1.5, True, None, "1e2", "NaN"])
def test_compact_numeric_values_must_be_nonnegative_decimal_strings(value):
    result, _ = extract(body_for(range(1, 2)), {
        "candidates": [], "lineItems": [{**compact_row(1), "quantity": value}],
    })
    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert result.rejections
    assert {row.reason_code for row in result.rejections} == {"INVALID_COMPACT_ROW"}


@pytest.mark.parametrize("fault", ["unknown-id", "missing-row-identity", "extra-field", "wrong-source"])
def test_compact_identity_and_single_source_proof_fail_closed(fault):
    body = body_for(range(1, 2))
    row = compact_row(1)
    if fault == "unknown-id":
        row["evidenceId"] = "unknown-evidence"
    elif fault == "missing-row-identity":
        body["evidenceCatalog"][0].pop("rowIdentity")
        body["evidenceCatalog"][0].pop("lineItemColumns")
    elif fault == "extra-field":
        row["sourceLocator"] = "provider-invented"
    else:
        row["declaredAmount"] = "999.00"
    result, _ = extract(body, {"candidates": [], "lineItems": [row]})
    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert result.rejections
    assert {item.reason_code for item in result.rejections} == {
        {"unknown-id": "UNKNOWN_EVIDENCE_ID", "missing-row-identity": "ROW_IDENTITY_UNPROVEN",
         "extra-field": "INVALID_COMPACT_ROW", "wrong-source": "COMPACT_ROW_NOT_GROUNDED"}[fault]
    }


def test_compact_cannot_borrow_missing_cells_from_another_fragment():
    body = body_for(range(1, 3))
    offset = 0
    for evidence, text in zip(body["evidenceCatalog"], ("共享维保服务\t2\t", "100.00\t200.00\n")):
        evidence.pop("lineItemColumns")
        evidence.update(text=text, textSha256=digest(text), startOffset=offset, endOffset=offset + len(text))
        offset += len(text)
    body["evidenceCatalog"][1]["rowIdentity"] = body["evidenceCatalog"][0]["rowIdentity"]

    result, _ = extract(body, {"candidates": [], "lineItems": [compact_row(1)]})

    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert {row.reason_code for row in result.rejections} == {"COMPACT_ROW_NOT_GROUNDED"}


def test_mapped_compact_and_legacy_conflicting_values_reject_entire_batch():
    body = body_for(range(1, 2))
    evidence = body["evidenceCatalog"][0]
    text = "QA-001\t共享维保服务\t2\t3\t6\n"
    evidence.update(text=text, endOffset=len(text), textSha256=digest(text))
    conflicting_legacy = {**legacy_row(1, group=evidence["rowIdentity"])[1], "valueDecimal": "3"}
    result, _ = extract(body, {
        "candidates": [conflicting_legacy],
        "lineItems": [{**compact_row(1), "unitPrice": "3", "declaredAmount": "6"}],
    })
    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert "MAPPED_LINE_VALUE_NOT_GROUNDED" in {row.reason_code for row in result.rejections}


def test_legacy_partial_fragments_still_coalesce_without_compact_cell_invention():
    body = body_for(range(1, 3))
    # Two adjacent slices of one actual physical row, not two source rows
    # mislabeled with the same row identity.
    offset = 0
    for evidence, text in zip(body["evidenceCatalog"], ("共享维保服务\t2\t", "100.00\t200.00\n")):
        evidence.pop("lineItemColumns")
        evidence.update(text=text, textSha256=digest(text), startOffset=offset, endOffset=offset + len(text))
        offset += len(text)
    body["evidenceCatalog"][1]["rowIdentity"] = body["evidenceCatalog"][0]["rowIdentity"]
    rows = [legacy_row(1, group="fragment-a")[0], legacy_row(2, group="fragment-b")[3]]
    result, _ = extract(body, {"candidates": rows, "lineItems": []})
    assert result.rejections == []
    assert [row.fact_type for row in result.candidates] == ["LINE_ITEM_NAME", "LINE_ITEM_DECLARED_AMOUNT"]
    assert len({row.group_key for row in result.candidates}) == 1


def test_bad_tail_row_forty_eight_cannot_publish_other_rows_from_its_batch():
    results = []
    for start in (1, 13, 25, 37):
        rows = [compact_row(i) for i in range(start, start + 12)]
        if start == 37:
            rows[-1].pop("declaredAmount")
        result, _ = extract(body_for(range(start, start + 12)), {"candidates": [], "lineItems": rows})
        results.append(result)
    assert [result.batch_status for result in results] == ["PARTIAL", "PARTIAL", "PARTIAL", "FAILED"]
    assert results[-1].candidates == []
    assert results[-1].rejections


def test_compact_expansion_cannot_exceed_existing_hundred_candidate_wire_bound():
    result, _ = extract(body_for(range(1, 27)), {
        "candidates": [], "lineItems": [compact_row(i) for i in range(1, 27)],
    })
    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert result.rejections


def test_compact_rows_are_rejected_outside_line_item_scope():
    body = body_for(range(1, 2))
    body["scope"] = "CORE"
    result, _ = extract(body, {"candidates": [], "lineItems": [compact_row(1)]})
    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert {row.reason_code for row in result.rejections} == {"FACT_OUTSIDE_SCOPE"}


def test_legacy_candidates_remain_accepted_alongside_valid_compact_rows():
    body = body_for(range(1, 3))
    legacy = legacy_row(1)
    original = deepcopy(legacy)
    result, _ = extract(body, {"candidates": legacy, "lineItems": [compact_row(2)]})
    assert len(result.candidates) == 8
    assert result.rejections == []
    assert legacy == original


def row_fragments(values: tuple[str, ...], fact_type: str):
    body = body_for(range(1, len(values) + 1))
    identity = body["evidenceCatalog"][0]["rowIdentity"]
    offset = 0
    rows = []
    for index, (evidence, value) in enumerate(zip(body["evidenceCatalog"], values), start=1):
        evidence.pop("lineItemColumns")
        text = value + ("\n" if index == len(values) else "\t")
        evidence.update(text=text, textSha256=digest(text), startOffset=offset,
                        endOffset=offset + len(text), rowIdentity=identity)
        offset += len(text)
        rows.append({
            "factType": fact_type, "groupKey": f"fragment-{index}",
            "evidenceId": evidence["evidenceId"], "contextCode": "CONTRACT_LINE_ITEM",
            "valueText": value if fact_type == "LINE_ITEM_NAME" else None,
            "valueDecimal": None if fact_type == "LINE_ITEM_NAME" else value,
            "confidence": 0.95,
        })
    return body, rows


@pytest.mark.parametrize("fact_type", ["LINE_ITEM_QUANTITY", "LINE_ITEM_UNIT_PRICE", "LINE_ITEM_DECLARED_AMOUNT"])
def test_equal_numeric_scales_across_row_fragments_keep_original_values_and_evidence(fact_type):
    # Rejects raw-string comparison and any fix that rewrites candidate identities.
    body, rows = row_fragments(("2", "2.0", "2.00"), fact_type)

    result, _ = extract(body, {"candidates": rows})

    assert result.batch_status == "PARTIAL"
    assert result.rejections == []
    assert [candidate.value_decimal for candidate in result.candidates] == ["2", "2.0", "2.00"]
    assert [candidate.segment_id for candidate in result.candidates] == ["evidence-1", "evidence-2", "evidence-3"]
    assert len({candidate.group_key for candidate in result.candidates}) == 1
    assert len({candidate.candidate_key for candidate in result.candidates}) == 3
    for candidate, row in zip(result.candidates, rows):
        isolated, _ = extract(body, {"candidates": [row]})
        assert candidate == isolated.candidates[0]


@pytest.mark.parametrize("values", [("2", "2.01"), ("9007199254740992", "9007199254740993")])
def test_distinct_numeric_values_remain_conflicting_without_float_rounding(values):
    body, rows = row_fragments(values, "LINE_ITEM_QUANTITY")

    result, _ = extract(body, {"candidates": rows})

    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert {row.reason_code for row in result.rejections} == {"ROW_IDENTITY_UNPROVEN"}


def test_numeric_looking_text_values_remain_exact_conflicts():
    body, rows = row_fragments(("2", "2.0"), "LINE_ITEM_NAME")

    result, _ = extract(body, {"candidates": rows})

    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert {row.reason_code for row in result.rejections} == {"ROW_IDENTITY_UNPROVEN"}


@pytest.mark.parametrize("cells", ["服务条目\t2\t10.00\t20.00", "QA-048\t服务条目\t2\t10.00\t20.00"])
def test_compact_numeric_fields_must_match_their_own_physical_cells(cells):
    body = body_for(range(1, 2))
    evidence = body["evidenceCatalog"][0]
    evidence.update(text=cells, textSha256=digest(cells), endOffset=len(cells))
    evidence["lineItemColumns"] = (["rowId"] if cells.startswith("QA-") else []) + ["name", "quantity", "unitPrice", "declaredAmount"]
    correct = {**compact_row(1), "name": "服务条目", "quantity": "2", "unitPrice": "10.00", "declaredAmount": "20.00"}
    valid, _ = extract(body, {"candidates": [], "lineItems": [correct]})
    assert len(valid.candidates) == 4

    swapped, _ = extract(body, {"candidates": [], "lineItems": [{**correct, "quantity": "10.00", "unitPrice": "2"}]})

    assert swapped.batch_status == "FAILED"
    assert swapped.candidates == []
    assert {row.reason_code for row in swapped.rejections} == {"COMPACT_ROW_NOT_GROUNDED"}


def test_compact_name_must_equal_full_name_cell_instead_of_a_supported_substring():
    body = body_for(range(1, 2))
    evidence = body["evidenceCatalog"][0]
    text = evidence["text"].replace("共享维保服务", "共享维保服务升级")
    evidence.update(text=text, textSha256=digest(text), endOffset=len(text))

    result, _ = extract(body, {"candidates": [], "lineItems": [compact_row(1)]})

    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert {row.reason_code for row in result.rejections} == {"COMPACT_ROW_NOT_GROUNDED"}


def test_compact_inline_values_without_unambiguous_cells_require_legacy_candidates():
    body = body_for(range(1, 2))
    evidence = body["evidenceCatalog"][0]
    evidence.pop("lineItemColumns")
    text = "共享维保服务，数量2，单价100.00，金额200.00"
    evidence.update(text=text, textSha256=digest(text), endOffset=len(text))

    compact, _ = extract(body, {"candidates": [], "lineItems": [compact_row(1)]})
    legacy, _ = extract(body, {"candidates": legacy_row(1)})

    assert compact.batch_status == "FAILED"
    assert compact.candidates == []
    assert {row.reason_code for row in compact.rejections} == {"COMPACT_ROW_NOT_GROUNDED"}
    assert len(legacy.candidates) == 4
    assert legacy.rejections == []


def compact_api(body, payload):
    gateway = Provider(payload)
    settings = Settings(contract_fact_extraction_enabled=True,
                        model_base_url="http://synthetic.invalid/v1", model_name="synthetic-fact-model")
    response = TestClient(create_app(settings, model_gateway=gateway)).post(
        "/ai/v2/agent/contract-fact-candidates", json=body,
    )
    return response, gateway


def test_compact_requires_server_column_mapping_and_legacy_still_accepts_unmapped_evidence():
    body = body_for(range(1, 2))
    body["evidenceCatalog"][0].pop("lineItemColumns", None)

    compact, _ = compact_api(body, {"candidates": [], "lineItems": [compact_row(1)]})
    legacy, gateway = compact_api(body, {"candidates": legacy_row(1)})

    assert compact.status_code == 200
    assert compact.json()["data"]["batchStatus"] == "FAILED"
    assert compact.json()["data"]["candidates"] == []
    assert legacy.status_code == 200
    assert len(legacy.json()["data"]["candidates"]) == 4
    assert "lineItemColumns" not in json.loads(gateway.operations[0].source_text)[0]


def test_server_permuted_columns_are_forwarded_and_ground_their_actual_cells():
    body = body_for(range(1, 2))
    evidence = body["evidenceCatalog"][0]
    text = "QA-001\t共享维保服务\t100.00\t2\t200.00\n"
    columns = ["rowId", "name", "unitPrice", "quantity", "declaredAmount"]
    evidence.update(text=text, textSha256=digest(text), endOffset=len(text), lineItemColumns=columns)

    response, gateway = compact_api(body, {"candidates": [], "lineItems": [compact_row(1)]})

    assert response.status_code == 200
    assert len(response.json()["data"]["candidates"]) == 4
    assert response.json()["data"]["rejections"] == []
    assert json.loads(gateway.operations[0].source_text)[0]["lineItemColumns"] == columns
    wrong, _ = compact_api(body, {"candidates": [], "lineItems": [{**compact_row(1), "quantity": "100.00", "unitPrice": "2"}]})
    assert wrong.status_code == 200
    assert wrong.json()["data"]["batchStatus"] == "FAILED"
    assert wrong.json()["data"]["candidates"] == []


@pytest.mark.parametrize("columns", [
    ["name", "quantity", "unitPrice"],
    ["name", "quantity", "unitPrice", "declaredAmount", "rowId", "rowId"],
    ["name", "quantity", "quantity", "declaredAmount"],
    ["rowId", "quantity", "unitPrice", "declaredAmount"],
    ["name", "quantity", "unitPrice", "totalAmount"],
    ["name", "quantity", "unitPrice", "declaredAmount", "name"],
])
def test_invalid_server_column_mapping_returns_422_before_any_provider_call(columns):
    body = body_for(range(1, 2))
    body["evidenceCatalog"][0]["lineItemColumns"] = columns

    response, gateway = compact_api(body, {"candidates": [], "lineItems": [compact_row(1)]})

    assert response.status_code == 422
    assert gateway.operations == []


def test_server_column_mapping_without_physical_row_identity_is_rejected_before_provider():
    body = body_for(range(1, 2))
    body["evidenceCatalog"][0].pop("rowIdentity")

    response, gateway = compact_api(body, {"candidates": [], "lineItems": [compact_row(1)]})

    assert response.status_code == 422
    assert gateway.operations == []


def test_provider_cannot_supply_its_own_mapping_for_unmapped_server_evidence():
    body = body_for(range(1, 2))
    columns = body["evidenceCatalog"][0].pop("lineItemColumns")
    row = {**compact_row(1), "lineItemColumns": columns}

    response, _ = compact_api(body, {"candidates": [], "lineItems": [row]})

    assert response.status_code == 200
    assert response.json()["data"]["batchStatus"] == "FAILED"
    assert response.json()["data"]["candidates"] == []
    assert {value["reasonCode"] for value in response.json()["data"]["rejections"]} == {"INVALID_COMPACT_ROW"}


@pytest.mark.parametrize("fault", ["quantity-from-price", "quantity-from-name", "price-from-amount", "amount-from-quantity", "partial-name"])
def test_mapped_legacy_cannot_bypass_cell_grounding_or_publish_the_good_row(fault):
    body = body_for(range(1, 3))
    evidence = body["evidenceCatalog"][0]
    text = "QA-048\t共享维保服务48\t2\t10.00\t20.00\n"
    evidence.update(text=text, textSha256=digest(text), endOffset=len(text))
    rows = legacy_row(1)
    rows[0]["valueText"] = "共享维保服务48"
    rows[2]["valueDecimal"] = "10.00"
    rows[3]["valueDecimal"] = "20.00"
    if fault == "quantity-from-price":
        rows[1]["valueDecimal"] = "10.00"
    elif fault == "quantity-from-name":
        rows[1]["valueDecimal"] = "48"
    elif fault == "price-from-amount":
        rows[2]["valueDecimal"] = "20.00"
    elif fault == "amount-from-quantity":
        rows[3]["valueDecimal"] = "2"
    else:
        rows[0]["valueText"] = "共享维保服务"

    result, _ = extract(body, {"candidates": [*rows, *legacy_row(2)]})

    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert {row.reason_code for row in result.rejections} == {"MAPPED_LINE_VALUE_NOT_GROUNDED"}


def test_valid_mapped_legacy_permutation_and_scales_preserve_existing_candidate_identity():
    body = body_for(range(1, 2))
    evidence = body["evidenceCatalog"][0]
    text = "共享维保服务\t200.00\tQA-001\t100.00\t2\n"
    evidence.update(text=text, textSha256=digest(text), endOffset=len(text),
                    lineItemColumns=["name", "declaredAmount", "rowId", "unitPrice", "quantity"])
    rows = legacy_row(1)
    rows[1]["valueDecimal"] = "2.00"
    rows[2]["valueDecimal"] = "100.0"
    rows[3]["valueDecimal"] = "200"

    mapped, _ = extract(body, {"candidates": rows})
    unmapped_body = deepcopy(body)
    unmapped_body["evidenceCatalog"][0].pop("lineItemColumns")
    original, _ = extract(unmapped_body, {"candidates": rows})

    assert mapped.batch_status == "PARTIAL"
    assert len(mapped.candidates) == 4
    assert mapped.rejections == []
    assert mapped.candidates == original.candidates
    assert [row.value_decimal for row in mapped.candidates[1:]] == ["2.00", "100.0", "200"]


def test_unmapped_legacy_retains_prior_candidate_grounding_compatibility():
    body = body_for(range(1, 2))
    body["evidenceCatalog"][0].pop("lineItemColumns")
    rows = legacy_row(1)
    rows[1]["valueDecimal"] = "100.00"

    result, _ = extract(body, {"candidates": rows})

    assert result.batch_status == "PARTIAL"
    assert len(result.candidates) == 4
    assert result.rejections == []


def test_mapped_legacy_conflict_cannot_skip_grounding_and_publish_another_row():
    body = body_for(range(1, 3))
    rows = legacy_row(1)
    conflicting = {**rows[1], "valueDecimal": "100.00"}

    result, _ = extract(body, {"candidates": [*rows, conflicting, *legacy_row(2)]})

    assert result.batch_status == "FAILED"
    assert result.candidates == []
    assert "MAPPED_LINE_VALUE_NOT_GROUNDED" in {row.reason_code for row in result.rejections}
