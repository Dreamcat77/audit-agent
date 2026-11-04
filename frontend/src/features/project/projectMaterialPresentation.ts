import type {
  CandidatePromotionState,
  DocumentRole,
  EffectiveDocumentRoleSource,
  FieldState,
  ProjectFile,
  ProjectFileParseMethod,
} from "./types";

export type ProjectMaterialTabKey = "CONTRACT" | "TENDER" | "BID" | "AWARD" | "OTHER";

const TAB_PRESENTATIONS: ReadonlyArray<{ key: ProjectMaterialTabKey; label: string }> = [
  { key: "CONTRACT", label: "合同正文" },
  { key: "TENDER", label: "招标文件" },
  { key: "BID", label: "投标文件" },
  { key: "AWARD", label: "中标通知书" },
  { key: "OTHER", label: "其他材料" },
];

const CATEGORY_TABS: Readonly<Record<string, ProjectMaterialTabKey>> = {
  CONTRACT_BODY: "CONTRACT",
  TENDER_DOCUMENT: "TENDER",
  BID_RESPONSE: "BID",
  AWARD_NOTICE: "AWARD",
  OTHER: "OTHER",
  SUPPLEMENT: "OTHER",
  UNKNOWN: "OTHER",
};

const FACT_LABELS: Readonly<Record<string, string>> = {
  PROJECT_NAME: "项目名称",
  PROJECT_NO: "项目编号",
  PURCHASER: "采购人",
  PROCUREMENT_AGENT: "采购代理机构",
  BUDGET_AMOUNT: "预算金额",
  CONTROL_PRICE: "最高限价",
  PACKAGE_SCOPE: "采购包范围",
  CONTRACT_NO: "合同编号",
  CONTRACT_NAME: "合同名称",
  PARTY_A: "甲方",
  PARTY_B: "乙方",
  SUPPLIER_NAME: "供应商名称",
  CREDIT_CODE: "统一社会信用代码",
  AMOUNT_NUMERIC: "合同金额（数字）",
  AMOUNT_UPPERCASE: "合同金额（大写）",
  AWARD_AMOUNT: "中标金额",
  AWARD_SUPPLIER: "中标供应商",
  AWARD_SUPPLIER_CREDIT_CODE: "中标供应商统一社会信用代码",
  LINE_ITEM_NAME: "明细名称",
  LINE_ITEM_QUANTITY: "数量",
  LINE_ITEM_UNIT_PRICE: "单价",
  LINE_ITEM_DECLARED_AMOUNT: "申报金额",
};

export interface ProjectMaterialPresentation {
  id: string;
  projectId: string;
  fileName: string;
  mediaType: string;
  classifierCategory: string;
  effectiveRole: DocumentRole;
  effectiveRoleSource: EffectiveDocumentRoleSource;
  reviewRole: DocumentRole;
  roleDecisionId: string | null;
  ingestionStatus: ProjectFile["ingestionStatus"];
  parseStatus: ProjectFile["parseStatus"];
  effectiveIdentityStatus: string | null;
  identityCurrent: boolean;
  excludedFromProjectUse: boolean;
  semanticEligible: boolean;
  statusLabel: string;
  parsedText: string | null;
  provenance: ParseProvenancePresentation;
}

function authoritativeReviewRole(file: ProjectFile): {
  effectiveRole: DocumentRole;
  effectiveRoleSource: EffectiveDocumentRoleSource;
  reviewRole: DocumentRole;
} {
  const effectiveRole = file.effectiveRole ?? "UNKNOWN";
  const effectiveRoleSource = file.effectiveRoleSource ?? "NO_AUTHORITATIVE_ROLE";
  const humanConfirmed = effectiveRoleSource === "HUMAN_CONFIRMATION"
    && Boolean(file.roleDecisionId);
  const systemConfirmed = effectiveRoleSource === "SYSTEM_AUTO_DECISION"
    && file.roleDecisionStatus === "CONFIRMED" && Boolean(file.roleDecisionId);
  const legacyEffective = effectiveRoleSource === "LEGACY_EFFECTIVE"
    && effectiveRole !== "UNKNOWN";
  return {
    effectiveRole,
    effectiveRoleSource,
    reviewRole: humanConfirmed || systemConfirmed || legacyEffective ? effectiveRole : "UNKNOWN",
  };
}

export interface ProjectMaterialTabPresentation {
  key: ProjectMaterialTabKey;
  label: string;
  files: ProjectMaterialPresentation[];
}

