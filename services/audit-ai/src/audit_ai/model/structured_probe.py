"""Aggregate-only no-write probe for Semantic provider protocol qualification."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import Enum
import json
import math
import time
from typing import Any, Literal, Mapping

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from audit_ai.model.contract_draft import (
    ProviderContractDraft,
    ProviderDraftValidationFailure,
    parse_provider_contract_draft,
    provider_contract_json_schema,
)
from audit_ai.model.gateway import (
    provider_compatible_json_schema,
    provider_http_failure_reason,
)


_STRICT = ConfigDict(extra="forbid", populate_by_name=True, strict=True)


class ProbeTransport(str, Enum):
    PROMPT_ONLY = "PROMPT_ONLY"
    OPENAI_COMPAT_STRUCTURED = "OPENAI_COMPAT_STRUCTURED"
    OLLAMA_NATIVE_STRUCTURED = "OLLAMA_NATIVE_STRUCTURED"


class ProbeLevel(str, Enum):
    LEVEL_1 = "LEVEL_1"
    LEVEL_2 = "LEVEL_2"
    LEVEL_3 = "LEVEL_3"
    LEVEL_4 = "LEVEL_4"


class _LevelOne(BaseModel):
    model_config = _STRICT
    category: Literal["CONTRACT_STRUCTURE", "OTHER_TEXT"]


class _LevelTwoItem(BaseModel):
    model_config = _STRICT
    status: Literal["READY", "INSUFFICIENT"]
    note: str | None
    evidence_ids: list[str] = Field(alias="evidenceIds")


class _LevelTwo(BaseModel):
    model_config = _STRICT
    items: list[_LevelTwoItem]


class _LevelThreeHint(BaseModel):
    model_config = _STRICT
    category: Literal["CONTRACT_STRUCTURE", "TERM_TERMINATION", "OTHER_TEXT"]
    attention: Literal["INFO", "REVIEW", "INSUFFICIENT"]
    problem_evidence_ids: list[str] = Field(alias="problemEvidenceIds")
    comparison_evidence_ids: list[str] = Field(alias="comparisonEvidenceIds")


class _LevelThree(BaseModel):
    model_config = _STRICT
    hints: list[_LevelThreeHint]


_LEVEL_MODELS: dict[ProbeLevel, type[BaseModel]] = {
    ProbeLevel.LEVEL_1: _LevelOne,
    ProbeLevel.LEVEL_2: _LevelTwo,
    ProbeLevel.LEVEL_3: _LevelThree,
    ProbeLevel.LEVEL_4: ProviderContractDraft,
}
_LEVEL_CATEGORY_SETS: dict[ProbeLevel, frozenset[str] | None] = {
    ProbeLevel.LEVEL_1: frozenset({"CONTRACT_STRUCTURE", "OTHER_TEXT"}),
    ProbeLevel.LEVEL_2: frozenset(),
    ProbeLevel.LEVEL_3: frozenset(
        {"CONTRACT_STRUCTURE", "TERM_TERMINATION", "OTHER_TEXT"}
    ),
    ProbeLevel.LEVEL_4: None,
}


@dataclass(frozen=True, slots=True)
class ProbeFixture:
    source: str
    allowed_evidence_ids: frozenset[str]
    required_categories: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class ProbeRequest:
    endpoint: str
    body: dict[str, Any]
    messages: tuple[dict[str, str], ...]


@dataclass(frozen=True, slots=True)
class ProbeObservation:
    transport_success: bool
    provider_http_success: bool
    json_parse_valid: bool
    schema_valid: bool
    completeness_valid: bool
    evidence_id_valid: bool
    business_validator_valid: bool
    repair_used: bool
    provider_call_count: int
    duration_ms: int
    prompt_tokens: int | None
    completion_tokens: int | None
    total_tokens: int | None
    finish_reason: str | None
    failure_stage: str | None
    failure_reason_code: str | None

    def as_safe_dict(self) -> dict[str, Any]:
        return asdict(self)


def aggregate_observations(
    observations: list[ProbeObservation],
) -> dict[str, Any]:
    """Return only finite counters/rates and safe reasons, never provider content."""

    total = len(observations)
    durations = sorted(item.duration_ms for item in observations)

    def passed(name: str) -> int:
        return sum(1 for item in observations if bool(getattr(item, name)))

    def rate(count: int) -> float:
        return count / total if total else 0.0

    failures: dict[str, int] = {}
    for item in observations:
        if item.failure_reason_code is not None:
            key = f"{item.failure_stage or 'UNKNOWN'}:{item.failure_reason_code}"
            failures[key] = failures.get(key, 0) + 1
    p95_index = max(0, math.ceil(total * 0.95) - 1) if total else 0
    counters = {
        name: passed(name)
        for name in (
            "transport_success",
            "provider_http_success",
            "json_parse_valid",
            "schema_valid",
            "completeness_valid",
            "evidence_id_valid",
            "business_validator_valid",
        )
    }
    repair_count = passed("repair_used")
    return {
        "total": total,
        "pass": counters,
        "rates": {name: rate(value) for name, value in counters.items()},
        "repairCount": repair_count,
        "repairRate": rate(repair_count),
        "providerCallCount": sum(item.provider_call_count for item in observations),
        "avgDurationMs": (
            round(sum(durations) / total, 2) if total else 0.0
        ),
        "p95DurationMs": durations[p95_index] if durations else 0,
        "failureCounts": dict(sorted(failures.items())),
    }


def probe_schema(level: ProbeLevel) -> dict[str, Any]:
    if level is ProbeLevel.LEVEL_4:
        authority = provider_contract_json_schema()
    else:
        authority = _LEVEL_MODELS[level].model_json_schema(by_alias=True)
    return provider_compatible_json_schema(authority)


def build_request(
    transport: ProbeTransport,
    level: ProbeLevel,
    fixture: ProbeFixture,
    model: str,
) -> ProbeRequest:
    schema = probe_schema(level)
    required_categories = _level_required_categories(level, fixture)
    system = (
        "Return exactly one JSON object matching this JSON Schema. Use only supplied "
        "evidence IDs. Do not return markdown or provider metadata. schema="
        + json.dumps(schema, ensure_ascii=False, separators=(",", ":"))
    )
    user = (
        f"level={level.value}\n"
        f"requiredCategories={','.join(required_categories)}\n"
        f"source:\n{fixture.source}"
    )
    messages = (
        {"role": "system", "content": system},
        {"role": "user", "content": user},
    )
    if transport is ProbeTransport.OLLAMA_NATIVE_STRUCTURED:
        return ProbeRequest(
            endpoint="/api/chat",
            body={
                "model": model,
                "messages": list(messages),
                "stream": False,
                "format": schema,
                "options": {"temperature": 0, "num_predict": 4096},
            },
            messages=messages,
        )
    response_format: dict[str, Any]
    if transport is ProbeTransport.PROMPT_ONLY:
        response_format = {"type": "json_object"}
    else:
        response_format = {
            "type": "json_schema",
            "json_schema": {
                "name": f"semantic_probe_{level.value.lower()}",
                "strict": True,
                "schema": schema,
            },
        }
    return ProbeRequest(
        endpoint="/v1/chat/completions",
        body={
            "model": model,
            "messages": list(messages),
            "temperature": 0,
            "stream": False,
            "max_tokens": 4096,
            "response_format": response_format,
        },
        messages=messages,
    )


def run_probe_once(
    *,
    base_url: str,
    api_key: str,
    model: str,
    transport: ProbeTransport,
    level: ProbeLevel,
    fixture: ProbeFixture,
    client: httpx.Client,
) -> ProbeObservation:
    request = build_request(transport, level, fixture, model)
    started = time.perf_counter_ns()
    try:
        response = client.post(
            f"{base_url.rstrip('/')}{request.endpoint}",
            headers={"Authorization": f"Bearer {api_key}"},
            json=request.body,
        )
    except (httpx.ConnectTimeout, httpx.ReadTimeout):
        reason = "READ_TIMEOUT"
        return _failure(started, "PROVIDER_TRANSPORT", reason)
    except httpx.HTTPError:
        return _failure(started, "PROVIDER_TRANSPORT", "CONNECT_ERROR")
    if response.status_code >= 400:
        reason, _ = provider_http_failure_reason(response.status_code)
        return _failure(
            started,
            "PROVIDER_TRANSPORT",
            reason,
            transport_success=True,
        )
    try:
        envelope = response.json()
        content, finish_reason, usage = _content(envelope, transport)
    except (ValueError, TypeError, KeyError, IndexError):
        return _failure(
            started,
            "PROVIDER_TRANSPORT",
            "INVALID_PROVIDER_RESPONSE",
            transport_success=True,
            provider_http_success=True,
        )
    try:
        payload = json.loads(content)
    except (ValueError, TypeError):
        return _failure(
            started,
            "JSON_PARSE",
            "INVALID_PROVIDER_JSON",
            transport_success=True,
            provider_http_success=True,
        )
    try:
        validated = _validate(level, payload)
    except ProviderDraftValidationFailure as failure:
        return _failure(
            started,
            "TYPED_SCHEMA",
            failure.reason.value,
            transport_success=True,
            provider_http_success=True,
            json_parse_valid=True,
            finish_reason=finish_reason,
            usage=usage,
        )
    except ValidationError:
        return _failure(
            started,
            "TYPED_SCHEMA",
            "INVALID_TYPED_SCHEMA",
            transport_success=True,
            provider_http_success=True,
            json_parse_valid=True,
            finish_reason=finish_reason,
            usage=usage,
        )
    completeness, evidence, business, stage, reason = _semantic_validators(
        level, validated, fixture
    )
    return ProbeObservation(
        transport_success=True,
        provider_http_success=True,
        json_parse_valid=True,
        schema_valid=True,
        completeness_valid=completeness,
        evidence_id_valid=evidence,
        business_validator_valid=business,
        repair_used=False,
        provider_call_count=1,
        duration_ms=_duration(started),
        prompt_tokens=usage[0],
        completion_tokens=usage[1],
        total_tokens=usage[2],
        finish_reason=finish_reason,
        failure_stage=stage,
        failure_reason_code=reason,
    )


def _validate(level: ProbeLevel, payload: Any) -> BaseModel:
    if level is ProbeLevel.LEVEL_4:
        if not isinstance(payload, Mapping):
            raise ValidationError.from_exception_data("ProviderContractDraft", [])
        return parse_provider_contract_draft(payload)
    return _LEVEL_MODELS[level].model_validate(payload)


def _semantic_validators(
    level: ProbeLevel,
    validated: BaseModel,
    fixture: ProbeFixture,
) -> tuple[bool, bool, bool, str | None, str | None]:
    required_categories = _level_required_categories(level, fixture)
    if level is ProbeLevel.LEVEL_1:
        completeness = not required_categories or (
            getattr(validated, "category") in required_categories
        )
        return completeness, True, completeness, (
            None if completeness else "COMPLETENESS"
        ), (None if completeness else "REQUIRED_CATEGORY_MISSING")
    dump = validated.model_dump(by_alias=True)
    if level is ProbeLevel.LEVEL_2:
        evidence_ids = [
            evidence_id
            for item in dump["items"]
            for evidence_id in item["evidenceIds"]
        ]
        evidence = set(evidence_ids).issubset(fixture.allowed_evidence_ids)
        return True, evidence, evidence, None if evidence else "EVIDENCE_REFERENCE", (
            None if evidence else "UNKNOWN_EVIDENCE_ID"
        )
    hints = dump["hints"]
    categories = {hint["category"] for hint in hints}
    completeness = set(required_categories).issubset(categories)
    evidence_ids = [
        evidence_id
        for hint in hints
        for key in ("problemEvidenceIds", "comparisonEvidenceIds")
        for evidence_id in hint[key]
    ]
    evidence = set(evidence_ids).issubset(fixture.allowed_evidence_ids)
    attention_valid = all(
        (hint["attention"] == "INFO" and not hint["problemEvidenceIds"]
            and bool(hint["comparisonEvidenceIds"]))
        or (hint["attention"] == "REVIEW" and bool(hint["problemEvidenceIds"]))
        or hint["attention"] == "INSUFFICIENT"
        for hint in hints
    )
    business = completeness and evidence and attention_valid
    if not completeness:
        return False, evidence, False, "COMPLETENESS", "REQUIRED_CATEGORY_MISSING"
    if not evidence:
        return True, False, False, "EVIDENCE_REFERENCE", "UNKNOWN_EVIDENCE_ID"
    if not attention_valid:
        return True, True, False, "BUSINESS_VALIDATION", "POLICY_REJECTED"
    return True, True, business, None, None


def _level_required_categories(
    level: ProbeLevel, fixture: ProbeFixture
) -> tuple[str, ...]:
    allowed = _LEVEL_CATEGORY_SETS[level]
    if allowed is None:
        return fixture.required_categories
    return tuple(
        category for category in fixture.required_categories if category in allowed
    )


def _content(
    body: Any, transport: ProbeTransport
) -> tuple[str, str | None, tuple[int | None, int | None, int | None]]:
    if not isinstance(body, Mapping):
        raise ValueError("invalid provider body")
    if transport is ProbeTransport.OLLAMA_NATIVE_STRUCTURED:
        message = body["message"]
        content = message["content"]
        prompt = _count(body.get("prompt_eval_count"))
        completion = _count(body.get("eval_count"))
        total = prompt + completion if prompt is not None and completion is not None else None
        finish = _finish(body.get("done_reason"))
    else:
        choice = body["choices"][0]
        content = choice["message"]["content"]
        usage = body.get("usage") if isinstance(body.get("usage"), Mapping) else {}
        prompt = _count(usage.get("prompt_tokens"))
        completion = _count(usage.get("completion_tokens"))
        total = _count(usage.get("total_tokens"))
        finish = _finish(choice.get("finish_reason"))
    if not isinstance(content, str):
        raise ValueError("invalid provider content")
    return content, finish, (prompt, completion, total)


def _failure(
    started: int,
    stage: str,
    reason: str,
    *,
    transport_success: bool = False,
    provider_http_success: bool = False,
    json_parse_valid: bool = False,
    finish_reason: str | None = None,
    usage: tuple[int | None, int | None, int | None] = (None, None, None),
) -> ProbeObservation:
    return ProbeObservation(
        transport_success=transport_success,
        provider_http_success=provider_http_success,
        json_parse_valid=json_parse_valid,
        schema_valid=False,
        completeness_valid=False,
        evidence_id_valid=False,
        business_validator_valid=False,
        repair_used=False,
        provider_call_count=1,
        duration_ms=_duration(started),
        prompt_tokens=usage[0],
        completion_tokens=usage[1],
        total_tokens=usage[2],
        finish_reason=finish_reason,
        failure_stage=stage,
        failure_reason_code=reason,
    )


def _duration(started: int) -> int:
    return max(0, (time.perf_counter_ns() - started) // 1_000_000)


def _count(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else None


def _finish(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        return "UNKNOWN"
    normalized = value.strip().upper()
    return normalized if normalized in {"STOP", "LENGTH", "CONTENT_FILTER", "TOOL_CALLS"} else "UNKNOWN"
