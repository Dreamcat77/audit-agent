import json
import logging
import re
from typing import Any

import pytest
from fastapi.testclient import TestClient

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import (
    BudgetSemanticOperation,
    ContractTextOperation,
    ModelCompletion,
    ModelGatewayFailure,
    ModelGatewayPort,
)


CONTRACT_TEXT = "合同正文包含合同组成、签章页和附件目录。"
QUANTIFIED_BREACH_TEXT = (
    "合同正文包含合同组成、签章页和附件目录。"
    "违约责任：逾期交付按合同金额1%/周计算，累计上限10%。"
)
FIXED_4_1_REQUIRED = (
    "CONTRACT_STRUCTURE",
    "PROJECT_IDENTITY",
    "SUPPLIER_IDENTITY",
    "DELIVERY_TERMS",
    "SERVICE_WARRANTY",
    "SCOPE_AND_SUBCONTRACTING",
)
CONTRACT_BODY_REQUIRED = (*FIXED_4_1_REQUIRED, "TERM_TERMINATION")


class SequencedGateway(ModelGatewayPort):
    def __init__(self, payloads: list[dict[str, Any] | Exception]) -> None:
        self.payloads = list(payloads)
        self.operations: list[ContractTextOperation] = []

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        self.operations.append(operation)
        if not self.payloads:
            raise AssertionError("unexpected provider call")
        payload = self.payloads.pop(0)
        if isinstance(payload, Exception):
            raise payload
        return ModelCompletion(payload=payload, model="model-test", duration_ms=5)

    def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
        raise AssertionError("budget operation was not expected")


def post_contract(
    gateway: ModelGatewayPort,
    *,
    materials: list[dict[str, str]] | None = None,
    request_id: str = "request-structure-gate",
    settings: Settings | None = None,
):
    return TestClient(create_app(
        settings=settings or Settings(), model_gateway=gateway
    )).post(
        "/ai/v1/agent/contract-text-analysis",
        headers={"X-Request-Id": request_id},
        json={
            "projectId": "project-structure-gate",
            "materials": materials or [{
                "fileId": "contract",
                "category": "CONTRACT_BODY",
                "text": CONTRACT_TEXT,
            }],
        },
    )


def info_hint(category: str, evidence_id: str = "C:S1") -> dict[str, Any]:
    return {
        "category": category,
        "attention": "INFO",
        "title": f"{category}条款提示",
        "description": "根据可见材料形成语义提示。",
        "comparisonEvidenceIds": [evidence_id],
    }


def review_hint(category: str, evidence_id: str = "C:S1") -> dict[str, Any]:
    return {
        "category": category,
        "attention": "REVIEW",
        "title": f"{category}条款待复核",
        "description": "根据可见条款建议人工复核。",
        "problemEvidenceIds": [evidence_id],
    }


def fixed_4_1_hints(
    *,
    exclude: tuple[str, ...] = (),
    evidence_id: str = "C:S1",
) -> list[dict[str, Any]]:
    return [
        info_hint(category, evidence_id)
        for category in FIXED_4_1_REQUIRED
        if category not in exclude
    ]


def contract_body_hints(
    *,
    exclude: tuple[str, ...] = (),
    evidence_id: str = "C:S1",
) -> list[dict[str, Any]]:
    hints = fixed_4_1_hints(exclude=exclude, evidence_id=evidence_id)
    if "TERM_TERMINATION" not in exclude:
        hints.append(info_hint("TERM_TERMINATION", evidence_id))
    return hints


def completion_payload(*hints: dict[str, Any]) -> dict[str, Any]:
    return {"summary": "已完成合同文字语义分析。", "hints": list(hints)}


