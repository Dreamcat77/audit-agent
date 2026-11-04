"""Typed contract-text and budget-semantic model routes."""

from __future__ import annotations

import json
import logging
import re
import time
import unicodedata
from collections.abc import Callable, Mapping
from dataclasses import dataclass, replace
from enum import Enum
from typing import Any
from typing import Literal

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.concurrency import run_in_threadpool

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.logging import log_event, diagnostic_context
from audit_ai.diagnostics import diagnosed
from audit_ai.model.chunker import BoundedChunker, ModelInputTooLarge
from audit_ai.model.adaptive_calls import adaptive_calls
from audit_ai.model.tender_chunks import prepare_tender, render_tender, split_units, crosscheck_tender
from audit_ai.model.tender_reconciliation import reconciliation_plans, apply_decision
from audit_ai.model.material_fields import text_parts
from audit_ai.model.contract_draft import (
    ModelHintDraft,
    ProviderValidationIssue,
    bounded_public_excerpt,
    reconstruct_public_hint,
)
from audit_ai.model.contract_correction import (
    ContractCorrectionFailure,
    ContractCorrectionPlan,
    CorrectionFailureReason,
    assign_server_hint_ids,
    build_correction_plan,
    parse_contract_correction,
    render_correction_source,
)
from audit_ai.model.evidence import (
    EvidenceCatalog,
    EvidenceSource,
    EvidenceBatch,
    plan_evidence_batch_members,
    segment_evidence_sources,
)
from audit_ai.model.contract_draft import (
    ProviderDraftValidationFailure,
    parse_provider_contract_draft,
)
from audit_ai.model.gateway import (
    BudgetSemanticOperation,
    ContractTextOperation,
    ModelGatewayFailure,
    ModelGatewayPort,
    ModelNotConfigured,
    ModelTotalBudgetExhausted,
    SpreadsheetHeaderMappingOperation,
    TenderComplianceOperation,
)
from audit_ai.schemas import ModelProvenance


_STRICT = ConfigDict(extra="forbid", populate_by_name=True)
LOGGER = logging.getLogger("audit_ai.model.router")
_STRONG_CONTRACT_VERDICT = re.compile(
    r"确定违法|确定违规|审核通过|审查通过|合同合法有效|不存在风险|无风险|无任何风险|"
    r"存在串标|涉嫌串标|必须驳回|应当驳回|"
    r"(?:合同|条款|文本)?.{0,4}完全合规|"
    r"(?:未发现|不存在).{0,8}(?:违法|违规)|"
    r"(?:合同|条款|文本)?.{0,4}符合.{0,8}(?:法律法规|法律|法规|监管规定)|"
    r"认定(?:为)?(?:合规|违规|违法|无效)|"
    r"\b(?:pass(?:ed)?|compliant|non-compliant|illegal|no risk)\b",
    re.IGNORECASE,
)
_NUMBER = r"[+-]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?"
_UNSIGNED_NUMBER = r"(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?"
_CHINESE_MONEY = r"[零〇一二三四五六七八九壹贰叁肆伍陆柒捌玖拾佰仟万亿圆元角分整正]+"
_MONETARY_UNIT = r"(?:万元|元)"
_PER_UNIT = r"(?:\s*/\s*(?:台|套|件|项))?"
_MONETARY_VALUE = re.compile(
    rf"(?:[￥¥]\s*{_NUMBER}(?:\s*{_MONETARY_UNIT})?{_PER_UNIT}"
    rf"|{_NUMBER}\s*{_MONETARY_UNIT}(?:整|正)?{_PER_UNIT}"
    rf"|人民币\s*(?:{_NUMBER}(?:\s*{_MONETARY_UNIT})?{_PER_UNIT}|{_CHINESE_MONEY}))",
    re.IGNORECASE,
)
_GENERIC_ARITHMETIC = re.compile(
    rf"{_UNSIGNED_NUMBER}\s*[×xX*÷/+＋\-－]\s*{_UNSIGNED_NUMBER}"
    rf"(?:\s*[=＝]\s*{_UNSIGNED_NUMBER})?"
)
_MONETARY_CONTEXT_TERM = (
    r"(?:合同总金额|合同金额|合同价款|合同价|中标金额|中标价|成交金额|成交价|预算金额|预算价|"
    r"申报金额|申报价|金额|价款|单价|总价|"
    r"报价|价格|费用|差额|预算结余|预算|节资率|阈值|amount|price|threshold)"
)
_ABSOLUTE_MONEY_CONTEXT_TERM = (
    r"(?:合同总金额|合同金额|合同价款|合同价|中标金额|中标价|成交金额|成交价|预算金额|预算价|"
    r"申报金额|申报价|金额|价款|单价|总价|"
    r"报价|价格|费用|差额|预算结余|预算|amount|price)"
)
_MONETARY_CONTEXT = re.compile(_MONETARY_CONTEXT_TERM, re.IGNORECASE)
_MONETARY_RELATION = (
    r"(?:为|是|高于|低于|大于|小于|等于|超过|超出|少于|多于|相等|相同|一致|不一致|"
    r"差异|结余|计算|测算|达到|未达到|exceed(?:s|ed)?|higher|lower|equals?)"
)
_MONETARY_CONCLUSION = re.compile(
    rf"(?:{_MONETARY_CONTEXT_TERM}.{{0,16}}{_MONETARY_RELATION}"
    rf"|{_MONETARY_RELATION}.{{0,16}}{_MONETARY_CONTEXT_TERM})",
    re.IGNORECASE,
)
_LABELLED_ABSOLUTE_NUMBER = re.compile(
    rf"{_ABSOLUTE_MONEY_CONTEXT_TERM}\s*(?::|：|为|是)?\s*{_NUMBER}"
    rf"(?![\d,.]*\s*[%％‰])",
    re.IGNORECASE,
)
_RATE_VALUE = rf"{_UNSIGNED_NUMBER}\s*[%％‰]"
_MONETARY_RATE_BASIS = re.compile(
    rf"{_ABSOLUTE_MONEY_CONTEXT_TERM}(?:的)?\s*{_RATE_VALUE}",
    re.IGNORECASE,
)
_MONETARY_RATE_BASIS_TEXT = (
    rf"{_ABSOLUTE_MONEY_CONTEXT_TERM}(?:的)?\s*{_RATE_VALUE}"
)
_RATE_PERIOD = r"(?:\s*/\s*(?:日|天|周|月|年|次|项|笔))?"
_RATE_CONTROLLED_ACTION = r"(?:\s*(?:计算|计取|收取|扣收|扣除))?"
_MONETARY_RATE_PAYMENT_CLAUSE = re.compile(
    rf"\s*(?:合同(?:约定)?\s*)?(?:按|以)\s*{_MONETARY_RATE_BASIS_TEXT}"
    rf"{_RATE_PERIOD}"
    rf"\s*(?:支付|计取|计算|收取|扣收|扣除)\s*(?:违约金|赔偿金)"
    rf"\s*",
    re.IGNORECASE,
)
_MONETARY_RATE_CAP_CLAUSE = re.compile(
    rf"\s*(?:合同(?:约定)?)?(?:累计)?(?:违约金|赔偿金|违约责任)"
    rf"(?:约定)?(?:累计)?"
    rf"(?:最高不超过|不超过|上限(?:为)?|最高限额(?:为)?)\s*"
    rf"{_MONETARY_RATE_BASIS_TEXT}\s*"
    rf"|\s*(?:以|按)\s*{_MONETARY_RATE_BASIS_TEXT}"
    rf"\s*(?:为|作为)\s*"
    rf"(?:(?:违约金|赔偿金)(?:约定)?(?:累计)?|累计)"
    rf"(?:上限|最高限额)\s*"
    rf"|\s*累计(?:最高)?不超过\s*{_MONETARY_RATE_BASIS_TEXT}\s*",
    re.IGNORECASE,
)
_MONETARY_RATE_ASSIGNMENT_CLAUSE = re.compile(
    rf"\s*(?:(?:违约金|赔偿金)(?:比例)?|(?:(?:合同)?约定)?违约责任比例)"
    rf"\s*(?:为|按|以)\s*"
    rf"{_MONETARY_RATE_BASIS_TEXT}{_RATE_PERIOD}{_RATE_CONTROLLED_ACTION}\s*"
    rf"|\s*(?:按|以)?\s*{_MONETARY_RATE_BASIS_TEXT}{_RATE_PERIOD}\s*"
    rf"(?:为|作为)\s*(?:约定)?(?:违约金|赔偿金|违约责任)(?:比例)?"
    rf"{_RATE_CONTROLLED_ACTION}(?:承担)?\s*",
    re.IGNORECASE,
)
_STANDALONE_MONETARY_RATE_BASIS = re.compile(
    rf"\s*(?:按|以)?\s*{_ABSOLUTE_MONEY_CONTEXT_TERM}(?:的)?\s*{_RATE_VALUE}"
    rf"(?:\s*/\s*(?:日|天|周|月|年|次|项|笔))?\s*",
    re.IGNORECASE,
)
_TRIGGERED_MONETARY_RATE_PAYMENT = re.compile(
    rf"\s*(?:合同(?:约定)?\s*)?(?:(?:甲方|乙方|采购人|供应商)\s*)?"
    rf"(?:(?:逾期交付|延期交付|未按期完成交付|未在约定期限(?:内)?完成交付|"
    rf"未按约定时间完成交付)(?:时|的)?\s*)?"
    rf"(?:每(?:延期|逾期)?满?(?:一|1)?(?:日|天|周|月)\s*)?"
    rf"(?:应当|应)?\s*"
    rf"(?:按|以)\s*{_MONETARY_RATE_BASIS_TEXT}"
    rf"(?:\s*(?:/\s*(?:日|天|周|月|年|次|项|笔)|每(?:日|天|周|月|年)))?"
    rf"\s*(?:支付|计取|计算|收取|扣收|扣除)\s*(?:违约金|赔偿金)\s*",
    re.IGNORECASE,
)
_NAMED_OR_IMPLICIT_MONETARY_RATE_CAP = re.compile(
    rf"\s*累计(?:违约金|赔偿金)?上限(?:为|是)?\s*"
    rf"{_MONETARY_RATE_BASIS_TEXT}\s*",
    re.IGNORECASE,
)
_BREACH_LIABILITY_TERM = re.compile(
    r"违约金|赔偿金|违约责任|breach|penalt(?:y|ies)",
    re.IGNORECASE,
)
_MONETARY_RATE_COMPARISON = re.compile(
    rf"{_MONETARY_CONTEXT_TERM}.{{0,12}}(?:较|比).{{0,12}}"
    rf"{_MONETARY_CONTEXT_TERM}.{{0,12}}"
    rf"(?:高|低|多|少|增加|减少|上升|下降).{{0,8}}"
    rf"{_UNSIGNED_NUMBER}\s*[%％‰]",
    re.IGNORECASE,
)
_CLAUSE = re.compile(r"[^。！？!?；;，,\r\n]+")
_IDENTIFIER_DOCUMENT_COMPARISON = re.compile(
    r"(?:编号|序号|编码|行号|代码|标识)(?:均|都|分别|一一|必须)?"
    r"(?:与|和|及|对应于?|对照(?:的)?|比对(?:的)?)"
    r"(?:对照的|对应的|分项)?(?P<quote>报价)(?:表|单)"
    r"(?:(?:中|内|上|所列|的)?(?:条目|明细)?"
    r"(?:编号|序号|编码|行号|代码|标识)|内容)?"
    r"(?:均|都|保持|一一)?(?:一致|相同)"
)
_DOCUMENT_IDENTIFIER_COMPARISON = re.compile(
    r"(?P<quote>报价)(?:表|单)(?:(?:中|内|所列|的)?(?:条目|明细|分项))?"
    r"(?:编号|序号|编码|行号|代码|标识)"
    r"(?:均|都|保持|一一)?(?:一致|相同)(?:性)?"
)
_MONETARY_CONTEXT_WINDOW_CHARS = 32
_MONETARY_REASONS = frozenset({"MONETARY_VALUE", "MONETARY_CALCULATION"})
_CONTRACT_SYNTHESIS_MAX_CHARS = 6000
_CONTRACT_SYNTHESIS_SUMMARY_CHARS = 120
_CONTRACT_SYNTHESIS_TITLE_CHARS = 140
_CONTRACT_SYNTHESIS_DESCRIPTION_CHARS = 400
_CONTRACT_SYNTHESIS_EXCERPT_CHARS = 200
_SAFE_FILTERED_SUMMARY = (
    "已完成合同文字语义分析，请结合来源材料进行人工复核。"
)
_EMPTY_SAFE_SUMMARY = (
    "本轮未生成可展示的文字语义建议，请结合精确校验及来源材料进行复核。"
)
_SYNTHESIS_BOUND_FALLBACK_SUMMARY = (
    "已完成分段合同文字语义分析；由于材料较长，请结合各项来源材料进行人工复核。"
)
_DEMO_BREACH_SAFE_COPY = {
    "REVIEW": (
        "违约责任条款需复核",
        "已识别违约责任相关条款或跨材料差异，请结合来源材料人工复核。",
    ),
    "INFO": (
        "违约责任条款",
        "已识别违约责任相关条款，请结合来源材料核对。",
    ),
}


def _has_monetary_calculation(output_text: str) -> bool:
    """Reject arithmetic near money while retaining clause rates and thresholds."""

    for clause_match in _CLAUSE.finditer(output_text):
        clause = clause_match.group(0)
        for expression in _GENERIC_ARITHMETIC.finditer(clause):
            context_start = max(
                0, expression.start() - _MONETARY_CONTEXT_WINDOW_CHARS
            )
            context_end = min(
                len(clause), expression.end() + _MONETARY_CONTEXT_WINDOW_CHARS
            )
            if _MONETARY_CONTEXT.search(clause[context_start:context_end]):
                return True
    return False


def _is_safe_monetary_rate_clause(clause: str) -> bool:
    """Recognize one monetary field used only as a percentage-rate basis."""

    return bool(
        _MONETARY_RATE_BASIS.search(clause)
        and len(_MONETARY_CONTEXT.findall(clause)) == 1
        and (
            _MONETARY_RATE_PAYMENT_CLAUSE.fullmatch(clause)
            or _MONETARY_RATE_CAP_CLAUSE.fullmatch(clause)
            or _MONETARY_RATE_ASSIGNMENT_CLAUSE.fullmatch(clause)
            or _STANDALONE_MONETARY_RATE_BASIS.fullmatch(clause)
            or _TRIGGERED_MONETARY_RATE_PAYMENT.fullmatch(clause)
            or _NAMED_OR_IMPLICIT_MONETARY_RATE_CAP.fullmatch(clause)
        )
    )


def _requires_quantified_breach_hint(source_text: str) -> bool:
    """Identify visible quantified breach clauses without deriving money facts."""

    return any(
        _BREACH_LIABILITY_TERM.search(clause)
        and _MONETARY_RATE_BASIS.search(clause)
        for clause in _CLAUSE.findall(source_text)
    )


def _normalized_locator_text(value: str) -> str:
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value))


def _excerpt_occurs_in_material(excerpt: str, material_text: str) -> bool:
    needle = _normalized_locator_text(excerpt)
    return bool(needle) and needle in _normalized_locator_text(material_text)


def _explicitly_reports_missing_material(
    title: str, material_category: str,
) -> bool:
    text = _normalized_locator_text(title).casefold().strip("。！？:：;；")
    terms = _MISSING_MATERIAL_TERMS.get(material_category, ())
    if not terms:
        return False
    marker = "(?:" + "|".join(map(re.escape, _MISSING_MATERIAL_MARKERS)) + ")"
    term = "(?:" + "|".join(map(re.escape, terms)) + ")"
    qualifier = "(?:整份|完整|必要|完整的|必要的)?"
    material_suffix = "(?:材料|文件)?"
    return bool(
        re.fullmatch(rf"{marker}{qualifier}{term}{material_suffix}", text)
        or re.fullmatch(rf"{qualifier}{term}{material_suffix}{marker}", text)
    )


def _monetary_field_identity(field: str) -> str:
    normalized = field.lower()
    aliases = (
        (("合同总金额", "合同金额", "合同价款", "合同价"), "CONTRACT_AMOUNT"),
        (("中标金额", "中标价"), "AWARD_AMOUNT"),
        (("成交金额", "成交价"), "DEAL_AMOUNT"),
        (("预算金额", "预算价", "预算"), "BUDGET_AMOUNT"),
        (("申报金额", "申报价"), "DECLARED_AMOUNT"),
    )
    for values, identity in aliases:
        if normalized in values:
            return identity
    return normalized


