import type { ProgressiveReplayState } from "./reviewProgressState";
import type {
  Contract,
  Finding,
  FindingSourceAnchor,
  ReviewProgress,
  ReviewProgressItem,
} from "./types";

export type FindingPresentationStatus =
  | "PASS"
  | "WARNING"
  | "FAIL"
  | "UNDETERMINED";

export interface FindingPresentationInput {
  checkStatus: "PASS" | "FAIL" | "NOT_EVALUATED";
  riskLevel: "GREEN" | "YELLOW" | "RED";
}

export interface FindingBusinessStatus {
  label: "核对一致" | "需复核" | "格式待复核" | "材料不足";
  prompt: "核对一致" | "建议重点复核" | "建议优先复核" | "请人工复核" | "暂无法判断";
  tone: "SUCCESS" | "ATTENTION" | "PRIORITY" | "NEUTRAL";
}

export interface FindingPresentationOptions {
  projectedSourceAvailable?: boolean;
}

function isAmountWordsFormatReview(
  finding: Pick<Finding, "ruleCode" | "checkStatus">,
  options: FindingPresentationOptions,
): boolean {
  return finding.ruleCode === "F02_AMOUNT_WORDS_MATCH"
    && finding.checkStatus === "NOT_EVALUATED"
    && options.projectedSourceAvailable === true;
}

export function findingBusinessStatus(
  finding: FindingPresentationInput & { blockSign: boolean; ruleCode?: string },
  options: FindingPresentationOptions = {},
): FindingBusinessStatus {
  if (finding.checkStatus === "PASS") {
    return { label: "核对一致", prompt: "核对一致", tone: "SUCCESS" };
  }
  if (finding.checkStatus === "NOT_EVALUATED") {
    if (isAmountWordsFormatReview(
      { ruleCode: finding.ruleCode ?? "", checkStatus: finding.checkStatus },
      options,
    )) {
      return { label: "格式待复核", prompt: "请人工复核", tone: "ATTENTION" };
    }
    return { label: "材料不足", prompt: "暂无法判断", tone: "NEUTRAL" };
  }
  return finding.blockSign
    ? { label: "需复核", prompt: "建议优先复核", tone: "PRIORITY" }
    : { label: "需复核", prompt: "建议重点复核", tone: "ATTENTION" };
}

export interface FindingPresentationSummary {
  pass: number;
  warning: number;
  fail: number;
  undetermined: number;
}

export interface F02CompletionPresentation {
  riskCount: number;
  greenCount: number;
  riskSummary: "无风险" | "低风险" | "高风险";
  availableActions: readonly ["OPEN_REVIEW_HISTORY"];
}

export interface FindingSourcePresentation {
  available: boolean;
  label: string;
}

export function reviewFindingDetailPresentation(
  finding: Pick<Finding, "ruleCode" | "checkStatus" | "riskLevel" | "itemKey" | "actualValue" | "expectedValue">,
  options: FindingPresentationOptions = {},
): string {
  const pass = finding.checkStatus === "PASS";
  const notEvaluated = finding.checkStatus === "NOT_EVALUATED";
  switch (finding.ruleCode) {
    case "F02_AMOUNT_WORDS_MATCH":
      if (isAmountWordsFormatReview(finding, options)) {
        return "已定位金额大写原文，但当前格式无法由系统可靠解析，请人工复核。";
      }
      if (notEvaluated) return "缺少可靠的金额大写信息，暂无法完成核对。";
      return pass ? "合同金额数字与金额大写一致。" : "合同金额数字与金额大写不一致。";
    case "F02_LINE_ITEM_ARITHMETIC": {
      if (notEvaluated) return `分项 ${finding.itemKey ?? "当前项"} 未提供可核对的数量和单价，行乘法暂无法完成核对。`;
      const item = finding.itemKey ?? "当前项";
      return pass
        ? `分项 ${item} 的数量 × 单价与申报金额一致。`
        : `分项 ${item} 的数量 × 单价与申报金额不一致。`;
    }
    case "F02_LINE_SUM_MATCH":
      if (notEvaluated) return "缺少可靠的合同分项金额信息，暂无法完成核对。";
      return pass ? "合同分项申报金额合计与合同总额一致。" : "合同分项申报金额合计与合同总额不一致。";
    case "F02_AWARD_AMOUNT_MATCH":
      if (notEvaluated) return "缺少可靠的中标金额依据，暂无法完成核对。";
      return pass ? "合同金额与中标金额一致。" : "合同金额与中标金额不一致。";
    case "F02_AWARD_SUPPLIER_MATCH":
      if (notEvaluated) return "缺少可靠的中标供应商依据，暂无法完成核对。";
      return pass ? "合同乙方与中标供应商一致。" : "合同乙方与中标供应商不一致。";
    default:
      return pass ? "该项核对完成。" : "该项存在差异，请核对当前值与目标值。";
  }
}

