"""Offline execution safety; synthetic clauses contain no customer material."""

from __future__ import annotations

import hashlib
import json
import re

import pytest

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.gateway import (
    ContractTextOperation,
    ModelCompletion,
    ModelGatewayFailure,
    ModelGatewayPort,
)
from audit_ai.model.router import ContractAnalysisRequest, ModelApplicationService


def dense_request() -> ContractAnalysisRequest:
    clauses = (
        "交付条款：供应商收到书面通知后组织实施，交付前提交实施计划并说明现场条件。",
        "验收条款：采购人组织验收，供应商提交测试记录，双方保存签字确认的验收意见。",
        "服务条款：供应商安排专人受理故障，记录处理过程，疑难事项应及时升级处理。",
        "分包条款：未经采购人书面同意不得转包，分包安排应说明工作范围和责任边界。",
        "终止条款：双方书面协商终止时应办理资料移交，未解决事项另行记录并确认。",
        "违约条款：发生延期应说明原因，提交纠正计划，并按合同约定办理责任复核。",
    )
    return ContractAnalysisRequest.model_validate({
        "projectId": "synthetic-project",
        "materials": [
            {
                "fileId": file_id,
                "category": category,
                "text": "\n".join(
                    f"第{index + 1}条 {clauses[index % len(clauses)]}"
                    for index in range(48)
                ),
            }
            for file_id, category in (
                ("synthetic-contract", "CONTRACT_BODY"),
                ("synthetic-bid", "BID_RESPONSE"),
                ("synthetic-tender", "TENDER_DOCUMENT"),
            )
        ],
    })


class OfflineGateway(ModelGatewayPort):
    """Replace only the external model; all planning/validation stays real."""

    def __init__(self, failures=None, *, seconds_per_call=0.0, invalid=False):
        self.operations: list[ContractTextOperation] = []
        self.failures = dict(failures or {})
        self.seconds_per_call = seconds_per_call
        self.now = 0.0
        self.invalid = invalid

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        self.operations.append(operation)
        self.now += self.seconds_per_call
        if failure := self.failures.get(len(self.operations)):
            raise failure
        if self.invalid:
            payload = {"summary": "合成测试摘要", "hints": "invalid"}
        else:
            if operation.purpose == "ANALYZE":
                evidence_id = re.search(
                    r"\[evidenceId=([^;]+);", operation.source_text
                ).group(1)
            elif operation.purpose == "SYNTHESIZE":
                evidence_id = json.loads(operation.source_text)["evidenceCatalog"][0]["evidenceId"]
            else:
                raise AssertionError("unexpected correction in valid offline fixture")
            payload = {
                "summary": "已识别合成材料的文字条款，请人工复核。",
                "hints": [
                    {
                        "category": category,
                        "attention": "INFO",
                        "title": "文字条款观察",
                        "description": "材料包含相关文字，请结合原文人工核对。",
                        "problemEvidenceIds": [],
                        "comparisonEvidenceIds": [evidence_id],
                        "unavailableMaterialCategory": None,
                    }
                    for category in (operation.required_categories or ("OTHER_TEXT",))
                ],
            }
        return ModelCompletion(
            payload=payload,
            model="offline-synthetic-model",
            provider="OLLAMA_NATIVE",
            duration_ms=10,
            semantic_contract="CONTRACT_SEMANTIC_V1",
        )


def bounded_service(gateway: OfflineGateway, *, max_chunks=20, max_chars=3200):
    return ModelApplicationService(
        Settings(
            model_name="offline-synthetic-model",
            model_chunk_chars=12000,
            model_max_chunks=max_chunks,
            model_total_budget_seconds=900.0,
            semantic_structured_transport="OLLAMA_NATIVE_STRUCTURED",
        ),
        gateway,
        clock=lambda: gateway.now,
        contract_execution_max_chars=max_chars,
    )


