import json
import logging
import re
from typing import Any

import httpx
from fastapi.testclient import TestClient
from pydantic import ValidationError
import pytest

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.contract_correction import CorrectionMode, parse_contract_correction
from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.gateway import (
    BudgetSemanticOperation,
    ContractTextOperation,
    ModelCompletion,
    ModelGatewayPort,
    OpenAiCompatibleModelGateway,
)


ALL_CATEGORIES = {
    "CONTRACT_STRUCTURE",
    "PROJECT_IDENTITY",
    "SUPPLIER_IDENTITY",
    "DELIVERY_TERMS",
    "SERVICE_WARRANTY",
    "SCOPE_AND_SUBCONTRACTING",
    "BREACH_LIABILITY",
    "TERM_TERMINATION",
    "OTHER_TEXT",
}
FIXED_4_1_REQUIRED = (
    "CONTRACT_STRUCTURE",
    "PROJECT_IDENTITY",
    "SUPPLIER_IDENTITY",
    "DELIVERY_TERMS",
    "SERVICE_WARRANTY",
    "SCOPE_AND_SUBCONTRACTING",
)
CONTRACT_BODY_REQUIRED = (*FIXED_4_1_REQUIRED, "TERM_TERMINATION")


def structure_info(evidence_id: str = "C:S1") -> dict[str, Any]:
    return {
        "category": "CONTRACT_STRUCTURE",
        "attention": "INFO",
        "title": "Contract structure",
        "description": "The visible contract structure was retained.",
        "comparisonEvidenceIds": [evidence_id],
    }


def fixed_4_1_info(
    evidence_id: str = "C:S1",
    *,
    exclude: tuple[str, ...] = (),
) -> list[dict[str, Any]]:
    return [
        {
            "category": category,
            "attention": "INFO",
            "title": f"{category} observation",
            "description": "The visible source supports this bounded observation.",
            "comparisonEvidenceIds": [evidence_id],
        }
        for category in FIXED_4_1_REQUIRED
        if category not in exclude
    ]


def contract_body_info(
    evidence_id: str = "C:S1",
    *,
    exclude: tuple[str, ...] = (),
) -> list[dict[str, Any]]:
    hints = fixed_4_1_info(evidence_id, exclude=exclude)
    if "TERM_TERMINATION" not in exclude:
        hints.append({
            "category": "TERM_TERMINATION",
            "attention": "INFO",
            "title": "TERM_TERMINATION observation",
            "description": "The visible source supports this bounded observation.",
            "comparisonEvidenceIds": [evidence_id],
        })
    return hints


class DraftGateway(ModelGatewayPort):
    def __init__(self, payload: dict[str, Any]) -> None:
        self.payload = payload
        self.operations: list[ContractTextOperation] = []

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        self.operations.append(operation)
        return ModelCompletion(payload=self.payload, model="qwen-test", duration_ms=7)

    def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
        raise AssertionError("budget operation was not expected")


def settings(**overrides: Any) -> Settings:
    return Settings(**overrides)


