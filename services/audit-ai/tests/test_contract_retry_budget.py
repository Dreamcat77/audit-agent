from typing import Any
import re

from fastapi.testclient import TestClient
import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.main import create_app
from audit_ai.model.gateway import (
    BudgetSemanticOperation,
    ContractTextOperation,
    DocumentRoleOperation,
    ModelCompletion,
    ModelGatewayFailure,
    ModelGatewayPort,
    OpenAiCompatibleModelGateway,
)
from audit_ai.model.router import ContractAnalysisRequest, ModelApplicationService


class SequencedGateway(ModelGatewayPort):
    def __init__(self, outcomes: list[ModelCompletion | Exception]) -> None:
        self.outcomes = list(outcomes)
        self.operations: list[ContractTextOperation] = []

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        self.operations.append(operation)
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
        raise AssertionError("budget operation was not expected")


class FakeClock:
    def __init__(self) -> None:
        self.now = 100.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class TimedSequencedGateway(ModelGatewayPort):
    def __init__(
        self,
        clock: FakeClock,
        outcomes: list[tuple[ModelCompletion | Exception, float]],
    ) -> None:
        self.clock = clock
        self.outcomes = list(outcomes)
        self.operations: list[ContractTextOperation] = []

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        self.operations.append(operation)
        outcome, elapsed_seconds = self.outcomes.pop(0)
        self.clock.advance(elapsed_seconds)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
        raise AssertionError("budget operation was not expected")


def request() -> dict[str, Any]:
    return {
        "projectId": "project-retry-budget",
        "materials": [
            {
                "fileId": "contract",
                "category": "CONTRACT_BODY",
                "text": "Breach penalty is contract amount 1% per day, capped at 10%.",
            }
        ],
    }


def missing_breach_completion() -> ModelCompletion:
    return ModelCompletion(
        payload={
            "summary": "Delivery term reviewed.",
            "hints": [
                {
                    "category": "DELIVERY_TERMS",
                    "attention": "INFO",
                    "title": "Delivery term",
                    "description": "The visible delivery term was reviewed.",
                    "problemEvidenceIds": [],
                    "comparisonEvidenceIds": ["C:S1"],
                },
                {
                    "category": "CONTRACT_STRUCTURE",
                    "attention": "INFO",
                    "title": "Contract structure",
                    "description": "The visible contract structure was retained.",
                    "comparisonEvidenceIds": ["C:S1"],
                },
                *(
                    {
                        "category": category,
                        "attention": "INFO",
                        "title": f"{category} observation",
                        "description": "The visible source supports this observation.",
                        "comparisonEvidenceIds": ["C:S1"],
                    }
                    for category in (
                        "PROJECT_IDENTITY",
                        "SUPPLIER_IDENTITY",
                        "SERVICE_WARRANTY",
                        "SCOPE_AND_SUBCONTRACTING",
                        "TERM_TERMINATION",
                    )
                ),
            ],
        },
        model="qwen-test",
        duration_ms=10,
    )


def valid_breach_completion() -> ModelCompletion:
    return ModelCompletion(
        payload={
            "missingCategoryHints": [
                {
                    "category": "BREACH_LIABILITY",
                    "attention": "REVIEW",
                    "title": "Breach rate and cap",
                    "description": "Review the daily rate and cumulative cap.",
                    "problemEvidenceIds": ["C:S1"],
                }
            ],
        },
        model="qwen-test",
        duration_ms=30,
    )


def complete_initial_completion() -> ModelCompletion:
    incomplete = missing_breach_completion()
    return ModelCompletion(
        payload={
            **incomplete.payload,
            "hints": [
                *incomplete.payload["hints"],
                {
                    "category": "BREACH_LIABILITY",
                    "attention": "REVIEW",
                    "title": "Breach rate and cap",
                    "description": "Review the daily rate and cumulative cap.",
                    "problemEvidenceIds": ["C:S1"],
                },
            ],
        },
        model="qwen-test",
        duration_ms=10,
    )