def test_opt_in_batches_preserve_every_catalog_identity_span_and_source_sha() -> None:
    # Catches truncation, resegmentation, reordered evidence and lost whitespace.
    request = dense_request()
    assert sum(len(material.text) for material in request.materials) > 5000
    assert sum(material.text.count("条款：") for material in request.materials) == 144
    service = bounded_service(OfflineGateway())
    catalog = service._contract_evidence_catalog(request)
    original_segments = catalog.segments

    chunks = service._contract_chunks(catalog)

    assert len(chunks) > 2
    assert all(len(chunk) <= 3200 for chunk in chunks)
    captured_ids = []
    captured_text_by_file = {material.file_id: [] for material in request.materials}
    for chunk in chunks:
        blocks = re.split(r"\n\n(?=\[evidenceId=)", chunk)
        for block in blocks:
            header, text = block.split("\n", 1)
            evidence_id = re.fullmatch(
                r"\[evidenceId=([^;]+);fileId=[^;]+;category=[^;]+;citable=(?:true|false)\]",
                header,
            ).group(1)
            segment = catalog.by_id[evidence_id]
            captured_ids.append(evidence_id)
            assert text == segment.exact_text
            material = next(item for item in request.materials if item.file_id == segment.file_id)
            assert text == material.text[segment.source_start:segment.source_end]
            captured_text_by_file[segment.file_id].append(text)
    assert captured_ids == [segment.evidence_id for segment in original_segments]
    assert catalog.segments == original_segments
    for material in request.materials:
        reconstructed = "".join(captured_text_by_file[material.file_id])
        assert hashlib.sha256(reconstructed.encode()).digest() == hashlib.sha256(material.text.encode()).digest()
        assert reconstructed == material.text


def test_opt_in_does_not_change_catalog_or_default_production_grouping() -> None:
    request = dense_request()
    default = ModelApplicationService(Settings(), OfflineGateway())
    bounded = bounded_service(OfflineGateway())
    catalog = default._contract_evidence_catalog(request)

    assert catalog.segments == bounded._contract_evidence_catalog(request).segments
    assert len(default._contract_chunks(catalog)) < len(bounded._contract_chunks(catalog))


def test_bounded_plan_over_max_chunks_fails_before_any_model_call() -> None:
    gateway = OfflineGateway()
    service = bounded_service(gateway, max_chunks=1)

    with pytest.raises(AuditAiError) as caught:
        service.analyze_contract(dense_request(), "synthetic-over-bound")

    assert caught.value.status_code == 413
    assert caught.value.code == "MODEL_INPUT_TOO_LARGE"
    assert gateway.operations == []


def test_smaller_bound_cannot_split_an_existing_evidence_identity() -> None:
    service = bounded_service(OfflineGateway(), max_chars=100)
    segment = EvidenceSegment("C:S1", "contract", "CONTRACT_BODY", 0, 100, "甲" * 100, True)
    catalog = EvidenceCatalog((segment,))

    with pytest.raises(AuditAiError) as caught:
        service._contract_chunks(catalog)

    assert caught.value.code == "MODEL_INPUT_TOO_LARGE"
    assert catalog.segments == (segment,)


def test_full_bounded_flow_returns_only_server_owned_exact_evidence() -> None:
    gateway = OfflineGateway()
    service = bounded_service(gateway)
    request = dense_request()

    response = service.analyze_contract(request, "synthetic-success")

    assert response.code == 200
    analyses = [operation for operation in gateway.operations if operation.purpose == "ANALYZE"]
    assert len(analyses) > 2
    assert [operation.chunk_index for operation in analyses] == list(range(1, len(analyses) + 1))
    assert all(operation.chunk_count == len(analyses) for operation in analyses)
    assert all(len(operation.source_text) <= 3200 for operation in analyses)
    assert len(gateway.operations) <= len(analyses) + 1
    assert response.data.hints
    sources = {material.file_id: material.text for material in request.materials}
    for hint in response.data.hints:
        assert hint.evidence_items
        for evidence in hint.evidence_items:
            assert evidence.file_id in sources
            assert evidence.excerpt in sources[evidence.file_id]