def test_internal_model_draft_is_reconstructed_to_the_frozen_public_dto() -> None:
    gateway = DraftGateway(
        {
            "summary": "已完成服务质保条款核对。",
            "hints": [
                {
                    "category": "SERVICE_WARRANTY",
                    "attention": "REVIEW",
                    "title": "质保期限存在差异",
                    "description": "合同、响应文件与采购文件需要交叉复核。",
                    "problemEvidenceIds": ["C:S1"],
                    "comparisonEvidenceIds": ["B:S2", "T:S3"],
                },
                *contract_body_info(exclude=("SERVICE_WARRANTY",)),
            ],
        }
    )
    request = {
        "projectId": "project-protocol",
        "materials": [
            {
                "fileId": "contract",
                "category": "CONTRACT_BODY",
                "text": "合同约定逾期每日承担违约责任。",
            },
            {
                "fileId": "bid",
                "category": "BID_RESPONSE",
                "text": "响应文件承诺提供三年质保。",
            },
            {
                "fileId": "tender",
                "category": "TENDER_DOCUMENT",
                "text": "采购文件要求提供五年质保。",
            },
        ],
    }

    response = TestClient(
        create_app(settings=settings(model_chunk_chars=2000), model_gateway=gateway)
    ).post("/ai/v1/agent/contract-text-analysis", json=request)

    assert response.status_code == 200
    assert response.json()["data"]["hints"][0] == {
            "category": "SERVICE_WARRANTY",
            "attention": "REVIEW",
            "title": "质保期限存在差异",
            "description": "合同、响应文件与采购文件需要交叉复核。",
            "evidenceRefs": ["contract", "bid", "tender"],
            "evidenceItems": [
                {
                    "fileId": "contract",
                    "status": "REVIEW",
                    "excerpt": "合同约定逾期每日承担违约责任。",
                },
                {
                    "fileId": "bid",
                    "status": "NORMAL",
                    "excerpt": "响应文件承诺提供三年质保。",
                },
                {
                    "fileId": "tender",
                    "status": "NORMAL",
                    "excerpt": "采购文件要求提供五年质保。",
                },
            ],
        }
    assert response.json()["data"]["hints"][1]["category"] == "CONTRACT_STRUCTURE"
    assert "problemEvidenceIds" not in response.text
    assert "comparisonEvidenceIds" not in response.text


def test_public_contract_equivalence_preserves_statuses_nullability_and_key_sets() -> None:
    cases = (
        (
            {
                "summary": "已核对普通条款。",
                "hints": [{
                    "category": "OTHER_TEXT",
                    "attention": "INFO",
                    "title": "普通条款",
                    "description": "保留来源材料供人工复核。",
                    "problemEvidenceIds": [],
                    "comparisonEvidenceIds": ["C:S1"],
                }, *contract_body_info()],
            },
            "NORMAL",
            {"fileId", "status", "excerpt"},
        ),
        (
            {
                "summary": "必要材料缺失。",
                "hints": [{
                    "category": "SERVICE_WARRANTY",
                    "attention": "INSUFFICIENT",
                    "title": "缺少响应材料",
                    "description": "无法核对响应材料中的服务承诺。",
                    "problemEvidenceIds": [],
                    "comparisonEvidenceIds": ["C:S1"],
                    "unavailableMaterialCategory": "BID_RESPONSE",
                }, *contract_body_info(exclude=("SERVICE_WARRANTY",))],
            },
            "INSUFFICIENT",
            {"fileId", "status", "unavailableMaterialCategory"},
        ),
    )

    for payload, expected_status, expected_item_keys in cases:
        response = TestClient(
            create_app(
                settings=settings(model_chunk_chars=2000),
                model_gateway=DraftGateway(payload),
            )
        ).post(
            "/ai/v1/agent/contract-text-analysis",
            json={
                "projectId": "project-public-equivalence",
                "materials": [{
                    "fileId": "contract",
                    "category": "CONTRACT_BODY",
                    "text": "合同正文包含可核验的服务条款。",
                }],
            },
        )

        assert response.status_code == 200
        public_hint = response.json()["data"]["hints"][0]
        assert set(public_hint) == {
            "category",
            "attention",
            "title",
            "description",
            "evidenceRefs",
            "evidenceItems",
        }
        public_item = public_hint["evidenceItems"][0]
        assert set(public_item) == expected_item_keys
        assert public_item["status"] == expected_status
        if expected_status == "INSUFFICIENT":
            assert "excerpt" not in public_item
        else:
            assert isinstance(public_item["excerpt"], str)
            assert public_item["excerpt"]
        assert not any(
            internal in response.text
            for internal in (
                "hintId",
                "evidenceId",
                "correctionPlan",
                "problemEvidenceIds",
                "comparisonEvidenceIds",
            )
        )


