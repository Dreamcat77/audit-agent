"""Internal model draft and public evidence reconstruction."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import hashlib
import json
from typing import Any, Literal, Mapping, Sequence

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment


_EVIDENCE_MATERIAL_CATEGORIES = frozenset(
    {
        "CONTRACT_BODY",
        "AWARD_NOTICE",
        "BID_RESPONSE",
        "TENDER_DOCUMENT",
        "SUPPLEMENT",
    }
)
_PUBLIC_EXCERPT_MAX_UTF8_BYTES = 1000
_STRICT_MACHINE_CONTRACT = ConfigDict(
    extra="forbid", populate_by_name=True, strict=True
)

ContractCategory = Literal[
    "CONTRACT_STRUCTURE",
    "PROJECT_IDENTITY",
    "SUPPLIER_IDENTITY",
    "DELIVERY_TERMS",
    "SERVICE_WARRANTY",
    "SCOPE_AND_SUBCONTRACTING",
    "BREACH_LIABILITY",
    "TERM_TERMINATION",
    "OTHER_TEXT",
]
Attention = Literal["INFO", "REVIEW", "INSUFFICIENT"]
MaterialCategory = Literal[
    "CONTRACT_BODY",
    "AWARD_NOTICE",
    "BID_RESPONSE",
    "TENDER_DOCUMENT",
    "SUPPLEMENT",
]


class ProviderHintDraft(BaseModel):
    """The only provider-owned fields accepted for one Semantic hint."""

    model_config = _STRICT_MACHINE_CONTRACT

    category: ContractCategory
    attention: Attention
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1, max_length=2000)
    problem_evidence_ids: list[str] = Field(
        alias="problemEvidenceIds", max_length=50
    )
    comparison_evidence_ids: list[str] = Field(
        alias="comparisonEvidenceIds", max_length=50
    )
    unavailable_material_category: MaterialCategory | None = Field(
        alias="unavailableMaterialCategory"
    )


class ProviderContractDraft(BaseModel):
    """Strict primary/synthesis response contract shared by schema and validator."""

    model_config = _STRICT_MACHINE_CONTRACT

    summary: str = Field(min_length=1, max_length=4000)
    hints: list[ProviderHintDraft] = Field(max_length=100)


class ProviderDraftFailureReason(str, Enum):
    MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD"
    TYPE_MISMATCH = "TYPE_MISMATCH"
    ENUM_VALUE_INVALID = "ENUM_VALUE_INVALID"
    EXTRA_PROPERTY = "EXTRA_PROPERTY"
    INVALID_TYPED_SCHEMA = "INVALID_TYPED_SCHEMA"


@dataclass(frozen=True, slots=True)
class ProviderValidationIssue:
    """Finite machine coordinates, never a provider value or property name."""

    field_path: str
    item_index: int | None
    error_kind: str
    actual_class: str
    allowed_values: tuple[str, ...] = ()
    null_allowed: bool = False
    category: ContractCategory | None = None
    schema_version: str = "contract-provider-draft-v2"

    def as_dict(self) -> dict[str, Any]:
        value = {"schemaVersion": self.schema_version, "fieldPath": self.field_path,
                 "itemIndex": self.item_index, "errorKind": self.error_kind,
                 "actualClass": self.actual_class, "allowedValues": list(self.allowed_values),
                 "nullAllowed": self.null_allowed}
        if self.category is not None:
            value["category"] = self.category
        return {**value, "errorId": hashlib.sha256(json.dumps(
            value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()}


class ProviderDraftValidationFailure(ValueError):
    def __init__(self, reason: ProviderDraftFailureReason, *,
                 safe_issues: tuple[ProviderValidationIssue, ...] = (), error_count: int = 0) -> None:
        super().__init__("semantic provider draft failed strict validation")
        self.reason = reason
        self.safe_issues = safe_issues
        self.error_count = error_count


def provider_contract_json_schema() -> dict[str, Any]:
    """Generate the provider schema from the exact runtime validation model."""

    return ProviderContractDraft.model_json_schema(by_alias=True)


def provider_contract_prompt_constraints() -> str:
    """PROMPT_ONLY and structured transports use the SAME runtime authority."""
    return "AUTHORITATIVE_PROVIDER_DRAFT_SCHEMA=" + json.dumps(
        provider_contract_json_schema(), ensure_ascii=False, separators=(",", ":"))


def _safe_validation_issue(error: Mapping[str, Any], payload: Mapping[str, Any]) -> ProviderValidationIssue:
    schema = provider_contract_json_schema()
    props = schema["$defs"]["ProviderHintDraft"]["properties"]
    aliases = {name: field.alias or name for name, field in ProviderHintDraft.model_fields.items()}
    names = set(props) | {"summary", "hints"}
    location = error.get("loc", ())
    path, item_index, field_name = "", None, None
    actual: Any = payload
    missing = False
    for part in location[:5]:
        if type(part) is int and 0 <= part < 100:
            path += f"[{part}]"
            if item_index is None:
                item_index = part
        else:
            safe = aliases.get(part, part) if isinstance(part, str) else "OTHER"
            safe = safe if safe in names else "OTHER"
            field_name = safe
            path += ("." if path else "") + safe
        try:
            actual = actual[part]
        except (KeyError, IndexError, TypeError):
            missing = True
            actual = None
    prop = props.get(field_name, {})
    allowed = prop.get("enum", next((part["enum"] for part in prop.get("anyOf", []) if "enum" in part), []))
    kind = error.get("type")
    kind = kind if kind in {"literal_error", "missing", "extra_forbidden", "string_type", "list_type",
                           "string_too_long", "string_too_short", "too_long", "model_type", "dict_type"} else "TYPE_OR_BOUND"
    classification = ("MISSING" if missing else "NULL" if actual is None else
                      "BOOLEAN" if type(actual) is bool else "INTEGER" if type(actual) is int else
                      "NUMBER" if type(actual) is float else "ARRAY" if isinstance(actual, list) else
                      "OBJECT" if isinstance(actual, dict) else "STRING" if isinstance(actual, str) else "OTHER")
    if isinstance(actual, str) and allowed:
        classification = ("NULL_SENTINEL_STRING" if actual.strip().lower() in {"null", "none", "n/a"}
                          else "UNRECOGNIZED_STRING")
    nullable = prop.get("type") == "null" or any(part.get("type") == "null" for part in prop.get("anyOf", []))
    return ProviderValidationIssue(path or "OTHER", item_index, kind, classification, tuple(allowed), nullable)


def parse_provider_contract_draft(
    payload: Mapping[str, Any],
) -> ProviderContractDraft:
    try:
        return ProviderContractDraft.model_validate(payload)
    except ValidationError as failure:
        errors = failure.errors(include_input=False, include_context=False, include_url=False)
        error_types = {str(error.get("type", "")) for error in errors}
        if "extra_forbidden" in error_types:
            reason = ProviderDraftFailureReason.EXTRA_PROPERTY
        elif "missing" in error_types:
            reason = ProviderDraftFailureReason.MISSING_REQUIRED_FIELD
        elif "literal_error" in error_types:
            reason = ProviderDraftFailureReason.ENUM_VALUE_INVALID
        elif error_types:
            reason = ProviderDraftFailureReason.TYPE_MISMATCH
        else:
            reason = ProviderDraftFailureReason.INVALID_TYPED_SCHEMA
        raise ProviderDraftValidationFailure(reason, safe_issues=tuple(
            _safe_validation_issue(error, payload) for error in errors[:8]), error_count=len(errors)) from None


def bounded_public_excerpt(value: str) -> str:
    """Return an exact Unicode prefix that fits the durable public DTO byte bound."""

    encoded = value.encode("utf-8")
    if len(encoded) <= _PUBLIC_EXCERPT_MAX_UTF8_BYTES:
        return value
    return encoded[:_PUBLIC_EXCERPT_MAX_UTF8_BYTES].decode("utf-8", errors="ignore")


@dataclass(frozen=True, slots=True)
class ModelHintDraft:
    category: str
    attention: str
    title: str
    description: str
    problem_evidence_ids: tuple[str, ...] = ()
    comparison_evidence_ids: tuple[str, ...] = ()
    unavailable_material_category: str | None = None


@dataclass(frozen=True, slots=True)
class PublicHintProjection:
    category: str
    attention: str
    title: str
    description: str
    evidence_refs: tuple[str, ...]
    evidence_items: tuple[dict[str, Any], ...]
    evidence_valid: bool

    def as_public_dict(self) -> dict[str, Any]:
        return {
            "category": self.category,
            "attention": self.attention,
            "title": self.title,
            "description": self.description,
            "evidenceRefs": list(self.evidence_refs),
            "evidenceItems": [dict(item) for item in self.evidence_items],
        }


def reconstruct_public_hint(
    draft: ModelHintDraft,
    catalog: EvidenceCatalog,
    *,
    available_material_categories: set[str],
    allowed_evidence_ids: set[str] | None = None,
) -> PublicHintProjection:
    """Resolve internal evidence IDs to the existing public evidence DTO shape."""

    problem_segments, problem_ids_valid = _resolve_citable_segments(
        draft.problem_evidence_ids, catalog, allowed_evidence_ids
    )
    comparison_segments, comparison_ids_valid = _resolve_citable_segments(
        draft.comparison_evidence_ids, catalog, allowed_evidence_ids
    )
    catalog_order = {
        segment.evidence_id: index
        for index, segment in enumerate(catalog.segments)
    }
    problem_segments.sort(key=lambda segment: catalog_order[segment.evidence_id])
    comparison_segments.sort(key=lambda segment: catalog_order[segment.evidence_id])
    evidence_valid = problem_ids_valid and comparison_ids_valid

    unavailable_category = draft.unavailable_material_category
    unavailable_valid = unavailable_category is None
    items: list[dict[str, Any]] = []
    selected_file_ids: set[str] = set()

    if unavailable_category is not None:
        unavailable_valid = (
            draft.attention == "INSUFFICIENT"
            and not problem_segments
            and unavailable_category in _EVIDENCE_MATERIAL_CATEGORIES
            and unavailable_category not in available_material_categories
            and bool(comparison_segments)
        )
        if unavailable_valid:
            anchor = comparison_segments[0]
            items.append(
                {
                    "fileId": anchor.file_id,
                    "status": "INSUFFICIENT",
                    "excerpt": None,
                    "unavailableMaterialCategory": unavailable_category,
                }
            )
            selected_file_ids.add(anchor.file_id)
            comparison_segments = []

    if unavailable_category is None:
        problem_status = (
            "REVIEW" if draft.attention == "REVIEW" else "INSUFFICIENT"
        )
        for segment in problem_segments:
            if segment.file_id not in selected_file_ids:
                items.append(_public_item(segment, problem_status))
                selected_file_ids.add(segment.file_id)
    for segment in comparison_segments:
        if segment.file_id not in selected_file_ids:
            items.append(_public_item(segment, "NORMAL"))
            selected_file_ids.add(segment.file_id)

    if draft.attention == "REVIEW":
        evidence_valid = evidence_valid and bool(problem_segments)
    elif draft.attention == "INSUFFICIENT":
        evidence_valid = evidence_valid and (
            bool(problem_segments)
            or (unavailable_category is not None and unavailable_valid)
        )
    else:
        evidence_valid = evidence_valid and not problem_segments and bool(
            comparison_segments
        )
    evidence_valid = evidence_valid and unavailable_valid

    refs: list[str] = []
    for item in items:
        file_id = item["fileId"]
        if file_id not in refs:
            refs.append(file_id)

    return PublicHintProjection(
        category=draft.category,
        attention=draft.attention,
        title=draft.title,
        description=draft.description,
        evidence_refs=tuple(refs),
        evidence_items=tuple(items),
        evidence_valid=evidence_valid,
    )


def _resolve_citable_segments(
    evidence_ids: Sequence[str],
    catalog: EvidenceCatalog,
    allowed_evidence_ids: set[str] | None,
) -> tuple[list[EvidenceSegment], bool]:
    segments: list[EvidenceSegment] = []
    seen: set[str] = set()
    valid = True
    for evidence_id in evidence_ids:
        if evidence_id in seen:
            continue
        seen.add(evidence_id)
        segment = catalog.by_id.get(evidence_id)
        if (
            segment is None
            or not segment.citable
            or (
                allowed_evidence_ids is not None
                and evidence_id not in allowed_evidence_ids
            )
        ):
            valid = False
            continue
        segments.append(segment)
    return segments, valid


def _public_item(segment: EvidenceSegment, status: str) -> dict[str, Any]:
    return {
        "fileId": segment.file_id,
        "status": status,
        "excerpt": bounded_public_excerpt(segment.exact_text),
    }