export function materialCategoryLabel(category: string): string {
  const tab = CATEGORY_TABS[category] ?? "OTHER";
  return TAB_PRESENTATIONS.find((item) => item.key === tab)?.label ?? "其他材料";
}

export function materialStatusLabel(file: Pick<ProjectFile, "ingestionStatus" | "parseStatus">): string {
  if (file.ingestionStatus === "UPLOAD_PENDING") return "处理中";
  if (file.ingestionStatus === "UPLOAD_FAILED") return "上传失败";
  if (file.parseStatus === "PARSED") return "已解析";
  if (file.parseStatus === "UNSUPPORTED") return "当前版本暂不支持文本解析";
  if (file.parseStatus === "FAILED") return "解析失败";
  if (file.parseStatus === "PENDING") return "等待解析";
  return "可用";
}

export interface ParseProvenancePresentation {
  methodLabel: string;
  confidenceLabel: string | null;
  pageCountLabel: string | null;
}

const PARSE_METHOD_LABELS: Readonly<Record<ProjectFileParseMethod, string>> = {
  PDF_TEXT: "PDF 文本解析",
  DOCX_TEXT: "Word 文本解析",
  DOC_TEXT: "Word 97-2003 文本解析",
  OCR: "OCR 文字识别",
};

export function parseProvenancePresentation(file: Pick<ProjectFile,
  "parseMethod" | "parseConfidence" | "pageCount">): ParseProvenancePresentation {
  return {
    methodLabel: file.parseMethod ? PARSE_METHOD_LABELS[file.parseMethod] : "—",
    confidenceLabel: file.parseMethod === "OCR" && file.parseConfidence != null
      ? `${(file.parseConfidence * 100).toFixed(1)}%`
      : null,
    pageCountLabel: file.pageCount == null ? null : `${file.pageCount} 页`,
  };
}

export function projectMaterialTabs(
  files: readonly ProjectFile[],
  expectedProjectId: string | null = null,
): ProjectMaterialTabPresentation[] {
  return TAB_PRESENTATIONS.map((tab) => ({
    ...tab,
    files: files
      .filter((file) => file.category !== "PACKAGE")
      .map((file) => ({ file, role: authoritativeReviewRole(file) }))
      .filter(({ role }) => (CATEGORY_TABS[role.reviewRole] ?? "OTHER") === tab.key)
      .map(({ file, role }) => ({
        id: file.id,
        projectId: file.projectId,
        fileName: file.originalFilename,
        mediaType: file.mediaType,
        classifierCategory: file.category,
        effectiveRole: role.effectiveRole,
        effectiveRoleSource: role.effectiveRoleSource,
        reviewRole: role.reviewRole,
        roleDecisionId: file.roleDecisionId ?? null,
        ingestionStatus: file.ingestionStatus,
        parseStatus: file.parseStatus,
        effectiveIdentityStatus: file.effectiveIdentityStatus ?? null,
        identityCurrent: file.identityCurrent === true,
        excludedFromProjectUse: file.excludedFromProjectUse === true,
        semanticEligible: role.reviewRole !== "UNKNOWN"
          && (expectedProjectId == null || file.projectId === expectedProjectId)
          && file.effectiveIdentityStatus === "SAME_PROJECT"
          && file.identityCurrent === true
          && file.excludedFromProjectUse === false
          && file.ingestionStatus === "AVAILABLE"
          && file.parseStatus === "PARSED"
          && Boolean(file.parsedText?.trim()),
        statusLabel: materialStatusLabel(file),
        parsedText: file.parsedText,
        fileSha256: file.fileSha256 ?? null,
        parseResultId: file.parseResultId ?? null,
        parseVersion: file.parseVersion ?? null,
        canonicalTextSha256: file.canonicalTextSha256 ?? null,
        provenance: parseProvenancePresentation(file),
      })),
  }));
}

export function candidatePromotionLabel(state: CandidatePromotionState): string {
  return state === "PROMOTED" ? "已生成正式合同" : "系统整理中";
}

export function fieldStateLabel(state: FieldState): "已识别" | "未识别" | "内容存在差异" {
  if (state === "CONFIRMED") return "已识别";
  if (state === "CONFLICT") return "内容存在差异";
  return "未识别";
}

export function factTypeLabel(factType: string): string {
  return FACT_LABELS[factType] ?? "其他已识别字段";
}