def _has_monetary_field_comparison(output_text: str) -> bool:
    fields = list(_MONETARY_CONTEXT.finditer(output_text))
    safe_rate_spans = [clause.span() for clause in _CLAUSE.finditer(output_text)
                       if _is_safe_monetary_rate_clause(clause.group(0))]
    def is_rate_basis(field):
        return any(start <= field.start() and field.end() <= end
                   for start, end in safe_rate_spans)
    for left_index, left in enumerate(fields):
        left_identity = _monetary_field_identity(left.group(0))
        for right in fields[left_index + 1:]:
            if right.start() - left.end() > 64:
                break
            if left_identity == _monetary_field_identity(right.group(0)):
                continue
            comparison_end = min(len(output_text), right.end() + 24)
            # Only the grammar inside independently complete rate clauses is
            # exempt. Keep comparisons outside them, including a third clause
            # "二者一致" after TWO safe rate clauses, as well as safe+unsafe pairs.
            if is_rate_basis(left) and is_rate_basis(right):
                remainder, cursor = [], left.start()
                for start, end in safe_rate_spans:
                    if start >= comparison_end:
                        break
                    if end <= cursor:
                        continue
                    remainder.append(output_text[cursor:max(cursor, start)])
                    cursor = min(comparison_end, end)
                remainder.append(output_text[cursor:comparison_end])
                if not re.search(_MONETARY_RELATION, "".join(remainder), re.IGNORECASE):
                    continue
            comparison_window = output_text[
                left.start():comparison_end
            ]
            if re.search(_MONETARY_RELATION, comparison_window, re.IGNORECASE):
                return True
    return False


def _identifier_document_comparison_view(output_text: str) -> str:
    """Disambiguate proven document names, never rewrite findings or money checks."""

    view = list(output_text)
    for clause_match in _CLAUSE.finditer(output_text):
        clause = clause_match.group(0)
        comparisons = [*list(_IDENTIFIER_DOCUMENT_COMPARISON.finditer(clause)),
                       *list(_DOCUMENT_IDENTIFIER_COMPARISON.finditer(clause))]
        document_spans = {match.span("quote") for match in comparisons}
        # A nearby identifier is not a blanket waiver. Every monetary term in
        # this entire clause must be one of the independently proven names;
        # actual price/amount fields or another quote verdict keep the guard.
        if not document_spans or any(
            field.span() not in document_spans
            for field in _MONETARY_CONTEXT.finditer(clause)
        ):
            continue
        # Preserve detection of additional judgments even when their financial
        # noun is not in the finite vocabulary (e.g. freight or tax). Neither an
        # earlier nor later relation may borrow the identifier's exception.
        if any(
            not any(match.start() <= relation.start()
                    and relation.end() <= match.end() for match in comparisons)
            for relation in re.finditer(_MONETARY_RELATION, clause, re.IGNORECASE)
        ):
            continue
        for start, end in document_spans:
            view[clause_match.start() + start:clause_match.start() + end] = (
                " " * (end - start)
            )
    return "".join(view)


def _has_unsafe_monetary_conclusion(output_text: str) -> bool:
    # Only this lexical comparison view is disambiguated. Absolute amounts,
    # labelled numbers, arithmetic and rate comparisons still see original text.
    output_text = _identifier_document_comparison_view(output_text)
    if _has_monetary_field_comparison(output_text):
        return True
    for clause_match in _CLAUSE.finditer(output_text):
        clause = clause_match.group(0)
        if (
            _MONETARY_CONCLUSION.search(clause)
            and not _is_safe_monetary_rate_clause(clause)
        ):
            return True
    return False


class ContractMaterialRequest(BaseModel):
    model_config = _STRICT

    file_id: str = Field(alias="fileId", min_length=1, max_length=100)
    category: str = Field(min_length=1, max_length=100)
    text: str = Field(min_length=1)


class ContractAnalysisRequest(BaseModel):
    model_config = _STRICT

    project_id: str = Field(alias="projectId", min_length=1, max_length=100)
    materials: list[ContractMaterialRequest] = Field(min_length=1, max_length=100)
    semantic_quality_protocol: Literal["contract-semantic-quality-v1"] | None = Field(
        default=None, alias="semanticQualityProtocol")
    semantic_money_mode: Literal["STRICT", "OBSERVE"] = Field(default="STRICT", alias="semanticMoneyMode")
    semantic_hierarchy_protocol: Literal["contract-bounded-hierarchy-v1", "contract-bounded-hierarchy-v2"] | None = Field(
        default=None, alias="semanticHierarchyProtocol")


class ContractSemanticAdvanceRequest(BaseModel):
    model_config = _STRICT
    request: ContractAnalysisRequest
    plan_fingerprint: str = Field(alias="planFingerprint", pattern=r"^[0-9a-f]{64}$")
    checkpoints: list[dict[str, Any]] = Field(max_length=64)
    expected_operation_fingerprint: str | None = Field(default=None, alias="expectedOperationFingerprint", pattern=r"^[0-9a-f]{64}$")
    remaining_ms: int = Field(alias="remainingMs", gt=0, le=3_600_000)
    execute: bool = False


class BudgetItemRequest(BaseModel):
    model_config = _STRICT

    item_id: str = Field(alias="itemId", min_length=1, max_length=100)
    description: str = Field(min_length=1)
    current_category: str | None = Field(
        default=None, alias="currentCategory", max_length=100
    )
    analysis_type: str = Field(
        default="GENERAL", alias="analysisType", min_length=1, max_length=50
    )
    subject_code: str | None = Field(
        default=None, alias="subjectCode", max_length=100
    )
    category_code: str | None = Field(
        default=None, alias="categoryCode", max_length=100
    )
    declared_subject_name: str | None = Field(
        default=None, alias="declaredSubjectName", max_length=200
    )
    approved_subject_name: str | None = Field(
        default=None, alias="approvedSubjectName", max_length=200
    )
    exception_type: str | None = Field(
        default=None, alias="exceptionType", max_length=100
    )
    exception_data: str | None = Field(
        default=None, alias="exceptionData", max_length=2000
    )


class BudgetAnalysisRequest(BaseModel):
    model_config = _STRICT

    source_id: str = Field(alias="sourceId", min_length=1, max_length=100)
    items: list[BudgetItemRequest] = Field(min_length=1, max_length=500)


class TenderSegmentRequest(BaseModel):
    model_config = _STRICT

    source_locator: str = Field(alias="sourceLocator", min_length=1, max_length=500)
    text: str = Field(min_length=1, max_length=100000)
    page_number: int | None = Field(default=None, alias="pageNumber", ge=1)
    confidence: float | None = Field(default=None, ge=0, le=1)


class TenderRuleFindingRequest(BaseModel):
    model_config = _STRICT

    rule_code: str = Field(alias="ruleCode", min_length=1, max_length=64)
    risk_category: str = Field(alias="riskCategory", min_length=1, max_length=64)
    risk_level: Literal["CRITICAL", "WARNING", "INFO"] = Field(alias="riskLevel")
    matched_text: str = Field(alias="matchedText", min_length=1, max_length=1000)
    source_locator: str = Field(alias="sourceLocator", min_length=1, max_length=500)
    remediation_suggestion: str = Field(alias="remediationSuggestion", min_length=1, max_length=2000)


class TenderComplianceRequest(BaseModel):
    model_config = _STRICT

    project_id: str = Field(alias="projectId", min_length=1, max_length=100)
    review_id: str = Field(alias="reviewId", min_length=1, max_length=100)
    full_text: str = Field(alias="fullText", min_length=1, max_length=500000)
    segments: list[TenderSegmentRequest] = Field(min_length=1, max_length=500)
    rule_findings: list[TenderRuleFindingRequest] = Field(alias="ruleFindings", max_length=500)


class HeaderPreviewRowRequest(BaseModel):
    model_config = _STRICT

    row_number: int = Field(alias="rowNumber", ge=1)
    cells: list[Any] = Field(max_length=200)


class HeaderMappingRequest(BaseModel):
    model_config = _STRICT

    source_id: str = Field(alias="sourceId", min_length=1, max_length=100)
    file_format: str = Field(alias="fileFormat", min_length=1, max_length=30)
    allowed_fields: list[str] = Field(alias="allowedFields", min_length=1, max_length=20)
    rows: list[HeaderPreviewRowRequest | list[Any]] = Field(min_length=1, max_length=20)