export interface ReviewSummaryBlock {
  label: "一致项" | "需复核项" | "格式待复核项" | "材料不足项";
  value: number;
}

export interface ReviewSummaryOptions {
  formatReviewFindingIds?: ReadonlySet<string>;
}

export interface F02FindingPresentationGroup {
  key: "AMOUNT_AND_LINE" | "EXACT_IDENTITY" | "OTHER";
  title: string;
  description: string;
  findings: Finding[];
}

const amountFindingRuleCodes = new Set([
  "F02_AMOUNT_WORDS_MATCH",
  "F02_LINE_ITEM_ARITHMETIC",
  "F02_LINE_SUM_MATCH",
  "F02_AWARD_AMOUNT_MATCH",
]);

export function groupF02FindingsForPresentation(
  findings: readonly Finding[],
): F02FindingPresentationGroup[] {
  const amountAndLine = findings.filter((finding) => amountFindingRuleCodes.has(finding.ruleCode));
  const exactIdentity = findings.filter((finding) => finding.ruleCode === "F02_AWARD_SUPPLIER_MATCH");
  const other = findings.filter((finding) =>
    !amountFindingRuleCodes.has(finding.ruleCode)
      && finding.ruleCode !== "F02_AWARD_SUPPLIER_MATCH");
  return [
    {
      key: "AMOUNT_AND_LINE" as const,
      title: "金额与明细精确校验",
      description: "系统对金额、数量×单价和分项合计进行精确核对。",
      findings: amountAndLine,
    },
    {
      key: "EXACT_IDENTITY" as const,
      title: "主体身份精确核对",
      description: "统一社会信用代码等结构化身份信息由精确规则核对。",
      findings: exactIdentity,
    },
    {
      key: "OTHER" as const,
      title: "其他精确校验",
      description: "基于已确认结构化事实执行精确核对。",
      findings: other,
    },
  ].filter((group) => group.findings.length > 0);
}

export type FindingSourceDisplayMode = "PROJECTED" | "LEGACY" | "UNAVAILABLE";

export function findingSourceDisplayMode(
  projected: { available: boolean } | null | undefined,
  legacyAvailable: boolean,
): FindingSourceDisplayMode {
  if (projected) return projected.available ? "PROJECTED" : "UNAVAILABLE";
  return legacyAvailable ? "LEGACY" : "UNAVAILABLE";
}

export interface AcknowledgementControl {
  label: "我已知悉";
  busy: boolean;
  disabled: boolean;
  statusText: string | null;
}

export type ReviewCompletionStatusText =
  | "审核进行中"
  | "复核提示待知悉"
  | "审核完成";

const amountRuleCodes = new Set([
  "F02_AMOUNT_WORDS_MATCH",
  "F02_LINE_ITEM_ARITHMETIC",
  "F02_LINE_SUM_MATCH",
  "F02_AWARD_AMOUNT_MATCH",
]);

const supportedMoneyPattern = /^(-?)(0|[1-9]\d{0,15})(?:\.(\d{1,4}))?$/;