def connect_error() -> ModelGatewayFailure:
    return ModelGatewayFailure(
        reason="CONNECT_ERROR", retryable=True, duration_ms=20
    )


def client(gateway: ModelGatewayPort) -> TestClient:
    return TestClient(create_app(settings=Settings(model_chunk_chars=2000), model_gateway=gateway))


def analyze_with_total_budget(
    outcomes: list[tuple[ModelCompletion | Exception, float]],
    *,
    total_budget_seconds: float = 10.0,
) -> tuple[TimedSequencedGateway, object]:
    clock = FakeClock()
    gateway = TimedSequencedGateway(clock, outcomes)
    service = ModelApplicationService(
        Settings(
            model_chunk_chars=2000,
            model_total_budget_seconds=total_budget_seconds,
        ),
        gateway,
        clock=clock,
    )
    result = service.analyze_contract(
        ContractAnalysisRequest.model_validate(request()), "total-budget-test"
    )
    return gateway, result


def test_total_budget_initial_success_uses_one_attempt() -> None:
    gateway, result = analyze_with_total_budget(
        [(complete_initial_completion(), 1.0)]
    )

    assert result.code == 200
    assert len(gateway.operations) == 1
    assert gateway.operations[0].provider_timeout_seconds == 10.0


def test_total_budget_allows_existing_semantic_retry() -> None:
    gateway, result = analyze_with_total_budget(
        [(missing_breach_completion(), 3.0), (valid_breach_completion(), 2.0)]
    )

    assert result.code == 200
    assert [operation.attempt for operation in gateway.operations] == [1, 2]
    assert [operation.provider_timeout_seconds for operation in gateway.operations] == [
        10.0,
        7.0,
    ]


def test_total_budget_allows_existing_transport_retry() -> None:
    gateway, result = analyze_with_total_budget(
        [(connect_error(), 3.0), (complete_initial_completion(), 2.0)]
    )

    assert result.code == 200
    assert [operation.transport_attempt for operation in gateway.operations] == [1, 2]
    assert [operation.provider_timeout_seconds for operation in gateway.operations] == [
        10.0,
        7.0,
    ]


def test_semantic_and_transport_retry_share_total_elapsed_budget() -> None:
    gateway, result = analyze_with_total_budget(
        [
            (missing_breach_completion(), 4.0),
            (connect_error(), 3.0),
            (valid_breach_completion(), 2.0),
        ]
    )

    assert result.code == 200
    assert len(gateway.operations) == 3
    assert [operation.provider_timeout_seconds for operation in gateway.operations] == [
        10.0,
        6.0,
        3.0,
    ]


def test_exhausted_total_budget_prevents_next_provider_call() -> None:
    clock = FakeClock()
    gateway = TimedSequencedGateway(
        clock,
        [
            (missing_breach_completion(), 6.0),
            (connect_error(), 4.0),
            (valid_breach_completion(), 0.0),
        ],
    )
    service = ModelApplicationService(
        Settings(model_chunk_chars=2000, model_total_budget_seconds=10.0),
        gateway,
        clock=clock,
    )

    with pytest.raises(AuditAiError) as caught:
        service.analyze_contract(
            ContractAnalysisRequest.model_validate(request()), "total-budget-test"
        )

    assert caught.value.code == "MODEL_TOTAL_BUDGET_EXHAUSTED"
    assert len(gateway.operations) == 2
    assert len(gateway.outcomes) == 1


def test_provider_read_timeout_is_distinct_from_total_budget_exhaustion() -> None:
    clock = FakeClock()
    read_timeout = ModelGatewayFailure(
        reason="READ_TIMEOUT", retryable=True, duration_ms=1000
    )
    gateway = TimedSequencedGateway(
        clock,
        [(read_timeout, 1.0), (read_timeout, 1.0)],
    )
    service = ModelApplicationService(
        Settings(model_chunk_chars=2000, model_total_budget_seconds=10.0),
        gateway,
        clock=clock,
    )

    with pytest.raises(AuditAiError) as caught:
        service.analyze_contract(
            ContractAnalysisRequest.model_validate(request()), "read-timeout-test"
        )

    assert caught.value.code == "MODEL_GATEWAY_ERROR"
    assert len(gateway.operations) == 2