def test_required_categories_are_explicit_before_the_first_provider_call() -> None:
    gateway = DraftGateway(
        {
            "summary": "已核对违约责任。",
            "hints": [
                {
                    "category": "BREACH_LIABILITY",
                    "attention": "REVIEW",
                    "title": "违约责任比例",
                    "description": "需要复核计取比例和累计上限。",
                    "problemEvidenceIds": ["C:S1"],
                    "comparisonEvidenceIds": [],
                },
                *contract_body_info(),
            ],
        }
    )
    request = {
        "projectId": "project-breach",
        "materials": [
            {
                "fileId": "contract",
                "category": "CONTRACT_BODY",
                "text": "Breach penalty is contract amount 1% per day, capped at 10%.",
            }
        ],
    }

    response = TestClient(
        create_app(settings=settings(model_chunk_chars=2000), model_gateway=gateway)
    ).post("/ai/v1/agent/contract-text-analysis", json=request)

    assert response.status_code == 200
    assert gateway.operations[0].required_categories == (
        *CONTRACT_BODY_REQUIRED,
        "BREACH_LIABILITY",
    )
    assert set(gateway.operations[0].allowed_categories) == ALL_CATEGORIES
    assert "evidenceId=C:S1" in gateway.operations[0].source_text
    assert request["materials"][0]["text"] in gateway.operations[0].source_text


def test_analysis_catalog_exposes_citable_metadata_without_hiding_masked_text() -> None:
    class CatalogGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            match = re.search(
                r"\[evidenceId=([^;]+);[^\]]*citable=true\]\n"
                r"[^\n]*违约责任：逾期交付按合同金额1%/周计算，累计上限10%。",
                operation.source_text,
            )
            assert match is not None
            return ModelCompletion(
                payload={
                    "summary": "已核对违约责任。",
                    "hints": [
                        {
                            "category": "BREACH_LIABILITY",
                            "attention": "REVIEW",
                            "title": "违约责任比例",
                            "description": "需要复核比例与累计上限。",
                            "problemEvidenceIds": [match.group(1)],
                            "comparisonEvidenceIds": [],
                        },
                        *contract_body_info(match.group(1)),
                    ],
                },
                model="qwen-test",
                duration_ms=1,
            )

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    masked = "[确定性数值字段已移除]"
    source = f"合同金额：{masked}。违约责任：逾期交付按合同金额1%/周计算，累计上限10%。"
    gateway = CatalogGateway()

    response = TestClient(
        create_app(settings=settings(model_chunk_chars=2000), model_gateway=gateway)
    ).post(
        "/ai/v1/agent/contract-text-analysis",
        json={
            "projectId": "project-citable-catalog",
            "materials": [
                {
                    "fileId": "contract",
                    "category": "CONTRACT_BODY",
                    "text": source,
                }
            ],
        },
    )

    assert response.status_code == 200
    model_source = gateway.operations[0].source_text
    assert "citable=true" in model_source
    assert "citable=false" in model_source
    assert masked in model_source
    assert source == "".join(
        block.split("]\n", 1)[1]
        for block in model_source.split("\n\n")
    )


def test_unsatisfiable_required_breach_catalog_fails_before_provider_call(
    monkeypatch, caplog
) -> None:
    from audit_ai.model import router as router_module

    source = "违约责任：逾期交付按合同金额1%/周计算，累计上限10%。"
    catalog = EvidenceCatalog(
        (
            EvidenceSegment(
                evidence_id="C:S1",
                file_id="contract",
                material_category="CONTRACT_BODY",
                source_start=0,
                source_end=len(source),
                exact_text=source,
                citable=False,
            ),
        )
    )
    monkeypatch.setattr(
        router_module,
        "segment_evidence_sources",
        lambda *_args, **_kwargs: catalog,
    )
    gateway = DraftGateway({"summary": "not called", "hints": []})
    caplog.set_level(logging.INFO, logger="audit_ai.model.router")

    response = TestClient(
        create_app(settings=settings(model_chunk_chars=2000), model_gateway=gateway)
    ).post(
        "/ai/v1/agent/contract-text-analysis",
        json={
            "projectId": "project-unsatisfiable-catalog",
            "materials": [
                {
                    "fileId": "contract",
                    "category": "CONTRACT_BODY",
                    "text": source,
                }
            ],
        },
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_INVALID_RESULT"
    assert gateway.operations == []
    assert "reason=EVIDENCE_CATALOG_UNSATISFIABLE" in caplog.text


def test_gateway_requests_internal_evidence_ids_without_changing_category_coverage() -> None:
    observed: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        observed["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "qwen-test",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {"summary": "已核对。", "hints": []},
                                ensure_ascii=False,
                            )
                        }
                    }
                ],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        settings(
            model_base_url="https://model.example.invalid/v1",
            model_api_key="unit-test-key",
            model_name="qwen-test",
        ),
        transport=httpx.MockTransport(handler),
    )
    gateway.analyze_contract(
        ContractTextOperation(
            project_id="project-1",
            chunk_index=1,
            chunk_count=1,
            source_text=(
                "[evidenceId=C:S1;fileId=contract;category=CONTRACT_BODY]\n"
                "逾期按约定比例承担违约责任。"
            ),
            required_categories=("BREACH_LIABILITY",),
            allowed_categories=tuple(sorted(ALL_CATEGORIES)),
        )
    )

    system_prompt = observed["body"]["messages"][0]["content"]
    user_prompt = observed["body"]["messages"][1]["content"]
    assert "problemEvidenceIds" in system_prompt
    assert "comparisonEvidenceIds" in system_prompt
    assert "server-owned exact text" in system_prompt
    assert "requiredCategories=BREACH_LIABILITY" in user_prompt
    assert "allowedCategories=" in user_prompt
    assert all(category in user_prompt for category in ALL_CATEGORIES)