@pytest.mark.parametrize('repeat_artifact', [False, True])
def test_masking_is_not_document_damage_and_correction_cannot_reintroduce_it(repeat_artifact):
    artifact = {**review_hint('SCOPE_AND_SUBCONTRACTING'),
                'description': '因金额字段移除，采购内容清单疑似不完整。'}
    real_missing = {**review_hint('SCOPE_AND_SUBCONTRACTING'),
                    'description': '合同未约定分包限制，建议结合采购要求复核。'}
    payload = completion_payload(*contract_body_hints(exclude=('SCOPE_AND_SUBCONTRACTING',)), artifact)
    payload['summary'] = '因金额字段被移除导致文本片段断裂。'
    gateway = SequencedGateway([payload, correction_payload(artifact if repeat_artifact else real_missing)])
    response = post_contract(gateway, materials=[{'fileId':'contract', 'category':'CONTRACT_BODY',
        'text':'采购内容：办公计算机20台，[确定性数值字段已移除]。交货期30日。'}])
    assert len(gateway.operations) == 2
    assert gateway.operations[1].purpose == 'SEMANTIC_COMPLETENESS_CORRECTION'
    assert gateway.operations[1].required_categories == ('SCOPE_AND_SUBCONTRACTING',)
    if repeat_artifact:
        assert response.status_code == 502
    else:
        assert response.status_code == 200
        data=response.json()['data']
        assert '移除' not in json.dumps(data['summaries'], ensure_ascii=False)
        assert any('未约定分包限制' in h['description'] for h in data['hints'])


def test_real_document_deletion_is_not_confused_with_server_masking():
    hint={**review_hint('SCOPE_AND_SUBCONTRACTING'),
          'description':'合同约定删除原设备清单后，未提供替代清单，建议补充核对。'}
    gateway=SequencedGateway([completion_payload(*contract_body_hints(exclude=('SCOPE_AND_SUBCONTRACTING',)),hint)])
    response=post_contract(gateway, materials=[{'fileId':'contract','category':'CONTRACT_BODY',
        'text':'双方约定删除原设备清单。合同总价：[确定性数值字段已移除]。'}])
    assert response.status_code == 200
    assert len(gateway.operations) == 1
    assert any('未提供替代清单' in h['description'] for h in response.json()['data']['hints'])


def correction_payload(*hints: dict[str, Any]) -> dict[str, Any]:
    return {"missingCategoryHints": list(hints)}


@pytest.mark.parametrize('fragment', ['项目名称为', '乙方（供应商）为', '采购内容仅列明',
                                    '交货期限为30日，付款以', '验收程序未明确，仅以'])
@pytest.mark.parametrize('repeat_fragment', [False, True])
def test_dangling_descriptions_require_bounded_correction_not_invented_completion(fragment, repeat_fragment):
    bad = {**info_hint('PROJECT_IDENTITY'), 'description': fragment}
    complete = {**info_hint('PROJECT_IDENTITY'), 'description': '项目名称为仿真办公设备采购，名称明确。'}
    gateway = SequencedGateway([
        completion_payload(*contract_body_hints(exclude=('PROJECT_IDENTITY',)), bad),
        correction_payload(bad if repeat_fragment else complete),
    ])
    response = post_contract(gateway)
    assert len(gateway.operations) == 2
    assert gateway.operations[1].required_categories == ('PROJECT_IDENTITY',)
    if repeat_fragment:
        assert response.status_code == 502
    else:
        assert response.status_code == 200
        assert any(h['description'] == complete['description'] for h in response.json()['data']['hints'])


@pytest.mark.parametrize('description', ['设备品牌为华为', '项目名称为仿真办公设备采购',
                                        '付款以验收合格为前提。', '采购内容仅列明设备名称，未见技术规格。'])
def test_complete_descriptions_and_names_ending_in_wei_are_unchanged(description):
    payload = completion_payload(*contract_body_hints(exclude=('PROJECT_IDENTITY',)),
                                 {**info_hint('PROJECT_IDENTITY'), 'description': description})
    gateway = SequencedGateway([payload])
    response = post_contract(gateway)
    assert response.status_code == 200
    assert len(gateway.operations) == 1
    assert any(h['description'] == description for h in response.json()['data']['hints'])