def test_terminal_gateway_failure_http_envelope_preserves_both_real_calls() -> None:
    gateway = SequencedGateway([connect_error(), connect_error()])

    response = client(gateway).post(
        "/ai/v1/agent/contract-text-analysis", json=request()
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_GATEWAY_ERROR"
    calls = response.json()["data"]["modelCalls"]
    assert [
        (
            call["ordinal"],
            call["phase"],
            call["outcome"],
            call["safeReason"],
            call["durationMs"],
            call["provider"],
            call["model"],
        )
        for call in calls
    ] == [
        (1, "TRANSPORT", "FAILED", "CONNECT_ERROR", 20,
         "OPENAI_COMPATIBLE", "UNKNOWN_MODEL"),
        (2, "REPAIR", "FAILED", "CONNECT_ERROR", 20,
         "OPENAI_COMPATIBLE", "UNKNOWN_MODEL"),
    ]
    assert len(gateway.operations) == 2


def test_gateway_caps_per_attempt_http_timeouts_to_remaining_budget() -> None:
    observed_timeout: dict[str, float] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        observed_timeout.update(request.extensions["timeout"])
        return httpx.Response(
            200,
            json={
                "model": "synthetic-model",
                "choices": [
                    {
                        "message": {
                            "content": '{"summary":"ok","hints":[]}'
                        }
                    }
                ],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="http://synthetic.invalid/v1",
            model_api_key="synthetic-key",
            model_name="synthetic-model",
            model_connect_timeout_seconds=5.0,
            model_read_timeout_seconds=480.0,
        ),
        transport=httpx.MockTransport(handler),
    )

    gateway.analyze_contract(
        ContractTextOperation(
            project_id="project",
            chunk_index=1,
            chunk_count=1,
            source_text="synthetic source",
            provider_timeout_seconds=3.5,
        )
    )

    assert observed_timeout["read"] == 3.5
    assert observed_timeout["connect"] == 3.5


def test_gateway_uses_global_timeout_except_for_contract_semantic_analysis() -> None:
    observed_read_timeouts: list[float] = []
    observed_connect_timeouts: list[float] = []

    def handler(request: httpx.Request) -> httpx.Response:
        observed_read_timeouts.append(request.extensions["timeout"]["read"])
        observed_connect_timeouts.append(request.extensions["timeout"]["connect"])
        return httpx.Response(
            200,
            json={
                "model": "synthetic-model",
                "choices": [{"message": {"content": "{}"}}],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="http://synthetic.invalid/v1",
            model_api_key="synthetic-key",
            model_name="synthetic-model",
            model_connect_timeout_seconds=5.0,
            model_read_timeout_seconds=60.0,
            contract_model_read_timeout_seconds=600.0,
        ),
        transport=httpx.MockTransport(handler),
    )

    gateway.analyze_document_role(
        DocumentRoleOperation(
            file_id="file-1",
            parse_result_id="parse-1",
            source_text="synthetic role source",
        )
    )
    gateway.analyze_budget(
        BudgetSemanticOperation(
            source_id="budget-1",
            chunk_index=1,
            chunk_count=1,
            source_text="synthetic budget source",
        )
    )
    gateway.analyze_contract(
        ContractTextOperation(
            project_id="project-1",
            chunk_index=1,
            chunk_count=1,
            source_text="synthetic contract source",
            provider_timeout_seconds=900.0,
        )
    )
    gateway.analyze_contract(
        ContractTextOperation(
            project_id="project-1",
            chunk_index=1,
            chunk_count=1,
            source_text="synthetic contract source",
            provider_timeout_seconds=450.0,
        )
    )

    assert observed_read_timeouts == [60.0, 60.0, 600.0, 450.0]
    assert observed_connect_timeouts == [5.0, 5.0, 5.0, 5.0]


def test_semantic_correction_survives_its_transport_retry(caplog) -> None:
    gateway = SequencedGateway(
        [missing_breach_completion(), connect_error(), valid_breach_completion()]
    )

    caplog.set_level("INFO", logger="audit_ai.model.router")
    response = client(gateway).post(
        "/ai/v1/agent/contract-text-analysis", json=request()
    )

    assert response.status_code == 200
    assert len(gateway.operations) == 3
    assert [operation.attempt for operation in gateway.operations] == [1, 2, 2]
    assert [operation.transport_attempt for operation in gateway.operations] == [1, 1, 2]
    assert [operation.retry_reason for operation in gateway.operations] == [
        None,
        "MISSING_REQUIRED_BREACH_CATEGORY",
        "MISSING_REQUIRED_BREACH_CATEGORY",
    ]
    assert [operation.purpose for operation in gateway.operations] == [
        "ANALYZE",
        "SEMANTIC_COMPLETENESS_CORRECTION",
        "SEMANTIC_COMPLETENESS_CORRECTION",
    ]
    assert gateway.operations[1].source_text == gateway.operations[2].source_text
    assert '"missingRequiredCategories":["BREACH_LIABILITY"]' in (
        gateway.operations[1].source_text
    )
    assert [operation.transport_retry_reason for operation in gateway.operations] == [
        None,
        None,
        "CONNECT_ERROR",
    ]
    assert all(operation.max_attempts == 2 for operation in gateway.operations)
    assert all(
        operation.max_transport_attempts == 2 for operation in gateway.operations
    )
    assert response.json()["data"]["durationMs"] == 60
    assert [(call["ordinal"], call["phase"], call["outcome"], call.get("safeReason"))
            for call in response.json()["data"]["modelCalls"]] == [
        (1, "TRANSPORT", "FAILED", "MISSING_REQUIRED_BREACH_CATEGORY"),
        (2, "REPAIR", "FAILED", "CONNECT_ERROR"),
        (3, "REPAIR", "SUCCEEDED", None),
    ]
    completed = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract semantic analysis completed")
    )
    assert completed["provider_call_count"] == 3
    assert completed["semantic_retry_count"] == 1
    assert completed["transport_retry_count"] == 1
    failed_transport = next(
        record.audit_fields
        for record in caplog.records
        if record.getMessage().startswith("contract model attempt failed")
    )
    assert failed_transport["attempt"] == 2
    assert failed_transport["transport_attempt"] == 1
    assert failed_transport["max_transport_attempts"] == 2


