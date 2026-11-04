import type {
  BreachClauseReview,
  ContractTermClauseReview,
  ContractTextAnalysis,
  ContractTextAnalysisAttention,
  ContractTextAnalysisCategory,
  ContractTextAnalysisEvidenceStatus,
} from "./types";

export interface ContractTextMaterialReference {
  id: string;
  fileName: string;
}

export interface ContractTextEvidenceReference extends ContractTextMaterialReference {
  status: ContractTextAnalysisEvidenceStatus;
  excerpt: string | null;
  locatorAvailable: boolean;
}

export interface BreachClauseEnrichment {
  rate: number;
  rateUnit: "PERCENT" | "PER_MILLE";
  frequency: "DAY" | "WEEK" | "MONTH" | "EVENT" | "ONCE";
  calculationBasis: "CONTRACT_AMOUNT" | "LINE_ITEM_AMOUNT" | "OTHER_OR_UNCLEAR";
  correspondingAmount: number | null;
  capRate: number | null;
  capRateUnit: "PERCENT" | "PER_MILLE" | null;
  capAmount: number | null;
  actualPenaltyAmount: number | null;
}

export interface TermDateEnrichment {
  type: ContractTermClauseReview["dates"][number]["type"];
  normalizedDate: string;
}

export type ContractTextAnalysisCoverage = "ASSESSED" | "UNASSESSED";

export interface ContractTermHelperFactPresentation {
  kind: "DATE" | "CONDITION";
  label: string;
  status: ContractTextAnalysisEvidenceStatus;
  description: string;
  source: ContractTextEvidenceReference;
}

export interface ContractTextAnalysisHintPresentation {
  category: ContractTextAnalysisCategory;
  categoryLabel: string;
  attention: ContractTextAnalysisAttention;
  attentionLabel: string;
  title: string;
  description: string;
  sources: ContractTextEvidenceReference[];
  problemSources: ContractTextEvidenceReference[];
  problemMaterialNotice: string | null;
  breachEnrichment: BreachClauseEnrichment | null;
  termDateEnrichments: TermDateEnrichment[];
}

export type ContractTextAnalysisSectionKey =
  | "SEMANTIC_COMPARISON"
  | "BREACH_LIABILITY"
  | "TERM_TERMINATION";

export interface ContractTextAnalysisSection {
  key: ContractTextAnalysisSectionKey;
  label: string;
  hints: ContractTextAnalysisHintPresentation[];
  slots: ContractTextAnalysisSlot[];
}

export interface ContractTextAnalysisSlot {
  category: ContractTextAnalysisCategory;
  categoryLabel: string;
  coverage: ContractTextAnalysisCoverage;
  coverageLabel: "已形成模型判断" | "未形成可靠判断";
  hints: ContractTextAnalysisHintPresentation[];
  helperFacts: ContractTermHelperFactPresentation[];
}

export interface ContractTextAnalysisPresentation {
  title: "智能语义审查";
  phase: "NOT_STARTED" | "RUNNING" | "RESULT" | "ERROR";
  statusLabel: string;
  detail: string | null;
  authorityNotice: string;
  summaries: string[];
  hints: ContractTextAnalysisHintPresentation[];
  sections: ContractTextAnalysisSection[];
  analysisDuration: string | null;
}

interface ContractTextAnalysisPresentationOptions {
  materials?: readonly ContractTextMaterialReference[];
  notice?: string | null;
  breachResult?: BreachClauseReview | null;
  termResult?: ContractTermClauseReview | null;
}

const CATEGORY_LABELS: Record<ContractTextAnalysisCategory, string> = {
  CONTRACT_STRUCTURE: "合同结构与条款完整性",
  PROJECT_IDENTITY: "项目身份语义核对",
  SUPPLIER_IDENTITY: "供应商语义核对",
  DELIVERY_TERMS: "交付与验收要求",
  SERVICE_WARRANTY: "服务与质保",
  SCOPE_AND_SUBCONTRACTING: "合同范围与分包",
  BREACH_LIABILITY: "违约责任",
  TERM_TERMINATION: "合同期限与终止条件",
  OTHER_TEXT: "其他文字条款",
};

const ATTENTION_LABELS: Record<ContractTextAnalysisAttention, string> = {
  INFO: "未发现明显文字冲突",
  REVIEW: "建议复核",
  INSUFFICIENT: "材料依据不足",
};

const SECTION_DEFINITIONS: ReadonlyArray<{
  key: ContractTextAnalysisSectionKey;
  label: string;
}> = [
  { key: "SEMANTIC_COMPARISON", label: "4.1 跨材料语义一致性" },
  { key: "BREACH_LIABILITY", label: "4.2 违约责任条款审查" },
  { key: "TERM_TERMINATION", label: "4.3 合同期限与终止条件审查" },
];

const SEMANTIC_CATEGORIES: readonly ContractTextAnalysisCategory[] = [
  "CONTRACT_STRUCTURE",
  "PROJECT_IDENTITY",
  "SUPPLIER_IDENTITY",
  "DELIVERY_TERMS",
  "SERVICE_WARRANTY",
  "SCOPE_AND_SUBCONTRACTING",
];

