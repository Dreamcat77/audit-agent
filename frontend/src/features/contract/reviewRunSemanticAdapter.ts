import { reviewFindingSourceLabel, reviewSafeReasonText, reviewStepLabel } from "./reviewRunPresentation.ts";
import type {
  ReviewEvidenceDto,
  ReviewFindingDto,
  ReviewStepOutputDto,
  ReviewStepType,
} from "./reviewRunTypes.ts";

const SECTION_TYPES = ["SEMANTIC_AI", "BREACH", "TERM"] as const;

const CATEGORY_LABELS: Readonly<Record<string, string>> = {
  CONTRACT_STRUCTURE: "合同结构",
  PROJECT_IDENTITY: "项目身份",
  SUPPLIER_IDENTITY: "供应商身份",
  DELIVERY_TERMS: "交付与验收",
  SERVICE_WARRANTY: "服务与质保",
  SCOPE_AND_SUBCONTRACTING: "服务范围与分包",
  BREACH_LIABILITY: "违约责任",
  TERM_TERMINATION: "履约期限与终止条件",
  OTHER_TEXT: "其他合同条款",
  BREACH: "违约责任",
  TERM: "履约期限与终止条件",
};

export interface DurableReviewEvidenceView {
  id: string;
  fileId: string;
  fileName: string;
  excerpt: string;
  locatorAvailable: boolean;
  locatorLabel: string;
  pageNumber: number | null;
  paragraphIndex: number | null;
  segmentIndex: number | null;
  startOffset: number | null;
  endOffset: number | null;
}

export interface DurableReviewIssueView {
  id: string;
  versionNo: number;
  categoryLabel: string;
  statusLabel: string;
  statusTone?: "neutral" | "review" | "uncertain";
  sourceLabel: string;
  summary: string;
  recommendation: string | null;
  evidence: readonly DurableReviewEvidenceView[];
}

export interface DurableReviewSectionView {
  key: "SEMANTIC" | "BREACH" | "TERM";
  label: string;
  statusLabel: string;
  statusTone?: "neutral" | "success" | "danger" | "uncertain";
  detail: string;
  issues: readonly DurableReviewIssueView[];
}

export interface DurableSemanticReviewPresentation {
  title: string;
  authorityNotice: string;
  sections: readonly DurableReviewSectionView[];
}

function sectionKey(type: typeof SECTION_TYPES[number]): DurableReviewSectionView["key"] {
  if (type === "SEMANTIC_AI") return "SEMANTIC";
  return type;
}

function statusLabel(status: ReviewStepOutputDto["step"]["status"]): string {
  if (status === "PENDING") return "等待开始";
  if (status === "RUNNING") return "正在审核";
  if (status === "COMPLETED") return "已完成";
  if (status === "UNKNOWN") return "结果状态待确认";
  return "未完成";
}

function categoryLabel(finding: ReviewFindingDto): string {
  return CATEGORY_LABELS[finding.category] ?? "审核提示";
}

function issueStatus(finding: ReviewFindingDto): string {
  if (finding.statusCode === "INSUFFICIENT" || finding.attentionCode === "INSUFFICIENT") return "关键事实暂无法确认";
  if (finding.statusCode === "REVIEW" || finding.attentionCode === "REVIEW"
      || finding.riskLevel === "RED" || finding.riskLevel === "YELLOW") return "建议复核";
  if (finding.statusCode === "INFO" || finding.attentionCode === "INFO") return "信息提示";
  return "已识别";
}

function issueTone(finding: ReviewFindingDto): DurableReviewIssueView["statusTone"] {
  if (finding.statusCode === "INSUFFICIENT" || finding.attentionCode === "INSUFFICIENT") return "uncertain";
  if (finding.statusCode === "REVIEW" || finding.attentionCode === "REVIEW"
      || finding.riskLevel === "RED" || finding.riskLevel === "YELLOW") return "review";
  return "neutral";
}

function sectionTone(status: ReviewStepOutputDto["step"]["status"]): DurableReviewSectionView["statusTone"] {
  if (status === "COMPLETED") return "success";
  if (status === "FAILED") return "danger";
  if (status === "UNKNOWN") return "uncertain";
  return "neutral";
}

function customerSummary(
  type: typeof SECTION_TYPES[number],
  finding: ReviewFindingDto,
): string {
  if (type === "SEMANTIC_AI") return finding.summary;
  const serializedFieldPattern = /(?:^|;\s*)[A-Za-z][A-Za-z0-9]*\s*=/;
  if (!serializedFieldPattern.test(finding.summary)) return finding.summary;
  return type === "BREACH"
    ? "系统已识别一项违约条款核对结果，请结合来源依据复核。"
    : "系统已识别一项履约期限或终止条件核对结果，请结合来源依据复核。";
}