def test_transport_retry_does_not_consume_the_semantic_retry_budget() -> None:
    gateway = SequencedGateway(
        [connect_error(), missing_breach_completion(), valid_breach_completion()]
    )

    response = client(gateway).post(
        "/ai/v1/agent/contract-text-analysis", json=request()
    )

    assert response.status_code == 200
    assert [operation.attempt for operation in gateway.operations] == [1, 1, 2]
    assert [operation.transport_attempt for operation in gateway.operations] == [1, 2, 1]
    assert gateway.operations[1].retry_reason is None
    assert gateway.operations[2].retry_reason == "MISSING_REQUIRED_BREACH_CATEGORY"
    assert len(gateway.operations) == 3


def test_global_extra_budget_never_allows_a_fourth_provider_call() -> None:
    gateway = SequencedGateway(
        [missing_breach_completion(), connect_error(), connect_error()]
    )

    response = client(gateway).post(
        "/ai/v1/agent/contract-text-analysis", json=request()
    )

    assert response.status_code == 502
    assert response.json()["code"] == "MODEL_GATEWAY_ERROR"
    assert [(call["ordinal"], call["phase"], call["outcome"], call.get("safeReason"))
            for call in response.json()["data"]["modelCalls"]] == [
        (1, "TRANSPORT", "FAILED", "MISSING_REQUIRED_BREACH_CATEGORY"),
        (2, "REPAIR", "FAILED", "CONNECT_ERROR"),
        (3, "REPAIR", "FAILED", "CONNECT_ERROR"),
    ]
    assert len(gateway.operations) == 3
    assert gateway.outcomes == []


