"""Strict, non-authoritative CONTRACT_FACT_CANDIDATES capability."""

from __future__ import annotations

import hashlib
import json
import logging
import re
import time
from decimal import Decimal
from urllib.parse import urlsplit, urlunsplit
from typing import Any, Literal

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator
from starlette.concurrency import run_in_threadpool

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.logging import log_event
from audit_ai.model.contract_fact_protocol import FACT_CONTEXT_CODES, FACT_PROMPT_VERSION
from audit_ai.model.gateway import (
    ContractFactOperation,
    ModelCompletion,
    ModelGatewayFailure,
    ModelGatewayPort,
    ModelNotConfigured,
)


_STRICT = ConfigDict(extra="forbid", populate_by_name=True)
_SHA256 = r"^[0-9a-f]{64}$"
_DECIMAL = r"^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$"
FactType = Literal[
    "CONTRACT_NO", "CONTRACT_NAME", "PARTY_A", "PARTY_B",
    "PARTY_B_CREDIT_CODE", "CONTRACT_AMOUNT", "AMOUNT_IN_WORDS",
    "LINE_ITEM_NAME", "LINE_ITEM_QUANTITY", "LINE_ITEM_UNIT_PRICE",
    "LINE_ITEM_DECLARED_AMOUNT", "AWARD_AMOUNT", "AWARD_SUPPLIER",
    "AWARD_SUPPLIER_CREDIT_CODE",
]
ContextCode = Literal[
    "CONTRACT_HEADER", "CONTRACT_PARTIES", "CONTRACT_AMOUNT",
    "CONTRACT_LINE_ITEM", "AWARD_FINAL_RESULT",
]
ContractFactScope = Literal["CORE", "LINE_ITEMS", "AWARD"]
_DECIMAL_FACTS = frozenset({
    "CONTRACT_AMOUNT", "LINE_ITEM_QUANTITY", "LINE_ITEM_UNIT_PRICE",
    "LINE_ITEM_DECLARED_AMOUNT", "AWARD_AMOUNT",
})
_LINE_FACTS = frozenset({
    "LINE_ITEM_NAME", "LINE_ITEM_QUANTITY", "LINE_ITEM_UNIT_PRICE",
    "LINE_ITEM_DECLARED_AMOUNT",
})
_EXPECTED_CONTEXT = FACT_CONTEXT_CODES
_SCOPE_FACTS = {
    "CORE": frozenset({
        "CONTRACT_NO", "CONTRACT_NAME", "PARTY_A", "PARTY_B",
        "PARTY_B_CREDIT_CODE", "CONTRACT_AMOUNT", "AMOUNT_IN_WORDS",
    }),
    "LINE_ITEMS": _LINE_FACTS,
    "AWARD": frozenset({
        "AWARD_AMOUNT", "AWARD_SUPPLIER", "AWARD_SUPPLIER_CREDIT_CODE",
    }),
}

LOGGER = logging.getLogger("audit_ai.model.contract_fact")


