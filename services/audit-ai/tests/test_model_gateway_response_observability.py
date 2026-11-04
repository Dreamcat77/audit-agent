"""Provider metadata only: fake HTTP; no content or inferred token logging."""
import logging

import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.logging import log_event
from audit_ai.model.gateway import (
    ContractTextOperation, ModelGatewayFailure, OpenAiCompatibleModelGateway,
)


def observe_length(caplog, message, usage=None, *, native=False):
    caplog.set_level(logging.INFO, logger="audit_ai")
    body = {"choices": [{"finish_reason": "length", "message": message}],
            "usage": usage or {"completion_tokens": 6144}}
    if native:
        body = {"done": True, "done_reason": "length", "message": message,
                "eval_count": 6144, "usage": usage or {}}
    calls = []
    def provider(request):
        calls.append(request)
        return httpx.Response(200, json=body)
    gateway = OpenAiCompatibleModelGateway(Settings(
        model_base_url="https://provider.invalid/v1", model_api_key="KEY_PRIVATE_CANARY",
        model_name="synthetic", semantic_structured_transport=(
            "OLLAMA_NATIVE_STRUCTURED" if native else "OPENAI_COMPAT_STRUCTURED")),
        transport=httpx.MockTransport(provider))
    with pytest.raises(ModelGatewayFailure) as caught:
        gateway.analyze_contract(ContractTextOperation(project_id="project-safe", chunk_index=1,
            chunk_count=8, source_text="PROMPT_PRIVATE_CANARY", request_id="request-safe"))
    assert caught.value.reason == "OUTPUT_TOKEN_LIMIT"
    assert caught.value.finish_reason == "LENGTH"
    assert caught.value.completion_tokens == 6144
    assert caught.value.retryable is False
    assert len(calls) == 1
    records = [r.audit_fields for r in caplog.records if r.getMessage().startswith("PROVIDER_ENVELOPE ")]
    assert len(records) == 1
    assert caplog.records.index(next(r for r in caplog.records if r.getMessage().startswith("PROVIDER_ENVELOPE "))) < caplog.records.index(
        next(r for r in caplog.records if r.getMessage().startswith("AI_HTTP_COMPLETED ")))
    for secret in ("CONTENT_PRIVATE_CANARY", "REASONING_PRIVATE_CANARY", "KEY_PRIVATE_CANARY",
                   "PROMPT_PRIVATE_CANARY", "UNKNOWN_FIELD_PRIVATE_CANARY"):
        assert secret not in caplog.text
    return records[0]


def test_length_logs_visible_and_reasoning_metadata_before_rejection(caplog):
    fields = observe_length(caplog, {"content": "中🙂", "reasoning_content": "REASONING_PRIVATE_CANARY",
        "UNKNOWN_FIELD_PRIVATE_CANARY": "CONTENT_PRIVATE_CANARY"},
        {"prompt_tokens": 4006, "completion_tokens": 6144, "reasoning_tokens": 6100,
         "completion_tokens_details": {"reasoning_tokens": 6099}})
    assert fields["provider_content_type"] == "STRING"
    assert fields["provider_content_char_count"] == 2
    assert fields["provider_reasoning_content_present"] == "YES"
    assert fields["provider_reasoning_content_chars"] == 24
    assert fields["provider_reasoning_tokens"] == 6100
    assert fields["provider_completion_reasoning_tokens"] == 6099


@pytest.mark.parametrize("message,kind,chars", [
    ({"content": ""}, "STRING", 0), ({}, "UNKNOWN", "UNKNOWN"),
    ({"content": None}, "NULL", "UNKNOWN"), ({"content": []}, "ARRAY", "UNKNOWN"),
    ({"content": {"secret": "CONTENT_PRIVATE_CANARY"}}, "OBJECT", "UNKNOWN"),
    ({"content": True}, "BOOLEAN", "UNKNOWN"), ({"content": 1}, "NUMBER", "UNKNOWN"),
    (None, "UNKNOWN", "UNKNOWN"), ([], "UNKNOWN", "UNKNOWN"),
])
def test_missing_or_nontext_content_is_not_reported_as_empty(caplog, message, kind, chars):
    fields = observe_length(caplog, message)
    assert fields["provider_content_type"] == kind
    assert fields["provider_content_char_count"] == chars
    assert fields["provider_reasoning_content_present"] == ("NO" if isinstance(message, dict) else "UNKNOWN")
    assert fields["provider_reasoning_content_chars"] == "UNKNOWN"
    assert fields["provider_reasoning_tokens"] == "UNKNOWN"
    assert fields["provider_completion_reasoning_tokens"] == "UNKNOWN"


@pytest.mark.parametrize("name", ["reasoning_content", "reasoning", "thinking"])
@pytest.mark.parametrize("value,chars", [(None, "UNKNOWN"), ([], "UNKNOWN"), ("", 0), ("中🙂", 2)])
def test_known_reasoning_fields_log_presence_and_only_string_lengths(caplog, name, value, chars):
    fields = observe_length(caplog, {"content": "", name: value})
    assert fields[f"provider_{name}_present"] == "YES"
    assert fields[f"provider_{name}_chars"] == chars


@pytest.mark.parametrize("value", [True, -1, 1.5, "6144", None, {}])
def test_reasoning_tokens_require_explicit_nonnegative_provider_integer(caplog, value):
    fields = observe_length(caplog, {"content": "", "reasoning_content": "REASONING_PRIVATE_CANARY"},
        {"completion_tokens": 6144, "reasoning_tokens": value,
         "completion_tokens_details": {"reasoning_tokens": value}})
    assert fields["provider_reasoning_tokens"] == "UNKNOWN"
    assert fields["provider_completion_reasoning_tokens"] == "UNKNOWN"


def test_native_thinking_and_explicit_usage_survive_observation_without_normalization_changes(caplog):
    fields = observe_length(caplog, {"content": "", "thinking": "中🙂"},
                            {"reasoning_tokens": 0}, native=True)
    assert fields["provider_thinking_present"] == "YES"
    assert fields["provider_thinking_chars"] == 2
    assert fields["provider_reasoning_tokens"] == 0
    assert fields["provider_completion_reasoning_tokens"] == "UNKNOWN"


@pytest.mark.parametrize("field,value", [
    ("provider_content_type", "CONTENT_PRIVATE_CANARY"),
    ("provider_content_char_count", True), ("provider_content_char_count", -1),
    ("provider_reasoning_content_present", "REASONING_PRIVATE_CANARY"),
    ("provider_reasoning_present", []), ("provider_content_type", {}),
    ("provider_reasoning_chars", 1.2), ("provider_reasoning_tokens", "6144"),
])
def test_new_metadata_logging_rejects_unapproved_types_or_values(field, value):
    with pytest.raises(ValueError):
        log_event(logging.getLogger("audit_ai.test"), "metadata-test", **{field: value})
