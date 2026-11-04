"""Server-owned semantic correction plans and strict provider deltas."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import json
from typing import Any, Literal, Mapping, Sequence, TypeVar

from pydantic import BaseModel, ConfigDict, Field
from audit_ai.model.contract_draft import Attention, ContractCategory, MaterialCategory


_STRICT = ConfigDict(extra="forbid", populate_by_name=True)


class MissingCategoryHintDraft(BaseModel):
    model_config = _STRICT

    category: ContractCategory
    attention: Attention
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1, max_length=2000)
    problem_evidence_ids: tuple[str, ...] = Field(
        default=(), alias="problemEvidenceIds", max_length=50
    )
    comparison_evidence_ids: tuple[str, ...] = Field(
        default=(), alias="comparisonEvidenceIds", max_length=50
    )
    unavailable_material_category: MaterialCategory | None = Field(
        default=None, alias="unavailableMaterialCategory"
    )


class EvidenceRepairDelta(BaseModel):
    model_config = _STRICT

    hint_id: str = Field(alias="hintId", min_length=1, max_length=100)
    problem_evidence_ids: tuple[str, ...] = Field(
        default=(), alias="problemEvidenceIds", max_length=50
    )
    comparison_evidence_ids: tuple[str, ...] = Field(
        default=(), alias="comparisonEvidenceIds", max_length=50
    )
    unavailable_material_category: MaterialCategory | None = Field(
        default=None, alias="unavailableMaterialCategory"
    )


class CorrectionMode(str, Enum):
    EVIDENCE_REPAIR = "EVIDENCE_REPAIR"
    SEMANTIC_COMPLETENESS_CORRECTION = "SEMANTIC_COMPLETENESS_CORRECTION"
    COMBINED_CORRECTION = "COMBINED_CORRECTION"


class SemanticCompletenessCorrectionDraft(BaseModel):
    model_config = _STRICT

    missing_category_hints: tuple[MissingCategoryHintDraft, ...] = Field(
        alias="missingCategoryHints", max_length=100
    )


class EvidenceRepairCorrectionDraft(BaseModel):
    model_config = _STRICT

    evidence_repairs: tuple[EvidenceRepairDelta, ...] = Field(
        alias="evidenceRepairs", max_length=100
    )


class CombinedCorrectionDraft(BaseModel):
    model_config = _STRICT

    missing_category_hints: tuple[MissingCategoryHintDraft, ...] = Field(
        alias="missingCategoryHints", max_length=100
    )
    evidence_repairs: tuple[EvidenceRepairDelta, ...] = Field(
        alias="evidenceRepairs", max_length=100
    )


def contract_correction_json_schema(purpose: str) -> dict[str, Any]:
    """Generate the exact provider correction schema from runtime models."""

    models: dict[str, type[BaseModel]] = {
        "SEMANTIC_COMPLETENESS_CORRECTION": SemanticCompletenessCorrectionDraft,
        "EVIDENCE_REPAIR": EvidenceRepairCorrectionDraft,
        "COMBINED_CORRECTION": CombinedCorrectionDraft,
    }
    try:
        model = models[purpose]
    except KeyError as failure:
        raise ValueError("unsupported contract correction purpose") from failure
    return model.model_json_schema(by_alias=True)


@dataclass(frozen=True, slots=True)
class ParsedContractCorrection:
    missing_category_hints: tuple[MissingCategoryHintDraft, ...]
    evidence_repairs: tuple[EvidenceRepairDelta, ...]


class CorrectionFailureReason(str, Enum):
    SCHEMA_INVALID = "CORRECTION_SCHEMA_INVALID"
    CATEGORY_SET_MISMATCH = "CORRECTION_CATEGORY_SET_MISMATCH"
    REPAIR_TARGET_MISMATCH = "CORRECTION_REPAIR_TARGET_MISMATCH"
    EVIDENCE_INVALID = "CORRECTION_EVIDENCE_INVALID"
    SEMANTIC_INVALID = "CORRECTION_SEMANTIC_INVALID"
    UNPROCESSABLE_RESULT = "CORRECTION_UNPROCESSABLE_RESULT"


class ContractCorrectionFailure(ValueError):
    def __init__(self, reason: CorrectionFailureReason) -> None:
        super().__init__("contract correction failed safely")
        self.reason = reason


def parse_contract_correction(
    payload: Mapping[str, Any], *, mode: CorrectionMode
) -> ParsedContractCorrection:
    """Validate only the server-selected correction representation."""

    if mode is CorrectionMode.SEMANTIC_COMPLETENESS_CORRECTION:
        draft = SemanticCompletenessCorrectionDraft.model_validate(payload)
        return ParsedContractCorrection(
            missing_category_hints=draft.missing_category_hints,
            evidence_repairs=(),
        )
    if mode is CorrectionMode.EVIDENCE_REPAIR:
        draft = EvidenceRepairCorrectionDraft.model_validate(payload)
        return ParsedContractCorrection(
            missing_category_hints=(),
            evidence_repairs=draft.evidence_repairs,
        )
    draft = CombinedCorrectionDraft.model_validate(payload)
    return ParsedContractCorrection(
        missing_category_hints=draft.missing_category_hints,
        evidence_repairs=draft.evidence_repairs,
    )


@dataclass(frozen=True, slots=True)
class EvidenceRepairTarget:
    hint_id: str
    category: str
    attention: str
    title: str
    description: str


@dataclass(frozen=True, slots=True)
class ContractCorrectionPlan:
    missing_required_categories: tuple[str, ...]
    evidence_repair_targets: tuple[EvidenceRepairTarget, ...]
    # Safe coordinates only, never the rejected provider text.
    rejected_hint_reasons: tuple[tuple[int, str, str], ...] = ()

    @property
    def mode(self) -> CorrectionMode:
        if self.missing_required_categories and self.evidence_repair_targets:
            return CorrectionMode.COMBINED_CORRECTION
        if self.missing_required_categories:
            return CorrectionMode.SEMANTIC_COMPLETENESS_CORRECTION
        if self.evidence_repair_targets:
            return CorrectionMode.EVIDENCE_REPAIR
        raise ValueError("correction plan must contain at least one semantic defect")


_HintT = TypeVar("_HintT")


def assign_server_hint_ids(
    hints: Sequence[_HintT], *, operation_key: str
) -> tuple[_HintT, ...]:
    """Replace any provider-supplied identity with stable request-local IDs."""

    if not operation_key or not operation_key.strip():
        raise ValueError("operation_key must not be blank")
    assigned: list[_HintT] = []
    for index, hint in enumerate(hints, start=1):
        model_copy = getattr(hint, "model_copy", None)
        if not callable(model_copy):
            raise TypeError("server hint must support immutable model_copy")
        assigned.append(model_copy(update={"hint_id": f"{operation_key}:H{index}"}))
    return tuple(assigned)


def build_correction_plan(
    hints: Sequence[Any],
    *,
    missing_required_categories: Sequence[str],
    evidence_repair_hint_ids: Sequence[str],
    rejected_hint_reasons: Sequence[tuple[int, str, str]] = (),
) -> ContractCorrectionPlan:
    """Build one immutable plan from server-assigned hint identities."""

    missing_categories = tuple(missing_required_categories)
    if len(set(missing_categories)) != len(missing_categories):
        raise ValueError("duplicate missing category")

    requested_hint_ids = tuple(evidence_repair_hint_ids)
    if len(set(requested_hint_ids)) != len(requested_hint_ids):
        raise ValueError("duplicate hintId")
    by_id = {
        hint_id: hint
        for hint in hints
        if isinstance((hint_id := getattr(hint, "hint_id", None)), str)
        and hint_id
    }
    targets: list[EvidenceRepairTarget] = []
    for hint_id in requested_hint_ids:
        hint = by_id.get(hint_id)
        if hint is None:
            raise ValueError("unknown hintId")
        targets.append(
            EvidenceRepairTarget(
                hint_id=hint_id,
                category=hint.category,
                attention=hint.attention,
                title=hint.title,
                description=hint.description,
            )
        )

    plan = ContractCorrectionPlan(
        missing_required_categories=missing_categories,
        evidence_repair_targets=tuple(targets),
        rejected_hint_reasons=tuple(
            (ordinal, category, reason)
            for ordinal, category, reason in rejected_hint_reasons
            if category in missing_categories
        ),
    )
    _ = plan.mode
    return plan


def render_correction_source(
    plan: ContractCorrectionPlan,
    hints: Sequence[Any],
    *,
    original_operation_source: str,
) -> str:
    """Keep the original visible source and add only server-owned correction metadata."""

    frozen_hints = [
        {
            "hintId": hint.hint_id,
            "category": hint.category,
            "attention": hint.attention,
            "title": hint.title,
            "description": hint.description,
        }
        for hint in hints
    ]
    payload = {
        "correctionPlan": {
            "mode": plan.mode.value,
            "missingRequiredCategories": list(plan.missing_required_categories),
            "evidenceRepairTargets": [
                {"hintId": target.hint_id}
                for target in plan.evidence_repair_targets
            ],
            "rejectedHintReasons": [
                {"providerOrdinal": ordinal, "category": category, "reason": reason}
                for ordinal, category, reason in plan.rejected_hint_reasons
            ],
            "validationPolicy": {
                "absoluteMonetaryValues": "FORBIDDEN",
                "monetaryArithmetic": "FORBIDDEN",
                "strongVerdicts": "FORBIDDEN",
                "evidenceIdentity": "SUPPLIED_CITABLE_ONLY",
                "allowedQuantifiedSemantics": [
                    "PERCENTAGE_RATE", "DATE", "DURATION", "FREQUENCY", "NON_MONETARY_THRESHOLD"
                ],
            },
        },
        "frozenHints": frozen_hints,
        "originalOperationSource": original_operation_source,
    }
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
