import type { ReviewRunStatus } from "./reviewRunTypes.ts";
import type { ContractReviewCategoryView } from "./eightCategoryReviewProjection.ts";

function valueLine(label: string, value: string | null): string[] {
  return value === null || value.length === 0 ? [] : [`  ${label}：${value}`];
}

export const BUSINESS_REVIEW_SCOPE = "结果仅针对本次冻结材料中已核对的事实；未覆盖、资料不足和外部核验项不能据此认定合规。人工确认表示已查看，不表示问题已解决；智能辅助提示需人工判断。";
export const MONETARY_OBSERVATION_MARKER = "【联调参考意见｜金额相关模型表述尚待人工复核】";

function observationLines(categories: readonly ContractReviewCategoryView[]): string[] {
  return categories.some(category => category.monetaryObservation?.mode === "OBSERVE")
    ? [MONETARY_OBSERVATION_MARKER] : [];
}

function canonicalOpinion(categories: readonly ContractReviewCategoryView[]): string | undefined {
  const values = [...new Set(categories.map(category => category.overallOpinion)
    .filter((value): value is string => typeof value === "string" && value.trim().length > 0))];
  return values.length === 1 ? values[0] : undefined;
}
const RUN_STATUS_LABELS: Record<ReviewRunStatus, string> = {
  PENDING: "待开始", RUNNING: "审核进行中", COMPLETED: "审核完成",
  PARTIALLY_COMPLETED: "部分完成", FAILED: "审核未完整完成", UNKNOWN: "结果状态待确认",
};

function opinionVersionLines(categories: readonly ContractReviewCategoryView[]): string[] {
  const versions = [...new Set(categories.map((category) => category.opinionVersion).filter((version) => version !== undefined))];
  return versions.length === 1 ? [`意见版本：${versions[0]}`] : [];
}

function itemLines(item: ContractReviewCategoryView["items"][number]): string[] {
  return [
    `- ${item.title}：${item.summary}`,
    ...valueLine("合同/当前值", item.actualValue),
    ...valueLine("对照/期望值", item.expectedValue),
    ...item.typedFacts.map((fact) => `  ${fact}`),
    ...item.evidence.map((evidence) => `  来源：${evidence.fileName}；${evidence.locatorAvailable ? "位置已验证" : "位置无法精确确认"}；摘录：${evidence.excerpt}`),
    ...valueLine("处理建议", item.recommendation),
  ];
}

export function buildCategoryOpinion(category: ContractReviewCategoryView): string {
  const lines = [
    ...observationLines([category]),
    `${category.order}. ${category.title}`,
    ...opinionVersionLines([category]),
    `状态：${category.statusLabel}`,
    `本项总结：${category.summary}`,
  ];
  for (const item of category.items) {
    lines.push(...itemLines(item));
  }
  if (category.uncoveredScopes.length > 0) {
    lines.push("未覆盖范围：");
    for (const value of category.uncoveredScopes) lines.push(`- ${value}`);
  }
  lines.push(`范围说明：${category.scopeStatement ?? BUSINESS_REVIEW_SCOPE}`);
  return lines.join("\n");
}

export function buildOverallOpinion(categories: readonly ContractReviewCategoryView[],
  runId: string, runStatus: ReviewRunStatus): string {
  const lines = [...observationLines(categories), runStatus === "COMPLETED" ? "合同审核综合意见" : "合同审核综合意见（草稿，待审核完成）",
    `审核记录：${runId}`, `审核状态：${RUN_STATUS_LABELS[runStatus]}`,
    `范围说明：${categories[0]?.scopeStatement ?? BUSINESS_REVIEW_SCOPE}`];
  lines.push(...opinionVersionLines(categories));
  const paragraph = canonicalOpinion(categories);
  if (paragraph) lines.push("", paragraph);
  const seen = new Set<string>();
  for (const category of categories) {
    lines.push(`\n${category.order}. ${category.title}：${category.statusLabel}`);
    lines.push(`本项总结：${category.summary}`);
    for (const item of category.items) {
      if (seen.has(item.stableId)) continue;
      seen.add(item.stableId);
      lines.push(...itemLines(item));
    }
    if (category.uncoveredScopes.length > 0) {
      lines.push(`- 未覆盖范围：${category.uncoveredScopes.join("；")}`);
    }
  }
  const unfinished = categories.filter((value) => value.coverage === "UNFINISHED").map((value) => value.title);
  const uncovered = categories.filter((value) => value.coverage === "NOT_COVERED" || value.coverage === "PARTIALLY_COVERED").map((value) => value.title);
  const insufficient = categories.filter((value) => value.judgement === "INSUFFICIENT"
    || value.items.some((item) => item.status === "INSUFFICIENT"))
    .map((value) => `${value.title}${value.uncoveredScopes.length > 0 ? `（${value.uncoveredScopes.join("；")}）` : ""}`);
  lines.push(`\n未完成范围：${unfinished.length > 0 ? unfinished.join("、") : "无"}`);
  lines.push(`未覆盖范围：${uncovered.length > 0 ? uncovered.join("、") : "无"}`);
  lines.push(`资料不足范围：${insufficient.length > 0 ? insufficient.join("、") : "无"}`);
  const waitingExternal = categories.filter((value) => value.coverage === "WAITING_EXTERNAL").map((value) => value.title);
  lines.push(`待外部核验范围：${waitingExternal.length > 0 ? waitingExternal.join("、") : "无"}`);
  return lines.join("\n");
}

