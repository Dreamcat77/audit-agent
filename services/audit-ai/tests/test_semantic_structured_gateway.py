from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.model.contract_correction import contract_correction_json_schema
from audit_ai.model.contract_draft import provider_contract_json_schema
from audit_ai.model.gateway import (
    BudgetSemanticOperation,
    ContractTextOperation,
    ModelCompletion,
    ModelGatewayFailure,
    OpenAiCompatibleModelGateway,
    provider_compatible_json_schema,
)
from audit_ai.model.router import ContractAnalysisRequest, ModelApplicationService


def _provider_payload() -> dict[str, Any]:
    return {
        "summary": "summary",
        "hints": [
            {
                "category": "CONTRACT_STRUCTURE",
                "attention": "INFO",
                "title": "title",
                "description": "description",
                "problemEvidenceIds": [],
                "comparisonEvidenceIds": ["evidence-1"],
                "unavailableMaterialCategory": None,
            }
        ],
    }


def _operation(*, purpose: str = "ANALYZE") -> ContractTextOperation:
    return ContractTextOperation(
        project_id="project-1",
        chunk_index=1,
        chunk_count=1,
        source_text="bounded-source",
        purpose=purpose,
        required_categories=("CONTRACT_STRUCTURE",),
    )


@pytest.mark.parametrize('purpose', ['ANALYZE', 'SEMANTIC_COMPLETENESS_CORRECTION', 'EVIDENCE_REPAIR', 'COMBINED_CORRECTION'])
@pytest.mark.parametrize('direct_text_endpoint', [False, True])
def test_direct_contract_text_reserves_output_for_complete_json_without_changing_frozen_legacy_policy(
    purpose, direct_text_endpoint,
):
    from dataclasses import replace
    captured=[]
    def handler(request):
        captured.append(json.loads(request.content))
        return httpx.Response(200,json={'choices':[{'finish_reason':'stop',
            'message':{'content':json.dumps(_provider_payload())}}]})
    gateway=OpenAiCompatibleModelGateway(Settings(model_base_url='https://provider.invalid/v1',
        model_api_key='synthetic-test-key',model_name='synthetic-model',
        semantic_structured_transport='OPENAI_COMPAT_STRUCTURED'),transport=httpx.MockTransport(handler))
    gateway.analyze_contract(replace(_operation(purpose=purpose),document_redaction_provenance=direct_text_endpoint))
    assert len(captured)==1
    wire=captured[0]
    assert wire['max_tokens']==4096 and wire['model']=='synthetic-model'
    assert wire['response_format']['type']=='json_schema'
    assert (wire.get('reasoning_effort')=='none') is direct_text_endpoint
    assert ('reasoning_effort' in wire) is direct_text_endpoint


def test_direct_contract_text_unsupported_reasoning_parameter_stops_without_loosening_limits():
    from dataclasses import replace
    calls=[]
    def handler(request):
        calls.append(request)
        return httpx.Response(400,json={'error':{'message':'unknown parameter reasoning_effort'}})
    gateway=OpenAiCompatibleModelGateway(Settings(model_base_url='https://provider.invalid/v1',
        model_api_key='synthetic-test-key',model_name='synthetic-model'),transport=httpx.MockTransport(handler))
    with pytest.raises(ModelGatewayFailure):
        gateway.analyze_contract(replace(_operation(),document_redaction_provenance=True))
    assert len(calls)==1


def test_openai_structured_semantic_uses_production_schema() -> None:
    observed: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        observed["url"] = str(request.url)
        observed["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "model-revision",
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {"content": json.dumps(_provider_payload())},
                    }
                ],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                },
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://provider.invalid/v1",
            model_api_key="test-key",
            model_name="configured-model",
            semantic_structured_transport="OPENAI_COMPAT_STRUCTURED",
        ),
        transport=httpx.MockTransport(handler),
    )

    completion = gateway.analyze_contract(_operation())

    response_format = observed["body"]["response_format"]
    assert observed["url"] == "https://provider.invalid/v1/chat/completions"
    assert response_format["type"] == "json_schema"
    assert response_format["json_schema"]["strict"] is True
    assert response_format["json_schema"]["schema"] == provider_compatible_json_schema(
        provider_contract_json_schema()
    )
    assert observed["body"]["max_tokens"] == 4096
    system_prompt = observed["body"]["messages"][0]["content"]
    assert "unavailableMaterialCategory (always present; null unless" in system_prompt
    assert "optional unavailableMaterialCategory" not in system_prompt
    assert completion.payload == _provider_payload()
    assert completion.semantic_contract == "CONTRACT_SEMANTIC_V1"
    assert completion.provider == "OPENAI_COMPATIBLE"
    assert completion.finish_reason == "STOP"
    assert completion.total_tokens == 30


