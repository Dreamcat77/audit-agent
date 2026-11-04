"""Strict, non-authoritative DOCUMENT_ROLE capability."""

from __future__ import annotations

import json
import re
from typing import Literal

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator
from starlette.concurrency import run_in_threadpool

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.model.gateway import (
    DocumentRoleOperation,
    ModelGatewayFailure,
    ModelGatewayPort,
    ModelNotConfigured,
)


_STRICT = ConfigDict(extra="forbid", populate_by_name=True)
Role = Literal[
    "CONTRACT_BODY",
    "AWARD_NOTICE",
    "TENDER_DOCUMENT",
    "BID_RESPONSE",
    "SUPPLEMENT",
    "OTHER",
    "UNKNOWN",
]
_SAFE_CODE = re.compile(r"^[A-Z][A-Z0-9_]{0,99}$")


class DocumentRoleSegment(BaseModel):
    model_config = _STRICT
    segment_id: str = Field(alias="segmentId", min_length=1, max_length=64)
    source_locator: str = Field(alias="sourceLocator", min_length=1, max_length=200)
    text_sha256: str = Field(alias="textSha256", pattern=r"^[0-9a-f]{64}$")
    text: str = Field(min_length=1, max_length=2000, repr=False)


class DocumentRoleRequest(BaseModel):
    model_config = _STRICT
    file_id: str = Field(alias="fileId", min_length=36, max_length=36)
    parse_result_id: str = Field(alias="parseResultId", min_length=36, max_length=36)
    parse_version: int = Field(alias="parseVersion", ge=1)
    canonical_text_sha256: str = Field(
        alias="canonicalTextSha256", pattern=r"^[0-9a-f]{64}$"
    )
    segments: list[DocumentRoleSegment] = Field(min_length=1, max_length=32)

    @model_validator(mode="after")
    def unique_segments(self) -> "DocumentRoleRequest":
        values = [value.segment_id for value in self.segments]
        if len(values) != len(set(values)):
            raise ValueError("segment IDs must be unique")
        return self


class CandidateRole(BaseModel):
    model_config = _STRICT
    role: Role
    confidence: float = Field(ge=0, le=1)
    reason_code: str = Field(alias="reasonCode", pattern=r"^[A-Z][A-Z0-9_]{0,99}$")


class ProviderDocumentRoleResult(BaseModel):
    model_config = _STRICT
    proposed_role: Role = Field(alias="proposedRole")
    confidence: float = Field(ge=0, le=1)
    candidate_roles: list[CandidateRole] = Field(alias="candidateRoles", max_length=7)
    reason_codes: list[str] = Field(alias="reasonCodes", max_length=20)
    evidence_segment_ids: list[str] = Field(alias="evidenceSegmentIds", max_length=32)

    @model_validator(mode="after")
    def coherent(self) -> "ProviderDocumentRoleResult":
        if any(_SAFE_CODE.fullmatch(code) is None for code in self.reason_codes):
            raise ValueError("invalid reason code")
        if self.proposed_role == "UNKNOWN":
            if self.candidate_roles or self.confidence != 0:
                raise ValueError("UNKNOWN must be an empty zero-confidence proposal")
        elif (
            not self.candidate_roles
            or self.candidate_roles[0].role != self.proposed_role
            or self.candidate_roles[0].confidence != self.confidence
            or not self.evidence_segment_ids
        ):
            raise ValueError("proposal, candidates, confidence, and evidence disagree")
        return self


class DocumentRoleResult(ProviderDocumentRoleResult):
    schema_version: str = Field(alias="schemaVersion")
    prompt_version: str = Field(alias="promptVersion")
    model_provider: str = Field(alias="modelProvider")
    model_name: str = Field(alias="modelName")
    model_revision: str = Field(alias="modelRevision")


class DocumentRoleEnvelope(BaseModel):
    code: int
    message: str
    data: DocumentRoleResult


class DocumentRoleService:
    def __init__(self, settings: Settings, gateway: ModelGatewayPort) -> None:
        self._settings = settings
        self._gateway = gateway

    def assess(self, body: DocumentRoleRequest, request_id: str) -> DocumentRoleEnvelope:
        if not self._settings.document_role_enabled:
            raise AuditAiError(503, "DOCUMENT_ROLE_DISABLED", "document role is disabled")
        source = json.dumps(
            [segment.model_dump(by_alias=True) for segment in body.segments],
            ensure_ascii=False,
            separators=(",", ":"),
        )
        try:
            completion = self._gateway.analyze_document_role(
                DocumentRoleOperation(
                    file_id=body.file_id,
                    parse_result_id=body.parse_result_id,
                    source_text=source,
                    request_id=request_id,
                )
            )
        except ModelNotConfigured as failure:
            raise AuditAiError(
                503, "DOCUMENT_ROLE_UNAVAILABLE", "document role is unavailable"
            ) from failure
        except ModelGatewayFailure as failure:
            if failure.reason in {"CONNECT_TIMEOUT", "READ_TIMEOUT"}:
                raise AuditAiError(
                    504, "DOCUMENT_ROLE_TIMEOUT", "document role timed out"
                ) from failure
            raise AuditAiError(
                503, "DOCUMENT_ROLE_UNAVAILABLE", "document role is unavailable"
            ) from failure
        try:
            proposal = ProviderDocumentRoleResult.model_validate(completion.payload)
        except ValidationError as failure:
            raise AuditAiError(
                502, "DOCUMENT_ROLE_INVALID_RESPONSE", "document role response is invalid"
            ) from failure
        allowed_segments = {segment.segment_id for segment in body.segments}
        if not set(proposal.evidence_segment_ids).issubset(allowed_segments):
            raise AuditAiError(
                502, "DOCUMENT_ROLE_INVALID_RESPONSE", "document role response is invalid"
            )
        result = DocumentRoleResult(
            **proposal.model_dump(by_alias=True),
            schemaVersion="document-role-v1",
            promptVersion="document-role-prompt-v1",
            modelProvider="OPENAI_COMPATIBLE",
            modelName=completion.model,
            modelRevision=completion.revision or "NOT_DECLARED_BY_PROVIDER",
        )
        return DocumentRoleEnvelope(code=200, message="success", data=result)


def create_document_role_router(settings: Settings, gateway: ModelGatewayPort) -> APIRouter:
    router = APIRouter()
    service = DocumentRoleService(settings, gateway)

    @router.post(
        "/ai/v1/agent/document-role",
        response_model=DocumentRoleEnvelope,
        response_model_exclude_none=True,
    )
    async def assess_document_role(
        body: DocumentRoleRequest, request: Request
    ) -> DocumentRoleEnvelope:
        request_id = getattr(request.state, "request_id", "unavailable")
        return await run_in_threadpool(service.assess, body, request_id)

    return router