function summaryLines(category: ContractReviewCategoryView): string[] {
  const supplied = category.conciseOpinion?.trim();
  const qualifications: string[] = [];
  if (!supplied) {
    // Historical projections have no business summary contract. Preserve every
    // distinct risk/insufficiency class rather than inventing or shortening facts.
    for (const [status, label] of [["ATTENTION", "需关注"], ["INSUFFICIENT", "资料不足"]] as const) {
      const titles = [...new Set(category.items.filter((item) => item.status === status).map((item) => item.title))];
      if (titles.length > 0) qualifications.push(`${label}：${titles.join("、")}`);
    }
  }
  const remainingScopes = category.uncoveredScopes.filter((scope) => !supplied?.includes(scope));
  if (remainingScopes.length > 0) qualifications.push(`未覆盖范围：${remainingScopes.join("；")}`);
  return [
    `${category.order}. ${category.title}：${category.statusLabel}`,
    supplied || category.summary,
    ...(qualifications.length > 0 ? [`${qualifications.join("；")}。`] : []),
    ...(category.items.length > 0 ? [`共${category.items.length}项检查，详见完整明细。`] : []),
  ];
}

/** Default business copy; full historical output remains in buildCategoryOpinion. */
export function buildCategorySummaryOpinion(category: ContractReviewCategoryView): string {
  return [...observationLines([category]), ...summaryLines(category), ...opinionVersionLines([category]),
    `范围说明：${category.scopeStatement ?? BUSINESS_REVIEW_SCOPE}`].join("\n");
}

/** Same frozen Run and business projection as the full opinion, without normal rows. */
export function buildOverallSummaryOpinion(categories: readonly ContractReviewCategoryView[],
  runId: string, runStatus: ReviewRunStatus): string {
  const supplied = canonicalOpinion(categories);
  if (supplied) {
    return [...observationLines(categories),
      runStatus === "COMPLETED" ? "合同审核综合意见（简版）" : "合同审核综合意见（简版草稿，待审核完成）",
      `审核记录：${runId}`, `审核状态：${RUN_STATUS_LABELS[runStatus]}`,
      ...opinionVersionLines(categories), "", supplied].join("\n");
  }
  const lines = [...observationLines(categories), runStatus === "COMPLETED" ? "合同审核综合意见（简版）" : "合同审核综合意见（简版草稿，待审核完成）",
    `审核记录：${runId}`, `审核状态：${RUN_STATUS_LABELS[runStatus]}`,
    `范围说明：${categories[0]?.scopeStatement ?? BUSINESS_REVIEW_SCOPE}`];
  lines.push(...opinionVersionLines(categories));
  for (const category of categories) lines.push("", ...summaryLines(category));
  const advice = [...new Set(categories.map((category) => category.overallAdvice?.trim()).filter(Boolean))];
  if (advice.length > 0) lines.push(`\n综合建议：${advice.join("；")}`);
  const ranges: [string, readonly ContractReviewCategoryView[]][] = [
    ["未完成范围", categories.filter((value) => value.coverage === "UNFINISHED")],
    ["未覆盖范围", categories.filter((value) => value.coverage === "NOT_COVERED" || value.coverage === "PARTIALLY_COVERED")],
    ["资料不足范围", categories.filter((value) => value.judgement === "INSUFFICIENT"
      || value.items.some((item) => item.status === "INSUFFICIENT"))],
    ["待外部核验范围", categories.filter((value) => value.coverage === "WAITING_EXTERNAL")],
  ];
  for (const [label, values] of ranges) lines.push(`${label}：${values.length > 0 ? values.map((value) => value.title).join("、") : "无"}`);
  return lines.join("\n");
}