def test_native_structured_semantic_normalizes_ollama_envelope() -> None:
    observed: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        observed["url"] = str(request.url)
        observed["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "model-revision",
                "message": {"role": "assistant", "content": json.dumps(_provider_payload())},
                "done": True,
                "done_reason": "stop",
                "prompt_eval_count": 12,
                "eval_count": 18,
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://provider.invalid/v1",
            model_api_key="test-key",
            model_name="configured-model",
            semantic_structured_transport="OLLAMA_NATIVE_STRUCTURED",
        ),
        transport=httpx.MockTransport(handler),
    )

    completion = gateway.analyze_contract(_operation())

    assert observed["url"] == "https://provider.invalid/api/chat"
    assert observed["body"]["format"] == provider_compatible_json_schema(
        provider_contract_json_schema()
    )
    assert observed["body"]["stream"] is False
    assert observed["body"]["options"] == {"temperature": 0, "num_predict": 4096}
    assert "response_format" not in observed["body"]
    assert completion.payload == _provider_payload()
    assert completion.semantic_contract == "CONTRACT_SEMANTIC_V1"
    assert completion.provider == "OLLAMA_NATIVE"
    assert completion.finish_reason == "STOP"
    assert completion.prompt_tokens == 12
    assert completion.completion_tokens == 18
    assert completion.total_tokens == 30


def test_prompt_only_remains_the_default_compatibility_path() -> None:
    observed: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        observed["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "model-revision",
                "choices": [{"message": {"content": json.dumps(_provider_payload())}}],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://provider.invalid/v1",
            model_api_key="test-key",
            model_name="configured-model",
        ),
        transport=httpx.MockTransport(handler),
    )

    completion = gateway.analyze_contract(_operation())

    assert observed["body"]["response_format"] == {"type": "json_object"}
    assert completion.semantic_contract is None


def test_structured_correction_uses_the_exact_correction_schema() -> None:
    observed: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        observed["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "model-revision",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps({"missingCategoryHints": []})
                        }
                    }
                ],
            },
        )

    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://provider.invalid/v1",
            model_api_key="test-key",
            model_name="configured-model",
            semantic_structured_transport="OPENAI_COMPAT_STRUCTURED",
        ),
        transport=httpx.MockTransport(handler),
    )

    completion = gateway.analyze_contract(
        _operation(purpose="SEMANTIC_COMPLETENESS_CORRECTION")
    )

    assert observed["body"]["response_format"]["json_schema"]["schema"] == (
        provider_compatible_json_schema(
            contract_correction_json_schema("SEMANTIC_COMPLETENESS_CORRECTION")
        )
    )
    assert completion.semantic_contract is None


def test_native_invalid_envelope_fails_with_a_safe_reason() -> None:
    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://provider.invalid/v1",
            model_api_key="test-key",
            model_name="configured-model",
            semantic_structured_transport="OLLAMA_NATIVE_STRUCTURED",
        ),
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json={"model": "model-revision", "done": True})
        ),
    )

    with pytest.raises(ModelGatewayFailure) as captured:
        gateway.analyze_contract(_operation())

    assert captured.value.reason == "INVALID_PROVIDER_RESPONSE"
    assert captured.value.retryable is True


def test_native_incomplete_envelope_is_not_accepted_as_a_completion() -> None:
    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://provider.invalid/v1",
            model_api_key="test-key",
            model_name="configured-model",
            semantic_structured_transport="OLLAMA_NATIVE_STRUCTURED",
        ),
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200,
                json={
                    "model": "model-revision",
                    "message": {
                        "role": "assistant",
                        "content": json.dumps(_provider_payload()),
                    },
                    "done": False,
                },
            )
        ),
    )

    with pytest.raises(ModelGatewayFailure) as captured:
        gateway.analyze_contract(_operation())

    assert captured.value.reason == "INVALID_PROVIDER_RESPONSE"