def test_semantic_retry_prompt_keeps_the_internal_evidence_id_protocol() -> None:
    observed: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        observed["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "qwen-test",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {"summary": "Retried.", "hints": []}
                            )
                        }
                    }
                ],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        settings(
            model_base_url="https://model.example.invalid/v1",
            model_api_key="unit-test-key",
            model_name="qwen-test",
        ),
        transport=httpx.MockTransport(handler),
    )
    gateway.analyze_contract(
        ContractTextOperation(
            project_id="project-1",
            chunk_index=1,
            chunk_count=1,
            source_text="[evidenceId=C:S1;fileId=contract;category=CONTRACT_BODY]\nText.",
            attempt=2,
            max_attempts=2,
            retry_reason="MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE",
        )
    )

    system_prompt = observed["body"]["messages"][0]["content"]
    assert "Use only supplied evidenceIds" in system_prompt
    assert "evidenceItems.fileId may use" not in system_prompt
    assert "excerpt must be copied" not in system_prompt


def test_gateway_uses_purpose_specific_delta_prompts_for_all_correction_modes() -> None:
    observed: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        observed.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "model": "qwen-test",
                "choices": [{
                    "message": {
                        "content": json.dumps({
                            "missingCategoryHints": [],
                            "evidenceRepairs": [],
                        })
                    }
                }],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        settings(
            model_base_url="https://model.example.invalid/v1",
            model_api_key="unit-test-key",
            model_name="qwen-test",
        ),
        transport=httpx.MockTransport(handler),
    )
    expectations = {
        "EVIDENCE_REPAIR": "may contain only hintId",
        "SEMANTIC_COMPLETENESS_CORRECTION": "genuinely missing required categories",
        "COMBINED_CORRECTION": "strict JSON combined delta",
    }

    for purpose in expectations:
        gateway.analyze_contract(
            ContractTextOperation(
                project_id="project-correction-prompt",
                chunk_index=1,
                chunk_count=1,
                source_text='{"correctionPlan":{},"originalOperationSource":"safe"}',
                attempt=2,
                max_attempts=2,
                purpose=purpose,
            )
        )

    for body, expected_phrase in zip(observed, expectations.values()):
        system_prompt = body["messages"][0]["content"]
        assert expected_phrase in system_prompt
        assert "Generate a new complete result" not in system_prompt

    evidence_prompt = observed[0]["messages"][0]["content"]
    completeness_prompt = observed[1]["messages"][0]["content"]
    combined_prompt = observed[2]["messages"][0]["content"]
    for prompt in (evidence_prompt, completeness_prompt, combined_prompt):
        assert "evidenceRepairs, when present, MUST always be a JSON array" in prompt
        assert "missingCategoryHints, when present, MUST always be a JSON array" in prompt
        assert (
            "CONTRACT_BODY, AWARD_NOTICE, BID_RESPONSE, TENDER_DOCUMENT, or SUPPLEMENT"
            in prompt
        )
        assert "omit unavailableMaterialCategory" in prompt
        assert "NONE, NULL, N/A, UNKNOWN, or NOT_APPLICABLE" in prompt

    assert "single top-level key evidenceRepairs" in evidence_prompt
    assert "single top-level key missingCategoryHints" in completeness_prompt
    assert "keys missingCategoryHints and evidenceRepairs" in combined_prompt