const TERM_DATE_LABELS: Record<ContractTermClauseReview["dates"][number]["type"], string> = {
  SIGNING_DATE: "签订日期",
  EFFECTIVE_DATE: "生效日期",
  PERFORMANCE_START_DATE: "履约开始日期",
  DELIVERY_DEADLINE: "交付期限",
  TERMINATION_DATE: "终止日期",
};

const TERM_CONDITION_LABELS: Record<ContractTermClauseReview["conditions"][number]["type"], string> = {
  EFFECTIVE_EVENT: "事件触发生效",
  TERMINATION: "解除/终止条件",
};

function durationLabel(durationMs: number): string {
  return `${(durationMs / 1000).toFixed(1)} 秒`;
}

function normalizedExcerpt(value: string): string {
  return value.normalize("NFKC").replace(/\s+/gu, "");
}

function sameVerifiedEvidence(
  fileId: string,
  helperExcerpt: string,
  evidenceItems: ContractTextAnalysis["hints"][number]["evidenceItems"],
): boolean {
  const helper = normalizedExcerpt(helperExcerpt);
  if (!helper) return false;
  return (evidenceItems ?? []).some((evidence) => {
    if (evidence.fileId !== fileId || !evidence.locatorAvailable || !evidence.excerpt) return false;
    const ai = normalizedExcerpt(evidence.excerpt);
    return Boolean(ai) && (helper.includes(ai) || ai.includes(helper));
  });
}

function breachEnrichment(
  hint: ContractTextAnalysis["hints"][number],
  result: BreachClauseReview | null | undefined,
): BreachClauseEnrichment | null {
  if (hint.category !== "BREACH_LIABILITY") return null;
  const item = result?.items.find((candidate) => sameVerifiedEvidence(
    candidate.sourceFileId,
    candidate.excerpt,
    hint.evidenceItems,
  ));
  if (!item || item.frequency === null) return null;
  const capRateUnit = item.capRateUnit === "PERCENT" || item.capRateUnit === "PER_MILLE"
    ? item.capRateUnit
    : null;
  return {
    rate: item.rate,
    rateUnit: item.rateUnit,
    frequency: item.frequency,
    calculationBasis: item.calculationBasis,
    correspondingAmount: item.correspondingAmount,
    capRate: capRateUnit === null ? null : item.capRate,
    capRateUnit,
    capAmount: capRateUnit === null ? null : item.capAmount,
    actualPenaltyAmount: item.actualPenaltyAmount,
  };
}

function termDateEnrichments(
  hint: ContractTextAnalysis["hints"][number],
  result: ContractTermClauseReview | null | undefined,
): TermDateEnrichment[] {
  if (hint.category !== "TERM_TERMINATION") return [];
  return (result?.dates ?? []).flatMap((date) => (
    date.normalizedDate && sameVerifiedEvidence(date.sourceFileId, date.excerpt, hint.evidenceItems)
      ? [{ type: date.type, normalizedDate: date.normalizedDate }]
      : []
  ));
}

function presentHints(
  result: ContractTextAnalysis | null,
  materials: readonly ContractTextMaterialReference[],
  options: ContractTextAnalysisPresentationOptions,
): ContractTextAnalysisHintPresentation[] {
  if (!result?.available) return [];
  const materialsById = new Map(materials.map((material) => [material.id, material]));
  return result.hints.map((hint) => {
    const sources = hint.evidenceRefs.flatMap((fileId) => {
      const material = materialsById.get(fileId);
      if (!material) return [];
      const item = hint.evidenceItems?.find((candidate) => candidate.fileId === fileId);
      return [{
        ...material,
        status: item?.status ?? "NORMAL" as const,
        excerpt: item?.excerpt ?? null,
        locatorAvailable: item?.locatorAvailable ?? false,
      }];
    });
    const problemSources = (hint.evidenceItems ?? []).flatMap((item) => {
      if (item.status === "NORMAL") return [];
      const material = materialsById.get(item.fileId);
      return material ? [{
        ...material,
        status: item.status,
        excerpt: item.excerpt,
        locatorAvailable: item.locatorAvailable,
      }] : [];
    });
    return {
      category: hint.category,
      categoryLabel: CATEGORY_LABELS[hint.category],
      attention: hint.attention,
      attentionLabel: ATTENTION_LABELS[hint.attention],
      title: hint.title,
      description: hint.description,
      sources,
      problemSources,
      problemMaterialNotice: hint.attention !== "INFO" && problemSources.length === 0
        ? "本条建议未标注具体问题材料，请结合上述对比来源复核。"
        : null,
      breachEnrichment: breachEnrichment(hint, options.breachResult),
      termDateEnrichments: termDateEnrichments(hint, options.termResult),
    };
  });
}