class ContractEvidenceItem(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    file_id: str = Field(alias="fileId", min_length=1, max_length=100)
    status: Literal["NORMAL", "REVIEW", "INSUFFICIENT"]
    excerpt: str | None = Field(default=None, max_length=1000)
    unavailable_material_category: Literal[
        "CONTRACT_BODY",
        "AWARD_NOTICE",
        "BID_RESPONSE",
        "TENDER_DOCUMENT",
        "SUPPLEMENT",
    ] | None = Field(default=None, alias="unavailableMaterialCategory")


class ContractHint(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    category: Literal[
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
    attention: Literal["INFO", "REVIEW", "INSUFFICIENT"]
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1, max_length=2000)
    evidence_refs: list[str] = Field(
        alias="evidenceRefs", max_length=50
    )
    evidence_items: list[ContractEvidenceItem] | None = Field(
        default=None, alias="evidenceItems", max_length=50
    )
    problem_evidence_ids: tuple[str, ...] = Field(default=(), exclude=True)
    comparison_evidence_ids: tuple[str, ...] = Field(default=(), exclude=True)
    hint_id: str | None = Field(default=None, exclude=True)


class ContractChunkResult(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    summary: str = Field(min_length=1, max_length=4000)
    hints: list[ContractHint] = Field(max_length=100)


class BudgetItemResult(BaseModel):
    model_config = _STRICT

    item_id: str = Field(alias="itemId", min_length=1, max_length=100)
    suggested_category: str | None = Field(
        default=None, alias="suggestedCategory", max_length=100
    )
    hint: str | None = Field(default=None, max_length=1000)
    confidence: float | None = Field(default=None, ge=0, le=1)
    judgment: str | None = Field(default=None, max_length=200)
    similarity_score: float | None = Field(
        default=None, alias="similarityScore", ge=0, le=1
    )
    enhanced_description: str | None = Field(
        default=None, alias="enhancedDescription", max_length=2000
    )
    suggestion: str | None = Field(default=None, max_length=1000)
    needs_confirmation: bool | None = Field(default=None, alias="needsConfirmation")


class BudgetChunkResult(BaseModel):
    model_config = _STRICT

    items: list[BudgetItemResult] = Field(max_length=500)


class TenderFindingResult(BaseModel):
    model_config = _STRICT

    matched_text: str = Field(alias="matchedText", min_length=1, max_length=1000)
    source_locator: str = Field(alias="sourceLocator", min_length=1, max_length=500)
    page_number: int | None = Field(default=None, alias="pageNumber", ge=1)
    risk_category: str = Field(alias="riskCategory", min_length=1, max_length=64)
    risk_level: Literal["CRITICAL", "WARNING", "INFO"] = Field(alias="riskLevel")
    rationale: str = Field(min_length=1, max_length=2000)
    remediation_suggestion: str = Field(alias="remediationSuggestion", min_length=1, max_length=2000)
    confidence: float = Field(ge=0, le=1)


class TenderComplianceChunkResult(BaseModel):
    model_config = _STRICT

    findings: list[TenderFindingResult] = Field(max_length=200)


class ContractModelCallData(BaseModel):
    model_config = _STRICT

    ordinal: int = Field(gt=0)
    phase: Literal["TRANSPORT", "REPAIR"]
    outcome: Literal["SUCCEEDED", "FAILED"]
    safe_reason: str | None = Field(default=None, alias="safeReason", max_length=64)
    duration_ms: int = Field(alias="durationMs", ge=0)
    finish_reason: str | None = Field(default=None, alias="finishReason", max_length=64)
    prompt_tokens: int | None = Field(default=None, alias="promptTokens", ge=0)
    completion_tokens: int | None = Field(default=None, alias="completionTokens", ge=0)
    total_tokens: int | None = Field(default=None, alias="totalTokens", ge=0)
    provider: str = Field(min_length=1, max_length=100)
    model: str = Field(min_length=1, max_length=200)


class ContractAnalysisData(ModelProvenance):
    operation: Literal["CONTRACT_TEXT_ANALYSIS"]
    summaries: list[str]
    hints: list[ContractHint]
    model_calls: list[ContractModelCallData] = Field(alias="modelCalls")


class BudgetAnalysisData(ModelProvenance):
    operation: Literal["BUDGET_SEMANTIC_ANALYSIS"]
    items: list[BudgetItemResult]


class TenderComplianceData(ModelProvenance):
    operation: Literal["PROCUREMENT_TENDER_COMPLIANCE"]
    findings: list[TenderFindingResult]


class ContractEnvelope(BaseModel):
    code: int
    message: str
    data: ContractAnalysisData


class BudgetEnvelope(BaseModel):
    code: int
    message: str
    data: BudgetAnalysisData


class TenderComplianceEnvelope(BaseModel):
    code: int
    message: str
    data: TenderComplianceData


class HeaderMapping(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    source_header: str = Field(alias="sourceHeader", min_length=1, max_length=200)
    source_column_index: int = Field(alias="sourceColumnIndex", ge=0)
    standard_field: str | None = Field(default=None, alias="standardField", max_length=100)
    confidence: float | None = Field(default=None, ge=0, le=1)


class HeaderMappingData(ModelProvenance):
    operation: Literal["PROCUREMENT_HEADER_MAPPING"]
    header_row_number: int | None = Field(default=None, alias="headerRowNumber", ge=1)
    mappings: list[HeaderMapping] = Field(max_length=200)


class HeaderMappingEnvelope(BaseModel):
    code: int
    message: str
    data: HeaderMappingData


class HeaderMappingModelResult(BaseModel):
    model_config = _STRICT

    header_row_number: int | None = Field(default=None, alias="headerRowNumber", ge=1)
    mappings: list[HeaderMapping] = Field(max_length=200)


class UnprocessableContractResult(ValueError):
    """The provider result has no safely iterable top-level hint collection."""

    def __init__(self, safe_reason: str = "UNPROCESSABLE_TOP_LEVEL", *,
                 safe_issues: tuple[ProviderValidationIssue, ...] = (), rejected_operation_fingerprint: str | None = None) -> None:
        super().__init__("contract provider result failed safe validation")
        self.safe_reason = safe_reason
        self.safe_issues = safe_issues
        self.rejected_operation_fingerprint = rejected_operation_fingerprint


@dataclass(frozen=True, slots=True)
class _CorrectionOutcome:
    result: ContractChunkResult
    model: str
    duration_ms: int
    provider_call_count: int
    transport_retry_count: int
    transport_retry_consumed: bool


class _ContractProviderBudget:
    def __init__(
        self,
        total_seconds: float | None,
        clock: Callable[[], float],
    ) -> None:
        self._total_seconds = total_seconds
        self._clock = clock
        self._started = clock()

    def bind(self, operation: ContractTextOperation) -> ContractTextOperation:
        if self._total_seconds is None:
            return operation
        remaining = self._total_seconds - (self._clock() - self._started)
        if remaining <= 0:
            raise ModelTotalBudgetExhausted(
                "contract provider total budget is exhausted"
            )
        return replace(operation, provider_timeout_seconds=remaining)


class _ContractHintFilterReason(str, Enum):
    INVALID_TYPED_HINT = "INVALID_TYPED_HINT"
    INVALID_CATEGORY = "INVALID_CATEGORY"
    INVALID_ATTENTION = "INVALID_ATTENTION"
    INVALID_TITLE_OR_DESCRIPTION = "INVALID_TITLE_OR_DESCRIPTION"
    STRONG_VERDICT = "STRONG_VERDICT"
    MONETARY_VALUE = "MONETARY_VALUE"
    MONETARY_CALCULATION = "MONETARY_CALCULATION"
    MONETARY_CONCLUSION = "MONETARY_CONCLUSION"
    INVALID_EVIDENCE_REF = "INVALID_EVIDENCE_REF"
    NON_CITABLE_EVIDENCE = "NON_CITABLE_EVIDENCE"
    MISSING_PROBLEM_EVIDENCE = "MISSING_PROBLEM_EVIDENCE"
    INVALID_INFO_EVIDENCE = "INVALID_INFO_EVIDENCE"
    INVALID_MISSING_MATERIAL_CLAIM = "INVALID_MISSING_MATERIAL_CLAIM"
    UNVERIFIED_PROBLEM_EVIDENCE = "UNVERIFIED_PROBLEM_EVIDENCE"
    OTHER_SAFE_FILTER = "OTHER_SAFE_FILTER"
    REDACTION_ARTIFACT_CLAIM = "REDACTION_ARTIFACT_CLAIM"
    INCOMPLETE_DESCRIPTION = "INCOMPLETE_DESCRIPTION"


@dataclass(frozen=True, slots=True)
class _ContractHintNormalization:
    hint: ContractHint | None
    evidence_incomplete: bool
    filter_reason: _ContractHintFilterReason | None
    category: str | None
    attention: str | None


_CONTRACT_CATEGORIES = frozenset({
    "CONTRACT_STRUCTURE",
    "PROJECT_IDENTITY",
    "SUPPLIER_IDENTITY",
    "DELIVERY_TERMS",
    "SERVICE_WARRANTY",
    "SCOPE_AND_SUBCONTRACTING",
    "BREACH_LIABILITY",
    "TERM_TERMINATION",
    "OTHER_TEXT",
})
_FIXED_4_1_REQUIRED_CATEGORIES = (
    "CONTRACT_STRUCTURE",
    "PROJECT_IDENTITY",
    "SUPPLIER_IDENTITY",
    "DELIVERY_TERMS",
    "SERVICE_WARRANTY",
    "SCOPE_AND_SUBCONTRACTING",
)
_CONTRACT_BODY_REQUIRED_CATEGORIES = (
    *_FIXED_4_1_REQUIRED_CATEGORIES,
    "TERM_TERMINATION",
)
_CONTRACT_EVIDENCE_MATERIAL_CATEGORIES = frozenset({
    "CONTRACT_BODY",
    "AWARD_NOTICE",
    "BID_RESPONSE",
    "TENDER_DOCUMENT",
    "SUPPLEMENT",
})
_MISSING_MATERIAL_TERMS = {
    "CONTRACT_BODY": ("合同正文", "合同文件", "合同材料", "contractbody"),
    "AWARD_NOTICE": ("中标通知书", "成交通知书", "中标通知", "awardnotice"),
    "BID_RESPONSE": ("投标响应", "响应文件", "响应材料", "bidresponse"),
    "TENDER_DOCUMENT": ("采购文件", "招标文件", "采购需求", "tenderdocument"),
    "SUPPLEMENT": ("补充协议", "补充文件", "补充材料", "supplement"),
}
_MISSING_MATERIAL_MARKERS = (
    "缺失", "缺少", "未提供", "未提交", "未附", "missing", "absent", "notprovided",
)
_CONTRACT_CATEGORY_ALIASES = {
    "STRUCTURE": "CONTRACT_STRUCTURE",
    "CONTRACT_STRUCTURE": "CONTRACT_STRUCTURE",
    "BREACH": "BREACH_LIABILITY",
    "BREACH_TERMS": "BREACH_LIABILITY",
    "BREACH_LIABILITY": "BREACH_LIABILITY",
    "PENALTY_TERMS": "BREACH_LIABILITY",
    "TERM": "TERM_TERMINATION",
    "CONTRACT_TERM": "TERM_TERMINATION",
    "TERMINATION": "TERM_TERMINATION",
    "TERM_TERMINATION": "TERM_TERMINATION",
}
_CORRECTION_VALIDATION_LOC_PARTS = frozenset(
    {
        "missingCategoryHints",
        "missing_category_hints",
        "evidenceRepairs",
        "evidence_repairs",
        "category",
        "attention",
        "title",
        "description",
        "problemEvidenceIds",
        "problem_evidence_ids",
        "comparisonEvidenceIds",
        "comparison_evidence_ids",
        "unavailableMaterialCategory",
        "unavailable_material_category",
        "hintId",
        "hint_id",
    }
)
_CORRECTION_VALIDATION_TYPE = re.compile(r"^[a-z0-9_]{1,80}$")
_SAFE_CORRECTION_VALIDATION_ENUMS = frozenset(
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
    }
)
_SAFE_CORRECTION_NULL_SENTINELS = {
    "": "EMPTY_STRING",
    "NONE": "NONE",
    "NULL": "NULL",
    "UNKNOWN": "UNKNOWN",
    "N/A": "NA",
    "NA": "NA",
    "NOT APPLICABLE": "NOT_APPLICABLE",
    "NOT_APPLICABLE": "NOT_APPLICABLE",
}
_MASK_TOKEN = "[确定性数值字段已移除]"
_REDACTION_PROCESS_REFERENCE = re.compile(
    r"确定性数值字段|(?:金额|价格|数值)字段.{0,12}(?:移除|屏蔽|遮蔽|脱敏)|"
    r"(?:移除|屏蔽|遮蔽|脱敏).{0,12}(?:金额|价格|数值)字段|"
    r"(?:mask(?:ed|ing)?|redact(?:ed|ion)?).{0,40}(?:amount|monetary|price|field)|"
    r"(?:amount|monetary|price|field).{0,40}(?:mask(?:ed|ing)?|redact(?:ed|ion)?)",
    re.IGNORECASE,
)


def _redaction_artifact_claim(text: str, catalog: EvidenceCatalog) -> bool:
    # System processing metadata cannot support a claim about the original
    # contract. Retain genuine missing-clause observations; reject only explicit
    # commentary on our masking, and use the existing bounded correction path.
    return (any(_MASK_TOKEN in segment.exact_text for segment in catalog.segments)
            and bool(_REDACTION_PROCESS_REFERENCE.search(text)))


def _incomplete_contract_description(description: str) -> bool:
    # Reject clear dangling predicates observed in real provider output, not
    # short complete statements or entity names ending in 为 (for example 华为).
    # Do not manufacture the missing continuation from the evidence or title.
    return bool(re.search(
        r"(?:项目名称为|(?:甲方|乙方)(?:[（(]供应商[）)])?为|"
        r"供应商(?:名称)?为|采购内容(?:为|仅列明)|仅(?:仅)?以|付款以)"
        r"[\s：:，,、（(]*$", description,
    ))

_ATTENTION_ALIASES = {
    "INFO": "INFO",
    "NORMAL": "INFO",
    "PASS": "INFO",
    "REVIEW": "REVIEW",
    "WARNING": "REVIEW",
    "WARN": "REVIEW",
    "INSUFFICIENT": "INSUFFICIENT",
    "MISSING": "INSUFFICIENT",
}
_EVIDENCE_STATUS_ALIASES = {
    "INFO": "NORMAL",
    "NORMAL": "NORMAL",
    "PASS": "NORMAL",
    "REVIEW": "REVIEW",
    "WARNING": "REVIEW",
    "WARN": "REVIEW",
    "INSUFFICIENT": "INSUFFICIENT",
    "MISSING": "INSUFFICIENT",
}


def _provider_token(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    token = value.strip().upper().replace("-", "_").replace(" ", "_")
    return token or None


def _provider_text(value: Any, max_length: int) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text or len(text) > max_length:
        return None
    return text


def _stable_required_categories(
    *category_groups: tuple[str, ...],
) -> tuple[str, ...]:
    categories: list[str] = []
    for group in category_groups:
        for category in group:
            if category not in categories:
                categories.append(category)
    return tuple(categories)


class ModelApplicationService:
    def __init__(
        self,
        settings: Settings,
        gateway: ModelGatewayPort,
        *,
        clock: Callable[[], float] = time.monotonic,
        contract_execution_max_chars: int | None = None,
        require_synthesis: bool = False,
    ) -> None:
        if contract_execution_max_chars is not None and (
            not isinstance(contract_execution_max_chars, int)
            or isinstance(contract_execution_max_chars, bool)
            or contract_execution_max_chars <= 0
        ):
            raise ValueError("contract execution character bound must be positive")
        self._gateway = gateway
        self._settings = settings
        self._configured_model = settings.model_name or "UNKNOWN_MODEL"
        self._configured_provider = (
            "OLLAMA_NATIVE"
            if settings.semantic_structured_transport == "OLLAMA_NATIVE_STRUCTURED"
            else "OPENAI_COMPATIBLE"
        )
        self._clock = clock
        self._model_total_budget_seconds = settings.model_total_budget_seconds
        self._model_chunk_chars = settings.model_chunk_chars
        self._model_max_chunks = settings.model_max_chunks
        # Explicit internal opt-in until representative provider validation.
        # Catalog segmentation continues to use the existing configured bound.
        self._contract_execution_max_chars = contract_execution_max_chars
        self._require_synthesis = require_synthesis
        self._demo_relax_breach_monetary = (
            settings.demo_relax_breach_monetary
        )
        self._structured_semantic = (
            settings.semantic_structured_transport != "PROMPT_ONLY"
        )
        self._synthesis_max_chars = _CONTRACT_SYNTHESIS_MAX_CHARS
        self._chunker = BoundedChunker(
            max_chars=settings.model_chunk_chars,
            max_chunks=settings.model_max_chunks,
        )

    @diagnosed("CONTRACT_TEXT_ANALYSIS")
    def analyze_contract(
        self, request: ContractAnalysisRequest, request_id: str
    ) -> ContractEnvelope:
        model_calls: list[ContractModelCallData] = []
        if request.semantic_hierarchy_protocol and not callable(getattr(self, "_bounded_hierarchy_phase", None)):
            raise AuditAiError(409, "SEMANTIC_HIERARCHY_POLICY_INCOMPATIBLE", "hierarchy requires frozen semantic work")
        if self._demo_relax_breach_monetary:
            log_event(
                LOGGER,
                "contract demo relaxation active",
                request_id=request_id,
                demo_relaxation="BREACH_MONETARY_FAIL_SOFT",
            )
        source = "\n\n".join(
            f"[fileId={material.file_id};category={material.category}]\n{material.text}"
            for material in request.materials
        )
        evidence_catalog = self._contract_evidence_catalog(request)
        catalog_metrics = self._contract_catalog_metrics(evidence_catalog)
        catalog_unsatisfiable = (
            catalog_metrics["required_breach_segment_count"] > 0
            and catalog_metrics["required_breach_citable_segment_count"] == 0
        )
        catalog_log_fields: dict[str, Any] = {
            "operation": "CONTRACT_TEXT_ANALYSIS",
            "outcome": "REJECTED" if catalog_unsatisfiable else "READY",
            "request_id": request_id,
            "semantic_defect_types": (
                "EVIDENCE_CATALOG_UNSATISFIABLE"
                if catalog_unsatisfiable
                else "NONE"
            ),
            **catalog_metrics,
        }
        if catalog_unsatisfiable:
            catalog_log_fields["reason"] = "EVIDENCE_CATALOG_UNSATISFIABLE"
        log_event(
            LOGGER,
            "contract evidence catalog prepared",
            **catalog_log_fields,
        )
        if catalog_unsatisfiable:
            raise self._invalid_result()
        batches = self._contract_batches(evidence_catalog)
        chunks = tuple(batch.source_text for batch in batches)
        log_event(LOGGER, "CONTRACT_CHUNKS_READY", stage="CHUNKING", request_id=request_id,
                  chunk_count=len(chunks), input_chars=len(source), chunk_char_limit=self._model_chunk_chars,
                  max_chunks=self._model_max_chunks)
        results: list[ContractChunkResult] = []
        fallback_hints: list[ContractHint] | None = None
        models: list[str] = []
        duration_ms = 0
        provider_call_count = 0
        semantic_retry_count = 0
        transport_retry_count = 0
        semantic_retry_available = True
        transport_retry_available = True
        visible_text_by_file_id = {
            material.file_id: material.text for material in request.materials
        }
        available_material_categories = {
            category
            for material in request.materials
            if (category := _provider_token(material.category)) is not None
        }
        request_level_required_categories = (
            _CONTRACT_BODY_REQUIRED_CATEGORIES
            if "CONTRACT_BODY" in available_material_categories
            else ()
        )
        chunk_required_categories = tuple(
            ("BREACH_LIABILITY",)
            if _requires_quantified_breach_hint(chunk)
            else ()
            for chunk in chunks
        )
        request_required_categories = _stable_required_categories(
            request_level_required_categories,
            (
                ("BREACH_LIABILITY",)
                if any(chunk_required_categories)
                else ()
            ),
        )
        self._log_contract_coverage_prepared(
            request_id=request_id,
            required_categories=request_required_categories,
        )
        provider_budget = _ContractProviderBudget(
            self._model_total_budget_seconds, self._clock
        )
        for index, chunk in enumerate(chunks, start=1):
            if getattr(self, "quality_protocol", None):
                # One correction at a unit, at most the two already frozen for
                # the whole durable plan. Replay reconstructs the same count.
                semantic_retry_available = semantic_retry_count < 2
            operation_evidence_ids = set(batches[index - 1].evidence_ids)
            max_attempts = 2 if semantic_retry_available else 1
            attempt = 1
            retry_reason: str | None = None
            validation_feedback: tuple[ProviderValidationIssue, ...] = ()
            regeneration_context_json = None
            transport_attempt = 1
            transport_retry_reason: str | None = None
            while True:
                operation = ContractTextOperation(
                    project_id=request.project_id,
                    chunk_index=index,
                    chunk_count=len(chunks),
                    source_text=chunk,
                    globally_available_material_categories=tuple(sorted(available_material_categories)),
                    current_operation_material_categories=tuple(sorted({
                        _provider_token(evidence_catalog.by_id[evidence_id].material_category)
                        for evidence_id in operation_evidence_ids
                    })),
                    request_id=request_id,
                    attempt=attempt,
                    max_attempts=max_attempts,
                    retry_reason=retry_reason,
                    validation_feedback=validation_feedback,
                    regeneration_context_json=regeneration_context_json,
                    quality_protocol=getattr(self, "quality_protocol", None),
                    generation_policy=("contract-semantic-work-v3" if getattr(self, "quality_protocol", None) else None),
                    required_categories=_stable_required_categories(
                        (
                            request_level_required_categories
                            if len(chunks) == 1
                            else ()
                        ),
                        chunk_required_categories[index - 1],
                    ),
                    allowed_categories=tuple(sorted(_CONTRACT_CATEGORIES)),
                    transport_attempt=transport_attempt,
                    max_transport_attempts=(
                        transport_attempt + (1 if transport_retry_available else 0)
                    ),
                    transport_retry_reason=transport_retry_reason,
                    document_redaction_provenance=True,
                )
                try:
                    provider_call_count += 1
                    completion = self._call_contract(operation, provider_budget)
                except AuditAiError as failure:
                    self._record_failure(model_calls, operation, failure.code, 0)
                    raise self._annotate_error(failure, model_calls) from failure
                except ModelGatewayFailure as failure:
                    duration_ms += failure.duration_ms
                    retrying = transport_retry_available and failure.retryable
                    self._record_failure(
                        model_calls, operation, failure.reason, failure.duration_ms
                    )
                    self._log_contract_gateway_failure(
                        request_id=request_id,
                        operation=operation,
                        gateway_reason=failure.reason,
                        duration_ms=failure.duration_ms,
                        retrying=retrying,
                    )
                    if retrying:
                        transport_retry_available = False
                        if self._structured_semantic:
                            semantic_retry_available = False
                        transport_retry_count += 1
                        transport_attempt += 1
                        transport_retry_reason = failure.reason
                        continue
                    raise self._gateway_error(model_calls) from failure

                duration_ms += completion.duration_ms
                try:
                    (
                        result,
                        filtered_hint_count,
                        evidence_repair_hint_ids,
                        normalization_diagnostics,
                    ) = self._normalize_contract_result(
                        completion.payload,
                        visible_text_by_file_id,
                        available_material_categories,
                        evidence_catalog,
                        operation_evidence_ids,
                        operation_key=f"A{index}",
                        demo_relax_breach_monetary=(
                            self._demo_relax_breach_monetary
                        ),
                        request_id=request_id,
                        strict_machine_contract=(
                            getattr(completion, "semantic_contract", None)
                            == "CONTRACT_SEMANTIC_V1"
                        ),
                    )
                except UnprocessableContractResult as failure:
                    rejection_reason = failure.safe_reason
                    validation_feedback = failure.safe_issues
                    self._record_completion(
                        model_calls, operation, completion,
                        outcome="FAILED", safe_reason=rejection_reason,
                    )
                    retrying = semantic_retry_available
                    self._log_contract_rejection(
                        request_id=request_id,
                        reason=rejection_reason,
                        operation_index=index,
                        operation_count=len(chunks),
                        attempt=attempt,
                        max_attempts=max_attempts,
                        retrying=retrying,
                        model=completion.model,
                        duration_ms=completion.duration_ms,
                        purpose=operation.purpose,
                        raw_hint_count=0,
                    )
                    if retrying:
                        if getattr(self, "quality_protocol", None):
                            from audit_ai.model.semantic_quality import build_regeneration_context
                            regeneration_context_json = build_regeneration_context(completion.payload,
                                failure.safe_issues, failure.rejected_operation_fingerprint)
                        semantic_retry_available = False
                        if self._structured_semantic:
                            transport_retry_available = False
                        semantic_retry_count += 1
                        attempt = 2
                        retry_reason = rejection_reason
                        transport_attempt = 1
                        transport_retry_reason = None
                        continue
                    raise self._invalid_result(model_calls) from failure

                self._log_contract_hint_normalization(
                    request_id=request_id,
                    operation=operation,
                    result=result,
                    filtered_hint_count=filtered_hint_count,
                    diagnostics=normalization_diagnostics,
                    evidence_repair_hint_ids=evidence_repair_hint_ids,
                )
                final_required_categories = operation.required_categories
                if len(chunks) == 1:
                    final_required_categories = _stable_required_categories(
                        request_level_required_categories,
                        operation.required_categories,
                    )
                missing_required_categories = tuple(
                    category
                    for category in final_required_categories
                    if not any(hint.category == category for hint in result.hints)
                )
                if any(
                    category in request_level_required_categories
                    for category in missing_required_categories
                ):
                    self._log_contract_completeness_required(
                        request_id=request_id,
                        missing_required_categories=missing_required_categories,
                    )
                result_model = completion.model
                if missing_required_categories or evidence_repair_hint_ids:
                    rejection_reason = (
                        "MISSING_REQUIRED_BREACH_CATEGORY"
                        if missing_required_categories == ("BREACH_LIABILITY",)
                        else "MISSING_REQUIRED_CATEGORY"
                        if missing_required_categories
                        else "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE"
                    )
                    retrying = semantic_retry_available
                    self._record_completion(
                        model_calls, operation, completion,
                        outcome="FAILED", safe_reason=rejection_reason,
                    )
                    self._log_contract_rejection(
                        request_id=request_id,
                        reason=rejection_reason,
                        operation_index=index,
                        operation_count=len(chunks),
                        attempt=attempt,
                        max_attempts=max_attempts,
                        retrying=retrying,
                        model=completion.model,
                        duration_ms=completion.duration_ms,
                        semantic_defect_types="_".join(
                            defect
                            for defect, present in (
                                (
                                    "MISSING_REQUIRED_CATEGORY",
                                    any(
                                        category != "BREACH_LIABILITY"
                                        for category in missing_required_categories
                                    ),
                                ),
                                (
                                    "MISSING_REQUIRED_BREACH_CATEGORY",
                                    "BREACH_LIABILITY"
                                    in missing_required_categories,
                                ),
                                (
                                    "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE",
                                    bool(evidence_repair_hint_ids),
                                ),
                            )
                            if present
                        ),
                        purpose=operation.purpose,
                    )
                    if not retrying:
                        raise self._invalid_result(model_calls)
                    semantic_retry_available = False
                    if self._structured_semantic:
                        transport_retry_available = False
                    semantic_retry_count += 1
                    try:
                        plan = build_correction_plan(
                            result.hints,
                            missing_required_categories=missing_required_categories,
                            evidence_repair_hint_ids=evidence_repair_hint_ids,
                            rejected_hint_reasons=tuple(
                                (ordinal, diagnostic.category, diagnostic.filter_reason.value)
                                for ordinal, diagnostic in normalization_diagnostics
                                if diagnostic.category is not None and diagnostic.filter_reason is not None
                            ),
                        )
                    except ValueError as failure:
                        raise self._invalid_result(model_calls) from failure
                    correction = self._apply_contract_correction(
                        request_id=request_id,
                        project_id=request.project_id,
                        operation_index=index,
                        operation_count=len(chunks),
                        operation_key=f"A{index}",
                        original_operation_source=chunk,
                        first_result=result,
                        plan=plan,
                        evidence_catalog=evidence_catalog,
                        allowed_evidence_ids=operation_evidence_ids,
                        available_material_categories=available_material_categories,
                        transport_retry_available=transport_retry_available,
                        retry_reason=rejection_reason,
                        provider_budget=provider_budget,
                        model_calls=model_calls,
                        document_redaction_provenance=operation.document_redaction_provenance,
                    )
                    result = correction.result
                    result_model = correction.model
                    duration_ms += correction.duration_ms
                    provider_call_count += correction.provider_call_count
                    transport_retry_count += correction.transport_retry_count
                    if correction.transport_retry_consumed:
                        transport_retry_available = False
                else:
                    self._record_completion(
                        model_calls, operation, completion, outcome="SUCCEEDED"
                    )

                if filtered_hint_count:
                    self._log_contract_filtered(
                        request_id=request_id,
                        reason="UNSAFE_OR_INVALID_HINT",
                        operation_index=index,
                        operation_count=len(chunks),
                        attempt=attempt,
                        max_attempts=max_attempts,
                        model=completion.model,
                        duration_ms=completion.duration_ms,
                        filtered_hint_count=filtered_hint_count,
                    )
                if result is not None:
                    results.append(result)
                    models.append(result_model)
                    break
        if request.semantic_hierarchy_protocol:
            phase = getattr(self, "_bounded_hierarchy_phase", None)
            if phase is None:
                raise AuditAiError(409, "SEMANTIC_HIERARCHY_POLICY_INCOMPATIBLE", "hierarchy requires frozen semantic work")
            results, stage_models, stage_duration, stage_calls, semantic_retry_count = phase(
                request=request, request_id=request_id, results=results, evidence_catalog=evidence_catalog,
                available_categories=available_material_categories, required_categories=request_required_categories,
                provider_budget=provider_budget, model_calls=model_calls, semantic_retry_count=semantic_retry_count)
            models.extend(stage_models)
            duration_ms += stage_duration
            provider_call_count += stage_calls
        elif len(chunks) > 1 or self._require_synthesis:
            synthesis_evidence_ids = {
                evidence_id
                for result in results
                for hint in result.hints
                for evidence_id in (
                    *hint.problem_evidence_ids,
                    *hint.comparison_evidence_ids,
                )
            }
            synthesis_source, compact_chars = self._contract_synthesis_source(
                results, evidence_catalog
            )
            if synthesis_source is None:
                fallback_hints = self._deduplicate_contract_hints(results)
                log_event(
                    LOGGER,
                    "contract synthesis input fallback",
                    operation="CONTRACT_TEXT_ANALYSIS",
                    outcome="FALLBACK",
                    reason="SYNTHESIS_INPUT_BOUND",
                    request_id=request_id,
                    chunk_count=len(chunks),
                    safe_hint_count=len(fallback_hints),
                    compact_chars=compact_chars,
                )
            else:
                attempt = 1
                retry_reason = None
                validation_feedback = ()
                regeneration_context_json = None
                transport_attempt = 1
                transport_retry_reason = None
                if getattr(self, "quality_protocol", None):
                    semantic_retry_available = semantic_retry_count < 2
                max_attempts = 2 if semantic_retry_available else 1
                while True:
                    synthesis_operation = ContractTextOperation(
                        project_id=request.project_id,
                        chunk_index=len(chunks),
                        chunk_count=len(chunks),
                        source_text=synthesis_source,
                        globally_available_material_categories=tuple(sorted(available_material_categories)),
                        current_operation_material_categories=tuple(sorted({
                            _provider_token(evidence_catalog.by_id[evidence_id].material_category)
                            for evidence_id in synthesis_evidence_ids
                        })),
                        request_id=request_id,
                        attempt=attempt,
                        max_attempts=max_attempts,
                        retry_reason=retry_reason,
                        validation_feedback=validation_feedback,
                        regeneration_context_json=regeneration_context_json,
                        quality_protocol=getattr(self, "quality_protocol", None),
                        document_redaction_provenance=True,
                        generation_policy=("contract-semantic-work-v3" if getattr(self, "quality_protocol", None) else None),
                        purpose="SYNTHESIZE",
                        required_categories=request_required_categories,
                        allowed_categories=tuple(sorted(_CONTRACT_CATEGORIES)),
                        transport_attempt=transport_attempt,
                        max_transport_attempts=(
                            transport_attempt
                            + (1 if transport_retry_available else 0)
                        ),
                        transport_retry_reason=transport_retry_reason,
                    )
                    try:
                        provider_call_count += 1
                        completion = self._call_contract(
                            synthesis_operation, provider_budget
                        )
                    except AuditAiError as failure:
                        self._record_failure(
                            model_calls, synthesis_operation, failure.code, 0
                        )
                        raise self._annotate_error(failure, model_calls) from failure
                    except ModelGatewayFailure as failure:
                        duration_ms += failure.duration_ms
                        retrying = transport_retry_available and failure.retryable
                        self._record_failure(
                            model_calls, synthesis_operation,
                            failure.reason, failure.duration_ms,
                        )
                        self._log_contract_gateway_failure(
                            request_id=request_id,
                            operation=synthesis_operation,
                            gateway_reason=failure.reason,
                            duration_ms=failure.duration_ms,
                            retrying=retrying,
                        )
                        if retrying:
                            transport_retry_available = False
                            if self._structured_semantic:
                                semantic_retry_available = False
                            transport_retry_count += 1
                            transport_attempt += 1
                            transport_retry_reason = failure.reason
                            continue
                        raise self._gateway_error(model_calls) from failure
                    duration_ms += completion.duration_ms
                    try:
                        (
                            synthesis_result,
                            filtered_hint_count,
                            evidence_repair_hint_ids,
                            normalization_diagnostics,
                        ) = self._normalize_contract_result(
                            completion.payload, visible_text_by_file_id,
                            available_material_categories,
                            evidence_catalog,
                            synthesis_evidence_ids,
                            operation_key="S1",
                            demo_relax_breach_monetary=(
                                self._demo_relax_breach_monetary
                            ),
                            request_id=request_id,
                            strict_machine_contract=(
                                getattr(completion, "semantic_contract", None)
                                == "CONTRACT_SEMANTIC_V1"
                            ),
                        )
                    except UnprocessableContractResult as failure:
                        rejection_reason = failure.safe_reason
                        validation_feedback = failure.safe_issues
                        self._record_completion(
                            model_calls, synthesis_operation, completion,
                            outcome="FAILED", safe_reason=rejection_reason,
                        )
                        retrying = semantic_retry_available
                        self._log_contract_rejection(
                            request_id=request_id,
                            reason=rejection_reason,
                            operation_index=len(chunks),
                            operation_count=len(chunks),
                            attempt=attempt,
                            max_attempts=max_attempts,
                            retrying=retrying,
                            model=completion.model,
                            duration_ms=completion.duration_ms,
                            purpose=synthesis_operation.purpose,
                            raw_hint_count=0,
                        )
                        if retrying:
                            if getattr(self, "quality_protocol", None):
                                from audit_ai.model.semantic_quality import build_regeneration_context
                                regeneration_context_json = build_regeneration_context(completion.payload,
                                    failure.safe_issues, failure.rejected_operation_fingerprint)
                            semantic_retry_available = False
                            if self._structured_semantic:
                                transport_retry_available = False
                            semantic_retry_count += 1
                            attempt = 2
                            retry_reason = rejection_reason
                            transport_attempt = 1
                            transport_retry_reason = None
                            continue
                        raise self._invalid_result(model_calls) from failure
                    self._log_contract_hint_normalization(
                        request_id=request_id,
                        operation=synthesis_operation,
                        result=synthesis_result,
                        filtered_hint_count=filtered_hint_count,
                        diagnostics=normalization_diagnostics,
                        evidence_repair_hint_ids=evidence_repair_hint_ids,
                    )
                    final_required_categories = _stable_required_categories(
                        request_level_required_categories,
                        synthesis_operation.required_categories,
                    )
                    missing_required_categories = tuple(
                        category
                        for category in final_required_categories
                        if not any(
                            hint.category == category
                            for hint in synthesis_result.hints
                        )
                    )
                    if any(
                        category in request_level_required_categories
                        for category in missing_required_categories
                    ):
                        self._log_contract_completeness_required(
                            request_id=request_id,
                            missing_required_categories=missing_required_categories,
                        )
                    result_model = completion.model
                    if missing_required_categories or evidence_repair_hint_ids:
                        rejection_reason = (
                            "MISSING_REQUIRED_BREACH_CATEGORY"
                            if missing_required_categories == ("BREACH_LIABILITY",)
                            else "MISSING_REQUIRED_CATEGORY"
                            if missing_required_categories
                            else "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE"
                        )
                        retrying = semantic_retry_available
                        self._record_completion(
                            model_calls, synthesis_operation, completion,
                            outcome="FAILED", safe_reason=rejection_reason,
                        )
                        self._log_contract_rejection(
                            request_id=request_id,
                            reason=rejection_reason,
                            operation_index=len(chunks),
                            operation_count=len(chunks),
                            attempt=attempt,
                            max_attempts=max_attempts,
                            retrying=retrying,
                            model=completion.model,
                            duration_ms=completion.duration_ms,
                            semantic_defect_types="_".join(
                                defect
                                for defect, present in (
                                    (
                                        "MISSING_REQUIRED_CATEGORY",
                                        any(
                                            category != "BREACH_LIABILITY"
                                            for category in missing_required_categories
                                        ),
                                    ),
                                    (
                                        "MISSING_REQUIRED_BREACH_CATEGORY",
                                        "BREACH_LIABILITY"
                                        in missing_required_categories,
                                    ),
                                    (
                                        "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE",
                                        bool(evidence_repair_hint_ids),
                                    ),
                                )
                                if present
                            ),
                            purpose=synthesis_operation.purpose,
                        )
                        if not retrying:
                            raise self._invalid_result(model_calls)
                        semantic_retry_available = False
                        if self._structured_semantic:
                            transport_retry_available = False
                        semantic_retry_count += 1
                        try:
                            plan = build_correction_plan(
                                synthesis_result.hints,
                                missing_required_categories=(
                                    missing_required_categories
                                ),
                                evidence_repair_hint_ids=(
                                    evidence_repair_hint_ids
                                ),
                                rejected_hint_reasons=tuple(
                                    (ordinal, diagnostic.category, diagnostic.filter_reason.value)
                                    for ordinal, diagnostic in normalization_diagnostics
                                    if diagnostic.category is not None and diagnostic.filter_reason is not None
                                ),
                            )
                        except ValueError as failure:
                            raise self._invalid_result(model_calls) from failure
                        correction = self._apply_contract_correction(
                            request_id=request_id,
                            project_id=request.project_id,
                            operation_index=len(chunks),
                            operation_count=len(chunks),
                            operation_key="S1",
                            original_operation_source=synthesis_source,
                            first_result=synthesis_result,
                            plan=plan,
                            evidence_catalog=evidence_catalog,
                            allowed_evidence_ids=synthesis_evidence_ids,
                            available_material_categories=(
                                available_material_categories
                            ),
                            transport_retry_available=transport_retry_available,
                            retry_reason=rejection_reason,
                            provider_budget=provider_budget,
                            model_calls=model_calls,
                            document_redaction_provenance=synthesis_operation.document_redaction_provenance,
                        )
                        synthesis_result = correction.result
                        result_model = correction.model
                        duration_ms += correction.duration_ms
                        provider_call_count += correction.provider_call_count
                        transport_retry_count += (
                            correction.transport_retry_count
                        )
                        if correction.transport_retry_consumed:
                            transport_retry_available = False
                    else:
                        self._record_completion(
                            model_calls, synthesis_operation, completion,
                            outcome="SUCCEEDED",
                        )
                    if filtered_hint_count:
                        self._log_contract_filtered(
                            request_id=request_id,
                            reason="UNSAFE_OR_INVALID_HINT",
                            operation_index=len(chunks),
                            operation_count=len(chunks),
                            attempt=attempt,
                            max_attempts=max_attempts,
                            model=completion.model,
                            duration_ms=completion.duration_ms,
                            filtered_hint_count=filtered_hint_count,
                        )
                    results = [synthesis_result]
                    models.append(result_model)
                    break
        try:
            model = self._one_model(models)
        except AuditAiError as failure:
            raise self._annotate_error(failure, model_calls) from failure
        summaries = (
            [_SYNTHESIS_BOUND_FALLBACK_SUMMARY]
            if fallback_hints is not None
            else [result.summary for result in results]
        )
        hints = (
            fallback_hints
            if fallback_hints is not None
            else [hint for result in results for hint in result.hints]
        )
        envelope = ContractEnvelope(
            code=200,
            message="success",
            data=ContractAnalysisData(
                operation="CONTRACT_TEXT_ANALYSIS",
                summaries=summaries,
                hints=hints,
                model=model,
                durationMs=duration_ms,
                modelCalls=model_calls,
            ),
        )
        log_event(
            LOGGER,
            "contract semantic analysis completed",
            request_id=request_id,
            operation="CONTRACT_TEXT_ANALYSIS",
            outcome="SUCCESS",
            model=model,
            duration_ms=duration_ms,
            material_count=len(request.materials),
            chunk_count=len(chunks),
            input_chars=len(source),
            output_hint_count=len(envelope.data.hints),
            provider_call_count=provider_call_count,
            semantic_retry_count=semantic_retry_count,
            transport_retry_count=transport_retry_count,
        )
        return envelope

    @diagnosed("BUDGET_SEMANTIC_ANALYSIS")
    def analyze_budget(
        self, request: BudgetAnalysisRequest, request_id: str
    ) -> BudgetEnvelope:
        source_rows = [
            json.dumps(
                {
                    "itemId": item.item_id,
                    "description": item.description,
                    "currentCategory": item.current_category,
                    "analysisType": item.analysis_type,
                    "subjectCode": item.subject_code,
                    "categoryCode": item.category_code,
                    "declaredSubjectName": item.declared_subject_name,
                    "approvedSubjectName": item.approved_subject_name,
                    "exceptionType": item.exception_type,
                    "exceptionData": item.exception_data,
                },
                ensure_ascii=False,
                separators=(",", ":"),
            )
            for item in request.items
        ]
        # Keep each item's JSON intact. A long row must never be cut in half
        # and sent without its immutable itemId to two model operations.
        chunks: list[str] = []
        current: list[str] = []
        chars = 0
        for row in source_rows:
            if len(row) > self._model_chunk_chars:
                raise AuditAiError(413, "MODEL_INPUT_TOO_LARGE", "budget item exceeds configured chunk limit")
            if current and (chars + len(row) + 1 > self._model_chunk_chars or len(current)>=8):
                chunks.append("\n".join(current)); current = []; chars = 0
            current.append(row); chars += len(row) + 1
        if current: chunks.append("\n".join(current))
        if len(chunks) > self._model_max_chunks:
            raise AuditAiError(413, "MODEL_INPUT_TOO_LARGE", "budget input exceeds configured chunk limit")
        results: list[BudgetChunkResult] = []
        log_event(LOGGER, "BUDGET_CHUNKS_READY", stage="CHUNKING", request_id=request_id,
                  chunk_count=len(chunks), item_count=len(request.items), input_chars=sum(map(len, source_rows)),
                  chunk_char_limit=self._model_chunk_chars, max_chunks=self._model_max_chunks)
        models: list[str] = []
        duration_ms = 0
        def invoke_budget(units,index,count):
            return self._call_budget(
                BudgetSemanticOperation(
                    source_id=request.source_id,
                    chunk_index=index,
                    chunk_count=count,
                    source_text="\n".join(units),
                    request_id=request_id,
                )
            )
        for index,(units,completion) in enumerate(adaptive_calls(
                [chunk.splitlines() for chunk in chunks],invoke_budget,
                budget_seconds=self._model_total_budget_seconds,clock=self._clock),start=1):
            allowed_item_ids = {json.loads(row)['itemId'] for row in units}
            try:
                result = BudgetChunkResult.model_validate(completion.payload)
            except ValidationError as failure:
                raise self._invalid_result() from failure
            returned_ids = [item.item_id for item in result.items]
            if set(returned_ids) != allowed_item_ids or len(returned_ids) != len(allowed_item_ids):
                log_event(LOGGER, "BUDGET_ID_COVERAGE_REJECTED", stage="RESULT_VALIDATION", request_id=request_id,
                          chunk_index=index, chunk_count=len(chunks),
                          missing_item_count=len(allowed_item_ids-set(returned_ids)),
                          foreign_item_count=len(set(returned_ids)-allowed_item_ids),
                          duplicate_item_count=len(returned_ids)-len(set(returned_ids)), outcome="INVALID_MODEL_RESULT")
                raise self._invalid_result()
            log_event(LOGGER, "BUDGET_CHUNK_VALIDATED", stage="RESULT_VALIDATION", request_id=request_id,
                      chunk_index=index, chunk_count=len(chunks), item_count=len(returned_ids), outcome="SUCCESS")
            results.append(result)
            models.append(completion.model)
            duration_ms += completion.duration_ms
        model = self._one_model(models)
        return BudgetEnvelope(
            code=200,
            message="success",
            data=BudgetAnalysisData(
                operation="BUDGET_SEMANTIC_ANALYSIS",
                items=[item for result in results for item in result.items],
                model=model,
                durationMs=duration_ms,
            ),
        )

    def analyze_tender_compliance(
        self, request: TenderComplianceRequest, request_id: str
    ) -> TenderComplianceEnvelope:
        started = self._clock()
        segment_by_locator = {segment.source_locator: segment for segment in request.segments}
        chunks = prepare_tender(request,self._settings,text_parts) + crosscheck_tender(request,self._settings)
        results: list[TenderComplianceChunkResult] = []
        models: list[str] = []
        duration_ms = 0
        seen: set[tuple[str, str, str]] = set()
        def invoke_tender(units,index,count,remaining):
            started = self._clock()
            operation = TenderComplianceOperation(
                    project_id=request.project_id,
                    review_id=request.review_id,
                    chunk_index=index,
                    chunk_count=count,
                    source_text=render_tender(units,request.rule_findings,self._settings.material_context_chars),
                    request_id=request_id,
                    timeout_seconds=remaining,
                )
            completion = self._call_tender_compliance(operation)
            if completion.finish_reason == 'LENGTH':
                return completion  # The adaptive caller handles proven output limits.
            try:
                TenderComplianceChunkResult.model_validate(completion.payload)
            except ValidationError:
                log_event(logging.getLogger('audit_ai.model'), 'TENDER_SCHEMA_REPAIR',
                          stage='RESULT_VALIDATION', outcome='RETRY', reason_code='INVALID_TYPED_SCHEMA',
                          request_id=request_id, chunk_index=index, chunk_count=count)
                remaining -= self._clock() - started
                if remaining <= 0:
                    raise AuditAiError(504,'MODEL_TOTAL_BUDGET_EXHAUSTED','bounded semantic operation exhausted')
                repaired = self._call_tender_compliance(replace(operation, schema_repair=True, timeout_seconds=remaining))
                completion = replace(repaired, duration_ms=completion.duration_ms + repaired.duration_ms)
            return completion
        for units,completion in adaptive_calls(chunks,invoke_tender,budget_seconds=self._model_total_budget_seconds,
                                                clock=self._clock,splitter=split_units,pass_remaining_budget=True,
                                                atomic_group=lambda group:any(unit.comparison for unit in group)):
            try:
                result = TenderComplianceChunkResult.model_validate(completion.payload)
            except ValidationError as failure:
                raise self._invalid_result() from failure
            for finding in result.findings:
                if re.search(r'(?:整份|全文|本文件|该文件|本材料|该材料).{0,16}(?:缺失|未提供|未上传|未约定|没有约定)',
                             finding.rationale):
                    # A local segment cannot prove document-wide absence.
                    raise self._invalid_result()
                segment = segment_by_locator.get(finding.source_locator)
                local=[u.piece.view(self._settings.material_context_chars) for u in units
                       if u.citable and u.piece.locator==finding.source_locator]
                if (segment is None or finding.matched_text not in segment.text
                        or not any(finding.matched_text in v.get('contextBefore','')+v['text']+v.get('contextAfter','') for v in local)):
                    raise self._invalid_result()
                key = (finding.source_locator, finding.matched_text, finding.risk_category)
                if key not in seen:
                    seen.add(key)
                    results.append(TenderComplianceChunkResult(findings=[finding]))
            models.append(completion.model)
            duration_ms += completion.duration_ms
        final = {(f.source_locator, f.matched_text, f.risk_category): f
                 for result in results for f in result.findings}
        plans = reconciliation_plans(request, list(final.values()), self._settings)
        for index, plan in enumerate(plans):
            remaining = self._model_total_budget_seconds - (self._clock() - started)
            if remaining <= 0:
                raise AuditAiError(504,'MODEL_TOTAL_BUDGET_EXHAUSTED','counterevidence budget exhausted')
            completion = self._call_tender_compliance(TenderComplianceOperation(
                project_id=request.project_id, review_id=request.review_id, chunk_index=index+1,
                chunk_count=len(plans), source_text=plan[3], request_id=request_id, timeout_seconds=remaining))
            try:
                if completion.finish_reason=='LENGTH':raise ValueError('truncated reconciliation')
                finding=TenderFindingResult.model_validate(apply_decision(plan,completion.payload))
            except (ValidationError,ValueError) as failure:
                raise self._invalid_result() from failure
            if self._clock()-started>self._model_total_budget_seconds:
                raise AuditAiError(504,'MODEL_TOTAL_BUDGET_EXHAUSTED','counterevidence budget exhausted')
            final[(finding.source_locator,finding.matched_text,finding.risk_category)]=finding
            models.append(completion.model);duration_ms+=completion.duration_ms
            log_event(logging.getLogger('audit_ai.model'),'TENDER_COUNTEREVIDENCE_REVIEW',
                      stage='RESULT_VALIDATION',outcome='VALIDATED',request_id=request_id,chunk_index=index+1)
        model = self._one_model(models)
        return TenderComplianceEnvelope(
            code=200,
            message="success",
            data=TenderComplianceData(
                operation="PROCUREMENT_TENDER_COMPLIANCE",
                findings=list(final.values()),
                model=model,
                durationMs=duration_ms,
            ),
        )

    def analyze_header_mapping(
        self, request: HeaderMappingRequest, request_id: str
    ) -> HeaderMappingEnvelope:
        allowed_fields = tuple(dict.fromkeys(request.allowed_fields))
        rows = tuple(
            {"rowNumber": index + 1, "cells": ["" if value is None else str(value) for value in row]}
            if isinstance(row, list) else
            {"rowNumber": row.row_number, "cells": ["" if value is None else str(value) for value in row.cells]}
            for index, row in enumerate(request.rows)
        )
        completion = self._call_header_mapping(
            SpreadsheetHeaderMappingOperation(
                source_id=request.source_id,
                file_format=request.file_format,
                allowed_fields=allowed_fields,
                rows=rows,
                request_id=request_id,
            )
        )
        try:
            result = HeaderMappingModelResult.model_validate(completion.payload)
        except ValidationError as failure:
            raise self._invalid_result() from failure
        row_numbers = {row["rowNumber"] for row in rows}
        max_columns = max((len(row["cells"]) for row in rows), default=0)
        if result.header_row_number is not None and result.header_row_number not in row_numbers:
            raise self._invalid_result()
        for mapping in result.mappings:
            if mapping.source_column_index >= max_columns:
                raise self._invalid_result()
            if mapping.standard_field is not None and mapping.standard_field not in allowed_fields:
                raise self._invalid_result()
        return HeaderMappingEnvelope(
            code=200,
            message="success",
            data=HeaderMappingData(
                operation="PROCUREMENT_HEADER_MAPPING",
                headerRowNumber=result.header_row_number,
                mappings=result.mappings,
                model=completion.model,
                durationMs=completion.duration_ms,
            ),
        )

    def _chunks(self, source: str) -> tuple[str, ...]:
        try:
            chunks = self._chunker.split(source)
        except ModelInputTooLarge as failure:
            raise AuditAiError(
                413, "MODEL_INPUT_TOO_LARGE", "model input exceeds configured limit"
            ) from failure
        if not chunks:
            raise AuditAiError(400, "EMPTY_MODEL_INPUT", "model input must not be empty")
        return chunks

    def _contract_evidence_catalog(
        self, request: ContractAnalysisRequest
    ) -> EvidenceCatalog:
        sources = tuple(
            EvidenceSource(
                file_id=material.file_id,
                material_category=material.category,
                text=material.text,
            )
            for material in request.materials
        )
        maximum_segment_index = sum(len(source.text) for source in sources) + len(
            sources
        )

        def source_limit(source: EvidenceSource) -> int:
            maximum_header = (
                f"[evidenceId=M:S{maximum_segment_index};"
                f"fileId={source.file_id};category={source.material_category}]"
            )
            return min(
                1000,
                self._model_chunk_chars - len(maximum_header) - 1,
            )

        try:
            return segment_evidence_sources(
                sources,
                max_segment_chars=source_limit,
            )
        except ValueError as failure:
            raise AuditAiError(
                413, "MODEL_INPUT_TOO_LARGE", "model input exceeds configured limit"
            ) from failure

    def _contract_chunks(
        self, catalog: EvidenceCatalog
    ) -> tuple[str, ...]:
        return tuple(batch.source_text for batch in self._contract_batches(catalog))

    def _contract_batches(
        self, catalog: EvidenceCatalog
    ) -> tuple[EvidenceBatch, ...]:
        effective_chunk_chars = self._model_chunk_chars + len(";citable=false")
        if self._contract_execution_max_chars is not None:
            effective_chunk_chars = min(
                effective_chunk_chars, self._contract_execution_max_chars
            )
        try:
            chunks = plan_evidence_batch_members(
                catalog,
                max_chars=effective_chunk_chars,
                max_batches=self._model_max_chunks,
            )
        except ModelInputTooLarge as failure:
            raise AuditAiError(
                413, "MODEL_INPUT_TOO_LARGE", "model input exceeds configured limit"
            ) from failure
        if not chunks:
            raise AuditAiError(400, "EMPTY_MODEL_INPUT", "model input must not be empty")
        return chunks

    @staticmethod
    def _contract_catalog_metrics(catalog: EvidenceCatalog) -> dict[str, int]:
        required_breach_segments = tuple(
            segment
            for segment in catalog.segments
            if _requires_quantified_breach_hint(segment.exact_text)
        )
        citable_segment_count = sum(
            1 for segment in catalog.segments if segment.citable
        )
        return {
            "evidence_segment_count": len(catalog.segments),
            "citable_segment_count": citable_segment_count,
            "non_citable_segment_count": (
                len(catalog.segments) - citable_segment_count
            ),
            "required_breach_segment_count": len(required_breach_segments),
            "required_breach_citable_segment_count": sum(
                1 for segment in required_breach_segments if segment.citable
            ),
        }

    @staticmethod
    def _evidence_ids_in_source(source: str) -> set[str]:
        return set(re.findall(r"\[evidenceId=([^;\]\r\n]+);", source))

    def _contract_synthesis_source(
        self,
        results: list[ContractChunkResult],
        evidence_catalog: EvidenceCatalog | None = None,
    ) -> tuple[str | None, int]:
        levels = (
            (None, None, None, None, True),
            (_CONTRACT_SYNTHESIS_SUMMARY_CHARS, None, None, None, True),
            (
                _CONTRACT_SYNTHESIS_SUMMARY_CHARS,
                _CONTRACT_SYNTHESIS_TITLE_CHARS,
                _CONTRACT_SYNTHESIS_DESCRIPTION_CHARS,
                None,
                True,
            ),
            (
                _CONTRACT_SYNTHESIS_SUMMARY_CHARS,
                _CONTRACT_SYNTHESIS_TITLE_CHARS,
                _CONTRACT_SYNTHESIS_DESCRIPTION_CHARS,
                _CONTRACT_SYNTHESIS_EXCERPT_CHARS,
                True,
            ),
            (
                None,
                _CONTRACT_SYNTHESIS_TITLE_CHARS,
                _CONTRACT_SYNTHESIS_DESCRIPTION_CHARS,
                _CONTRACT_SYNTHESIS_EXCERPT_CHARS,
                False,
            ),
        )
        source = ""
        for (
            summary_chars,
            title_chars,
            description_chars,
            excerpt_chars,
            include_summary,
        ) in levels:
            partials: list[dict[str, Any]] = []
            for result in results:
                partial: dict[str, Any] = {
                    "hints": [
                        self._contract_synthesis_hint(
                            hint,
                            title_chars=title_chars,
                            description_chars=description_chars,
                            excerpt_chars=excerpt_chars,
                        )
                        for hint in result.hints
                    ]
                }
                if include_summary:
                    partial["summary"] = (
                        result.summary
                        if summary_chars is None
                        else result.summary[:summary_chars]
                    )
                partials.append(partial)
            source = json.dumps(
                {
                    "partialResults": partials,
                    "evidenceCatalog": self._contract_synthesis_evidence_catalog(
                        results,
                        evidence_catalog,
                        exact_text_chars=excerpt_chars,
                    ),
                    "omittedHintCount": 0,
                },
                ensure_ascii=False,
                separators=(",", ":"),
            )
            if len(source) <= self._synthesis_max_chars:
                return source, len(source)
        return None, len(source)

    @staticmethod
    def _contract_synthesis_evidence_catalog(
        results: list[ContractChunkResult],
        evidence_catalog: EvidenceCatalog | None,
        *,
        exact_text_chars: int | None,
    ) -> list[dict[str, Any]]:
        if evidence_catalog is None:
            return []
        referenced_ids: list[str] = []
        for result in results:
            for hint in result.hints:
                for evidence_id in (
                    *hint.problem_evidence_ids,
                    *hint.comparison_evidence_ids,
                ):
                    if evidence_id not in referenced_ids:
                        referenced_ids.append(evidence_id)
        items: list[dict[str, Any]] = []
        for evidence_id in referenced_ids:
            segment = evidence_catalog.by_id.get(evidence_id)
            if segment is None or not segment.citable:
                continue
            exact_text = segment.exact_text
            if exact_text_chars is not None:
                exact_text = exact_text[:exact_text_chars]
            items.append(
                {
                    "evidenceId": evidence_id,
                    "fileId": segment.file_id,
                    "materialCategory": segment.material_category,
                    "citable": True,
                    "exactText": exact_text,
                }
            )
        return items

    @staticmethod
    def _contract_synthesis_hint(
        hint: ContractHint,
        *,
        title_chars: int | None,
        description_chars: int | None,
        excerpt_chars: int | None,
    ) -> dict[str, Any]:
        compact: dict[str, Any] = {
            "category": hint.category,
            "attention": hint.attention,
            "title": hint.title,
            "description": hint.description,
            "problemEvidenceIds": list(hint.problem_evidence_ids),
            "comparisonEvidenceIds": list(hint.comparison_evidence_ids),
        }
        unavailable_category = next(
            (
                item.unavailable_material_category
                for item in (hint.evidence_items or [])
                if item.unavailable_material_category is not None
            ),
            None,
        )
        if unavailable_category is not None:
            compact["unavailableMaterialCategory"] = unavailable_category
        if title_chars is not None:
            compact["title"] = compact["title"][:title_chars]
        if description_chars is not None:
            compact["description"] = compact["description"][:description_chars]
        return compact

    @staticmethod
    def _deduplicate_contract_hints(
        results: list[ContractChunkResult],
    ) -> list[ContractHint]:
        hints: list[ContractHint] = []
        seen: set[str] = set()
        for result in results:
            for hint in result.hints:
                fingerprint = hint.model_dump_json(
                    by_alias=True, exclude_none=True
                )
                if fingerprint in seen:
                    continue
                seen.add(fingerprint)
                hints.append(hint)
        return hints

    @staticmethod
    def _normalize_contract_result(
        payload: Mapping[str, Any], visible_text_by_file_id: Mapping[str, str],
        available_material_categories: set[str],
        evidence_catalog: EvidenceCatalog,
        allowed_evidence_ids: set[str],
        operation_key: str,
        demo_relax_breach_monetary: bool = False,
        request_id: str | None = None,
        strict_machine_contract: bool = False,
        monetary_mode: str = "STRICT",
    ) -> tuple[
        ContractChunkResult,
        int,
        tuple[str, ...],
        tuple[tuple[int, _ContractHintNormalization], ...],
    ]:
        if strict_machine_contract:
            try:
                payload = parse_provider_contract_draft(payload).model_dump(
                    by_alias=True, exclude_none=False
                )
            except ProviderDraftValidationFailure as failure:
                for issue in failure.safe_issues:
                    descriptor = issue.as_dict()
                    log_event(LOGGER, "contract provider field rejected", operation="CONTRACT_TEXT_ANALYSIS",
                              request_id=request_id or "unavailable", stage="RESULT_VALIDATION",
                              purpose="SYNTHESIZE" if operation_key.startswith("S") else "ANALYZE",
                              chunk_index=int(operation_key[1:]) if operation_key[1:].isdecimal() else 0,
                              reason=failure.reason.value, validation_error_count=failure.error_count,
                              validation_error_loc=issue.field_path, validation_error_type=issue.error_kind,
                              validation_schema_version=descriptor["schemaVersion"],
                              validation_error_id=descriptor["errorId"],
                              validation_allowed_values=",".join(issue.allowed_values) or "NONE",
                              validation_value_class=issue.actual_class)
                raise UnprocessableContractResult(failure.reason.value, safe_issues=failure.safe_issues) from None
        raw_hints = payload.get("hints")
        if not isinstance(raw_hints, list):
            raise UnprocessableContractResult()

        raw_summary = _provider_text(payload.get("summary"), 4000)
        summary = raw_summary or "已完成合同文字语义分析。"
        if (
            _STRONG_CONTRACT_VERDICT.search(summary)
            or _redaction_artifact_claim(summary, evidence_catalog)
            or (monetary_mode != "OBSERVE" and ModelApplicationService._monetary_rejection_reason(summary))
        ):
            summary = _SAFE_FILTERED_SUMMARY

        hints: list[ContractHint] = []
        filtered_hint_count = 0
        evidence_incomplete_flags: list[bool] = []
        diagnostics: list[tuple[int, _ContractHintNormalization]] = []
        for item_index, raw_hint in enumerate(raw_hints[:100], start=1):
            normalization = ModelApplicationService._normalize_contract_hint(
                raw_hint,
                visible_text_by_file_id,
                available_material_categories,
                evidence_catalog,
                allowed_evidence_ids,
                demo_relax_breach_monetary=demo_relax_breach_monetary,
                request_id=request_id,
                monetary_mode=monetary_mode,
            )
            diagnostics.append((item_index, normalization))
            if normalization.hint is None:
                filtered_hint_count += 1
            else:
                hints.append(normalization.hint)
                evidence_incomplete_flags.append(normalization.evidence_incomplete)
        filtered_hint_count += max(0, len(raw_hints) - 100)
        if not hints:
            summary = _EMPTY_SAFE_SUMMARY
        assigned_hints = assign_server_hint_ids(hints, operation_key=operation_key)
        return (
            ContractChunkResult(summary=summary, hints=assigned_hints),
            filtered_hint_count,
            tuple(
                hint.hint_id
                for hint, evidence_incomplete in zip(
                    assigned_hints, evidence_incomplete_flags
                )
                if evidence_incomplete and hint.hint_id is not None
            ),
            tuple(diagnostics),
        )

    @staticmethod
    def _normalize_contract_hint(
        raw_hint: Any, visible_text_by_file_id: Mapping[str, str],
        available_material_categories: set[str],
        evidence_catalog: EvidenceCatalog,
        allowed_evidence_ids: set[str],
        *,
        demo_relax_breach_monetary: bool = False,
        request_id: str | None = None,
        monetary_mode: str = "STRICT",
    ) -> _ContractHintNormalization:
        if not isinstance(raw_hint, Mapping):
            return _ContractHintNormalization(
                hint=None,
                evidence_incomplete=False,
                filter_reason=_ContractHintFilterReason.INVALID_TYPED_HINT,
                category=None,
                attention=None,
            )
        category_token = _provider_token(raw_hint.get("category"))
        category = _CONTRACT_CATEGORY_ALIASES.get(category_token or "", category_token)
        if category not in _CONTRACT_CATEGORIES:
            category = "OTHER_TEXT"
        attention = _ATTENTION_ALIASES.get(
            _provider_token(raw_hint.get("attention")) or ""
        )
        title = _provider_text(raw_hint.get("title"), 200)
        description = _provider_text(raw_hint.get("description"), 2000)
        if attention is None:
            return _ContractHintNormalization(
                hint=None,
                evidence_incomplete=False,
                filter_reason=_ContractHintFilterReason.INVALID_ATTENTION,
                category=category,
                attention=None,
            )
        if title is None or description is None:
            return _ContractHintNormalization(
                hint=None,
                evidence_incomplete=False,
                filter_reason=(
                    _ContractHintFilterReason.INVALID_TITLE_OR_DESCRIPTION
                ),
                category=category,
                attention=attention,
            )

        if _incomplete_contract_description(description):
            return _ContractHintNormalization(
                hint=None, evidence_incomplete=False,
                filter_reason=_ContractHintFilterReason.INCOMPLETE_DESCRIPTION,
                category=category, attention=attention,
            )

        if _redaction_artifact_claim(f"{title}\n{description}", evidence_catalog):
            return _ContractHintNormalization(
                hint=None, evidence_incomplete=False,
                filter_reason=_ContractHintFilterReason.REDACTION_ARTIFACT_CLAIM,
                category=category, attention=attention,
            )

        if any(
            key in raw_hint
            for key in (
                "problemEvidenceIds",
                "comparisonEvidenceIds",
                "unavailableMaterialCategory",
            )
        ):
            hint, evidence_incomplete = (
                ModelApplicationService._normalize_contract_draft_hint(
                    raw_hint=raw_hint,
                    category=category,
                    attention=attention,
                    title=title,
                    description=description,
                    available_material_categories=available_material_categories,
                    evidence_catalog=evidence_catalog,
                    allowed_evidence_ids=allowed_evidence_ids,
                    demo_relax_breach_monetary=(
                        demo_relax_breach_monetary
                    ),
                    request_id=request_id,
                    monetary_mode=monetary_mode,
                )
            )
            filter_reason = None
            if hint is None:
                filter_reason = ModelApplicationService._hint_text_filter_reason(
                    f"{title}\n{description}", monetary_mode=monetary_mode
                ) or _ContractHintFilterReason.INVALID_INFO_EVIDENCE
            return _ContractHintNormalization(
                hint=hint,
                evidence_incomplete=evidence_incomplete,
                filter_reason=filter_reason,
                category=category,
                attention=attention,
            )

        raw_refs = raw_hint.get("evidenceRefs")
        refs: list[str] = []
        if isinstance(raw_refs, list):
            for raw_ref in raw_refs:
                if (
                    isinstance(raw_ref, str)
                    and raw_ref in visible_text_by_file_id
                    and raw_ref not in refs
                    and len(refs) < 50
                ):
                    refs.append(raw_ref)
        if not refs:
            return _ContractHintNormalization(
                hint=None,
                evidence_incomplete=False,
                filter_reason=_ContractHintFilterReason.INVALID_EVIDENCE_REF,
                category=category,
                attention=attention,
            )

        raw_output_parts = [title, description]
        raw_items = raw_hint.get("evidenceItems")
        if isinstance(raw_items, list):
            raw_output_parts.extend(
                excerpt
                for raw_item in raw_items[:50]
                if isinstance(raw_item, Mapping)
                and (excerpt := _provider_text(raw_item.get("excerpt"), 1000))
            )
        raw_output_text = "\n".join(raw_output_parts)
        filter_reason = ModelApplicationService._hint_text_filter_reason(
            raw_output_text, monetary_mode=monetary_mode
        )
        if filter_reason is not None:
            return _ContractHintNormalization(
                hint=None,
                evidence_incomplete=False,
                filter_reason=filter_reason,
                category=category,
                attention=attention,
            )

        evidence_items: list[ContractEvidenceItem] | None = None
        if isinstance(raw_items, list):
            evidence_items = []
            seen: set[str] = set()
            for raw_item in raw_items[:50]:
                if not isinstance(raw_item, Mapping):
                    continue
                file_id = raw_item.get("fileId")
                status = _EVIDENCE_STATUS_ALIASES.get(
                    _provider_token(raw_item.get("status")) or ""
                )
                if (
                    not isinstance(file_id, str)
                    or file_id not in refs
                    or file_id in seen
                    or status is None
                ):
                    continue
                excerpt_value = raw_item.get("excerpt")
                excerpt = None
                unavailable_material_category = None
                requested_unavailable_category = _provider_token(
                    raw_item.get("unavailableMaterialCategory")
                )
                if (
                    attention == "INSUFFICIENT"
                    and status == "INSUFFICIENT"
                    and requested_unavailable_category in _CONTRACT_EVIDENCE_MATERIAL_CATEGORIES
                    and requested_unavailable_category not in available_material_categories
                    and _explicitly_reports_missing_material(
                        title, requested_unavailable_category
                    )
                ):
                    unavailable_material_category = requested_unavailable_category
                if excerpt_value is not None:
                    excerpt = _provider_text(excerpt_value, 1000)
                    if excerpt is None:
                        continue
                    excerpt = bounded_public_excerpt(excerpt)
                    if _MASK_TOKEN in excerpt:
                        excerpt = None
                    elif not _excerpt_occurs_in_material(
                        excerpt, visible_text_by_file_id[file_id]
                    ):
                        continue
                locator_objectively_unavailable = (
                    attention == "INSUFFICIENT"
                    and status == "INSUFFICIENT"
                    and (
                        unavailable_material_category is not None
                        or not _normalized_locator_text(visible_text_by_file_id[file_id])
                    )
                )
                if (
                    excerpt is None
                    and status != "NORMAL"
                    and not locator_objectively_unavailable
                ):
                    continue
                seen.add(file_id)
                evidence_items.append(ContractEvidenceItem(
                    fileId=file_id,
                    status=status,
                    excerpt=excerpt,
                    unavailableMaterialCategory=unavailable_material_category,
                ))

        problem_items = [
            item for item in (evidence_items or []) if item.status != "NORMAL"
        ]
        if attention == "REVIEW":
            evidence_invalid = not any(item.excerpt for item in problem_items)
        elif attention == "INSUFFICIENT":
            evidence_invalid = not any(
                item.excerpt
                or (
                    item.status == "INSUFFICIENT"
                    and item.excerpt is None
                    and (
                        item.unavailable_material_category is not None
                        or not _normalized_locator_text(
                            visible_text_by_file_id[item.file_id]
                        )
                    )
                )
                for item in problem_items
            )
        else:
            evidence_invalid = False
        problem_evidence_ids, comparison_evidence_ids = (
            ModelApplicationService._legacy_evidence_ids(
                refs,
                evidence_items or [],
                evidence_catalog,
            )
        )
        return _ContractHintNormalization(
            hint=ContractHint(
                category=category,
                attention=attention,
                title=title,
                description=description,
                evidenceRefs=refs,
                evidenceItems=evidence_items,
                problem_evidence_ids=problem_evidence_ids,
                comparison_evidence_ids=comparison_evidence_ids,
            ),
            evidence_incomplete=evidence_invalid,
            filter_reason=None,
            category=category,
            attention=attention,
        )

    @staticmethod
    def _legacy_evidence_ids(
        refs: list[str],
        items: list[ContractEvidenceItem],
        evidence_catalog: EvidenceCatalog,
    ) -> tuple[tuple[str, ...], tuple[str, ...]]:
        problem_ids: list[str] = []
        comparison_ids: list[str] = []
        files_with_items: set[str] = set()
        for item in items:
            files_with_items.add(item.file_id)
            candidates = [
                segment
                for segment in evidence_catalog.segments
                if segment.file_id == item.file_id and segment.citable
            ]
            if item.excerpt is not None:
                candidates = [
                    segment
                    for segment in candidates
                    if _excerpt_occurs_in_material(item.excerpt, segment.exact_text)
                ]
            if not candidates:
                continue
            target = problem_ids if item.status != "NORMAL" else comparison_ids
            evidence_id = candidates[0].evidence_id
            if evidence_id not in target:
                target.append(evidence_id)
        for file_id in refs:
            if file_id in files_with_items:
                continue
            segment = next(
                (
                    candidate
                    for candidate in evidence_catalog.segments
                    if candidate.file_id == file_id and candidate.citable
                ),
                None,
            )
            if segment is not None and segment.evidence_id not in comparison_ids:
                comparison_ids.append(segment.evidence_id)
        return tuple(problem_ids), tuple(comparison_ids)

    @staticmethod
    def _normalize_contract_draft_hint(
        *,
        raw_hint: Mapping[str, Any],
        category: str,
        attention: str,
        title: str,
        description: str,
        available_material_categories: set[str],
        evidence_catalog: EvidenceCatalog,
        allowed_evidence_ids: set[str],
        demo_relax_breach_monetary: bool = False,
        request_id: str | None = None,
        monetary_mode: str = "STRICT",
    ) -> tuple[ContractHint | None, bool]:
        raw_output_text = f"{title}\n{description}"
        if _incomplete_contract_description(description):
            return None, False
        if _redaction_artifact_claim(raw_output_text, evidence_catalog):
            return None, False
        if _STRONG_CONTRACT_VERDICT.search(raw_output_text):
            return None, False
        monetary_reason = ModelApplicationService._monetary_rejection_reason(
            raw_output_text
        )
        if monetary_reason is not None and monetary_mode != "OBSERVE":
            if not (
                demo_relax_breach_monetary
                and raw_hint.get("category") == "BREACH_LIABILITY"
                and raw_hint.get("attention") in _DEMO_BREACH_SAFE_COPY
                and category == "BREACH_LIABILITY"
                and attention in _DEMO_BREACH_SAFE_COPY
                and monetary_reason in _MONETARY_REASONS
            ):
                return None, False
            title, description = _DEMO_BREACH_SAFE_COPY[attention]
            log_event(
                LOGGER,
                "contract demo breach monetary text sanitized",
                request_id=request_id or "unavailable",
                category=category,
                attention=attention,
                original_rejection_reason=monetary_reason,
            )

        problem_ids, problem_ids_valid = ModelApplicationService._draft_evidence_ids(
            raw_hint.get("problemEvidenceIds")
        )
        comparison_ids, comparison_ids_valid = (
            ModelApplicationService._draft_evidence_ids(
                raw_hint.get("comparisonEvidenceIds")
            )
        )
        unavailable_category = _provider_token(
            raw_hint.get("unavailableMaterialCategory")
        )
        projection = reconstruct_public_hint(
            ModelHintDraft(
                category=category,
                attention=attention,
                title=title,
                description=description,
                problem_evidence_ids=problem_ids,
                comparison_evidence_ids=comparison_ids,
                unavailable_material_category=unavailable_category,
            ),
            evidence_catalog,
            available_material_categories=available_material_categories,
            allowed_evidence_ids=allowed_evidence_ids,
        )
        evidence_valid = (
            projection.evidence_valid
            and problem_ids_valid
            and comparison_ids_valid
            and len(projection.evidence_items) <= 50
        )
        if unavailable_category is not None and not _explicitly_reports_missing_material(
            title, unavailable_category
        ):
            evidence_valid = False
        if not projection.evidence_refs and attention == "INFO":
            return None, False

        return ContractHint(
            category=category,
            attention=attention,
            title=title,
            description=description,
            evidenceRefs=list(projection.evidence_refs),
            evidenceItems=list(projection.evidence_items[:50]),
            problem_evidence_ids=problem_ids,
            comparison_evidence_ids=comparison_ids,
        ), not evidence_valid

    @staticmethod
    def _draft_evidence_ids(value: Any) -> tuple[tuple[str, ...], bool]:
        if value is None:
            return (), True
        if not isinstance(value, list) or len(value) > 50:
            return (), False
        evidence_ids: list[str] = []
        for raw_id in value:
            evidence_id = _provider_text(raw_id, 100)
            if evidence_id is None:
                return (), False
            if evidence_id not in evidence_ids:
                evidence_ids.append(evidence_id)
        return tuple(evidence_ids), True

    def _apply_contract_correction(
        self,
        *,
        request_id: str,
        project_id: str,
        operation_index: int,
        operation_count: int,
        operation_key: str,
        original_operation_source: str,
        first_result: ContractChunkResult,
        plan: ContractCorrectionPlan,
        evidence_catalog: EvidenceCatalog,
        allowed_evidence_ids: set[str],
        available_material_categories: set[str],
        transport_retry_available: bool,
        retry_reason: str,
        provider_budget: _ContractProviderBudget,
        model_calls: list[ContractModelCallData],
        document_redaction_provenance: bool = False,
    ) -> _CorrectionOutcome:
        correction_source = render_correction_source(
            plan,
            first_result.hints,
            original_operation_source=original_operation_source,
        )
        duration_ms = 0
        provider_call_count = 0
        transport_retry_count = 0
        transport_attempt = 1
        max_transport_attempts = 2 if transport_retry_available else 1
        transport_retry_reason: str | None = None

        while True:
            operation = ContractTextOperation(
                project_id=project_id,
                chunk_index=operation_index,
                chunk_count=operation_count,
                source_text=correction_source,
                globally_available_material_categories=tuple(sorted(available_material_categories)),
                current_operation_material_categories=tuple(sorted({
                    _provider_token(evidence_catalog.by_id[evidence_id].material_category)
                    for evidence_id in allowed_evidence_ids
                })),
                request_id=request_id,
                attempt=2,
                max_attempts=2,
                retry_reason=retry_reason,
                purpose=plan.mode.value,
                required_categories=plan.missing_required_categories,
                allowed_categories=tuple(sorted(_CONTRACT_CATEGORIES)),
                transport_attempt=transport_attempt,
                max_transport_attempts=max_transport_attempts,
                transport_retry_reason=transport_retry_reason,
                document_redaction_provenance=document_redaction_provenance,
            )
            try:
                provider_call_count += 1
                completion = self._call_contract(operation, provider_budget)
            except AuditAiError as failure:
                self._record_failure(model_calls, operation, failure.code, 0)
                raise self._annotate_error(failure, model_calls) from failure
            except ModelGatewayFailure as failure:
                duration_ms += failure.duration_ms
                retrying = (
                    transport_retry_available
                    and transport_retry_count == 0
                    and failure.retryable
                )
                self._record_failure(
                    model_calls, operation, failure.reason, failure.duration_ms
                )
                self._log_contract_gateway_failure(
                    request_id=request_id,
                    operation=operation,
                    gateway_reason=failure.reason,
                    duration_ms=failure.duration_ms,
                    retrying=retrying,
                )
                if retrying:
                    transport_retry_count = 1
                    transport_attempt = 2
                    transport_retry_reason = failure.reason
                    continue
                raise self._gateway_error(model_calls) from failure

            duration_ms += completion.duration_ms
            try:
                corrected = self._merge_contract_correction(
                    first_result=first_result,
                    payload=completion.payload,
                    plan=plan,
                    operation_key=operation_key,
                    evidence_catalog=evidence_catalog,
                    allowed_evidence_ids=allowed_evidence_ids,
                    available_material_categories=available_material_categories,
                    demo_relax_breach_monetary=(
                        self._demo_relax_breach_monetary
                    ),
                    request_id=request_id,
                )
            except ValidationError as failure:
                self._record_completion(
                    model_calls, operation, completion,
                    outcome="FAILED", safe_reason="INVALID_CORRECTION_SCHEMA",
                )
                self._log_correction_schema_rejection(
                    request_id=request_id,
                    plan=plan,
                    payload=completion.payload,
                    operation_index=operation_index,
                    operation_count=operation_count,
                    model=completion.model,
                    duration_ms=completion.duration_ms,
                    failure=failure,
                )
                raise self._invalid_result(model_calls) from failure
            except ContractCorrectionFailure as failure:
                self._record_completion(
                    model_calls, operation, completion,
                    outcome="FAILED", safe_reason=failure.reason.value,
                )
                self._log_correction_rejection(
                    request_id=request_id,
                    reason=failure.reason,
                    plan=plan,
                    payload=completion.payload,
                    operation_index=operation_index,
                    operation_count=operation_count,
                    model=completion.model,
                    duration_ms=completion.duration_ms,
                )
                raise self._invalid_result(model_calls) from failure
            except (ValueError, UnprocessableContractResult) as failure:
                self._record_completion(
                    model_calls, operation, completion,
                    outcome="FAILED", safe_reason="UNPROCESSABLE_RESULT",
                )
                self._log_correction_rejection(
                    request_id=request_id,
                    reason=CorrectionFailureReason.UNPROCESSABLE_RESULT,
                    plan=plan,
                    payload=completion.payload,
                    operation_index=operation_index,
                    operation_count=operation_count,
                    model=completion.model,
                    duration_ms=completion.duration_ms,
                )
                raise self._invalid_result(model_calls) from failure
            self._record_completion(
                model_calls, operation, completion, outcome="SUCCEEDED"
            )
            return _CorrectionOutcome(
                result=corrected,
                model=completion.model,
                duration_ms=duration_ms,
                provider_call_count=provider_call_count,
                transport_retry_count=transport_retry_count,
                transport_retry_consumed=transport_retry_count > 0,
            )

    @staticmethod
    def _merge_contract_correction(
        *,
        first_result: ContractChunkResult,
        payload: Mapping[str, Any],
        plan: ContractCorrectionPlan,
        operation_key: str,
        evidence_catalog: EvidenceCatalog,
        allowed_evidence_ids: set[str],
        available_material_categories: set[str],
        demo_relax_breach_monetary: bool = False,
        request_id: str | None = None,
    ) -> ContractChunkResult:
        correction = parse_contract_correction(payload, mode=plan.mode)
        missing_categories = [
            draft.category for draft in correction.missing_category_hints
        ]
        if set(missing_categories) != set(plan.missing_required_categories):
            raise ContractCorrectionFailure(
                CorrectionFailureReason.CATEGORY_SET_MISMATCH
            )

        repair_ids = [repair.hint_id for repair in correction.evidence_repairs]
        expected_repair_ids = [
            target.hint_id for target in plan.evidence_repair_targets
        ]
        if (
            len(set(repair_ids)) != len(repair_ids)
            or set(repair_ids) != set(expected_repair_ids)
        ):
            raise ContractCorrectionFailure(
                CorrectionFailureReason.REPAIR_TARGET_MISMATCH
            )

        repairs_by_id = {
            repair.hint_id: repair for repair in correction.evidence_repairs
        }
        merged_hints: list[ContractHint] = []
        for hint in first_result.hints:
            repair = repairs_by_id.get(hint.hint_id or "")
            if repair is None:
                merged_hints.append(hint)
                continue
            projection = reconstruct_public_hint(
                ModelHintDraft(
                    category=hint.category,
                    attention=hint.attention,
                    title=hint.title,
                    description=hint.description,
                    problem_evidence_ids=repair.problem_evidence_ids,
                    comparison_evidence_ids=repair.comparison_evidence_ids,
                    unavailable_material_category=(
                        repair.unavailable_material_category
                    ),
                ),
                evidence_catalog,
                available_material_categories=available_material_categories,
                allowed_evidence_ids=allowed_evidence_ids,
            )
            if not projection.evidence_valid:
                raise ContractCorrectionFailure(
                    CorrectionFailureReason.EVIDENCE_INVALID
                )
            if (
                repair.unavailable_material_category is not None
                and not _explicitly_reports_missing_material(
                    hint.title, repair.unavailable_material_category
                )
            ):
                raise ContractCorrectionFailure(
                    CorrectionFailureReason.EVIDENCE_INVALID
                )
            merged_hints.append(
                ContractHint(
                    category=hint.category,
                    attention=hint.attention,
                    title=hint.title,
                    description=hint.description,
                    evidenceRefs=list(projection.evidence_refs),
                    evidenceItems=list(projection.evidence_items),
                    problem_evidence_ids=repair.problem_evidence_ids,
                    comparison_evidence_ids=repair.comparison_evidence_ids,
                    hint_id=hint.hint_id,
                )
            )

        existing_categories = {hint.category for hint in first_result.hints}
        for index, missing_draft in enumerate(
            correction.missing_category_hints, start=1
        ):
            if missing_draft.category in existing_categories:
                raise ContractCorrectionFailure(
                    CorrectionFailureReason.CATEGORY_SET_MISMATCH
                )
            raw_hint = missing_draft.model_dump(
                mode="json", by_alias=True, exclude_none=True
            )
            normalized, evidence_incomplete = (
                ModelApplicationService._normalize_contract_draft_hint(
                    raw_hint=raw_hint,
                    category=missing_draft.category,
                    attention=missing_draft.attention,
                    title=missing_draft.title,
                    description=missing_draft.description,
                    available_material_categories=available_material_categories,
                    evidence_catalog=evidence_catalog,
                    allowed_evidence_ids=allowed_evidence_ids,
                    demo_relax_breach_monetary=(
                        demo_relax_breach_monetary
                    ),
                    request_id=request_id,
                )
            )
            if normalized is None:
                raise ContractCorrectionFailure(
                    CorrectionFailureReason.SEMANTIC_INVALID
                )
            if evidence_incomplete:
                raise ContractCorrectionFailure(
                    CorrectionFailureReason.EVIDENCE_INVALID
                )
            merged_hints.append(
                normalized.model_copy(
                    update={"hint_id": f"{operation_key}:M{index}"}
                )
            )

        final_categories = existing_categories.union(missing_categories)
        if not set(plan.missing_required_categories).issubset(final_categories):
            raise ContractCorrectionFailure(
                CorrectionFailureReason.SEMANTIC_INVALID
            )
        return ContractChunkResult(
            summary=first_result.summary,
            hints=merged_hints,
        )

    @staticmethod
    def _hint_text_filter_reason(
        output_text: str,
        *, monetary_mode: str = "STRICT",
    ) -> _ContractHintFilterReason | None:
        if _STRONG_CONTRACT_VERDICT.search(output_text):
            return _ContractHintFilterReason.STRONG_VERDICT
        if monetary_mode == "OBSERVE":
            return None
        monetary_reason = ModelApplicationService._monetary_rejection_reason(
            output_text
        )
        if monetary_reason is None:
            return None
        return _ContractHintFilterReason(monetary_reason)

    @staticmethod
    def _monetary_rejection_reason(output_text: str) -> str | None:
        if (
            _MONETARY_VALUE.search(output_text)
            or _LABELLED_ABSOLUTE_NUMBER.search(output_text)
        ):
            return "MONETARY_VALUE"
        if (
            _has_monetary_calculation(output_text)
            or _MONETARY_RATE_COMPARISON.search(output_text)
            or _has_unsafe_monetary_conclusion(output_text)
        ):
            return "MONETARY_CALCULATION"
        return None

    @staticmethod
    def _correction_result_counts(payload: Mapping[str, Any]) -> tuple[int, int]:
        missing = payload.get("missingCategoryHints")
        repairs = payload.get("evidenceRepairs")
        return (
            len(missing) if isinstance(missing, list) else 0,
            len(repairs) if isinstance(repairs, list) else 0,
        )

    @staticmethod
    def _correction_returned_categories(
        payload: Mapping[str, Any],
    ) -> tuple[str, ...]:
        items = payload.get("missingCategoryHints")
        if not isinstance(items, list):
            return ()
        return tuple(
            category
            for item in items
            if isinstance(item, Mapping)
            and isinstance((category := item.get("category")), str)
            and category in _CONTRACT_CATEGORIES
        )

    @staticmethod
    def _correction_log_fields(
        *,
        request_id: str,
        reason: CorrectionFailureReason,
        plan: ContractCorrectionPlan,
        payload: Mapping[str, Any],
        operation_index: int,
        operation_count: int,
        model: str,
        duration_ms: int,
    ) -> dict[str, str | int]:
        returned_missing_count, returned_repair_count = (
            ModelApplicationService._correction_result_counts(payload)
        )
        returned_categories = (
            ModelApplicationService._correction_returned_categories(payload)
        )
        return {
            "request_id": request_id,
            "operation": "CONTRACT_TEXT_ANALYSIS",
            "outcome": "REJECTED",
            "reason": reason.value,
            "correction_failure_reason": reason.value,
            "final_model_rejection_reason": reason.value,
            "attempt": 2,
            "max_attempts": 2,
            "purpose": plan.mode.value,
            "correction_mode": plan.mode.value,
            "correction_requested_categories": (
                ",".join(plan.missing_required_categories) or "NONE"
            ),
            "correction_returned_categories": (
                ",".join(returned_categories) or "NONE"
            ),
            "chunk_index": operation_index,
            "chunk_count": operation_count,
            "model": model,
            "duration_ms": duration_ms,
            "missing_category_count": len(plan.missing_required_categories),
            "repair_target_count": len(plan.evidence_repair_targets),
            "evidence_repair_target_count": len(plan.evidence_repair_targets),
            "returned_missing_category_count": returned_missing_count,
            "returned_repair_count": returned_repair_count,
        }

    @staticmethod
    def _log_correction_rejection(
        *,
        request_id: str,
        reason: CorrectionFailureReason,
        plan: ContractCorrectionPlan,
        payload: Mapping[str, Any],
        operation_index: int,
        operation_count: int,
        model: str,
        duration_ms: int,
    ) -> None:
        log_event(
            LOGGER,
            "contract correction result rejected",
            **ModelApplicationService._correction_log_fields(
                request_id=request_id,
                reason=reason,
                plan=plan,
                payload=payload,
                operation_index=operation_index,
                operation_count=operation_count,
                model=model,
                duration_ms=duration_ms,
            ),
        )

    @staticmethod
    def _log_correction_schema_rejection(
        *,
        request_id: str,
        plan: ContractCorrectionPlan,
        payload: Mapping[str, Any],
        operation_index: int,
        operation_count: int,
        model: str,
        duration_ms: int,
        failure: ValidationError,
    ) -> None:
        errors = failure.errors(
            include_url=False,
            include_context=False,
            include_input=False,
        )
        fields = ModelApplicationService._correction_log_fields(
            request_id=request_id,
            reason=CorrectionFailureReason.SCHEMA_INVALID,
            plan=plan,
            payload=payload,
            operation_index=operation_index,
            operation_count=operation_count,
            model=model,
            duration_ms=duration_ms,
        )
        log_event(
            LOGGER,
            "correction validation rejected",
            **fields,
            validation_error_class=type(failure).__name__,
            validation_error_count=len(errors),
        )
        for index, error in enumerate(errors, start=1):
            raw_location = error.get("loc")
            location_parts: list[str] = []
            if isinstance(raw_location, tuple):
                for part in raw_location:
                    if isinstance(part, int) and part >= 0:
                        location_parts.append(str(part))
                    elif (
                        isinstance(part, str)
                        and part in _CORRECTION_VALIDATION_LOC_PARTS
                    ):
                        location_parts.append(part)
                    else:
                        location_parts.append("unknown")
            error_type = error.get("type")
            safe_error_type = (
                error_type
                if isinstance(error_type, str)
                and _CORRECTION_VALIDATION_TYPE.fullmatch(error_type)
                else "unknown"
            )
            actual_fields = ModelApplicationService._safe_correction_actual_fields(
                payload=payload,
                raw_location=raw_location,
            )
            log_event(
                LOGGER,
                "correction validation error observed",
                request_id=request_id,
                operation="CONTRACT_TEXT_ANALYSIS",
                outcome="REJECTED",
                reason=CorrectionFailureReason.SCHEMA_INVALID.value,
                attempt=2,
                max_attempts=2,
                purpose=plan.mode.value,
                correction_mode=plan.mode.value,
                validation_error_index=index,
                validation_error_loc=".".join(location_parts) or "unknown",
                validation_error_type=safe_error_type,
                **actual_fields,
            )

    @staticmethod
    def _safe_correction_actual_fields(
        *, payload: Mapping[str, Any], raw_location: Any
    ) -> dict[str, str]:
        if not isinstance(raw_location, tuple) or not raw_location:
            return {}
        if (
            len(raw_location) == 1
            and raw_location[0] in {"evidenceRepairs", "missingCategoryHints"}
            and raw_location[0] in payload
        ):
            actual = payload[raw_location[0]]
            actual_type = type(actual).__name__
            if actual_type not in {
                "str",
                "NoneType",
                "bool",
                "int",
                "float",
                "list",
                "dict",
            }:
                actual_type = "other"
            return {"validation_actual_type": actual_type}
        if (
            len(raw_location) != 3
            or raw_location[0] not in {"evidenceRepairs", "missingCategoryHints"}
            or not isinstance(raw_location[1], int)
            or raw_location[1] < 0
            or raw_location[2]
            not in {
                "unavailableMaterialCategory",
                "unavailable_material_category",
            }
        ):
            return {}
        items = payload.get(raw_location[0])
        if not isinstance(items, list) or raw_location[1] >= len(items):
            return {}
        item = items[raw_location[1]]
        if not isinstance(item, Mapping):
            return {}
        actual_key = next(
            (
                key
                for key in (
                    "unavailableMaterialCategory",
                    "unavailable_material_category",
                )
                if key in item
            ),
            None,
        )
        if actual_key is None:
            return {}
        actual = item[actual_key]
        actual_type = type(actual).__name__
        if actual_type not in {
            "str",
            "NoneType",
            "bool",
            "int",
            "float",
            "list",
            "dict",
        }:
            actual_type = "other"
        fields = {"validation_actual_type": actual_type}
        if isinstance(actual, str):
            normalized_actual = actual.strip().upper()
            safe_enum = _SAFE_CORRECTION_NULL_SENTINELS.get(normalized_actual)
            if safe_enum is None and normalized_actual in _SAFE_CORRECTION_VALIDATION_ENUMS:
                safe_enum = normalized_actual
            if safe_enum is None and (
                "NOT APPLICABLE" in normalized_actual
                or "NOT_APPLICABLE" in normalized_actual
                or "NOT REQUIRED" in normalized_actual
                or "NOT_REQUIRED" in normalized_actual
                or "N/A" in normalized_actual
                or "不适用" in actual
                or "无需" in actual
            ):
                safe_enum = "NOT_APPLICABLE_VARIANT"
            if safe_enum is None and (
                "NONE" in normalized_actual
                or "NULL" in normalized_actual
                or "NO MISSING" in normalized_actual
                or "NO_MISSING" in normalized_actual
                or "NO UNAVAILABLE" in normalized_actual
                or "NO_UNAVAILABLE" in normalized_actual
                or actual.strip() == "无"
                or "无缺失" in actual
                or "无不可用" in actual
            ):
                safe_enum = "NONE_VARIANT"
            if safe_enum is None and "AVAILABLE" in normalized_actual:
                safe_enum = "AVAILABLE_VARIANT"
            fields["validation_actual_enum"] = safe_enum or "UNRECOGNIZED"
        return fields

    @staticmethod
    def _log_contract_rejection(
        *,
        request_id: str,
        reason: str,
        operation_index: int,
        operation_count: int,
        attempt: int,
        max_attempts: int,
        retrying: bool,
        model: str | None = None,
        duration_ms: int | None = None,
        semantic_defect_types: str | None = None,
        purpose: str | None = None,
        raw_hint_count: int | None = None,
    ) -> None:
        fields: dict[str, str | int] = {
            "operation": "CONTRACT_TEXT_ANALYSIS",
            "request_id": request_id,
            "reason": reason,
            "outcome": "RETRYING" if retrying else "REJECTED",
            "attempt": attempt,
            "max_attempts": max_attempts,
            "chunk_index": operation_index,
            "chunk_count": operation_count,
        }
        if model is not None:
            fields["model"] = model
        if duration_ms is not None:
            fields["duration_ms"] = duration_ms
        if semantic_defect_types is not None:
            fields["semantic_defect_types"] = semantic_defect_types
        if purpose is not None:
            fields["purpose"] = purpose
        if raw_hint_count is not None:
            fields["raw_hint_count"] = raw_hint_count
        if not retrying:
            fields["final_model_rejection_reason"] = reason
        log_event(LOGGER, "contract semantic result rejected", **fields)

    @staticmethod
    def _log_contract_filtered(
        *,
        request_id: str,
        reason: str,
        operation_index: int,
        operation_count: int,
        attempt: int,
        max_attempts: int,
        model: str,
        duration_ms: int,
        filtered_hint_count: int,
    ) -> None:
        log_event(
            LOGGER,
            "contract semantic result filtered",
            operation="CONTRACT_TEXT_ANALYSIS",
            request_id=request_id,
            reason=reason,
            outcome="FILTERED",
            attempt=attempt,
            max_attempts=max_attempts,
            chunk_index=operation_index,
            chunk_count=operation_count,
            model=model,
            duration_ms=duration_ms,
            filtered_hint_count=filtered_hint_count,
        )

    @staticmethod
    def _log_contract_completeness_required(
        *,
        request_id: str,
        missing_required_categories: tuple[str, ...],
    ) -> None:
        log_event(
            LOGGER,
            "contract semantic completeness required",
            operation="CONTRACT_TEXT_ANALYSIS",
            request_id=request_id,
            missing_required_categories=",".join(missing_required_categories),
            required_category_count=len(missing_required_categories),
        )

    @staticmethod
    def _log_contract_coverage_prepared(
        *,
        request_id: str,
        required_categories: tuple[str, ...],
    ) -> None:
        log_event(
            LOGGER,
            "contract semantic coverage prepared",
            operation="CONTRACT_TEXT_ANALYSIS",
            request_id=request_id,
            required_categories=",".join(required_categories) or "NONE",
            required_category_count=len(required_categories),
        )

    @staticmethod
    def _log_contract_hint_normalization(
        *,
        request_id: str,
        operation: ContractTextOperation,
        result: ContractChunkResult,
        filtered_hint_count: int,
        diagnostics: tuple[tuple[int, _ContractHintNormalization], ...],
        evidence_repair_hint_ids: tuple[str, ...],
    ) -> None:
        filtered_categories: list[str] = []
        filter_reason_counts = {
            reason: 0 for reason in _ContractHintFilterReason
        }
        for item_index, diagnostic in diagnostics:
            if diagnostic.hint is not None or diagnostic.filter_reason is None:
                continue
            fields: dict[str, str | int] = {
                "operation": "CONTRACT_TEXT_ANALYSIS",
                "request_id": request_id,
                "attempt": operation.attempt,
                "purpose": operation.purpose,
                "chunk_index": operation.chunk_index,
                "item_index": item_index,
                "category": diagnostic.category or "NONE",
                "attention": diagnostic.attention or "NONE",
                "filter_reason": diagnostic.filter_reason.value,
            }
            log_event(LOGGER, "contract semantic hint filtered", **fields)
            filter_reason_counts[diagnostic.filter_reason] += 1
            if (
                diagnostic.category is not None
                and diagnostic.category not in filtered_categories
            ):
                filtered_categories.append(diagnostic.category)

        accepted_categories: list[str] = []
        for hint in result.hints:
            if hint.category not in accepted_categories:
                accepted_categories.append(hint.category)
        classified_filtered_count = sum(filter_reason_counts.values())
        overflow_filtered_count = max(
            0, filtered_hint_count - classified_filtered_count
        )
        filter_reason_counts[
            _ContractHintFilterReason.OTHER_SAFE_FILTER
        ] += overflow_filtered_count
        rendered_filter_reason_counts = ",".join(
            f"{reason.value}:{count}"
            for reason, count in filter_reason_counts.items()
            if count
        ) or "NONE"
        log_event(
            LOGGER,
            "contract semantic hint normalization completed",
            operation="CONTRACT_TEXT_ANALYSIS",
            request_id=request_id,
            attempt=operation.attempt,
            purpose=operation.purpose,
            input_hint_count=len(result.hints) + filtered_hint_count,
            raw_hint_count=len(result.hints) + filtered_hint_count,
            accepted_hint_count=len(result.hints),
            normalized_hint_count=len(result.hints),
            filtered_hint_count=filtered_hint_count,
            filter_reason_counts=rendered_filter_reason_counts,
            accepted_categories=",".join(accepted_categories) or "NONE",
            filtered_categories=",".join(filtered_categories) or "NONE",
            returned_categories=",".join(accepted_categories) or "NONE",
            evidence_repair_target_count=len(evidence_repair_hint_ids),
        )

    @staticmethod
    def _log_contract_gateway_failure(
        *,
        request_id: str,
        operation: ContractTextOperation,
        gateway_reason: str,
        duration_ms: int,
        retrying: bool,
    ) -> None:
        log_event(
            LOGGER,
            "contract model attempt failed",
            request_id=request_id,
            operation="CONTRACT_TEXT_ANALYSIS",
            outcome="RETRYING" if retrying else "REJECTED",
            gateway_reason=gateway_reason,
            attempt=operation.attempt,
            max_attempts=operation.max_attempts,
            transport_attempt=operation.transport_attempt,
            max_transport_attempts=operation.max_transport_attempts,
            chunk_index=operation.chunk_index,
            chunk_count=operation.chunk_count,
            duration_ms=duration_ms,
        )

    @staticmethod
    def _call_phase(operation: ContractTextOperation) -> Literal["TRANSPORT", "REPAIR"]:
        if (operation.transport_attempt > 1 or operation.attempt > 1
                or operation.purpose not in {"ANALYZE", "SYNTHESIZE"}):
            return "REPAIR"
        return "TRANSPORT"

    @staticmethod
    def _record_completion(
        calls: list[ContractModelCallData],
        operation: ContractTextOperation,
        completion: Any,
        *,
        outcome: Literal["SUCCEEDED", "FAILED"],
        safe_reason: str | None = None,
    ) -> None:
        calls.append(ContractModelCallData(
            ordinal=len(calls) + 1,
            phase=ModelApplicationService._call_phase(operation),
            outcome=outcome,
            safeReason=safe_reason,
            durationMs=completion.duration_ms,
            finishReason=completion.finish_reason,
            promptTokens=completion.prompt_tokens,
            completionTokens=completion.completion_tokens,
            totalTokens=completion.total_tokens,
            provider=completion.provider,
            model=completion.model,
        ))

    def _record_failure(
        self,
        calls: list[ContractModelCallData],
        operation: ContractTextOperation,
        reason: str,
        duration_ms: int,
    ) -> None:
        calls.append(ContractModelCallData(
            ordinal=len(calls) + 1,
            phase=self._call_phase(operation),
            outcome="FAILED",
            safeReason=reason,
            durationMs=duration_ms,
            provider=self._configured_provider,
            model=self._configured_model,
        ))

    @staticmethod
    def _call_error_data(calls: list[ContractModelCallData]) -> dict[str, Any]:
        return {"modelCalls": [call.model_dump(by_alias=True) for call in calls]}

    @classmethod
    def _annotate_error(
        cls, error: AuditAiError, calls: list[ContractModelCallData]
    ) -> AuditAiError:
        if error.data is None and calls:
            error.data = cls._call_error_data(calls)
        return error

    def _call_contract(
        self,
        operation: ContractTextOperation,
        provider_budget: _ContractProviderBudget,
    ):
        try:
            with diagnostic_context(chunk_index=operation.chunk_index, chunk_count=operation.chunk_count):
                return self._gateway.analyze_contract(provider_budget.bind(operation))
        except ModelNotConfigured as failure:
            raise AuditAiError(
                503, "MODEL_NOT_CONFIGURED", "model runtime is not configured"
            ) from failure
        except ModelTotalBudgetExhausted as failure:
            raise AuditAiError(
                504,
                "MODEL_TOTAL_BUDGET_EXHAUSTED",
                "model total time budget is exhausted",
            ) from failure

    def _call_budget(self, operation: BudgetSemanticOperation):
        try:
            with diagnostic_context(chunk_index=operation.chunk_index, chunk_count=operation.chunk_count):
                return self._gateway.analyze_budget(operation)
        except ModelNotConfigured as failure:
            raise AuditAiError(
                503, "MODEL_NOT_CONFIGURED", "model runtime is not configured"
            ) from failure
        except ModelGatewayFailure as failure:
            raise self._gateway_error() from failure

    def _call_tender_compliance(self, operation: TenderComplianceOperation):
        try:
            with diagnostic_context(chunk_index=operation.chunk_index, chunk_count=operation.chunk_count):
                return self._gateway.analyze_tender_compliance(operation)
        except ModelNotConfigured as failure:
            raise AuditAiError(503, "MODEL_NOT_CONFIGURED", "model runtime is not configured") from failure
        except ModelGatewayFailure as failure:
            raise self._gateway_error() from failure

    def _call_header_mapping(self, operation: SpreadsheetHeaderMappingOperation):
        try:
            return self._gateway.analyze_header_mapping(operation)
        except ModelNotConfigured as failure:
            raise AuditAiError(503, "MODEL_NOT_CONFIGURED", "model runtime is not configured") from failure
        except ModelGatewayFailure as failure:
            raise self._gateway_error() from failure

    @staticmethod
    def _one_model(models: list[str]) -> str:
        if not models or len(set(models)) != 1:
            raise ModelApplicationService._invalid_result()
        return models[0]

    @staticmethod
    def _invalid_result(
        calls: list[ContractModelCallData] | None = None,
    ) -> AuditAiError:
        return AuditAiError(
            502, "MODEL_INVALID_RESULT", "model returned an invalid result",
            ModelApplicationService._call_error_data(calls) if calls else None,
        )

    @staticmethod
    def _gateway_error(
        calls: list[ContractModelCallData] | None = None,
    ) -> AuditAiError:
        return AuditAiError(
            502, "MODEL_GATEWAY_ERROR", "model service is unavailable",
            ModelApplicationService._call_error_data(calls) if calls else None,
        )


def create_model_router(settings: Settings, gateway: ModelGatewayPort) -> APIRouter:
    router = APIRouter()
    service = ModelApplicationService(settings, gateway)
    from audit_ai.model.semantic_work import ContractSemanticWorkService
    work = ContractSemanticWorkService(settings, gateway)

    @router.post("/ai/v1/agent/contract-semantic-work/plan")
    async def plan_semantic_work(body: ContractAnalysisRequest):
        return await run_in_threadpool(work.plan, body)

    @router.post("/ai/v1/agent/contract-semantic-work/advance")
    async def advance_semantic_work(body: ContractSemanticAdvanceRequest, request: Request):
        return await run_in_threadpool(work.advance, body.request, body.plan_fingerprint,
                                      body.checkpoints, body.expected_operation_fingerprint,
                                      remaining_ms=body.remaining_ms, execute=body.execute,
                                      request_id=getattr(request.state, "request_id", "unavailable"))

    @router.post(
        "/ai/v1/agent/contract-text-analysis", response_model=ContractEnvelope,
        response_model_exclude_none=True,
    )
    async def analyze_contract(
        body: ContractAnalysisRequest, request: Request
    ) -> ContractEnvelope:
        request_id = getattr(request.state, "request_id", "unavailable")
        return await run_in_threadpool(service.analyze_contract, body, request_id)

    @router.post(
        "/ai/v1/agent/budget-semantic-analysis",
        response_model=BudgetEnvelope,
        response_model_exclude_none=True,
    )
    async def analyze_budget(
        body: BudgetAnalysisRequest, request: Request
    ) -> BudgetEnvelope:
        request_id = getattr(request.state, "request_id", "unavailable")
        return await run_in_threadpool(service.analyze_budget, body, request_id)

    @router.post(
        "/ai/v1/agent/procurement-tender-compliance",
        response_model=TenderComplianceEnvelope,
        response_model_exclude_none=True,
    )
    async def analyze_tender_compliance(
        body: TenderComplianceRequest, request: Request
    ) -> TenderComplianceEnvelope:
        request_id = getattr(request.state, "request_id", "unavailable")
        return await run_in_threadpool(service.analyze_tender_compliance, body, request_id)

    @router.post(
        "/ai/v1/agent/procurement-header-mapping",
        response_model=HeaderMappingEnvelope,
    )
    async def analyze_header_mapping(
        body: HeaderMappingRequest, request: Request
    ) -> HeaderMappingEnvelope:
        request_id = getattr(request.state, "request_id", "unavailable")
        return await run_in_threadpool(service.analyze_header_mapping, body, request_id)

    return router