def completed_fields(caplog) -> dict[str, Any]:
    return next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract semantic analysis completed")
    )


def test_single_chunk_missing_structure_uses_one_minimal_completeness_correction(
    caplog,
) -> None:
    gateway = SequencedGateway([
        completion_payload(
            review_hint("BREACH_LIABILITY"),
            *contract_body_hints(exclude=("CONTRACT_STRUCTURE",)),
        ),
        correction_payload(info_hint("CONTRACT_STRUCTURE")),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(gateway, request_id="single-missing-structure")

    assert response.status_code == 200
    assert gateway.operations[0].required_categories == CONTRACT_BODY_REQUIRED
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    assert gateway.operations[0].document_redaction_provenance is True
    assert gateway.operations[1].document_redaction_provenance is True
    assert gateway.operations[1].required_categories == ("CONTRACT_STRUCTURE",)
    assert [hint["category"] for hint in response.json()["data"]["hints"]] == [
        "BREACH_LIABILITY",
        "PROJECT_IDENTITY",
        "SUPPLIER_IDENTITY",
        "DELIVERY_TERMS",
        "SERVICE_WARRANTY",
        "SCOPE_AND_SUBCONTRACTING",
        "TERM_TERMINATION",
        "CONTRACT_STRUCTURE",
    ]
    completeness = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith(
            "contract semantic completeness required"
        )
    )
    assert completeness == {
        "operation": "CONTRACT_TEXT_ANALYSIS",
        "request_id": "single-missing-structure",
        "missing_required_categories": "CONTRACT_STRUCTURE",
        "required_category_count": 1,
    }
    completed = completed_fields(caplog)
    assert completed["provider_call_count"] == 2
    assert completed["semantic_retry_count"] == 1
    assert not any(
        record.getMessage().startswith("contract semantic hint filtered")
        for record in caplog.records
    )


def test_structure_already_present_does_not_consume_semantic_retry(caplog) -> None:
    gateway = SequencedGateway([
        completion_payload(*contract_body_hints()),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(gateway, request_id="structure-present")

    assert response.status_code == 200
    assert len(gateway.operations) == 1
    assert gateway.operations[0].required_categories == CONTRACT_BODY_REQUIRED
    assert "TERM_TERMINATION" in gateway.operations[0].required_categories
    assert "OTHER_TEXT" not in gateway.operations[0].required_categories
    completed = completed_fields(caplog)
    assert completed["provider_call_count"] == 1
    assert completed["semantic_retry_count"] == 0


def test_contract_body_requires_fixed_4_1_plus_term_on_first_call_without_retry(
    caplog,
) -> None:
    gateway = SequencedGateway([
        completion_payload(
            *fixed_4_1_hints(),
            info_hint("TERM_TERMINATION"),
        ),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(gateway, request_id="term-present-first-pass")

    assert response.status_code == 200
    assert len(gateway.operations) == 1
    assert gateway.operations[0].required_categories == CONTRACT_BODY_REQUIRED
    completed = completed_fields(caplog)
    assert completed["provider_call_count"] == 1
    assert completed["semantic_retry_count"] == 0


def test_missing_only_term_uses_one_completeness_correction(caplog) -> None:
    gateway = SequencedGateway([
        completion_payload(*fixed_4_1_hints()),
        correction_payload(info_hint("TERM_TERMINATION")),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(gateway, request_id="term-corrected-once")

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[0].required_categories == CONTRACT_BODY_REQUIRED
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    assert gateway.operations[1].required_categories == ("TERM_TERMINATION",)
    assert "TERM_TERMINATION" in {
        hint["category"] for hint in response.json()["data"]["hints"]
    }
    completed = completed_fields(caplog)
    assert completed["provider_call_count"] == 2
    assert completed["semantic_retry_count"] == 1


def test_term_correction_omission_fails_closed_without_third_attempt() -> None:
    gateway = SequencedGateway([
        completion_payload(*fixed_4_1_hints()),
        correction_payload(),
    ])

    response = post_contract(gateway, request_id="term-correction-omitted")

    assert response.status_code == 502
    assert len(gateway.operations) == 2
    assert [operation.purpose for operation in gateway.operations] == [
        "ANALYZE",
        "SEMANTIC_COMPLETENESS_CORRECTION",
    ]
    assert gateway.operations[1].required_categories == ("TERM_TERMINATION",)


def test_all_fixed_4_1_and_required_breach_first_call_needs_no_correction(
    caplog,
) -> None:
    gateway = SequencedGateway([
        completion_payload(
            *contract_body_hints(),
            review_hint("BREACH_LIABILITY"),
        ),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(
        gateway,
        request_id="fixed-4-1-with-breach-complete",
        materials=[{
            "fileId": "contract",
            "category": "CONTRACT_BODY",
            "text": QUANTIFIED_BREACH_TEXT,
        }],
    )

    assert response.status_code == 200
    assert len(gateway.operations) == 1
    assert gateway.operations[0].required_categories == (
        *CONTRACT_BODY_REQUIRED,
        "BREACH_LIABILITY",
    )
    completed = completed_fields(caplog)
    assert completed["provider_call_count"] == 1
    assert completed["semantic_retry_count"] == 0


def test_request_without_contract_body_does_not_require_structure(caplog) -> None:
    gateway = SequencedGateway([
        completion_payload(info_hint("DELIVERY_TERMS", "T:S1")),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(
        gateway,
        request_id="no-contract-body",
        materials=[{
            "fileId": "tender",
            "category": "TENDER_DOCUMENT",
            "text": "采购文件列示交付期限与交付地点。",
        }],
    )

    assert response.status_code == 200
    assert len(gateway.operations) == 1
    assert gateway.operations[0].required_categories == ()
    assert response.json()["data"]["hints"][0]["category"] == "DELIVERY_TERMS"
    assert not any(
        record.getMessage().startswith("contract semantic completeness required")
        for record in caplog.records
    )


def test_structure_and_breach_missing_share_one_completeness_correction() -> None:
    gateway = SequencedGateway([
        completion_payload(
            *contract_body_hints(exclude=("CONTRACT_STRUCTURE",)),
        ),
        correction_payload(
            info_hint("CONTRACT_STRUCTURE"),
            review_hint("BREACH_LIABILITY"),
        ),
    ])

    response = post_contract(
        gateway,
        materials=[{
            "fileId": "contract",
            "category": "CONTRACT_BODY",
            "text": QUANTIFIED_BREACH_TEXT,
        }],
    )

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[0].required_categories == (
        *CONTRACT_BODY_REQUIRED,
        "BREACH_LIABILITY",
    )
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    assert gateway.operations[1].required_categories == (
        "CONTRACT_STRUCTURE",
        "BREACH_LIABILITY",
    )
    correction_source = json.loads(gateway.operations[1].source_text)
    assert correction_source["correctionPlan"]["missingRequiredCategories"] == [
        "CONTRACT_STRUCTURE",
        "BREACH_LIABILITY",
    ]


def test_combined_missing_plan_rejects_partial_category_correction() -> None:
    gateway = SequencedGateway([
        completion_payload(
            *contract_body_hints(exclude=("CONTRACT_STRUCTURE",)),
        ),
        correction_payload(info_hint("CONTRACT_STRUCTURE")),
    ])

    response = post_contract(
        gateway,
        materials=[{
            "fileId": "contract",
            "category": "CONTRACT_BODY",
            "text": QUANTIFIED_BREACH_TEXT,
        }],
    )

    assert response.status_code == 502
    assert len(gateway.operations) == 2


def test_structure_plan_rejects_wrong_correction_category() -> None:
    gateway = SequencedGateway([
        completion_payload(
            *contract_body_hints(exclude=("CONTRACT_STRUCTURE",)),
        ),
        correction_payload(info_hint("OTHER_TEXT")),
    ])

    response = post_contract(gateway)

    assert response.status_code == 502
    assert len(gateway.operations) == 2


def test_valid_structure_info_requires_comparison_evidence() -> None:
    gateway = SequencedGateway([
        completion_payload(
            *contract_body_hints(exclude=("CONTRACT_STRUCTURE",)),
        ),
        correction_payload(info_hint("CONTRACT_STRUCTURE")),
    ])

    response = post_contract(gateway)

    assert response.status_code == 200
    structure = response.json()["data"]["hints"][-1]
    assert structure["category"] == "CONTRACT_STRUCTURE"
    assert structure["attention"] == "INFO"
    assert structure["evidenceRefs"] == ["contract"]


def test_structure_info_without_comparison_evidence_fails_closed() -> None:
    fake_structure = info_hint("CONTRACT_STRUCTURE")
    fake_structure["comparisonEvidenceIds"] = []
    gateway = SequencedGateway([
        completion_payload(
            *contract_body_hints(exclude=("CONTRACT_STRUCTURE",)),
        ),
        correction_payload(fake_structure),
    ])

    response = post_contract(gateway)

    assert response.status_code == 502


def test_valid_structure_review_requires_citable_problem_evidence() -> None:
    gateway = SequencedGateway([
        completion_payload(
            *contract_body_hints(exclude=("CONTRACT_STRUCTURE",)),
        ),
        correction_payload(review_hint("CONTRACT_STRUCTURE")),
    ])

    response = post_contract(gateway)

    assert response.status_code == 200
    structure = response.json()["data"]["hints"][-1]
    assert structure["attention"] == "REVIEW"
    assert structure["evidenceItems"] == [{
        "fileId": "contract",
        "status": "REVIEW",
        "excerpt": CONTRACT_TEXT,
    }]


def test_structure_review_with_invented_evidence_fails_closed() -> None:
    gateway = SequencedGateway([
        completion_payload(
            *contract_body_hints(exclude=("CONTRACT_STRUCTURE",)),
        ),
        correction_payload(review_hint("CONTRACT_STRUCTURE", "C:S999")),
    ])

    response = post_contract(gateway)

    assert response.status_code == 502


@pytest.mark.parametrize(
    "verdict",
    [
        "合同合法有效",
        "审核通过",
        "合同符合相关法律法规",
        "条款完全合规",
        "无任何风险",
    ],
)
def test_structure_correction_cannot_bypass_existing_strong_verdict_filter(
    verdict: str,
) -> None:
    unsafe_structure = review_hint("CONTRACT_STRUCTURE")
    unsafe_structure["description"] = verdict
    gateway = SequencedGateway([
        completion_payload(
            *contract_body_hints(exclude=("CONTRACT_STRUCTURE",)),
        ),
        correction_payload(unsafe_structure),
    ])

    response = post_contract(gateway)

    assert response.status_code == 502


def test_fixed_4_1_categories_are_required_when_contract_body_exists() -> None:
    gateway = SequencedGateway([
        completion_payload(
            info_hint("CONTRACT_STRUCTURE"),
            review_hint("BREACH_LIABILITY"),
        ),
        correction_payload(
            *contract_body_hints(exclude=("CONTRACT_STRUCTURE",)),
        ),
    ])

    response = post_contract(
        gateway,
        materials=[{
            "fileId": "contract",
            "category": "CONTRACT_BODY",
            "text": QUANTIFIED_BREACH_TEXT,
        }],
    )

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[0].required_categories == (
        *CONTRACT_BODY_REQUIRED,
        "BREACH_LIABILITY",
    )
    assert gateway.operations[1].required_categories == (
        *FIXED_4_1_REQUIRED[1:],
        "TERM_TERMINATION",
    )
    assert {
        hint["category"] for hint in response.json()["data"]["hints"]
    } == {*CONTRACT_BODY_REQUIRED, "BREACH_LIABILITY"}


def test_sparse_result_partial_fixed_4_1_correction_fails_closed() -> None:
    gateway = SequencedGateway([
        completion_payload(
            review_hint("BREACH_LIABILITY"),
            info_hint("TERM_TERMINATION"),
        ),
        correction_payload(*fixed_4_1_hints(
            exclude=("SCOPE_AND_SUBCONTRACTING",),
        )),
    ])

    response = post_contract(
        gateway,
        materials=[{
            "fileId": "contract",
            "category": "CONTRACT_BODY",
            "text": QUANTIFIED_BREACH_TEXT,
        }],
    )

    assert response.status_code == 502
    assert len(gateway.operations) == 2
    assert gateway.operations[1].required_categories == FIXED_4_1_REQUIRED


def test_filtered_required_category_is_repaired_as_missing(caplog) -> None:
    invalid_service = info_hint("SERVICE_WARRANTY", "C:S999")
    gateway = SequencedGateway([
        completion_payload(
            *contract_body_hints(exclude=("SERVICE_WARRANTY",)),
            invalid_service,
        ),
        correction_payload(info_hint("SERVICE_WARRANTY")),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(gateway, request_id="filtered-required-category")

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[1].required_categories == ("SERVICE_WARRANTY",)
    assert any(
        record.audit_fields["category"] == "SERVICE_WARRANTY"
        and record.audit_fields["filter_reason"] == "INVALID_INFO_EVIDENCE"
        for record in caplog.records
        if record.getMessage().startswith("contract semantic hint filtered")
    )


def test_required_project_info_with_problem_evidence_fails_closed() -> None:
    invalid_info = info_hint("PROJECT_IDENTITY")
    invalid_info.pop("comparisonEvidenceIds")
    invalid_info["problemEvidenceIds"] = ["C:S1"]
    gateway = SequencedGateway([
        completion_payload(*contract_body_hints(exclude=("PROJECT_IDENTITY",))),
        correction_payload(invalid_info),
    ])

    response = post_contract(gateway)

    assert response.status_code == 502
    assert len(gateway.operations) == 2


def test_required_service_review_without_problem_evidence_fails_closed() -> None:
    invalid_review = review_hint("SERVICE_WARRANTY")
    invalid_review["problemEvidenceIds"] = []
    gateway = SequencedGateway([
        completion_payload(*contract_body_hints(exclude=("SERVICE_WARRANTY",))),
        correction_payload(invalid_review),
    ])

    response = post_contract(gateway)

    assert response.status_code == 502
    assert len(gateway.operations) == 2


def test_transport_retry_replays_same_fixed_4_1_semantic_operation(caplog) -> None:
    gateway = SequencedGateway([
        ModelGatewayFailure(
            reason="CONNECT_ERROR", retryable=True, duration_ms=11
        ),
        completion_payload(*contract_body_hints()),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(gateway, request_id="fixed-4-1-transport-retry")

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    first, second = gateway.operations
    assert first.required_categories == CONTRACT_BODY_REQUIRED
    assert second.required_categories == first.required_categories
    assert second.source_text == first.source_text
    assert [first.transport_attempt, second.transport_attempt] == [1, 2]
    assert [first.attempt, second.attempt] == [1, 1]
    completed = completed_fields(caplog)
    assert completed["semantic_retry_count"] == 0
    assert completed["transport_retry_count"] == 1


def test_fixed_4_1_coverage_log_is_safe_and_complete(caplog) -> None:
    gateway = SequencedGateway([
        completion_payload(*contract_body_hints()),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(gateway, request_id="fixed-4-1-coverage-log")

    assert response.status_code == 200
    coverage = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract semantic coverage prepared")
    )
    assert coverage == {
        "operation": "CONTRACT_TEXT_ANALYSIS",
        "request_id": "fixed-4-1-coverage-log",
        "required_categories": ",".join(CONTRACT_BODY_REQUIRED),
        "required_category_count": 7,
    }


class MultiChunkCoverageGateway(ModelGatewayPort):
    def __init__(self, missing_on_synthesis: tuple[str, ...] = ()) -> None:
        self.operations: list[ContractTextOperation] = []
        self.missing_on_synthesis = missing_on_synthesis

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        self.operations.append(operation)
        if operation.purpose == "SEMANTIC_COMPLETENESS_CORRECTION":
            payload = correction_payload(*(
                info_hint(category)
                for category in self.missing_on_synthesis
            ))
        elif operation.purpose == "SYNTHESIZE":
            synthesis = json.loads(operation.source_text)
            evidence_id = synthesis["evidenceCatalog"][0]["evidenceId"]
            payload = completion_payload(*contract_body_hints(
                exclude=self.missing_on_synthesis,
                evidence_id=evidence_id,
            ))
        else:
            evidence_id = re.search(r"evidenceId=([^;]+)", operation.source_text)
            assert evidence_id is not None
            payload = completion_payload(info_hint("OTHER_TEXT", evidence_id.group(1)))
        return ModelCompletion(payload=payload, model="model-test", duration_ms=5)

    def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
        raise AssertionError("budget operation was not expected")


def test_multi_chunk_requires_fixed_4_1_only_at_final_synthesis(caplog) -> None:
    gateway = MultiChunkCoverageGateway(("SERVICE_WARRANTY", "DELIVERY_TERMS"))
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(
        gateway,
        request_id="multi-final-structure",
        settings=Settings(model_chunk_chars=90, model_max_chunks=20),
        materials=[{
            "fileId": "contract",
            "category": "CONTRACT_BODY",
            "text": "合同正文列示组成与附件。" * 18,
        }],
    )

    assert response.status_code == 200
    analyze_operations = [
        operation for operation in gateway.operations
        if operation.purpose == "ANALYZE"
    ]
    synthesis_index = next(
        index for index, operation in enumerate(gateway.operations)
        if operation.purpose == "SYNTHESIZE"
    )
    correction_operations = [
        operation for operation in gateway.operations
        if operation.purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    ]
    assert len(analyze_operations) > 1
    assert all(
        not set(CONTRACT_BODY_REQUIRED).intersection(operation.required_categories)
        for operation in analyze_operations
    )
    synthesis_operation = gateway.operations[synthesis_index]
    assert synthesis_operation.required_categories == CONTRACT_BODY_REQUIRED
    assert len(correction_operations) == 1
    assert gateway.operations.index(correction_operations[0]) > synthesis_index
    assert correction_operations[0].required_categories == (
        "DELIVERY_TERMS",
        "SERVICE_WARRANTY",
    )
    assert {
        hint["category"] for hint in response.json()["data"]["hints"]
    } == set(CONTRACT_BODY_REQUIRED)
    completed = completed_fields(caplog)
    assert completed["provider_call_count"] == len(analyze_operations) + 2
    assert completed["semantic_retry_count"] == 1


def test_multi_chunk_complete_synthesis_needs_no_correction(caplog) -> None:
    gateway = MultiChunkCoverageGateway()
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(
        gateway,
        request_id="multi-complete-fixed-4-1",
        settings=Settings(model_chunk_chars=90, model_max_chunks=20),
        materials=[{
            "fileId": "contract",
            "category": "CONTRACT_BODY",
            "text": "合同正文列示组成与附件。" * 18,
        }],
    )

    assert response.status_code == 200
    analyze_operations = [
        operation for operation in gateway.operations
        if operation.purpose == "ANALYZE"
    ]
    synthesis_operations = [
        operation for operation in gateway.operations
        if operation.purpose == "SYNTHESIZE"
    ]
    assert len(analyze_operations) > 1
    assert all(operation.required_categories == () for operation in analyze_operations)
    assert len(synthesis_operations) == 1
    assert synthesis_operations[0].required_categories == CONTRACT_BODY_REQUIRED
    assert all(
        operation.purpose != "SEMANTIC_COMPLETENESS_CORRECTION"
        for operation in gateway.operations
    )
    completed = completed_fields(caplog)
    assert completed["semantic_retry_count"] == 0


def test_consumed_semantic_retry_prevents_third_call_for_missing_structure() -> None:
    gateway = SequencedGateway([
        {"summary": "无效结果", "hints": "not-a-list"},
        completion_payload(info_hint("SERVICE_WARRANTY")),
    ])

    response = post_contract(gateway)

    assert response.status_code == 502
    assert len(gateway.operations) == 2
    assert [operation.attempt for operation in gateway.operations] == [1, 2]
    assert [operation.purpose for operation in gateway.operations] == [
        "ANALYZE",
        "ANALYZE",
    ]


def test_pm44_03_shaped_sparse_result_repairs_all_fixed_4_1_after_safety_filter(
    caplog,
) -> None:
    first_payload = completion_payload(
        {
            **review_hint("BREACH_LIABILITY"),
            "description": "价格按2×3计算",
        },
        {
            **review_hint("BREACH_LIABILITY"),
            "comparisonEvidenceIds": ["T:S2", "B:S3"],
        },
        info_hint("TERM_TERMINATION"),
    )
    gateway = SequencedGateway([
        first_payload,
        correction_payload(*fixed_4_1_hints()),
    ])
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = post_contract(
        gateway,
        request_id="pm44-03-shaped",
        materials=[
            {
                "fileId": "contract",
                "category": "CONTRACT_BODY",
                "text": "逾期交付按合同金额1%/周支付违约金，未约定累计上限。",
            },
            {
                "fileId": "tender",
                "category": "TENDER_DOCUMENT",
                "text": "采购文件约定违约金累计上限为合同金额10%。",
            },
            {
                "fileId": "bid",
                "category": "BID_RESPONSE",
                "text": "响应文件接受违约金累计上限为合同金额10%。",
            },
            {
                "fileId": "award",
                "category": "AWARD_NOTICE",
                "text": "中标通知列示项目名称与供应商名称。",
            },
        ],
    )

    assert response.status_code == 200
    assert len(gateway.operations) == 2
    assert gateway.operations[0].required_categories == (
        *CONTRACT_BODY_REQUIRED,
        "BREACH_LIABILITY",
    )
    assert gateway.operations[1].purpose == "SEMANTIC_COMPLETENESS_CORRECTION"
    assert gateway.operations[1].required_categories == FIXED_4_1_REQUIRED
    hints = response.json()["data"]["hints"]
    assert len([
        hint for hint in hints if hint["category"] in FIXED_4_1_REQUIRED
    ]) == 6
    assert any(
        hint["category"] == "BREACH_LIABILITY"
        and hint["attention"] == "REVIEW"
        for hint in hints
    )
    assert any(hint["category"] == "TERM_TERMINATION" for hint in hints)
    assert all(
        len({item["fileId"] for item in hint.get("evidenceItems", [])})
        == len(hint.get("evidenceItems", []))
        for hint in hints
    )
    public_data = json.dumps(response.json()["data"], ensure_ascii=False)
    for internal_field in (
        "requiredCategories",
        "required_categories",
        "hintId",
        "evidenceId",
        "correctionPlan",
    ):
        assert internal_field not in public_data
    filtered = [
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract semantic hint filtered")
    ]
    assert any(
        record["category"] == "BREACH_LIABILITY"
        and record["filter_reason"] == "MONETARY_CALCULATION"
        for record in filtered
    )
    normalization = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith(
            "contract semantic hint normalization completed"
        )
    )
    assert normalization["input_hint_count"] == 3
    assert normalization["accepted_hint_count"] == 2
    assert normalization["filtered_hint_count"] == 1
    completed = completed_fields(caplog)
    assert completed["provider_call_count"] == 2
    assert completed["semantic_retry_count"] == 1