def _synthetic_missing_category_hint(
    unavailable_material_category: str,
) -> dict[str, Any]:
    return {
        "category": "SERVICE_WARRANTY",
        "attention": "INSUFFICIENT",
        "title": "Synthetic missing material",
        "description": "A synthetic category is unavailable.",
        "comparisonEvidenceIds": ["C:S1"],
        "unavailableMaterialCategory": unavailable_material_category,
    }


def test_completeness_correction_strictly_rejects_none_placeholder() -> None:
    with pytest.raises(ValidationError):
        parse_contract_correction(
            {
                "missingCategoryHints": [
                    _synthetic_missing_category_hint("NONE")
                ]
            },
            mode=CorrectionMode.SEMANTIC_COMPLETENESS_CORRECTION,
        )


def test_combined_correction_strictly_rejects_evidence_repairs_object() -> None:
    with pytest.raises(ValidationError):
        parse_contract_correction(
            {
                "missingCategoryHints": [],
                "evidenceRepairs": {
                    "hintId": "A1:H1",
                    "problemEvidenceIds": ["C:S1"],
                },
            },
            mode=CorrectionMode.COMBINED_CORRECTION,
        )


def test_combined_correction_strictly_rejects_unknown_placeholder() -> None:
    with pytest.raises(ValidationError):
        parse_contract_correction(
            {
                "missingCategoryHints": [
                    _synthetic_missing_category_hint("UNKNOWN")
                ],
                "evidenceRepairs": [],
            },
            mode=CorrectionMode.COMBINED_CORRECTION,
        )


def _capture_contract_provider_request(
    operation: ContractTextOperation,
) -> dict[str, Any]:
    observed: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        observed["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "protocol-test-model",
                "choices": [{"message": {"content": "{}"}}],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        settings(
            model_base_url="https://model.example.invalid/v1",
            model_api_key="unit-test-key",
            model_name="protocol-test-model",
        ),
        transport=httpx.MockTransport(handler),
    )
    gateway.analyze_contract(operation)
    return observed["body"]


def test_synthesis_outbound_contract_requires_complete_typed_citable_hints() -> None:
    body = _capture_contract_provider_request(
        ContractTextOperation(
            project_id="project-protocol",
            chunk_index=2,
            chunk_count=2,
            source_text=(
                '{"partialResults":[],"evidenceCatalog":['
                '{"evidenceId":"C:S1","citable":true}]}'
            ),
            purpose="SYNTHESIZE",
            required_categories=("CONTRACT_STRUCTURE", "BREACH_LIABILITY"),
        )
    )

    system_message = body["messages"][0]
    user_message = body["messages"][1]
    assert body["response_format"] == {"type": "json_object"}
    assert system_message["role"] == "system"
    assert user_message["role"] == "user"
    assert "requiredCategories" in system_message["content"]
    assert "every item in hints" in system_message["content"].casefold()
    assert "evidenceCatalog" in system_message["content"]
    assert "citable=true" in system_message["content"]
    assert "INFO" in system_message["content"]
    assert "problemEvidenceIds must be empty" in system_message["content"]
    assert "comparisonEvidenceIds must contain" in system_message["content"]
    assert "REVIEW" in system_message["content"]
    assert "problemEvidenceIds must contain" in system_message["content"]
    assert "requiredCategories=CONTRACT_STRUCTURE,BREACH_LIABILITY" in (
        user_message["content"]
    )