function termHelperFacts(
  result: ContractTermClauseReview | null | undefined,
  materials: readonly ContractTextMaterialReference[],
): ContractTermHelperFactPresentation[] {
  const materialsById = new Map(materials.map((material) => [material.id, material]));
  const dates = (result?.dates ?? []).flatMap((date) => {
    const material = materialsById.get(date.sourceFileId);
    if (!material) return [];
    return [{
      kind: "DATE" as const,
      label: TERM_DATE_LABELS[date.type],
      status: date.status,
      description: date.normalizedDate ?? "材料中未填写具体日期",
      source: {
        ...material,
        status: date.status,
        excerpt: date.excerpt,
        locatorAvailable: date.locatorAvailable,
      },
    }];
  });
  const conditions = (result?.conditions ?? []).flatMap((condition) => {
    const material = materialsById.get(condition.sourceFileId);
    if (!material) return [];
    return [{
      kind: "CONDITION" as const,
      label: TERM_CONDITION_LABELS[condition.type],
      status: condition.status,
      description: condition.description,
      source: {
        ...material,
        status: condition.status,
        excerpt: condition.excerpt,
        locatorAvailable: condition.locatorAvailable,
      },
    }];
  });
  return [...dates, ...conditions];
}

function slot(
  category: ContractTextAnalysisCategory,
  hints: ContractTextAnalysisHintPresentation[],
  helperFacts: ContractTermHelperFactPresentation[] = [],
): ContractTextAnalysisSlot {
  const slotHints = hints.filter((hint) => hint.category === category);
  const coverage = slotHints.length > 0 ? "ASSESSED" : "UNASSESSED";
  return {
    category,
    categoryLabel: CATEGORY_LABELS[category],
    coverage,
    coverageLabel: coverage === "ASSESSED" ? "已形成模型判断" : "未形成可靠判断",
    hints: slotHints,
    helperFacts,
  };
}

function sections(
  hints: ContractTextAnalysisHintPresentation[],
  termResult: ContractTermClauseReview | null | undefined,
  materials: readonly ContractTextMaterialReference[],
): ContractTextAnalysisSection[] {
  const semanticCategories = hints.some((hint) => hint.category === "OTHER_TEXT")
    ? [...SEMANTIC_CATEGORIES, "OTHER_TEXT" as const]
    : SEMANTIC_CATEGORIES;
  return SECTION_DEFINITIONS.map(({ key, label }) => {
    const sectionHints = hints.filter((hint) => key === "SEMANTIC_COMPARISON"
      ? hint.category !== "BREACH_LIABILITY" && hint.category !== "TERM_TERMINATION"
      : hint.category === key);
    const slots = key === "SEMANTIC_COMPARISON"
      ? semanticCategories.map((category) => slot(category, hints))
      : key === "BREACH_LIABILITY"
        ? [slot("BREACH_LIABILITY", hints)]
        : [slot("TERM_TERMINATION", hints, termHelperFacts(termResult, materials))];
    return { key, label, hints: sectionHints, slots };
  });
}

export function contractTextAnalysisPresentation(
  result: ContractTextAnalysis | null,
  loading: boolean,
  error: string | null,
  options: ContractTextAnalysisPresentationOptions = {},
): ContractTextAnalysisPresentation {
  const materials = options.materials ?? [];
  const hints = presentHints(result, materials, options);
  const common = {
    title: "智能语义审查" as const,
    authorityNotice: "系统以一次智能语义分析结果核对不同材料中的项目、供应商、交付、质保和服务要求，并覆盖违约责任、合同期限与终止条件；金额计算仍由精确校验负责。",
    analysisDuration: result ? durationLabel(result.durationMs) : null,
  };
  if (loading) return {
    ...common,
    phase: "RUNNING",
    statusLabel: "正在分析",
    detail: "正在进行合同语义分析",
    summaries: [],
    hints: [],
    sections: [],
  };
  if (error) return {
    ...common,
    phase: "ERROR",
    statusLabel: "暂不可用",
    detail: `${error} 精确校验不受影响。`,
    summaries: [],
    hints: [],
    sections: [],
  };
  if (!result && options.notice) return {
    ...common,
    phase: "NOT_STARTED",
    statusLabel: "等待重新分析",
    detail: options.notice,
    summaries: [],
    hints: [],
    sections: [],
  };
  if (!result) return {
    ...common,
    phase: "NOT_STARTED",
    statusLabel: "等待开始",
    detail: "开始智能审核后，将基于本次所选材料请求真实语义分析。",
    summaries: [],
    hints: [],
    sections: [],
  };
  if (!result.available) return {
    ...common,
    phase: "ERROR",
    statusLabel: "当前不可用",
    detail: "当前未返回可用的语义审查建议；精确校验不受影响。",
    summaries: [],
    hints: [],
    sections: [],
  };
  return {
    ...common,
    phase: "RESULT",
    statusLabel: "分析完成",
    detail: result.hints.length === 0
      ? "本轮未生成可展示的文字语义建议；金额与数值结果请查看精确校验。"
      : null,
    summaries: result.summaries,
    hints,
    sections: sections(hints, options.termResult, materials),
  };
}