def _provider_identity(base_url: str | None) -> str:
    if base_url is None or not base_url.strip():
        raise ValueError("model provider endpoint identity is unavailable")
    parsed = urlsplit(base_url.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("model provider endpoint identity is invalid")
    authority = parsed.hostname.lower()
    if parsed.port is not None:
        authority = f"{authority}:{parsed.port}"
    normalized = urlunsplit((parsed.scheme.lower(), authority, parsed.path.rstrip("/"), "", ""))
    return "OPENAI_COMPATIBLE_ENDPOINT_SHA256:" + hashlib.sha256(
        normalized.encode("utf-8")
    ).hexdigest()


class ContractFactSegment(BaseModel):
    model_config = _STRICT
    segment_id: str = Field(alias="segmentId", pattern=r"^[A-Za-z0-9._:-]{1,64}$")
    source_locator: str = Field(alias="sourceLocator", min_length=1, max_length=300)
    text_sha256: str = Field(alias="textSha256", pattern=_SHA256)
    start_offset: int = Field(alias="startOffset", ge=0)
    end_offset: int = Field(alias="endOffset", gt=0)
    text: str = Field(min_length=1, max_length=4000, repr=False)

    @model_validator(mode="after")
    def verify_server_segment(self) -> "ContractFactSegment":
        if self.end_offset - self.start_offset != len(self.text):
            raise ValueError("segment offsets do not match text")
        digest = hashlib.sha256(self.text.encode("utf-8")).hexdigest()
        if digest != self.text_sha256:
            raise ValueError("segment text hash does not match")
        return self


class ContractFactRequest(BaseModel):
    model_config = _STRICT
    project_id: str = Field(alias="projectId", min_length=36, max_length=36)
    file_id: str = Field(alias="fileId", min_length=36, max_length=36)
    file_sha256: str = Field(alias="fileSha256", pattern=_SHA256)
    parse_result_id: str = Field(alias="parseResultId", min_length=36, max_length=36)
    parse_version: int = Field(alias="parseVersion", ge=1)
    canonical_text_sha256: str = Field(alias="canonicalTextSha256", pattern=_SHA256)
    segments: list[ContractFactSegment] = Field(min_length=1, max_length=32)

    @model_validator(mode="after")
    def unique_segments(self) -> "ContractFactRequest":
        ids = [segment.segment_id for segment in self.segments]
        if len(ids) != len(set(ids)):
            raise ValueError("segment IDs must be unique")
        return self


class ProviderContractFactCandidate(BaseModel):
    model_config = _STRICT
    fact_type: FactType = Field(alias="factType")
    group_key: str | None = Field(default=None, alias="groupKey", pattern=r"^[A-Za-z0-9._:-]{1,80}$")
    value_text: str | None = Field(default=None, alias="valueText", min_length=1, max_length=500)
    value_decimal: str | None = Field(default=None, alias="valueDecimal", pattern=_DECIMAL)
    segment_id: str = Field(alias="segmentId", min_length=1, max_length=64)
    source_locator: str = Field(alias="sourceLocator", min_length=1, max_length=300)
    excerpt: str = Field(min_length=1, max_length=1000, repr=False)
    excerpt_start: int = Field(alias="excerptStart", ge=0)
    excerpt_end: int = Field(alias="excerptEnd", gt=0)
    context_code: ContextCode = Field(alias="contextCode")
    confidence: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def coherent_typed_candidate(self) -> "ProviderContractFactCandidate":
        decimal_fact = self.fact_type in _DECIMAL_FACTS
        if decimal_fact != (self.value_decimal is not None) or decimal_fact == (self.value_text is not None):
            raise ValueError("candidate typed value is incoherent")
        if (self.fact_type in _LINE_FACTS) != (self.group_key is not None):
            raise ValueError("line group key is incoherent")
        if _EXPECTED_CONTEXT[self.fact_type] != self.context_code:
            raise ValueError("candidate context is incoherent")
        if self.excerpt_end <= self.excerpt_start:
            raise ValueError("candidate excerpt offsets are invalid")
        return self


class ProviderContractFactResult(BaseModel):
    model_config = _STRICT
    candidates: list[ProviderContractFactCandidate] = Field(max_length=100)


class RawProviderContractFactResult(BaseModel):
    model_config = _STRICT
    candidates: list[Any] = Field(max_length=100)


class ContractFactCandidate(ProviderContractFactCandidate):
    candidate_key: str = Field(alias="candidateKey", pattern=r"^cfc-v1-[0-9a-f]{64}$")


class ContractFactResult(BaseModel):
    model_config = _STRICT
    candidates: list[ContractFactCandidate]
    schema_version: str = Field(alias="schemaVersion")
    prompt_version: str = Field(alias="promptVersion")
    model_provider: str = Field(alias="modelProvider")
    model_name: str = Field(alias="modelName")
    model_revision: str = Field(alias="modelRevision")
    duration_ms: int = Field(alias="durationMs", ge=0)


class ContractFactEnvelope(BaseModel):
    code: int
    message: str
    data: ContractFactResult


class ContractFactEvidence(BaseModel):
    model_config = _STRICT
    evidence_id: str = Field(alias="evidenceId", pattern=r"^[A-Za-z0-9._:-]{1,100}$")
    source_locator: str = Field(alias="sourceLocator", min_length=1, max_length=300)
    text_sha256: str = Field(alias="textSha256", pattern=_SHA256)
    start_offset: int = Field(alias="startOffset", ge=0)
    end_offset: int = Field(alias="endOffset", gt=0)
    text: str = Field(min_length=1, max_length=240, repr=False)
    row_identity: str | None = Field(default=None, alias="rowIdentity", pattern=r"^row:[0-9a-f]{64}$")
    line_item_columns: list[Literal["rowId", "name", "quantity", "unitPrice", "declaredAmount"]] | None = Field(
        default=None, alias="lineItemColumns", min_length=4, max_length=5,
    )

    @model_validator(mode="after")
    def verify_server_evidence(self) -> "ContractFactEvidence":
        if self.end_offset - self.start_offset != len(self.text):
            raise ValueError("evidence offsets do not match text")
        if hashlib.sha256(self.text.encode("utf-8")).hexdigest() != self.text_sha256:
            raise ValueError("evidence text hash does not match")
        if self.line_item_columns is not None:
            required = {"name", "quantity", "unitPrice", "declaredAmount"}
            if (len(set(self.line_item_columns)) != len(self.line_item_columns)
                    or not required.issubset(self.line_item_columns)):
                raise ValueError("line item columns must uniquely map all required fields")
            if self.row_identity is None:
                raise ValueError("mapped line item evidence requires a server row identity")
        return self


class ContractFactRequestV2(BaseModel):
    model_config = _STRICT
    project_id: str = Field(alias="projectId", min_length=36, max_length=36)
    file_id: str = Field(alias="fileId", min_length=36, max_length=36)
    file_sha256: str = Field(alias="fileSha256", pattern=_SHA256)
    parse_result_id: str = Field(alias="parseResultId", min_length=36, max_length=36)
    parse_version: int = Field(alias="parseVersion", ge=1)
    canonical_text_sha256: str = Field(alias="canonicalTextSha256", pattern=_SHA256)
    scope: ContractFactScope
    batch_ordinal: int = Field(default=1, alias="batchOrdinal", ge=1, le=128)
    batch_count: int = Field(default=1, alias="batchCount", ge=1, le=128)
    remaining_budget_ms: int = Field(default=900000, alias="remainingBudgetMs", ge=1)
    evidence_catalog: list[ContractFactEvidence] = Field(
        alias="evidenceCatalog", min_length=1, max_length=32
    )

    @model_validator(mode="after")
    def unique_evidence(self) -> "ContractFactRequestV2":
        if self.batch_ordinal > self.batch_count:
            raise ValueError("batch identity is incoherent")
        ids = [item.evidence_id for item in self.evidence_catalog]
        if len(ids) != len(set(ids)):
            raise ValueError("evidence IDs must be unique")
        return self


class ProviderContractFactCandidateV2(BaseModel):
    model_config = _STRICT
    fact_type: FactType = Field(alias="factType")
    group_key: str | None = Field(default=None, alias="groupKey", pattern=r"^[A-Za-z0-9._:-]{1,80}$")
    value_text: str | None = Field(default=None, alias="valueText", min_length=1, max_length=500)
    value_decimal: str | None = Field(default=None, alias="valueDecimal", pattern=_DECIMAL)
    evidence_id: str = Field(alias="evidenceId", min_length=1, max_length=100)
    context_code: ContextCode = Field(alias="contextCode")
    confidence: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def coherent_typed_candidate(self) -> "ProviderContractFactCandidateV2":
        decimal_fact = self.fact_type in _DECIMAL_FACTS
        if decimal_fact != (self.value_decimal is not None) or decimal_fact == (self.value_text is not None):
            raise ValueError("candidate typed value is incoherent")
        if (self.fact_type in _LINE_FACTS) != (self.group_key is not None):
            raise ValueError("line group key is incoherent")
        if _EXPECTED_CONTEXT[self.fact_type] != self.context_code:
            raise ValueError("candidate context is incoherent")
        return self


class ProviderCompactLineItemV2(BaseModel):
    """Provider-only compression; never a public or durable line-item schema."""

    model_config = _STRICT
    evidence_id: str = Field(alias="evidenceId", min_length=1, max_length=100)
    group_key: str | None = Field(default=None, alias="groupKey", pattern=r"^[A-Za-z0-9._:-]{1,80}$")
    name: str = Field(min_length=1, max_length=500)
    quantity: str = Field(pattern=_DECIMAL)
    unit_price: str = Field(alias="unitPrice", pattern=_DECIMAL)
    declared_amount: str = Field(alias="declaredAmount", pattern=_DECIMAL)
    confidence: float = Field(ge=0, le=1, strict=True)

    @model_validator(mode="after")
    def nonblank_name(self) -> "ProviderCompactLineItemV2":
        if not self.name.strip():
            raise ValueError("compact line name must not be blank")
        return self


class RawProviderContractFactResultV2(BaseModel):
    model_config = _STRICT
    candidates: list[Any] = Field(max_length=100)
    line_items: list[Any] = Field(default_factory=list, alias="lineItems", max_length=100)

    @model_validator(mode="after")
    def bounded_normalized_candidates(self) -> "RawProviderContractFactResultV2":
        if len(self.candidates) + 4 * len(self.line_items) > 100:
            raise ValueError("expanded provider candidates exceed the existing wire bound")
        return self


class ContractFactCandidateV2(BaseModel):
    model_config = _STRICT
    candidate_key: str = Field(alias="candidateKey", pattern=r"^cfc-v2-[0-9a-f]{64}$")
    fact_type: FactType = Field(alias="factType")
    group_key: str | None = Field(default=None, alias="groupKey")
    value_text: str | None = Field(default=None, alias="valueText")
    value_decimal: str | None = Field(default=None, alias="valueDecimal")
    segment_id: str = Field(alias="segmentId")
    source_locator: str = Field(alias="sourceLocator")
    excerpt: str
    excerpt_start: int = Field(alias="excerptStart")
    excerpt_end: int = Field(alias="excerptEnd")
    context_code: ContextCode = Field(alias="contextCode")
    confidence: float


class ContractFactRejectionV2(BaseModel):
    model_config = _STRICT
    provider_ordinal: int = Field(alias="providerOrdinal", ge=1)
    stage: Literal["TYPED_SCHEMA", "SCOPE", "EVIDENCE", "IDENTITY"]
    reason_code: str = Field(alias="reasonCode", pattern=r"^[A-Z0-9_]{1,64}$")
    safe_shape_hash: str = Field(alias="safeShapeHash", pattern=_SHA256)


class ContractModelCallV2(BaseModel):
    model_config = _STRICT
    ordinal: int = Field(ge=1)
    phase: Literal["TRANSPORT", "REPAIR"]
    outcome: Literal["SUCCEEDED", "FAILED"]
    safe_reason: str | None = Field(default=None, alias="safeReason")
    duration_ms: int = Field(alias="durationMs", ge=0)
    finish_reason: str | None = Field(default=None, alias="finishReason")
    prompt_tokens: int | None = Field(default=None, alias="promptTokens", ge=0)
    completion_tokens: int | None = Field(default=None, alias="completionTokens", ge=0)
    total_tokens: int | None = Field(default=None, alias="totalTokens", ge=0)


class ContractFactResultV2(BaseModel):
    model_config = _STRICT
    candidates: list[ContractFactCandidateV2]
    rejections: list[ContractFactRejectionV2]
    scope: ContractFactScope
    batch_ordinal: int = Field(default=1, alias="batchOrdinal", ge=1, le=128)
    batch_count: int = Field(default=1, alias="batchCount", ge=1, le=128)
    batch_status: Literal["EMPTY", "PARTIAL", "COMPLETE", "FAILED", "UNKNOWN"] = Field(alias="batchStatus")
    model_calls: list[ContractModelCallV2] = Field(alias="modelCalls")
    schema_version: str = Field(alias="schemaVersion")
    prompt_version: str = Field(alias="promptVersion")
    model_provider: str = Field(alias="modelProvider")
    model_name: str = Field(alias="modelName")
    model_revision: str = Field(alias="modelRevision")
    duration_ms: int = Field(alias="durationMs", ge=0)


class ContractFactEnvelopeV2(BaseModel):
    code: int
    message: str
    data: ContractFactResultV2


class ContractFactService:
    def __init__(self, settings: Settings, gateway: ModelGatewayPort) -> None:
        self._settings = settings
        self._gateway = gateway

    def extract(self, body: ContractFactRequest, request_id: str) -> ContractFactEnvelope:
        if not self._settings.contract_fact_extraction_enabled:
            raise AuditAiError(
                503, "CONTRACT_FACT_EXTRACTION_DISABLED", "contract fact extraction is disabled"
            )
        source = json.dumps(
            [segment.model_dump(by_alias=True) for segment in body.segments],
            ensure_ascii=False, separators=(",", ":"),
        )
        failed_duration_ms = 0
        transport_attempt = 1
        transport_retry_reason: str | None = None
        try:
            while True:
                try:
                    completion = self._gateway.extract_contract_facts(ContractFactOperation(
                        project_id=body.project_id, file_id=body.file_id,
                        file_sha256=body.file_sha256, parse_result_id=body.parse_result_id,
                        parse_version=body.parse_version,
                        canonical_text_sha256=body.canonical_text_sha256,
                        source_text=source, request_id=request_id,
                        transport_attempt=transport_attempt, max_transport_attempts=2,
                        transport_retry_reason=transport_retry_reason,
                    ))
                    if failed_duration_ms:
                        completion = ModelCompletion(
                            payload=completion.payload, model=completion.model,
                            revision=completion.revision,
                            duration_ms=failed_duration_ms + completion.duration_ms,
                            finish_reason=completion.finish_reason,
                            prompt_tokens=completion.prompt_tokens,
                            completion_tokens=completion.completion_tokens,
                            total_tokens=completion.total_tokens,
                        )
                    break
                except ModelGatewayFailure as failure:
                    if (
                        transport_attempt == 1
                        and failure.retryable
                        and failure.reason == "INVALID_PROVIDER_JSON"
                    ):
                        failed_duration_ms += failure.duration_ms
                        transport_attempt = 2
                        transport_retry_reason = failure.reason
                        continue
                    raise
        except ModelNotConfigured as failure:
            raise AuditAiError(503, "CONTRACT_FACT_UNAVAILABLE", "contract fact extraction is unavailable") from failure
        except ModelGatewayFailure as failure:
            status = 504 if failure.reason in {"CONNECT_TIMEOUT", "READ_TIMEOUT"} else 503
            code = "CONTRACT_FACT_TIMEOUT" if status == 504 else "CONTRACT_FACT_UNAVAILABLE"
            raise AuditAiError(status, code, "contract fact extraction failed safely") from failure
        try:
            raw_provider = RawProviderContractFactResult.model_validate(completion.payload)
        except ValidationError as failure:
            self._log_schema_rejection(request_id, "REJECTED", failure.error_count())
            raise AuditAiError(502, "CONTRACT_FACT_INVALID_RESPONSE", "contract fact response is invalid") from failure
        valid_candidates: list[ProviderContractFactCandidate] = []
        validation_error_count = 0
        first_failure: ValidationError | None = None
        for raw_candidate in raw_provider.candidates:
            try:
                valid_candidates.append(
                    ProviderContractFactCandidate.model_validate(raw_candidate)
                )
            except ValidationError as failure:
                if first_failure is None:
                    first_failure = failure
                validation_error_count += failure.error_count()
        if validation_error_count:
            self._log_schema_rejection(
                request_id,
                "REJECTED",
                validation_error_count,
            )
            raise AuditAiError(502, "CONTRACT_FACT_INVALID_RESPONSE", "contract fact response is invalid") from first_failure
        provider = ProviderContractFactResult(candidates=valid_candidates)
        candidates: list[ContractFactCandidate] = []
        candidate_keys: set[str] = set()
        evidence_error_count = 0
        first_evidence_failure: ValueError | None = None
        for provider_candidate in provider.candidates:
            try:
                candidate = self._validate_and_key(
                    body, ProviderContractFactResult(candidates=[provider_candidate])
                )[0]
                if candidate.candidate_key in candidate_keys:
                    raise ValueError("candidate identity is duplicated")
                candidate_keys.add(candidate.candidate_key)
                candidates.append(candidate)
            except ValueError as failure:
                if first_evidence_failure is None:
                    first_evidence_failure = failure
                evidence_error_count += 1
        if evidence_error_count:
            self._log_evidence_rejection(
                request_id,
                "REJECTED",
                evidence_error_count,
            )
            raise AuditAiError(
                502, "CONTRACT_FACT_INVALID_RESPONSE", "contract fact response is invalid"
            ) from first_evidence_failure
        provider_identity = _provider_identity(self._settings.model_base_url)
        result = ContractFactResult(
            candidates=candidates,
            schemaVersion="contract-fact-candidates-v1",
            promptVersion="contract-fact-prompt-v1",
            modelProvider=provider_identity,
            modelName=completion.model,
            modelRevision=completion.revision or "NOT_DECLARED_BY_PROVIDER",
            durationMs=completion.duration_ms,
        )
        return ContractFactEnvelope(code=200, message="success", data=result)

    @staticmethod
    def _log_schema_rejection(request_id: str, outcome: str, error_count: int) -> None:
        log_event(
            LOGGER, "contract fact response rejected",
            diagnostic_schema_version=1,
            module="AUDIT_AI",
            layer="APPLICATION",
            request_id=request_id,
            operation="CONTRACT_FACT_CANDIDATES",
            stage="FACT_RESULT_VALIDATION",
            outcome=outcome,
            reason="INVALID_TYPED_SCHEMA",
            validation_error_class="ValidationError",
            validation_error_count=error_count,
        )

    @staticmethod
    def _log_evidence_rejection(request_id: str, outcome: str, error_count: int) -> None:
        log_event(
            LOGGER, "contract fact response rejected",
            diagnostic_schema_version=1,
            module="AUDIT_AI",
            layer="APPLICATION",
            request_id=request_id,
            operation="CONTRACT_FACT_CANDIDATES",
            stage="FACT_RESULT_VALIDATION",
            outcome=outcome,
            reason="INVALID_EVIDENCE_REF",
            validation_error_count=error_count,
        )

    @staticmethod
    def _validate_and_key(
        body: ContractFactRequest, provider: ProviderContractFactResult
    ) -> list[ContractFactCandidate]:
        segments = {segment.segment_id: segment for segment in body.segments}
        result: list[ContractFactCandidate] = []
        keys: set[str] = set()
        for candidate in provider.candidates:
            segment = segments.get(candidate.segment_id)
            if segment is None or candidate.source_locator != segment.source_locator:
                raise ValueError("candidate source identity is invalid")
            if candidate.excerpt_end > len(segment.text):
                raise ValueError("candidate excerpt is out of range")
            if segment.text[candidate.excerpt_start:candidate.excerpt_end] != candidate.excerpt:
                raise ValueError("candidate excerpt is not exact")
            payload = candidate.model_dump(by_alias=True)
            if candidate.fact_type in _LINE_FACTS:
                group_identity = json.dumps(
                    [segment.segment_id, candidate.group_key],
                    ensure_ascii=False, separators=(",", ":"),
                )
                payload["groupKey"] = "line:" + hashlib.sha256(
                    group_identity.encode("utf-8")
                ).hexdigest()
            identity = {
                "fileSha256": body.file_sha256,
                "parseResultId": body.parse_result_id,
                "parseVersion": body.parse_version,
                **payload,
            }
            encoded = json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            key = f"cfc-v1-{hashlib.sha256(encoded.encode('utf-8')).hexdigest()}"
            if key in keys:
                raise ValueError("candidate identity is duplicated")
            keys.add(key)
            result.append(ContractFactCandidate(
                **payload, candidateKey=key
            ))
        return result


class ContractFactServiceV2:
    def __init__(self, settings: Settings, gateway: ModelGatewayPort) -> None:
        self._settings = settings
        self._gateway = gateway

    def extract(self, body: ContractFactRequestV2, request_id: str) -> ContractFactEnvelopeV2:
        if not self._settings.contract_fact_extraction_enabled:
            raise AuditAiError(
                503, "CONTRACT_FACT_EXTRACTION_DISABLED", "contract fact extraction is disabled"
            )
        deadline = time.monotonic() + min(
            body.remaining_budget_ms / 1000,
            self._settings.model_total_budget_seconds or 900,
        )
        source = json.dumps(
            [{"evidenceId": item.evidence_id, "text": item.text,
              **({"rowIdentity": item.row_identity} if item.row_identity else {}),
              **({"lineItemColumns": item.line_item_columns} if item.line_item_columns is not None else {})}
             for item in body.evidence_catalog],
            ensure_ascii=False, separators=(",", ":"),
        )
        calls: list[ContractModelCallV2] = []
        transport_attempt = 1
        transport_retry_reason: str | None = None
        while True:
            try:
                completion = self._gateway.extract_contract_facts(ContractFactOperation(
                    project_id=body.project_id,
                    file_id=body.file_id,
                    file_sha256=body.file_sha256,
                    parse_result_id=body.parse_result_id,
                    parse_version=body.parse_version,
                    canonical_text_sha256=body.canonical_text_sha256,
                    source_text=source,
                    request_id=request_id,
                    transport_attempt=transport_attempt,
                    max_transport_attempts=2,
                    transport_retry_reason=transport_retry_reason,
                    scope=body.scope,
                    protocol_version=2,
                    batch_ordinal=body.batch_ordinal,
                    batch_count=body.batch_count,
                    provider_timeout_seconds=self._remaining_seconds(deadline),
                ))
                calls.append(self._success_call(transport_attempt, completion))
                break
            except ModelNotConfigured as failure:
                raise AuditAiError(
                    503, "CONTRACT_FACT_UNAVAILABLE", "contract fact extraction is unavailable"
                ) from failure
            except ModelGatewayFailure as failure:
                calls.append(ContractModelCallV2(
                    ordinal=transport_attempt,
                    phase="TRANSPORT" if transport_attempt == 1 else "REPAIR",
                    outcome="FAILED",
                    safeReason=failure.reason,
                    durationMs=failure.duration_ms,
                    finishReason=failure.finish_reason,
                    promptTokens=failure.prompt_tokens,
                    completionTokens=failure.completion_tokens,
                    totalTokens=failure.total_tokens,
                ))
                if (
                    transport_attempt == 1
                    and failure.retryable
                    and failure.reason == "INVALID_PROVIDER_JSON"
                ):
                    transport_attempt = 2
                    transport_retry_reason = failure.reason
                    continue
                return self._failed_result(
                    body, calls, [], self._settings.model_name,
                    "NOT_DECLARED_BY_PROVIDER",
                )

        repair_reason: str | None = None
        repaired_empty = False
        try:
            raw_provider = RawProviderContractFactResultV2.model_validate(completion.payload)
        except ValidationError as failure:
            self._log_response_schema_rejection(body, request_id, completion.payload, failure,
                                                transport_attempt)
            candidates = []
            rejections = [self._rejection(
                1, "TYPED_SCHEMA", "INVALID_RESPONSE_SCHEMA", completion.payload
            )]
            repair_reason = "INVALID_RESPONSE_SCHEMA"
        else:
            candidates, rejections = self._validate_candidates(body, raw_provider)
            if (not candidates and rejections
                    and all(row.reason_code == "INVALID_TYPED_SCHEMA" for row in rejections)):
                repair_reason = "INVALID_TYPED_SCHEMA"

        if repair_reason is not None:
            calls[-1] = self._failed_completion_call(transport_attempt, completion, repair_reason)
            # JSON, envelope and typed-schema repairs share the same two-call
            # limit and the original deadline; a second invalid response ends it.
            if transport_attempt != 1:
                return self._failed_result(
                    body, calls, rejections, completion.model,
                    completion.revision or "NOT_DECLARED_BY_PROVIDER",
                )
            self._log_result_validation(
                request_id, "REPAIR_REQUIRED", repair_reason,
                len(candidates), len(rejections), 1, "YES",
            )
            try:
                repaired_completion = self._gateway.extract_contract_facts(
                    ContractFactOperation(
                        project_id=body.project_id,
                        file_id=body.file_id,
                        file_sha256=body.file_sha256,
                        parse_result_id=body.parse_result_id,
                        parse_version=body.parse_version,
                        canonical_text_sha256=body.canonical_text_sha256,
                        source_text=source,
                        request_id=request_id,
                        transport_attempt=2,
                        max_transport_attempts=2,
                        transport_retry_reason=repair_reason,
                        scope=body.scope,
                        protocol_version=2,
                        batch_ordinal=body.batch_ordinal,
                        batch_count=body.batch_count,
                        provider_timeout_seconds=self._remaining_seconds(deadline),
                    )
                )
            except ModelNotConfigured as failure:
                raise AuditAiError(
                    503, "CONTRACT_FACT_UNAVAILABLE",
                    "contract fact extraction is unavailable",
                ) from failure
            except ModelGatewayFailure as failure:
                calls.append(ContractModelCallV2(
                    ordinal=2,
                    phase="REPAIR",
                    outcome="FAILED",
                    safeReason=failure.reason,
                    durationMs=failure.duration_ms,
                    finishReason=failure.finish_reason,
                    promptTokens=failure.prompt_tokens,
                    completionTokens=failure.completion_tokens,
                    totalTokens=failure.total_tokens,
                ))
                return self._failed_result(
                    body, calls, rejections, completion.model,
                    completion.revision or "NOT_DECLARED_BY_PROVIDER",
                )

            repair_failure_reason: str | None = None
            try:
                repaired_raw = RawProviderContractFactResultV2.model_validate(
                    repaired_completion.payload
                )
            except ValidationError as failure:
                self._log_response_schema_rejection(body, request_id, repaired_completion.payload,
                                                    failure, 2)
                repaired_candidates: list[ContractFactCandidateV2] = []
                repaired_rejections: list[ContractFactRejectionV2] = []
                repair_failure_reason = "INVALID_RESPONSE_SCHEMA"
            else:
                repaired_candidates, repaired_rejections = self._validate_candidates(
                    body, repaired_raw
                )
                if repaired_rejections or (not repaired_candidates
                                          and repair_reason != "INVALID_RESPONSE_SCHEMA"):
                    repair_failure_reason = (
                        repaired_rejections[0].reason_code
                        if repaired_rejections else "EMPTY_REPAIR_RESULT"
                    )

            if repair_failure_reason is not None:
                self._log_result_validation(
                    request_id, "REJECTED", repair_reason,
                    len(repaired_candidates), len(repaired_rejections), 2, "YES",
                )
                calls.append(self._failed_completion_call(
                    2, repaired_completion, repair_failure_reason
                ))
                return self._failed_result(
                    body, calls, rejections, repaired_completion.model,
                    repaired_completion.revision or "NOT_DECLARED_BY_PROVIDER",
                )

            calls.append(self._success_call(2, repaired_completion))
            completion = repaired_completion
            candidates = repaired_candidates
            # Preserve historical rejection evidence; only the actual strictly
            # valid correction response can establish an empty scope result.
            repaired_empty = not candidates

        if not candidates:
            batch_status = "FAILED" if rejections and not repaired_empty else "EMPTY"
        else:
            # Candidate rows prove only partial business-fact coverage. The model
            # is not allowed to declare an original document or line set complete.
            batch_status = "PARTIAL"
        result = ContractFactResultV2(
            candidates=candidates,
            rejections=rejections,
            scope=body.scope,
            batchOrdinal=body.batch_ordinal,
            batchCount=body.batch_count,
            batchStatus=batch_status,
            modelCalls=calls,
            schemaVersion="contract-fact-candidates-v2",
            promptVersion=FACT_PROMPT_VERSION,
            modelProvider=_provider_identity(self._settings.model_base_url),
            modelName=completion.model,
            modelRevision=completion.revision or "NOT_DECLARED_BY_PROVIDER",
            durationMs=sum(call.duration_ms for call in calls),
        )
        self._log_result_validation(
            request_id,
            "ACCEPTED" if candidates else batch_status,
            "NONE" if candidates or not rejections or repaired_empty else "INVALID_TYPED_SCHEMA",
            len(candidates), len(rejections), len(calls),
            "YES" if len(calls) > 1 else "NO",
        )
        return ContractFactEnvelopeV2(code=200, message="success", data=result)

    def _failed_result(
        self,
        body: ContractFactRequestV2,
        calls: list[ContractModelCallV2],
        rejections: list[ContractFactRejectionV2],
        model_name: str,
        model_revision: str,
    ) -> ContractFactEnvelopeV2:
        result = ContractFactResultV2(
            candidates=[],
            rejections=rejections,
            scope=body.scope,
            batchOrdinal=body.batch_ordinal,
            batchCount=body.batch_count,
            batchStatus=("UNKNOWN" if any(call.safe_reason in {
                "READ_TIMEOUT", "TOTAL_BUDGET_EXCEEDED", "TRANSPORT_OUTCOME_UNKNOWN", "PROVIDER_5XX"
            } for call in calls) else "FAILED"),
            modelCalls=calls,
            schemaVersion="contract-fact-candidates-v2",
            promptVersion=FACT_PROMPT_VERSION,
            modelProvider=_provider_identity(self._settings.model_base_url),
            modelName=model_name,
            modelRevision=model_revision,
            durationMs=sum(call.duration_ms for call in calls),
        )
        return ContractFactEnvelopeV2(code=200, message="success", data=result)

    @staticmethod
    def _log_response_schema_rejection(
        body: ContractFactRequestV2, request_id: str, payload: Any,
        failure: ValidationError, attempt: int,
    ) -> None:
        # Provider field names and values are untrusted. Keep only known wire
        # paths, root/value types and Pydantic error kinds; never log its input.
        fields = {"candidates", "lineItems", "line_items"}
        keys = sorted(key if key in fields else "unknown" for key in payload) if isinstance(payload, dict) else []
        errors = failure.errors(include_url=False, include_context=False, include_input=False)
        for index, error in enumerate(errors[:16], start=1):
            location = error.get("loc", ())
            safe_location = ".".join(
                str(part) if isinstance(part, int) and part >= 0 else
                part if isinstance(part, str) and part in fields else "unknown"
                for part in location
            ) or "root"
            actual = payload
            for part in location:
                if isinstance(actual, dict) and part in actual:
                    actual = actual[part]
                else:
                    break
            actual_type = type(actual).__name__
            if actual_type not in {"dict", "list", "str", "NoneType", "bool", "int", "float"}:
                actual_type = "other"
            error_type = error.get("type", "unknown")
            if not isinstance(error_type, str) or not re.fullmatch(r"[a-z_]{1,64}", error_type):
                error_type = "unknown"
            log_event(
                LOGGER, "FACT_RESPONSE_SCHEMA_REJECTED", diagnostic_schema_version=1,
                module="AUDIT_AI", layer="APPLICATION", operation="CONTRACT_FACT_CANDIDATES",
                stage="FACT_RESULT_VALIDATION", outcome="REJECTED",
                reason_code="INVALID_RESPONSE_SCHEMA", request_id=request_id,
                purpose=body.scope, chunk_index=body.batch_ordinal, chunk_count=body.batch_count,
                attempt=attempt, max_attempts=2, top_level_keys=",".join(keys) or "NONE",
                validation_error_class="ValidationError", validation_error_count=len(errors),
                validation_error_index=index, validation_error_loc=safe_location,
                validation_error_type=error_type, validation_actual_type=actual_type,
            )

    def _validate_candidates(
        self,
        body: ContractFactRequestV2,
        raw_provider: RawProviderContractFactResultV2,
    ) -> tuple[list[ContractFactCandidateV2], list[ContractFactRejectionV2]]:
        evidence = {item.evidence_id: item for item in body.evidence_catalog}
        provider_candidates = list(raw_provider.candidates)
        candidates: list[ContractFactCandidateV2] = []
        rejections: list[ContractFactRejectionV2] = []
        for ordinal, raw_row in enumerate(raw_provider.line_items, start=len(provider_candidates) + 1):
            try:
                row = ProviderCompactLineItemV2.model_validate(raw_row)
            except ValidationError:
                rejections.append(self._rejection(ordinal, "TYPED_SCHEMA", "INVALID_COMPACT_ROW", raw_row))
                continue
            if body.scope != "LINE_ITEMS":
                rejections.append(self._rejection(ordinal, "SCOPE", "FACT_OUTSIDE_SCOPE", raw_row))
                continue
            selected = evidence.get(row.evidence_id)
            if selected is None:
                rejections.append(self._rejection(ordinal, "EVIDENCE", "UNKNOWN_EVIDENCE_ID", raw_row))
                continue
            if not selected.row_identity:
                rejections.append(self._rejection(ordinal, "IDENTITY", "ROW_IDENTITY_UNPROVEN", raw_row))
                continue
            if not self._compact_row_grounded(row, selected):
                rejections.append(self._rejection(ordinal, "EVIDENCE", "COMPACT_ROW_NOT_GROUNDED", raw_row))
                continue
            # All four values share one server-owned physical row and evidence
            # item. Cross-fragment partial rows must use the existing candidates
            # path, which retains their original individual source identities.
            provider_candidates.extend({
                "factType": fact_type,
                "groupKey": row.group_key or selected.row_identity,
                "valueText": value if fact_type == "LINE_ITEM_NAME" else None,
                "valueDecimal": None if fact_type == "LINE_ITEM_NAME" else value,
                "evidenceId": row.evidence_id,
                "contextCode": "CONTRACT_LINE_ITEM",
                "confidence": row.confidence,
            } for fact_type, value in (
                ("LINE_ITEM_NAME", row.name),
                ("LINE_ITEM_QUANTITY", row.quantity),
                ("LINE_ITEM_UNIT_PRICE", row.unit_price),
                ("LINE_ITEM_DECLARED_AMOUNT", row.declared_amount),
            ))
        if rejections:
            # A malformed compact row cannot publish the other rows of a batch.
            return [], rejections
        candidate_keys: set[str] = set()
        evidence_groups: dict[tuple[str, str], set[str]] = {}
        row_values: dict[tuple[str, str], set[str | Decimal]] = {}
        for raw_candidate in provider_candidates:
            if not isinstance(raw_candidate, dict) or raw_candidate.get("factType") not in _LINE_FACTS:
                continue
            selected = evidence.get(raw_candidate.get("evidenceId"))
            group = raw_candidate.get("groupKey")
            if selected is not None and selected.row_identity and isinstance(group, str):
                evidence_groups.setdefault((selected.row_identity, selected.evidence_id), set()).add(group)
                decimal_value = raw_candidate.get("valueDecimal")
                identity: str | Decimal = json.dumps(
                    [raw_candidate.get("valueText"), decimal_value], ensure_ascii=True, sort_keys=True
                )
                if (raw_candidate["factType"] in _DECIMAL_FACTS
                        and raw_candidate.get("valueText") is None
                        and isinstance(decimal_value, str) and re.fullmatch(_DECIMAL, decimal_value)):
                    # Decimal equality ignores scale without rounding. This is
                    # only conflict detection; source values and keys stay exact.
                    identity = Decimal(decimal_value)
                row_values.setdefault((selected.row_identity, raw_candidate["factType"]), set()).add(identity)
        ambiguous_rows = {row for (row, _), groups in evidence_groups.items() if len(groups) > 1}
        ambiguous_rows.update(row for (row, _), values in row_values.items() if len(values) > 1)
        mapped_line_failure = False
        for ordinal, raw_candidate in enumerate(provider_candidates, start=1):
            try:
                candidate = ProviderContractFactCandidateV2.model_validate(raw_candidate)
            except ValidationError:
                rejections.append(self._rejection(
                    ordinal, "TYPED_SCHEMA", "INVALID_TYPED_SCHEMA", raw_candidate
                ))
                continue
            if candidate.fact_type not in _SCOPE_FACTS[body.scope]:
                rejections.append(self._rejection(
                    ordinal, "SCOPE", "FACT_OUTSIDE_SCOPE", raw_candidate
                ))
                continue
            selected = evidence.get(candidate.evidence_id)
            if selected is None:
                rejections.append(self._rejection(
                    ordinal, "EVIDENCE", "UNKNOWN_EVIDENCE_ID", raw_candidate
                ))
                continue
            if selected.line_item_columns is not None and candidate.fact_type in _LINE_FACTS:
                column = {
                    "LINE_ITEM_NAME": "name", "LINE_ITEM_QUANTITY": "quantity",
                    "LINE_ITEM_UNIT_PRICE": "unitPrice", "LINE_ITEM_DECLARED_AMOUNT": "declaredAmount",
                }[candidate.fact_type]
                value = candidate.value_text if candidate.fact_type == "LINE_ITEM_NAME" else candidate.value_decimal
                if not self._mapped_line_value_grounded(selected, column, value):
                    mapped_line_failure = True
                    rejections.append(self._rejection(
                        ordinal, "EVIDENCE", "MAPPED_LINE_VALUE_NOT_GROUNDED", raw_candidate
                    ))
                    continue
            # Check mapped values before identity conflicts can filter a row;
            # otherwise a wrong cell could evade the whole-batch failure fence.
            # A server-owned row can span fragments and model group aliases,
            # but competing values or invented rows remain unproven.
            if selected.row_identity and candidate.fact_type in _LINE_FACTS and selected.row_identity in ambiguous_rows:
                rejections.append(self._rejection(
                    ordinal, "IDENTITY", "ROW_IDENTITY_UNPROVEN", raw_candidate
                ))
                continue
            resolved = self._resolve_and_key(body, candidate, selected)
            if resolved.candidate_key in candidate_keys:
                rejections.append(self._rejection(
                    ordinal, "IDENTITY", "DUPLICATE_CANDIDATE_IDENTITY", raw_candidate
                ))
                continue
            candidate_keys.add(resolved.candidate_key)
            candidates.append(resolved)
        if mapped_line_failure or (raw_provider.line_items and rejections):
            return [], rejections
        return candidates, rejections

    @staticmethod
    def _compact_row_grounded(row: ProviderCompactLineItemV2, evidence: ContractFactEvidence) -> bool:
        return all(ContractFactServiceV2._mapped_line_value_grounded(evidence, column, value)
                   for column, value in (("name", row.name), ("quantity", row.quantity),
                                         ("unitPrice", row.unit_price), ("declaredAmount", row.declared_amount)))

    @staticmethod
    def _mapped_line_value_grounded(evidence: ContractFactEvidence, column: str, value: str | None) -> bool:
        # Only server-owned header mapping proves each cell's field. Neither
        # column count nor unordered number membership establishes that mapping.
        columns = evidence.line_item_columns
        if columns is None or value is None:
            return False
        cells = evidence.text.rstrip("\r\n").split("\t")
        if len(cells) != len(columns) or any("\r" in cell or "\n" in cell for cell in cells):
            return False
        mapped = dict(zip(columns, cells))
        if "rowId" in mapped and not mapped["rowId"].strip():
            return False
        if column == "name":
            return value == mapped["name"]
        numeric_cell = r"(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\.[0-9]+)?"
        actual = mapped[column].strip()
        if not re.fullmatch(numeric_cell, actual):
            return False
        # Scale/comma normalization only: no multiplication or amount decision.
        return Decimal(actual.replace(",", "")) == Decimal(value)

    @staticmethod
    def _remaining_seconds(deadline: float) -> float:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ModelGatewayFailure(reason="TOTAL_BUDGET_EXCEEDED", retryable=False, duration_ms=0)
        return remaining

    @staticmethod
    def _success_call(ordinal: int, completion: ModelCompletion) -> ContractModelCallV2:
        return ContractModelCallV2(
            ordinal=ordinal,
            phase="TRANSPORT" if ordinal == 1 else "REPAIR",
            outcome="SUCCEEDED",
            durationMs=completion.duration_ms,
            finishReason=completion.finish_reason,
            promptTokens=completion.prompt_tokens,
            completionTokens=completion.completion_tokens,
            totalTokens=completion.total_tokens,
        )

    @staticmethod
    def _failed_completion_call(
        ordinal: int, completion: ModelCompletion, reason: str
    ) -> ContractModelCallV2:
        return ContractModelCallV2(
            ordinal=ordinal,
            phase="TRANSPORT" if ordinal == 1 else "REPAIR",
            outcome="FAILED",
            safeReason=reason,
            durationMs=completion.duration_ms,
            finishReason=completion.finish_reason,
            promptTokens=completion.prompt_tokens,
            completionTokens=completion.completion_tokens,
            totalTokens=completion.total_tokens,
        )

    @staticmethod
    def _shape_hash(value: Any) -> str:
        if isinstance(value, dict):
            shape = {str(key): type(item).__name__ for key, item in sorted(value.items())}
        else:
            shape = {"root": type(value).__name__}
        encoded = json.dumps(shape, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(encoded.encode("utf-8")).hexdigest()

    @classmethod
    def _rejection(cls, ordinal: int, stage: str, reason: str,
                   value: Any) -> ContractFactRejectionV2:
        return ContractFactRejectionV2(
            providerOrdinal=ordinal,
            stage=stage,
            reasonCode=reason,
            safeShapeHash=cls._shape_hash(value),
        )

    @staticmethod
    def _resolve_and_key(body: ContractFactRequestV2,
                         candidate: ProviderContractFactCandidateV2,
                         evidence: ContractFactEvidence) -> ContractFactCandidateV2:
        group_key = candidate.group_key
        if candidate.fact_type in _LINE_FACTS:
            group_identity = json.dumps(
                ([evidence.row_identity] if evidence.row_identity else
                 [evidence.evidence_id, candidate.group_key]),
                ensure_ascii=False, separators=(",", ":"),
            )
            group_key = "line:" + hashlib.sha256(group_identity.encode("utf-8")).hexdigest()
        identity = {
            "fileSha256": body.file_sha256,
            "parseResultId": body.parse_result_id,
            "parseVersion": body.parse_version,
            "scope": body.scope,
            "factType": candidate.fact_type,
            "groupKey": group_key,
            "valueText": candidate.value_text,
            "valueDecimal": candidate.value_decimal,
            "evidenceId": candidate.evidence_id,
            "contextCode": candidate.context_code,
        }
        encoded = json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        key = "cfc-v2-" + hashlib.sha256(encoded.encode("utf-8")).hexdigest()
        return ContractFactCandidateV2(
            candidateKey=key,
            factType=candidate.fact_type,
            groupKey=group_key,
            valueText=candidate.value_text,
            valueDecimal=candidate.value_decimal,
            segmentId=evidence.evidence_id,
            sourceLocator=evidence.source_locator,
            excerpt=evidence.text,
            excerptStart=0,
            excerptEnd=len(evidence.text),
            contextCode=candidate.context_code,
            confidence=candidate.confidence,
        )

    @staticmethod
    def _log_schema_rejection(request_id: str, outcome: str, error_count: int) -> None:
        ContractFactService._log_schema_rejection(request_id, outcome, error_count)

    @staticmethod
    def _log_result_validation(
        request_id: str,
        outcome: str,
        reason_code: str,
        accepted_count: int,
        rejected_count: int,
        attempt: int,
        repair_attempted: str,
    ) -> None:
        log_event(
            LOGGER,
            "FACT_RESULT_VALIDATION",
            diagnostic_schema_version=1,
            module="AUDIT_AI",
            layer="APPLICATION",
            operation="CONTRACT_FACT_CANDIDATES",
            stage="FACT_RESULT_VALIDATION",
            outcome=outcome,
            reason_code=reason_code,
            request_id=request_id,
            accepted_count=accepted_count,
            rejected_count=rejected_count,
            attempt=attempt,
            repair_attempted=repair_attempted,
        )


def create_contract_fact_router(settings: Settings, gateway: ModelGatewayPort) -> APIRouter:
    router = APIRouter()
    service = ContractFactService(settings, gateway)
    service_v2 = ContractFactServiceV2(settings, gateway)

    @router.post(
        "/ai/v1/agent/contract-fact-candidates",
        response_model=ContractFactEnvelope,
        response_model_exclude_none=False,
    )
    async def extract_contract_facts(
        body: ContractFactRequest, request: Request
    ) -> ContractFactEnvelope:
        request_id = getattr(request.state, "request_id", "unavailable")
        return await run_in_threadpool(service.extract, body, request_id)

    @router.post(
        "/ai/v2/agent/contract-fact-candidates",
        response_model=ContractFactEnvelopeV2,
        response_model_exclude_none=False,
    )
    async def extract_contract_facts_v2(
        body: ContractFactRequestV2, request: Request
    ) -> ContractFactEnvelopeV2:
        request_id = getattr(request.state, "request_id", "unavailable")
        return await run_in_threadpool(service_v2.extract, body, request_id)

    return router