def test_multi_chunk_and_synthesis_share_the_same_two_extra_calls() -> None:
    class SharedBudgetGateway(ModelGatewayPort):
        def __init__(self) -> None:
            self.operations: list[ContractTextOperation] = []

        def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
            self.operations.append(operation)
            call = len(self.operations)
            evidence_ids = re.findall(r"evidenceId=([^;]+)", operation.source_text)
            if call == 1:
                return ModelCompletion(
                    payload={
                        "summary": "Review required.",
                        "hints": [
                            {
                                "category": "SERVICE_WARRANTY",
                                "attention": "REVIEW",
                                "title": "Warranty term",
                                "description": "Review the visible warranty term.",
                                "problemEvidenceIds": [],
                                "comparisonEvidenceIds": evidence_ids,
                            }
                        ],
                    },
                    model="qwen-test",
                    duration_ms=1,
                )
            if operation.purpose == "EVIDENCE_REPAIR":
                return ModelCompletion(
                    payload={
                        "evidenceRepairs": [
                            {
                                "hintId": "A1:H1",
                                "problemEvidenceIds": ["C:S1"],
                            }
                        ],
                    },
                    model="qwen-test",
                    duration_ms=1,
                )
            if call == 3:
                raise connect_error()
            if operation.purpose == "SYNTHESIZE":
                evidence_ids = re.findall(r"[CB]:S\d+", operation.source_text)
            hints = [
                {
                    "category": "SERVICE_WARRANTY",
                    "attention": "INFO",
                    "title": "Warranty term",
                    "description": "The visible warranty term was retained.",
                    "problemEvidenceIds": [],
                    "comparisonEvidenceIds": list(dict.fromkeys(evidence_ids)),
                }
            ]
            if operation.purpose == "SYNTHESIZE":
                hints.extend(
                    {
                        "category": category,
                        "attention": "INFO",
                        "title": f"{category} observation",
                        "description": "The visible source supports this observation.",
                        "comparisonEvidenceIds": [evidence_ids[0]],
                    }
                        for category in (
                            "CONTRACT_STRUCTURE",
                            "PROJECT_IDENTITY",
                            "SUPPLIER_IDENTITY",
                            "DELIVERY_TERMS",
                            "SCOPE_AND_SUBCONTRACTING",
                            "TERM_TERMINATION",
                        )
                )
            return ModelCompletion(
                payload={
                    "summary": "Visible terms retained.",
                    "hints": hints,
                },
                model="qwen-test",
                duration_ms=1,
            )

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = SharedBudgetGateway()
    response = TestClient(
        create_app(
            settings=Settings(model_chunk_chars=135, model_max_chunks=10),
            model_gateway=gateway,
        )
    ).post(
        "/ai/v1/agent/contract-text-analysis",
        json={
            "projectId": "project-shared-budget",
            "materials": [
                {
                    "fileId": "contract",
                    "category": "CONTRACT_BODY",
                    "text": "Contract warranty is three years and includes onsite service.",
                },
                {
                    "fileId": "bid",
                    "category": "BID_RESPONSE",
                    "text": "Bid response warranty is five years and includes onsite service.",
                },
            ],
        },
    )

    assert response.status_code == 200
    assert len(gateway.operations) == 5
    assert [operation.attempt for operation in gateway.operations] == [1, 2, 1, 1, 1]
    assert [operation.transport_attempt for operation in gateway.operations] == [
        1,
        1,
        1,
        2,
        1,
    ]
    assert gateway.operations[1].retry_reason == (
        "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE"
    )
    assert gateway.operations[1].purpose == "EVIDENCE_REPAIR"
    assert gateway.operations[3].transport_retry_reason == "CONNECT_ERROR"
    assert gateway.operations[-1].purpose == "SYNTHESIZE"
    assert gateway.operations[-1].max_attempts == 1
    assert gateway.operations[-1].max_transport_attempts == 1