def test_invalid_provider_json_transport_retry_requests_a_fresh_complete_object() -> None:
    body = _capture_contract_provider_request(
        ContractTextOperation(
            project_id="project-protocol",
            chunk_index=1,
            chunk_count=2,
            source_text="[evidenceId=C:S1;citable=true] Synthetic clause.",
            required_categories=("CONTRACT_STRUCTURE",),
            transport_attempt=2,
            max_transport_attempts=2,
            transport_retry_reason="INVALID_PROVIDER_JSON",
        )
    )

    system_prompt = body["messages"][0]["content"]
    assert "prior provider response was incomplete or malformed JSON" in system_prompt
    assert "generate a fresh complete JSON object" in system_prompt
    assert "keep every text field concise" in system_prompt
    assert "final closing brace" in system_prompt


def test_semantic_correction_outbound_is_bounded_to_plan_and_original_source() -> None:
    body = _capture_contract_provider_request(
        ContractTextOperation(
            project_id="project-protocol",
            chunk_index=1,
            chunk_count=2,
            source_text=(
                '{"correctionPlan":{"missingRequiredCategories":'
                '["BREACH_LIABILITY"]},"frozenHints":[],'
                '"originalOperationSource":"[evidenceId=C:S1;'
                'citable=true]\\nVisible clause."}'
            ),
            purpose="SEMANTIC_COMPLETENESS_CORRECTION",
            required_categories=("BREACH_LIABILITY",),
            attempt=2,
            max_attempts=2,
        )
    )

    system_prompt = body["messages"][0]["content"]
    assert body["response_format"] == {"type": "json_object"}
    assert "correctionPlan.missingRequiredCategories" in system_prompt
    assert "missingCategoryHints" in system_prompt
    assert "Do not repeat or rewrite frozenHints" in system_prompt
    assert "originalOperationSource" in system_prompt
    assert "citable=true" in system_prompt
    assert "not present in originalOperationSource" in system_prompt
    assert "INFO" in system_prompt
    assert "problemEvidenceIds must be empty" in system_prompt
    assert "comparisonEvidenceIds must contain" in system_prompt
    assert "requiredCategories=BREACH_LIABILITY" in body["messages"][1]["content"]


def test_combined_correction_outbound_preserves_bounded_delta_contract() -> None:
    body = _capture_contract_provider_request(
        ContractTextOperation(
            project_id="project-protocol",
            chunk_index=1,
            chunk_count=2,
            source_text=(
                '{"correctionPlan":{"missingRequiredCategories":'
                '["BREACH_LIABILITY"],"evidenceRepairTargets":'
                '[{"hintId":"A1:H1"}]},"frozenHints":[],'
                '"originalOperationSource":"[evidenceId=C:S1;'
                'citable=true]\\nVisible clause."}'
            ),
            purpose="COMBINED_CORRECTION",
            required_categories=("BREACH_LIABILITY",),
            attempt=2,
            max_attempts=2,
        )
    )

    system_prompt = body["messages"][0]["content"]
    assert body["response_format"] == {"type": "json_object"}
    assert "missingCategoryHints" in system_prompt
    assert "evidenceRepairs" in system_prompt
    assert "correctionPlan.missingRequiredCategories" in system_prompt
    assert "correctionPlan.evidenceRepairTargets" in system_prompt
    assert "Do not repeat or rewrite frozenHints" in system_prompt
    assert "originalOperationSource" in system_prompt
    assert "citable=true" in system_prompt
    assert "not present in originalOperationSource" in system_prompt