function canonicalSupportedMoney(
  value: string | number | null,
): { negative: boolean; integer: string; fraction: string } | null {
  if (value === null) return null;
  if (typeof value === "number" && !Number.isFinite(value)) return null;
  const match = supportedMoneyPattern.exec(String(value));
  if (!match) return null;
  const fraction = match[3] ?? "";
  const maximumIntegerDigits = fraction.length > 2 ? 14 : 16;
  if (match[2]!.length > maximumIntegerDigits) return null;
  return {
    negative: match[1] === "-",
    integer: match[2]!,
    fraction,
  };
}

export function formatRmbAmount(value: string | number | null): string {
  const decimal = canonicalSupportedMoney(value);
  if (!decimal) return "暂无可靠金额";
  const fraction = decimal.fraction.padEnd(4, "0");
  let cents = BigInt(decimal.integer) * 100n
    + BigInt(fraction.slice(0, 2));
  // Product display rounds the exact decimal magnitude to cents using HALF_UP,
  // then reapplies the sign; no binary floating-point conversion is involved.
  if (fraction[2]! >= "5") cents += 1n;
  const roundedInteger = cents / 100n;
  const roundedFraction = (cents % 100n).toString().padStart(2, "0");
  const groupedInteger = new Intl.NumberFormat("zh-CN", {
    useGrouping: true,
    maximumFractionDigits: 0,
  }).format(roundedInteger);
  return `${decimal.negative && cents !== 0n ? "-" : ""}¥${groupedInteger}.${roundedFraction}`;
}

export function displayChineseUppercaseAmount(value: string | null): string {
  return value === null || value.trim() === "" ? "暂无可靠金额大写" : value;
}

export function formatFindingComparisonValue(
  ruleCode: string,
  value: string | null,
): string {
  if (value === null) return "暂无可靠数据";
  return amountRuleCodes.has(ruleCode) ? formatRmbAmount(value) : value;
}

export interface FindingComparisonCard {
  label: string;
  value: string;
  detail?: string;
}

export interface FindingComparisonPresentation {
  cards: FindingComparisonCard[];
}

/** Build a rule-specific explanation without changing stored finding values. */
export function createFindingComparisonPresentation(
  finding: Finding,
  contract: Contract | null,
  options: FindingPresentationOptions = {},
): FindingComparisonPresentation {
  if (isAmountWordsFormatReview(finding, options)) return { cards: [] };

  switch (finding.ruleCode) {
    case "F02_LINE_ITEM_ARITHMETIC": {
      const lineItem = contract?.lineItems.find((item) => item.itemKey === finding.itemKey);
      if (lineItem?.basis === "AMOUNT_ONLY") {
        return { cards: [{
          label: "申报金额（数量、单价未提供）",
          value: formatRmbAmount(lineItem.declaredAmount),
        }] };
      }
      return {
        cards: [
          {
            label: "数量 × 单价计算金额",
            value: formatFindingComparisonValue(finding.ruleCode, finding.expectedValue),
            ...(lineItem ? { detail: `${lineItem.quantity} × ${formatRmbAmount(lineItem.unitPrice)}` } : {}),
          },
          {
            label: "申报金额",
            value: formatFindingComparisonValue(finding.ruleCode, finding.actualValue),
          },
        ],
      };
    }
    case "F02_LINE_SUM_MATCH":
      return {
        cards: [
          { label: "分项申报金额合计", value: formatRmbAmount(finding.actualValue) },
          { label: "合同总金额", value: formatRmbAmount(finding.expectedValue) },
        ],
      };
    case "F02_AWARD_AMOUNT_MATCH":
      return {
        cards: [
          { label: "合同金额", value: formatRmbAmount(finding.actualValue) },
          { label: "中标金额", value: formatRmbAmount(finding.expectedValue) },
        ],
      };
    case "F02_AMOUNT_WORDS_MATCH":
      return {
        cards: [
          { label: "金额大写解析值", value: formatRmbAmount(finding.actualValue) },
          { label: "合同数字金额", value: formatRmbAmount(finding.expectedValue) },
        ],
      };
    case "F02_AWARD_SUPPLIER_MATCH":
      return contract?.awardNotice ? {
        cards: [
          { label: "合同乙方", value: contract.partyB },
          { label: "中标供应商", value: contract.awardNotice.winningBidder },
        ],
      } : {
        cards: [
          { label: "供应商核对结果", value: finding.actualValue ?? "暂无可靠数据" },
          { label: "供应商期望结果", value: finding.expectedValue ?? "暂无可靠数据" },
        ],
      };
    default:
      return {
        cards: [
          { label: "当前值", value: formatFindingComparisonValue(finding.ruleCode, finding.actualValue) },
          { label: "确认值", value: formatFindingComparisonValue(finding.ruleCode, finding.expectedValue) },
        ],
      };
  }
}