def test_total_budget_exhaustion_keeps_successful_prefix_and_blocks_next_call() -> None:
    gateway = OfflineGateway(seconds_per_call=300.0)
    service = bounded_service(gateway)

    with pytest.raises(AuditAiError) as caught:
        service.analyze_contract(dense_request(), "synthetic-budget")

    assert caught.value.code == "MODEL_TOTAL_BUDGET_EXHAUSTED"
    assert len(gateway.operations) == 3
    assert [operation.provider_timeout_seconds for operation in gateway.operations] == [900.0, 600.0, 300.0]
    assert [call["outcome"] for call in caught.value.data["modelCalls"]] == [
        "SUCCEEDED", "SUCCEEDED", "SUCCEEDED", "FAILED",
    ]
    assert caught.value.data["modelCalls"][-1]["safeReason"] == "MODEL_TOTAL_BUDGET_EXHAUSTED"


def test_unknown_outcome_retains_call_prefix_and_never_automatically_replays() -> None:
    gateway = OfflineGateway({3: ModelGatewayFailure(
        reason="TRANSPORT_OUTCOME_UNKNOWN", retryable=False, duration_ms=120000,
    )})
    service = bounded_service(gateway)

    with pytest.raises(AuditAiError) as caught:
        service.analyze_contract(dense_request(), "synthetic-unknown")

    assert caught.value.code == "MODEL_GATEWAY_ERROR"
    assert [operation.chunk_index for operation in gateway.operations] == [1, 2, 3]
    calls = caught.value.data["modelCalls"]
    assert [call["ordinal"] for call in calls] == [1, 2, 3]
    assert [call["outcome"] for call in calls] == ["SUCCEEDED", "SUCCEEDED", "FAILED"]
    assert calls[-1]["safeReason"] == "TRANSPORT_OUTCOME_UNKNOWN"
    assert "source_text" not in json.dumps(caught.value.data)


def test_explicit_new_request_starts_at_first_chunk_without_claiming_checkpoint_resume() -> None:
    gateway = OfflineGateway({2: ModelGatewayFailure(
        reason="TRANSPORT_OUTCOME_UNKNOWN", retryable=False, duration_ms=120000,
    )})
    service = bounded_service(gateway)
    request = dense_request()
    catalog_before = service._contract_evidence_catalog(request)
    with pytest.raises(AuditAiError) as original:
        service.analyze_contract(request, "synthetic-original")
    original_calls = json.loads(json.dumps(original.value.data))
    original_count = len(gateway.operations)

    response = service.analyze_contract(request, "synthetic-explicit-new-request")

    assert response.code == 200
    assert gateway.operations[original_count].chunk_index == 1
    assert service._contract_evidence_catalog(request).segments == catalog_before.segments
    assert original.value.data == original_calls
    assert original_calls["modelCalls"][-1]["safeReason"] == "TRANSPORT_OUTCOME_UNKNOWN"


def test_retry_budget_is_global_across_bounded_chunks() -> None:
    gateway = OfflineGateway({
        1: ModelGatewayFailure(reason="READ_TIMEOUT", retryable=True, duration_ms=120000),
        4: ModelGatewayFailure(reason="READ_TIMEOUT", retryable=True, duration_ms=120000),
    })

    with pytest.raises(AuditAiError) as caught:
        bounded_service(gateway).analyze_contract(dense_request(), "synthetic-retries")

    assert [operation.chunk_index for operation in gateway.operations] == [1, 1, 2, 3]
    assert [operation.transport_attempt for operation in gateway.operations] == [1, 2, 1, 1]
    assert [call["outcome"] for call in caught.value.data["modelCalls"]] == [
        "FAILED", "SUCCEEDED", "SUCCEEDED", "FAILED",
    ]


def test_invalid_semantic_result_exhausts_shared_retry_without_partial_success() -> None:
    gateway = OfflineGateway(invalid=True)

    with pytest.raises(AuditAiError) as caught:
        bounded_service(gateway).analyze_contract(dense_request(), "synthetic-invalid")

    assert caught.value.code == "MODEL_INVALID_RESULT"
    assert [operation.chunk_index for operation in gateway.operations] == [1, 1]
    assert [call["outcome"] for call in caught.value.data["modelCalls"]] == ["FAILED", "FAILED"]