def test_multi_chunk_synthesis_retains_request_wide_ids_and_cross_file_evidence() -> None:
    class CrossChunkGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            if operation.purpose == "SYNTHESIZE":
                return ModelCompletion(
                    payload={
                        "summary": "Cross-file warranty terms reviewed.",
                        "hints": [
                            {
                                "category": "SERVICE_WARRANTY",
                                "attention": "REVIEW",
                                "title": "Warranty terms differ",
                                "description": "Review the two visible warranty terms.",
                                "problemEvidenceIds": ["C:S1"],
                                "comparisonEvidenceIds": ["B:S2"],
                            },
                            *contract_body_info(
                                "C:S1", exclude=("SERVICE_WARRANTY",)
                            ),
                        ],
                    },
                    model="qwen-test",
                    duration_ms=5,
                )
            evidence_ids = re.findall(r"evidenceId=([^;]+)", operation.source_text)
            return ModelCompletion(
                payload={
                    "summary": "Chunk reviewed.",
                    "hints": [
                        {
                            "category": "SERVICE_WARRANTY",
                            "attention": "INFO",
                            "title": "Visible warranty term",
                            "description": "The visible source term was retained.",
                            "problemEvidenceIds": [],
                            "comparisonEvidenceIds": evidence_ids,
                        },
                        structure_info(evidence_ids[0]),
                    ],
                },
                model="qwen-test",
                duration_ms=3,
            )

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    contract_text = "Contract warranty is three years and includes onsite service."
    bid_text = "Bid response warranty is five years and includes onsite service."
    gateway = CrossChunkGateway()
    response = TestClient(
        create_app(
            settings=settings(model_chunk_chars=135, model_max_chunks=10),
            model_gateway=gateway,
        )
    ).post(
        "/ai/v1/agent/contract-text-analysis",
        json={
            "projectId": "project-cross-chunk",
            "materials": [
                {
                    "fileId": "contract",
                    "category": "CONTRACT_BODY",
                    "text": contract_text,
                },
                {
                    "fileId": "bid",
                    "category": "BID_RESPONSE",
                    "text": bid_text,
                },
            ],
        },
    )

    assert response.status_code == 200
    analyze_operations = [
        operation for operation in gateway.operations if operation.purpose == "ANALYZE"
    ]
    synthesis_operation = next(
        operation
        for operation in gateway.operations
        if operation.purpose == "SYNTHESIZE"
    )
    assert len(analyze_operations) == 2
    assert contract_text in "\n".join(
        operation.source_text for operation in analyze_operations
    )
    assert bid_text in "\n".join(operation.source_text for operation in analyze_operations)
    assert "problemEvidenceIds" in synthesis_operation.source_text
    assert "comparisonEvidenceIds" in synthesis_operation.source_text
    assert "C:S1" in synthesis_operation.source_text
    assert "B:S2" in synthesis_operation.source_text
    assert "evidenceItems" not in synthesis_operation.source_text
    synthesis_input = json.loads(synthesis_operation.source_text)
    assert synthesis_input["evidenceCatalog"] == [
        {
            "evidenceId": "C:S1",
            "fileId": "contract",
            "materialCategory": "CONTRACT_BODY",
            "citable": True,
            "exactText": contract_text,
        },
        {
            "evidenceId": "B:S2",
            "fileId": "bid",
            "materialCategory": "BID_RESPONSE",
            "citable": True,
            "exactText": bid_text,
        },
    ]
    assert response.json()["data"]["hints"][0]["evidenceRefs"] == [
        "contract",
        "bid",
    ]


def test_public_projection_selects_one_bounded_server_excerpt_per_file() -> None:
    class AllEvidenceGateway(ModelGatewayPort):
        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            evidence_ids = re.findall(r"evidenceId=([^;]+)", operation.source_text)
            return ModelCompletion(
                payload={
                    "summary": "All visible segments retained.",
                    "hints": [
                        {
                            "category": "OTHER_TEXT",
                            "attention": "INFO",
                            "title": "Visible text",
                            "description": "All source segments remain available.",
                            "problemEvidenceIds": [],
                            "comparisonEvidenceIds": evidence_ids,
                        },
                        *contract_body_info(evidence_ids[0]),
                    ],
                },
                model="qwen-test",
                duration_ms=1,
            )

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    source_text = "A" * 1500
    response = TestClient(
        create_app(
            settings=settings(model_chunk_chars=2000),
            model_gateway=AllEvidenceGateway(),
        )
    ).post(
        "/ai/v1/agent/contract-text-analysis",
        json={
            "projectId": "project-excerpt-bound",
            "materials": [
                {
                    "fileId": "contract",
                    "category": "CONTRACT_BODY",
                    "text": source_text,
                }
            ],
        },
    )

    assert response.status_code == 200
    excerpts = [
        item["excerpt"]
        for item in response.json()["data"]["hints"][0]["evidenceItems"]
    ]
    assert response.json()["data"]["hints"][0]["evidenceRefs"] == ["contract"]
    assert excerpts == [source_text[:1000]]
    assert all(len(excerpt) <= 1000 for excerpt in excerpts)