export function presentFindingSource(
  source: { label?: string } | null | undefined,
): FindingSourcePresentation {
  if (!source) return { available: false, label: "暂无可靠来源定位" };
  return {
    available: true,
    label: source.label?.trim() || "查看可靠来源定位",
  };
}

export function createF02CompletionPresentation(
  progress: import("./types").ReviewProgress,
): F02CompletionPresentation {
  const findings = progress.items.filter((item) => item.type === "FINDING");
  const riskCount = findings.filter((item) => item.risk !== null && item.risk !== "GREEN").length;
  return {
    riskCount,
    greenCount: findings.filter((item) => item.risk === "GREEN").length,
    riskSummary: riskCount === 0 ? "无风险" : riskCount <= 2 ? "低风险" : "高风险",
    availableActions: ["OPEN_REVIEW_HISTORY"],
  };
}

export function reviewSummaryBlocks(
  progress: Pick<ReviewProgress, "items">,
  options: ReviewSummaryOptions = {},
): ReviewSummaryBlock[] {
  const findings = progress.items.filter((item) => item.type === "FINDING");
  const formatReviewFindingIds = options.formatReviewFindingIds ?? new Set<string>();
  const formatReviewCount = findings.filter((item) => (
    item.checkResultId != null && formatReviewFindingIds.has(item.checkResultId)
  )).length;
  return [
    { label: "一致项", value: findings.filter((item) => item.status === "PASS").length },
    { label: "需复核项", value: findings.filter((item) => item.status === "FAIL").length },
    ...(formatReviewCount > 0
      ? [{ label: "格式待复核项" as const, value: formatReviewCount }]
      : []),
    {
      label: "材料不足项",
      value: findings.filter((item) => item.status === "NOT_EVALUATED"
        && (item.checkResultId == null || !formatReviewFindingIds.has(item.checkResultId))).length,
    },
  ];
}

function hasMatchingAcknowledgement(item: ReviewProgressItem): boolean {
  return item.checkResultId != null
    && item.acknowledgement?.checkResultId === item.checkResultId;
}

function isAcknowledgeableFinding(item: ReviewProgressItem): boolean {
  return item.type === "FINDING"
    && (item.status === "NOT_EVALUATED" && item.risk === "YELLOW"
      || item.status === "FAIL" && item.risk === "RED");
}

export interface RiskAcknowledgementPresentation {
  total: number;
  current: number | null;
  remaining: number;
  statusText: string | null;
}

export function deriveRiskAcknowledgementPresentation(
  progress: ReviewProgress,
  pendingCheckResultId: string | null,
): RiskAcknowledgementPresentation {
  const items = progress.items
    .map((item, sourceIndex) => ({ item, sourceIndex }))
    .filter(({ item }) => isAcknowledgeableFinding(item))
    .sort((left, right) => left.item.displayOrder - right.item.displayOrder
      || left.sourceIndex - right.sourceIndex)
    .map(({ item }) => item);
  const pendingIndex = pendingCheckResultId == null
    ? -1
    : items.findIndex((item) => item.checkResultId === pendingCheckResultId);
  const remaining = items.filter((item) => !hasMatchingAcknowledgement(item)).length;
  const current = pendingIndex < 0 ? null : pendingIndex + 1;
  return {
    total: items.length,
    current,
    remaining,
    statusText: current == null
      ? null
      : `当前第 ${current}/${items.length} 项复核提示 · 还有 ${remaining} 项需要逐项知悉`,
  };
}