@pytest.mark.parametrize(
    ("status", "reason", "retryable"),
    [
        (400, "PROVIDER_400", False),
        (401, "PROVIDER_401", False),
        (403, "PROVIDER_403", False),
        (404, "PROVIDER_404", False),
        (405, "PROVIDER_405", False),
        (409, "PROVIDER_409", False),
        (413, "PROVIDER_413", False),
        (415, "PROVIDER_415", False),
        (422, "PROVIDER_422", False),
        (418, "PROVIDER_4XX_OTHER", False),
        (429, "PROVIDER_429", True),
        (503, "PROVIDER_5XX", True),
    ],
)
def test_structured_http_failures_keep_finite_safe_reasons(
    status: int, reason: str, retryable: bool
) -> None:
    gateway = OpenAiCompatibleModelGateway(
        Settings(
            model_base_url="https://provider.invalid/v1",
            model_api_key="test-key",
            model_name="configured-model",
            semantic_structured_transport="OPENAI_COMPAT_STRUCTURED",
        ),
        transport=httpx.MockTransport(
            lambda _: httpx.Response(status, text="provider body must not escape")
        ),
    )

    with pytest.raises(ModelGatewayFailure) as captured:
        gateway.analyze_contract(_operation())

    assert captured.value.reason == reason
    assert captured.value.retryable is retryable


def test_semantic_transport_configuration_fails_closed() -> None:
    with pytest.raises(ValueError, match="semantic_structured_transport"):
        Settings(semantic_structured_transport="UNSAFE_FALLBACK")


def test_structured_path_allows_only_one_total_retry() -> None:
    class Gateway:
        def __init__(self) -> None:
            self.calls = 0

        def analyze_contract(self, _operation: ContractTextOperation) -> ModelCompletion:
            self.calls += 1
            if self.calls == 1:
                raise ModelGatewayFailure(
                    reason="READ_TIMEOUT", retryable=True, duration_ms=1
                )
            if self.calls == 2:
                return ModelCompletion(
                    payload={"summary": "summary"},
                    model="model-revision",
                    duration_ms=1,
                    semantic_contract="CONTRACT_SEMANTIC_V1",
                )
            raise AssertionError("structured path attempted a third provider call")

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = Gateway()
    settings = Settings(
        model_name="configured-model",
        semantic_structured_transport="OPENAI_COMPAT_STRUCTURED",
    )
    request = ContractAnalysisRequest.model_validate(
        {
            "projectId": "project-1",
            "materials": [
                {
                    "fileId": "file-1",
                    "category": "CONTRACT_BODY",
                    "text": "contract body",
                }
            ],
        }
    )

    with pytest.raises(AuditAiError) as captured:
        ModelApplicationService(settings, gateway).analyze_contract(
            request, "request-1"
        )

    assert captured.value.code == "MODEL_INVALID_RESULT"
    assert gateway.calls == 2


def test_structured_semantic_retry_disables_a_following_transport_retry() -> None:
    class Gateway:
        def __init__(self) -> None:
            self.calls = 0

        def analyze_contract(self, _operation: ContractTextOperation) -> ModelCompletion:
            self.calls += 1
            if self.calls == 1:
                return ModelCompletion(
                    payload={"summary": "missing required hints"},
                    model="model-revision",
                    duration_ms=1,
                    semantic_contract="CONTRACT_SEMANTIC_V1",
                )
            if self.calls == 2:
                raise ModelGatewayFailure(
                    reason="READ_TIMEOUT", retryable=True, duration_ms=1
                )
            raise AssertionError("structured path attempted a third provider call")

        def analyze_budget(self, _operation: BudgetSemanticOperation) -> ModelCompletion:
            raise AssertionError("budget operation was not expected")

    gateway = Gateway()
    request = ContractAnalysisRequest.model_validate(
        {
            "projectId": "project-1",
            "materials": [
                {
                    "fileId": "file-1",
                    "category": "CONTRACT_BODY",
                    "text": "contract body",
                }
            ],
        }
    )

    with pytest.raises(AuditAiError) as captured:
        ModelApplicationService(
            Settings(
                model_name="configured-model",
                semantic_structured_transport="OPENAI_COMPAT_STRUCTURED",
            ),
            gateway,
        ).analyze_contract(request, "request-1")

    assert captured.value.code == "MODEL_GATEWAY_ERROR"
    assert gateway.calls == 2
