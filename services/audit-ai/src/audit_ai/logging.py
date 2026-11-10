"""Small allowlisted logging seam for technical observability."""

from __future__ import annotations

import logging
import os
import re
from contextvars import ContextVar
from contextlib import contextmanager
from typing import Any


_SAFE_FIELDS = frozenset(
    {
        "request_id",
        "validation_schema_version",
        "validation_error_id",
        "validation_allowed_values",
        "validation_value_class",
        "diagnostic_schema_version",
        "module",
        "layer",
        "operation",
        "stage",
        "outcome",
        "reason_code",
        "method",
        "route_template",
        "status_code",
        "reason",
        "gateway_reason",
        "duration_ms",
        "attempt",
        "max_attempts",
        "document_id",
        "engine",
        "model",
        "material_count",
        "chunk_index",
        "chunk_count",
        "input_chars",
        "output_hint_count",
        "filtered_hint_count",
        "raw_hint_count",
        "normalized_hint_count",
        "filter_reason_counts",
        "safe_hint_count",
        "compact_chars",
        "provider_call_count",
        "semantic_retry_count",
        "transport_retry_count",
        "transport_attempt",
        "max_transport_attempts",
        "evidence_segment_count",
        "citable_segment_count",
        "non_citable_segment_count",
        "required_breach_segment_count",
        "required_breach_citable_segment_count",
        "semantic_defect_types",
        "purpose",
        "top_level_keys",
        "summary_present",
        "hints_present",
        "missing_category_hints_present",
        "evidence_repairs_present",
        "hint_count",
        "missing_category_hint_count",
        "evidence_repair_count",
        "correction_item_kind",
        "item_index",
        "category",
        "attention",
        "category_valid_shape",
        "attention_valid_shape",
        "has_title",
        "has_description",
        "has_problem_evidence_ids",
        "problem_evidence_count",
        "has_comparison_evidence_ids",
        "comparison_evidence_count",
        "has_unavailable_material_category",
        "has_hint_id",
        "correction_mode",
        "missing_category_count",
        "repair_target_count",
        "evidence_repair_target_count",
        "returned_missing_category_count",
        "returned_repair_count",
        "returned_categories",
        "correction_requested_categories",
        "correction_returned_categories",
        "validation_error_class",
        "correction_failure_reason",
        "final_model_rejection_reason",
        "validation_error_count",
        "validation_error_index",
        "validation_error_loc",
        "validation_error_type",
        "validation_actual_type",
        "validation_actual_enum",
        "provider_finish_reason",
        "provider_content_shape",
        "provider_content_chars",
        "json_error_kind",
        "json_error_position",
        "filter_reason",
        "input_hint_count",
        "accepted_hint_count",
        "accepted_count",
        "rejected_count",
        "repair_attempted",
        "accepted_categories",
        "filtered_categories",
        "missing_required_categories",
        "required_categories",
        "required_category_count",
        "demo_relaxation",
        "original_rejection_reason",
    }
)
_PIPELINE_COUNTS = frozenset({
    "worker_count", "requested_workers", "memory_budget_mb",
    "http_status", "input_bytes", "output_bytes", "prompt_chars", "user_chars",
    "prompt_tokens", "completion_tokens", "total_tokens", "max_output_tokens",
    "connect_timeout_ms", "read_timeout_ms", "remaining_budget_ms", "page_count",
    "row_count", "fact_count", "text_limit", "byte_limit", "segment_count",
    "rotation_degrees", "deskew_millidegrees", "orientation_candidates",
    "explicit_fact_count", "item_count", "missing_item_count", "duplicate_item_count",
    "foreign_item_count", "validation_error_count",
    "chunk_char_limit", "max_chunks", "total_budget_ms",
    "empty_page_count",
    "empty_chunk_count", "chunks_with_facts",
    "orientation_quality_milli", "classifier_confidence_milli", "ocr_page_count",
})
_PROVIDER_METADATA_COUNTS = frozenset({
    "provider_content_char_count", "provider_reasoning_content_chars",
    "provider_reasoning_chars", "provider_thinking_chars",
    "provider_reasoning_tokens", "provider_completion_reasoning_tokens",
})
_PROVIDER_METADATA_PRESENCE = frozenset({
    "provider_reasoning_content_present", "provider_reasoning_present", "provider_thinking_present",
})
_SAFE_FIELDS = _SAFE_FIELDS | _PROVIDER_METADATA_COUNTS | _PROVIDER_METADATA_PRESENCE | {"provider_content_type"}
_SAFE_FIELDS = _SAFE_FIELDS | _PIPELINE_COUNTS | frozenset({
    "stage", "call_id", "error_class", "provider_error_kind", "transport",
    "endpoint_fingerprint", "configuration_ready", "retryable", "media_kind",
    "material_id", "parse_method",
    "resource_id",
    "transport_error_kind", "model_fingerprint", "response_format",
    "thinking_disabled", "response_content_kind",
    "response_body_sha256",
})
_CONTEXT: ContextVar[dict[str, Any]] = ContextVar("audit_diagnostic_context", default={})