export function nextExpandedFindingId(
  current: string | null,
  previousPendingCheckResultId: string | null,
  pendingCheckResultId: string | null,
): string | null {
  return pendingCheckResultId != null && pendingCheckResultId !== previousPendingCheckResultId
    ? pendingCheckResultId
    : current;
}

export function handleAcknowledgementActionClick(
  event: { stopPropagation(): void },
  findingId: string,
  acknowledge: (findingId: string) => void,
): void {
  event.stopPropagation();
  acknowledge(findingId);
}

export function findingSourceAnchorPresentationKey(
  anchor: FindingSourceAnchor,
  index: number,
): string {
  return [
    anchor.role,
    anchor.fileId,
    anchor.locator,
    anchor.pageNumber ?? "",
    anchor.paragraphIndex ?? "",
    anchor.excerpt,
    index,
  ].join(":");
}

export function reviewCompletionStatusText(
  state: ProgressiveReplayState | null,
): ReviewCompletionStatusText {
  if (!state) return "审核进行中";
  return state.complete ? "审核完成" : "复核提示待知悉";
}

export function progressRiskNodePresentation(
  risk: "GREEN" | "YELLOW" | "RED",
  acknowledged: boolean,
): { icon: "CHECK" | "EXCLAMATION"; statusText: string } {
  if (risk === "GREEN") return { icon: "CHECK", statusText: "核对完成" };
  if (acknowledged) return { icon: "EXCLAMATION", statusText: "已知悉" };
  return {
    icon: "EXCLAMATION",
    statusText: "待知悉",
  };
}

export function createAcknowledgementControl(
  item: ReviewProgressItem,
  state: ProgressiveReplayState,
  loadingResultId: string | null,
  error: string | null,
): AcknowledgementControl | null {
  const eligible = isAcknowledgeableFinding(item);
  const acknowledged = hasMatchingAcknowledgement(item);
  if (
    !eligible
    || !item.checkResultId
    || acknowledged
    || state.pendingAcknowledgementItem?.checkResultId !== item.checkResultId
  ) return null;
  const busy = loadingResultId === item.checkResultId;
  return {
    label: "我已知悉",
    busy,
    disabled: busy,
    statusText: busy ? "正在提交复核提示知悉…" : error,
  };
}

export function isCurrentPendingAcknowledgementItem(
  item: ReviewProgressItem | null | undefined,
  pendingItem: ReviewProgressItem | null | undefined,
): boolean {
  return item?.checkResultId != null
    && pendingItem?.checkResultId != null
    && item.checkResultId === pendingItem.checkResultId;
}

export function findingPresentationStatus(
  finding: FindingPresentationInput,
): FindingPresentationStatus {
  if (finding.checkStatus === "NOT_EVALUATED") return "UNDETERMINED";
  if (finding.checkStatus === "PASS") return "PASS";
  if (finding.riskLevel === "RED") return "FAIL";
  if (finding.riskLevel === "YELLOW") return "WARNING";
  return "UNDETERMINED";
}

export function aggregateFindingPresentation(
  findings: readonly FindingPresentationInput[],
): FindingPresentationStatus {
  const statuses = findings.map(findingPresentationStatus);
  if (statuses.some((status) => status === "FAIL")) return "FAIL";
  if (statuses.some((status) => status === "UNDETERMINED")) return "UNDETERMINED";
  if (statuses.some((status) => status === "WARNING")) return "WARNING";
  return statuses.length > 0 ? "PASS" : "UNDETERMINED";
}

export function summarizeFindingPresentation(
  findings: readonly FindingPresentationInput[],
): FindingPresentationSummary {
  const summary: FindingPresentationSummary = {
    pass: 0,
    warning: 0,
    fail: 0,
    undetermined: 0,
  };
  findings.forEach((finding) => {
    const status = findingPresentationStatus(finding);
    if (status === "PASS") summary.pass += 1;
    if (status === "WARNING") summary.warning += 1;
    if (status === "FAIL") summary.fail += 1;
    if (status === "UNDETERMINED") summary.undetermined += 1;
  });
  return summary;
}