function locatorLabel(evidence: ReviewEvidenceDto): string {
  if (!evidence.locatorAvailable) return "暂无法精确定位原文位置，可查看来源材料。";
  const locations = [
    evidence.pageNumber === null ? null : `第 ${evidence.pageNumber} 页`,
    evidence.paragraphIndex === null ? null : `第 ${evidence.paragraphIndex} 段`,
    evidence.tableIndex === null ? null : `第 ${evidence.tableIndex} 个表格`,
    evidence.rowIndex === null ? null : `第 ${evidence.rowIndex} 行`,
    evidence.cellIndex === null ? null : `第 ${evidence.cellIndex} 个单元格`,
    evidence.segmentIndex === null ? null : `第 ${evidence.segmentIndex} 个文本片段`,
  ].filter((value): value is string => value !== null);
  return locations.length > 0 ? locations.join(" · ") : "已在来源材料中定位";
}

function evidenceView(evidence: ReviewEvidenceDto, fileNames: ReadonlyMap<string, string>): DurableReviewEvidenceView {
  return {
    id: evidence.evidenceId,
    fileId: evidence.fileId,
    fileName: fileNames.get(evidence.fileId) ?? "来源材料",
    excerpt: evidence.boundedExcerpt,
    locatorAvailable: evidence.locatorAvailable,
    locatorLabel: locatorLabel(evidence),
    pageNumber: evidence.locatorAvailable ? evidence.pageNumber : null,
    paragraphIndex: evidence.locatorAvailable ? evidence.paragraphIndex : null,
    segmentIndex: evidence.locatorAvailable ? evidence.segmentIndex : null,
    startOffset: evidence.locatorAvailable ? evidence.startOffset : null,
    endOffset: evidence.locatorAvailable ? evidence.endOffset : null,
  };
}

function section(
  type: typeof SECTION_TYPES[number],
  output: ReviewStepOutputDto | undefined,
  fileNames: ReadonlyMap<string, string>,
): DurableReviewSectionView {
  if (!output) return {
    key: sectionKey(type),
    label: reviewStepLabel(type),
    statusLabel: "等待开始",
    statusTone: "neutral",
    detail: "等待本审核环节开始。",
    issues: [],
  };
  const evidenceByFinding = new Map<string, ReviewEvidenceDto[]>();
  for (const item of output.evidence) {
    const values = evidenceByFinding.get(item.findingId) ?? [];
    values.push(item);
    evidenceByFinding.set(item.findingId, values);
  }
  const issues = output.findings.map((finding): DurableReviewIssueView => ({
    id: finding.findingId,
    versionNo: finding.versionNo,
    categoryLabel: categoryLabel(finding),
    statusLabel: issueStatus(finding),
    statusTone: issueTone(finding),
    sourceLabel: reviewFindingSourceLabel(finding.source),
    summary: customerSummary(type, finding),
    recommendation: finding.recommendation,
    evidence: (evidenceByFinding.get(finding.findingId) ?? [])
      .sort((left, right) => left.ordinal - right.ordinal)
      .map((item) => evidenceView(item, fileNames)),
  }));
  const detail = output.step.status === "COMPLETED"
    ? issues.length > 0 ? `已形成 ${issues.length} 项审核提示。` : "本环节已完成，未发现需提示的问题。"
    : output.step.status === "PENDING" ? "等待本审核环节开始。"
      : output.step.status === "RUNNING" ? "正在处理本审核环节，请稍候。"
        : reviewSafeReasonText(output.step.safeReasonCode, output.step.status);
  return {
    key: sectionKey(type),
    label: reviewStepLabel(type),
    statusLabel: statusLabel(output.step.status),
    statusTone: sectionTone(output.step.status),
    detail,
    issues,
  };
}

export function adaptDurableSemanticReview(
  outputs: readonly ReviewStepOutputDto[],
  fileNames: ReadonlyMap<string, string>,
): DurableSemanticReviewPresentation {
  return {
    title: "智能语义与条款审核",
    authorityNotice: "智能分析用于辅助发现需复核内容，最终判断以业务人员核验为准。",
    sections: SECTION_TYPES.map((type) => section(
      type,
      outputs.find((output) => output.step.stepType === type),
      fileNames,
    )),
  };
}