@contextmanager
def diagnostic_context(**fields: Any):
    """Bound correlation to this request; threadpool copies ContextVars safely."""
    token = _CONTEXT.set({**_CONTEXT.get(), **fields})
    try:
        yield
    finally:
        _CONTEXT.reset(token)
_COUNT_FIELDS = frozenset(
    {
        "duration_ms",
        "attempt",
        "max_attempts",
        "material_count",
        "chunk_index",
        "chunk_count",
        "input_chars",
        "output_hint_count",
        "filtered_hint_count",
        "raw_hint_count",
        "normalized_hint_count",
        "safe_hint_count",
        "compact_chars",
        "provider_call_count",
        "semantic_retry_count",
        "transport_retry_count",
        "transport_attempt",
        "max_transport_attempts",
        "evidence_segment_count",
        "citable_segment_count",
        "non_citable_segment_count",
        "required_breach_segment_count",
        "required_breach_citable_segment_count",
        "hint_count",
        "missing_category_hint_count",
        "evidence_repair_count",
        "item_index",
        "problem_evidence_count",
        "comparison_evidence_count",
        "missing_category_count",
        "repair_target_count",
        "evidence_repair_target_count",
        "returned_missing_category_count",
        "returned_repair_count",
        "validation_error_count",
        "validation_error_index",
        "input_hint_count",
        "accepted_hint_count",
        "accepted_count",
        "rejected_count",
        "required_category_count",
        "provider_content_chars",
        "json_error_position",
        "status_code",
    }
)
_SAFE_REASON_CODES = frozenset(
    {
        "INPUT_LIMIT", "PREVIOUS_SPLIT", "OUTPUT_TRUNCATED",
        "MATERIAL_AI_CONTEXT_LIMIT",
        "NONE",
        "INTERNAL_ERROR",
        "UPLOAD_TOO_LARGE",
        "EMPTY_UPLOAD",
        "OCR_UNSUPPORTED_MEDIA_TYPE",
        "OCR_INVALID_RESULT",
        "OCR_RUNTIME_UNAVAILABLE",
        "OCR_EXECUTION_FAILED",
        "EMPTY_MODEL_INPUT",
        "MODEL_NOT_CONFIGURED",
        "MODEL_INPUT_TOO_LARGE",
        "MODEL_RECONCILIATION_LIMIT",
        "MATERIAL_AI_POLICY_DRIFT",
        "MATERIAL_AI_CALL_LIMIT",
        "MODEL_TOTAL_BUDGET_EXHAUSTED",
        "MODEL_INVALID_RESULT",
        "MODEL_GATEWAY_ERROR",
        "DOCUMENT_ROLE_DISABLED",
        "DOCUMENT_ROLE_UNAVAILABLE",
        "DOCUMENT_ROLE_TIMEOUT",
        "DOCUMENT_ROLE_INVALID_RESPONSE",
        "MATERIAL_AI_INPUT_LIMIT",
        "MATERIAL_AI_CALL_LIMIT",
        "MATERIAL_AI_DUPLICATE_SOURCE",
        "MATERIAL_AI_TIME_LIMIT",
        "MATERIAL_AI_CALL_TIMEOUT",
        "MATERIAL_AI_TRUNCATED",
        "MATERIAL_AI_INVALID_RESULT",
        "MATERIAL_AI_UNGROUNDED",
        "BUDGET_ROW_IDENTITY_UNVERIFIED",
        "MATERIAL_AI_RESULT_LIMIT",
        "MATERIAL_AI_UNAVAILABLE",
        "MATERIAL_AI_FAILED",
        "MATERIAL_AI_BUSY",
        "CONTRACT_FACT_EXTRACTION_DISABLED",
        "CONTRACT_FACT_UNAVAILABLE",
        "CONTRACT_FACT_TIMEOUT",
        "CONTRACT_FACT_INVALID_RESPONSE",
        "INVALID_RESPONSE_SCHEMA",
        "INVALID_TYPED_SCHEMA",
        "REQUEST_VALIDATION_FAILED",
    }
)
_COUNT_FIELDS = _COUNT_FIELDS | _PIPELINE_COUNTS
_SAFE_REASONS = frozenset(
    {
        "INVALID_EVIDENCE_REF",
        "STRONG_VERDICT",
        "MONETARY_VALUE",
        "MONETARY_CALCULATION",
        "INVALID_TYPED_SCHEMA",
        "MISSING_REQUIRED_FIELD",
        "TYPE_MISMATCH",
        "ENUM_VALUE_INVALID",
        "EXTRA_PROPERTY",
        "UNPROCESSABLE_TOP_LEVEL",
        "MISSING_REQUIRED_BREACH_CATEGORY",
        "MISSING_REQUIRED_CATEGORY",
        "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE",
        "UNSAFE_OR_INVALID_HINT",
        "SYNTHESIS_INPUT_BOUND",
        "EVIDENCE_CATALOG_UNSATISFIABLE",
        "CORRECTION_SCHEMA_INVALID",
        "CORRECTION_CATEGORY_SET_MISMATCH",
        "CORRECTION_REPAIR_TARGET_MISMATCH",
        "CORRECTION_EVIDENCE_INVALID",
        "CORRECTION_SEMANTIC_INVALID",
        "CORRECTION_UNPROCESSABLE_RESULT",
    }
)
_SAFE_GATEWAY_REASONS = frozenset(
    {
        "OUTPUT_TOKEN_LIMIT",
        "CONNECT_TIMEOUT",
        "READ_TIMEOUT",
        "CONNECT_ERROR",
        "PROVIDER_400",
        "PROVIDER_401",
        "PROVIDER_403",
        "PROVIDER_404",
        "PROVIDER_405",
        "PROVIDER_409",
        "PROVIDER_413",
        "PROVIDER_415",
        "PROVIDER_422",
        "PROVIDER_429",
        "PROVIDER_4XX",
        "PROVIDER_4XX_OTHER",
        "PROVIDER_5XX",
        "INVALID_PROVIDER_JSON",
        "INVALID_PROVIDER_RESPONSE",
        "MODEL_REFUSED",
        "TOTAL_BUDGET_EXCEEDED",
        "TRANSPORT_OUTCOME_UNKNOWN",
        "PROVIDER_CAPACITY_BUSY",
        "PROVIDER_DISPATCH_NOT_STARTED",
    }
)
_SAFE_FILTER_REASONS = frozenset(
    {
        "INVALID_TYPED_HINT",
        "INVALID_CATEGORY",
        "INVALID_ATTENTION",
        "INVALID_TITLE_OR_DESCRIPTION",
        "STRONG_VERDICT",
        "MONETARY_VALUE",
        "MONETARY_CALCULATION",
        "MONETARY_CONCLUSION",
        "INVALID_EVIDENCE_REF",
        "NON_CITABLE_EVIDENCE",
        "MISSING_PROBLEM_EVIDENCE",
        "INVALID_INFO_EVIDENCE",
        "INVALID_MISSING_MATERIAL_CLAIM",
        "UNVERIFIED_PROBLEM_EVIDENCE",
        "OTHER_SAFE_FILTER",
        "REDACTION_ARTIFACT_CLAIM",
        "INCOMPLETE_DESCRIPTION",
    }
)
_SAFE_CONTRACT_CATEGORIES = frozenset(
    {
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
)
_SAFE_ATTENTIONS = frozenset({"INFO", "REVIEW", "INSUFFICIENT"})
_SAFE_VALIDATION_ACTUAL_TYPES = frozenset(
    {"str", "NoneType", "bool", "int", "float", "list", "dict", "other"}
)
_SAFE_VALIDATION_ACTUAL_ENUMS = frozenset(
    {
        "CONTRACT_BODY",
        "AWARD_NOTICE",
        "BID_RESPONSE",
        "TENDER_DOCUMENT",
        "SUPPLEMENT",
        "NONE",
        "NULL",
        "UNKNOWN",
        "NOT_APPLICABLE",
        "EMPTY_STRING",
        "NA",
        "NONE_VARIANT",
        "NOT_APPLICABLE_VARIANT",
        "AVAILABLE_VARIANT",
        "UNRECOGNIZED",
    }
)
_SAFE_PROVIDER_FINISH_REASONS = frozenset(
    {"STOP", "LENGTH", "CONTENT_FILTER", "TOOL_CALLS", "MISSING", "UNKNOWN"}
)
_SAFE_PROVIDER_CONTENT_SHAPES = frozenset(
    {
        "EMPTY",
        "CODE_FENCE",
        "OBJECT_BOUNDED",
        "OBJECT_TRUNCATED",
        "ARRAY_BOUNDED",
        "ARRAY_TRUNCATED",
        "OTHER",
    }
)
_SAFE_JSON_ERROR_KINDS = frozenset(
    {
        "UNTERMINATED_STRING",
        "EXPECTING_DELIMITER",
        "EXPECTING_PROPERTY_NAME",
        "EXTRA_DATA",
        "EXPECTING_VALUE",
        "INVALID_CONTROL_CHARACTER",
        "OTHER",
    }
)
_UNSAFE_LOG_CHARACTER = re.compile(r"[^A-Za-z0-9._:/-]")


def configure_runtime_logging() -> None:
    """Expose audit_ai INFO events through Uvicorn's existing safe handler."""

    configured = os.getenv("AUDIT_TECH_LOG_LEVEL", "INFO").upper()
    level = {
        "DEBUG": logging.DEBUG,
        "INFO": logging.INFO,
        "WARNING": logging.WARNING,
        "ERROR": logging.ERROR,
        "CRITICAL": logging.CRITICAL,
    }.get(configured, logging.INFO)
    audit_logger = logging.getLogger("audit_ai")
    audit_logger.setLevel(level)
    if audit_logger.handlers:
        return
    uvicorn_handlers = list(logging.getLogger("uvicorn").handlers)
    if uvicorn_handlers:
        audit_logger.handlers.extend(uvicorn_handlers)
        audit_logger.propagate = False
    else:
        audit_logger.propagate = True


def safe_reason_code(value: object) -> str:
    """Return an allowlisted diagnostic code without changing API behavior."""

    if isinstance(value, str) and value in _SAFE_REASON_CODES:
        return value
    return "INTERNAL_ERROR"


def log_event(
    logger: logging.Logger, event: str, *, level: int = logging.INFO, **fields: Any
) -> None:
    """Log an event with metadata restricted to an explicit safe-field allowlist."""

    fields = {**_CONTEXT.get(), **fields}
    unsafe = set(fields).difference(_SAFE_FIELDS)
    if unsafe:
        raise ValueError("unsafe logging field")
    sanitized = {name: _safe_value(name, value) for name, value in fields.items()}
    rendered = " ".join(
        f"{name}={sanitized[name]}" for name in sorted(sanitized)
    )
    logger.log(
        level,
        "%s %s",
        event,
        rendered,
        extra={"audit_fields": sanitized},
    )


def _safe_value(name: str, value: Any) -> str | int:
    if name in _PROVIDER_METADATA_COUNTS:
        if value == "UNKNOWN" or (isinstance(value, int) and not isinstance(value, bool) and value >= 0):
            return value
        raise ValueError("provider metadata counts must be non-negative integers or UNKNOWN")
    if name in _PROVIDER_METADATA_PRESENCE:
        if not isinstance(value, str) or value not in {"YES", "NO", "UNKNOWN"}:
            raise ValueError("provider metadata presence must be YES, NO or UNKNOWN")
        return value
    if name == "provider_content_type":
        if not isinstance(value, str) or value not in {"STRING", "NULL", "BOOLEAN", "NUMBER", "ARRAY", "OBJECT", "UNKNOWN"}:
            raise ValueError("provider content type is not allowlisted")
        return value
    if name == "response_body_sha256":
        if not isinstance(value, str) or re.fullmatch(r"[a-f0-9]{64}", value) is None:
            raise ValueError("response body digest must be a SHA256")
        return value
    if name == "diagnostic_schema_version":
        if value != 1:
            raise ValueError("diagnostic schema version must be 1")
        return 1
    if name == "reason_code":
        if not isinstance(value, str) or value not in _SAFE_REASON_CODES:
            raise ValueError("reason_code logging field is not allowlisted")
        return value
    if name == "module":
        if value != "AUDIT_AI":
            raise ValueError("module logging field is not allowlisted")
        return value
    if name == "layer":
        if value not in {"HTTP", "APPLICATION", "INTEGRATION"}:
            raise ValueError("layer logging field is not allowlisted")
        return value
    if name == "stage":
        if value not in {
            "AI_HTTP",
            "APPLICATION",
            "CAPACITY",
            "CHUNKING",
            "EXTRACTION",
            "FACT_RESULT_VALIDATION",
            "HTTP",
            "HTTP_VALIDATION",
            "MERGE",
            "MODEL_ENVELOPE",
            "MODEL_HTTP",
            "OCR",
            "OCR_STORAGE",
            "RESULT_VALIDATION",
        }:
            raise ValueError("stage logging field is not allowlisted")
        return value
    if name == "method":
        if value not in {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"}:
            raise ValueError("method logging field is not allowlisted")
        return value
    if name == "repair_attempted":
        if value not in {"YES", "NO"}:
            raise ValueError("repair_attempted logging field must be YES or NO")
        return value
    if name == "status_code":
        if isinstance(value, bool) or not isinstance(value, int) or not 100 <= value <= 599:
            raise ValueError("status_code logging field is invalid")
        return value
    if name in {
        "reason",
        "correction_failure_reason",
        "final_model_rejection_reason",
    }:
        if not isinstance(value, str) or value not in _SAFE_REASONS:
            raise ValueError("reason logging field is not allowlisted")
        return value
    if name == "gateway_reason":
        if not isinstance(value, str) or value not in _SAFE_GATEWAY_REASONS:
            raise ValueError("gateway_reason logging field is not allowlisted")
        return value
    if name == "filter_reason":
        if not isinstance(value, str) or value not in _SAFE_FILTER_REASONS:
            raise ValueError("filter_reason logging field is not allowlisted")
        return value
    if name == "demo_relaxation":
        if value != "BREACH_MONETARY_FAIL_SOFT":
            raise ValueError("demo relaxation logging field is not allowlisted")
        return value
    if name == "original_rejection_reason":
        if value not in {"MONETARY_VALUE", "MONETARY_CALCULATION"}:
            raise ValueError("original rejection reason is not allowlisted")
        return value
    if name == "category":
        if not isinstance(value, str) or value not in (
            _SAFE_CONTRACT_CATEGORIES | {"NONE"}
        ):
            raise ValueError("category logging field is not allowlisted")
        return value
    if name == "attention":
        if not isinstance(value, str) or value not in (_SAFE_ATTENTIONS | {"NONE"}):
            raise ValueError("attention logging field is not allowlisted")
        return value
    if name in {
        "accepted_categories",
        "filtered_categories",
        "missing_required_categories",
        "required_categories",
        "returned_categories",
        "correction_requested_categories",
        "correction_returned_categories",
    }:
        if not isinstance(value, str):
            raise ValueError("category-list logging field must be a string")
        categories = value.split(",")
        if not categories or any(
            category not in _SAFE_CONTRACT_CATEGORIES | {"NONE"}
            for category in categories
        ):
            raise ValueError("category-list logging field is not allowlisted")
        return ",".join(categories)
    if name == "filter_reason_counts":
        if value == "NONE":
            return value
        if not isinstance(value, str):
            raise ValueError("filter reason counts must be a string")
        entries = value.split(",")
        observed: set[str] = set()
        for entry in entries:
            reason, separator, raw_count = entry.partition(":")
            if (
                separator != ":"
                or reason not in _SAFE_FILTER_REASONS
                or reason in observed
                or not raw_count.isascii()
                or not raw_count.isdecimal()
                or int(raw_count) <= 0
            ):
                raise ValueError("filter reason counts are not allowlisted")
            observed.add(reason)
        return ",".join(entries)
    if name == "validation_error_class":
        if value != "ValidationError":
            raise ValueError("validation error class is not allowlisted")
        return value
    if name == "validation_schema_version":
        if value != "contract-provider-draft-v2":
            raise ValueError("validation schema is not allowlisted")
        return value
    if name == "validation_error_id":
        if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value):
            raise ValueError("validation error identity is invalid")
        return value
    if name == "validation_allowed_values":
        from audit_ai.model.contract_draft import Attention, ContractCategory, MaterialCategory
        from typing import get_args
        allowed = set(get_args(Attention)) | set(get_args(ContractCategory)) | set(get_args(MaterialCategory)) | {"NONE"}
        if not isinstance(value, str) or len(value) > 500 or any(token not in allowed for token in value.split(",")):
            raise ValueError("validation enum values are not allowlisted")
        return value
    if name == "validation_value_class":
        if value not in {"MISSING", "NULL", "BOOLEAN", "INTEGER", "NUMBER", "ARRAY", "OBJECT", "STRING",
                         "OTHER", "NULL_SENTINEL_STRING", "UNRECOGNIZED_STRING"}:
            raise ValueError("validation value classification is invalid")
        return value
    if name == "validation_actual_type":
        if value not in _SAFE_VALIDATION_ACTUAL_TYPES:
            raise ValueError("validation actual type is not allowlisted")
        return value
    if name == "validation_actual_enum":
        if value not in _SAFE_VALIDATION_ACTUAL_ENUMS:
            raise ValueError("validation actual enum is not allowlisted")
        return value
    if name == "provider_finish_reason":
        if value not in _SAFE_PROVIDER_FINISH_REASONS:
            raise ValueError("provider finish reason is not allowlisted")
        return value
    if name == "provider_content_shape":
        if value not in _SAFE_PROVIDER_CONTENT_SHAPES:
            raise ValueError("provider content shape is not allowlisted")
        return value
    if name == "json_error_kind":
        if value not in _SAFE_JSON_ERROR_KINDS:
            raise ValueError("json error kind is not allowlisted")
        return value
    if name in {"category_valid_shape", "attention_valid_shape"}:
        if value not in {"YES", "NO"}:
            raise ValueError("shape logging field must be YES or NO")
        return value
    if name in _COUNT_FIELDS:
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError("count logging fields must be non-negative integers")
        return value
    rendered = str(value)[:200]
    return _UNSAFE_LOG_CHARACTER.sub("_", rendered)
