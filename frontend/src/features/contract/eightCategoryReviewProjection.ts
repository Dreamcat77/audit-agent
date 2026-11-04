import type {
  ReviewEvidenceDto,
  ReviewFindingDto,
  ReviewOpinionMetadataDto,
  ReviewRunDto,
  ReviewRunStatus,
  ReviewStepOutputDto,
  ReviewStepStatus,
  ReviewStepType,
} from "./reviewRunTypes.ts";
import { assertReviewOpinionMetadata } from "./reviewRunOpinionMetadata.ts";

export type ContractReviewCategoryId =
  | "QUANTITATIVE"
  | "DRAFT_STRUCTURE"
  | "BREACH"
  | "TERM"
  | "PARTY_QUALIFICATION"
  | "ATTACHMENT_COMPLETENESS"
  | "EFFECTIVENESS_COPIES"
  | "CLAUSE_RISK";

export type ContractReviewCoverage = "COVERED" | "PARTIALLY_COVERED" | "NOT_COVERED" | "UNFINISHED"
  | "WAITING_EXTERNAL";
export type ContractReviewJudgement = "NORMAL" | "ATTENTION" | "INSUFFICIENT" | "UNAVAILABLE";
export type ContractReviewItemStatus = "NORMAL" | "ATTENTION" | "INSUFFICIENT";

export interface ContractReviewCategoryEvidenceView {
  evidenceId: string;
  fileId: string;
  fileName: string;
  excerpt: string;
  locatorAvailable: boolean;
  pageNumber: number | null;
  paragraphIndex: number | null;
  segmentIndex: number | null;
  startOffset: number | null;
  endOffset: number | null;
}

export interface ContractReviewCategoryItemView {
  stableId: string;
  outputType: ReviewStepType;
  outputId: string;
  title: string;
  status: ContractReviewItemStatus;
  summary: string;
  actualValue: string | null;
  expectedValue: string | null;
  recommendation: string | null;
  typedFacts: readonly string[];
  source: "DETERMINISTIC" | "MODEL_ASSISTED";
  evidence: readonly ContractReviewCategoryEvidenceView[];
  primary: boolean;
}

export interface ContractReviewCategoryView extends ReviewOpinionMetadataDto {
  projectionVersion?: number;
  opinionVersion?: number;
  conciseOpinion?: string;
  overallAdvice?: string;
  scopeStatement?: string;
  id: ContractReviewCategoryId;
  order: 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8;
  title: string;
  coverage: ContractReviewCoverage;
  judgement: ContractReviewJudgement;
  statusLabel: string;
  attentionCount: number;
  summary: string;
  uncoveredScopes: readonly string[];
  items: readonly ContractReviewCategoryItemView[];
}

interface CategoryDefinition {
  id: ContractReviewCategoryId;
  order: ContractReviewCategoryView["order"];
  title: string;
}

const DEFINITIONS: readonly CategoryDefinition[] = [
  { id: "QUANTITATIVE", order: 1, title: "合同条款量化一致性审查" },
  { id: "DRAFT_STRUCTURE", order: 2, title: "合同草本结构对标审查" },
  { id: "BREACH", order: 3, title: "违约责任条款量化审查" },
  { id: "TERM", order: 4, title: "合同期限与终止条件格式审查" },
  { id: "PARTY_QUALIFICATION", order: 5, title: "合同主体资格审查" },
  { id: "ATTACHMENT_COMPLETENESS", order: 6, title: "附件清单完整性审查" },
  { id: "EFFECTIVENESS_COPIES", order: 7, title: "合同生效条件与份数审查" },
  { id: "CLAUSE_RISK", order: 8, title: "合同条款风险提示" },
];

const F02_LABELS: Readonly<Record<string, string>> = {
  F02_AMOUNT_WORDS_MATCH: "合同金额大小写一致性",
  F02_LINE_ITEM_ARITHMETIC: "合同分项金额计算",
  F02_LINE_SUM_MATCH: "合同分项合计与总金额一致性",
  F02_AWARD_AMOUNT_MATCH: "合同金额与中标金额一致性",
  F02_AWARD_SUPPLIER_MATCH: "合同乙方与中标供应商一致性",
};

const RULE_DETAILS: Readonly<Record<string, string>> = {
  "Amount in words matches numeric contract amount": "合同金额大小写一致",
  "Amount in words does not match numeric contract amount": "合同金额大小写不一致",
  "Amount in words is missing or invalid": "合同大写金额缺失或无法识别",
  "Line quantity multiplied by unit price matches declared amount": "数量乘以单价与分项金额一致",
  "Line declared amount differs from quantity multiplied by unit price": "数量乘以单价与分项金额不一致",
  "Line item has declared amount only; quantity and unit price are unavailable": "仅取得分项金额，数量和单价尚不可用",
  "Contract has no line item basis": "尚未取得合同明细核对依据",
  "Line item sum matches contract amount": "分项合计与合同金额一致",
  "Line item sum differs from contract amount": "分项合计与合同金额不一致",
  "Contract has no line totals to sum": "尚未取得可汇总的分项金额",
  "Contract amount matches award amount": "合同金额与中标金额一致",
  "Contract amount differs from award amount": "合同金额与中标金额不一致",
  "Award notice amount basis is missing": "尚未取得中标金额核对依据",
  "Contract supplier matches award supplier by normalized supplier name": "合同乙方与中标供应商的规范化名称一致",
  "Contract supplier differs from award supplier by normalized supplier name": "合同乙方与中标供应商的规范化名称不一致",
  "Contract supplier matches award supplier by credit code": "合同乙方与中标供应商的统一社会信用代码一致",
  "Contract supplier differs from award supplier by credit code": "合同乙方与中标供应商的统一社会信用代码不一致",
  "Award notice supplier basis is missing": "尚未取得中标供应商核对依据",
  "Supplier identity basis is incomplete": "供应商身份核对依据不完整",
};

function human(value: string): string {
  const labels: Readonly<Record<string, string>> = {
    PARTY_A: "甲方", PARTY_B: "乙方", BOTH: "双方", CONTRACT_AMOUNT: "合同总价",
    UNDELIVERED_AMOUNT: "未交付部分金额", OTHER_OR_UNCLEAR: "其他或尚不明确",
    PERCENT: "%", PER_MILLE: "‰", DAY: "每天", PER_DAY: "每天", DAILY: "每天",
    WEEK: "每周", PER_WEEK: "每周", WEEKLY: "每周", MONTH: "每月", PER_MONTH: "每月", MONTHLY: "每月",
    EVENT: "每次", ONCE: "一次性", ONE_OFF: "一次性", SIGNING_DATE: "签约日期", EFFECTIVE_DATE: "生效日期",
    PERFORMANCE_START_DATE: "履约开始日期", DELIVERY_DEADLINE: "交付日期", TERMINATION_DATE: "终止日期",
  };
  return labels[value] ?? value;
}

function ruleValue(rule: string, value: string | null): string | null {
  if (rule !== "F02_AWARD_SUPPLIER_MATCH") return value;
  return value === "MATCH" ? "一致" : value === "DIFFERENT" ? "不一致" : value;
}

function conflict(): never {
  throw new Error("八项审核投影无法验证同一审核记录的持久化结果");
}

function stableId(runId: string, outputType: ReviewStepType, outputId: string): string {
  return `${runId}:${outputType}:${outputId}`;
}

function itemStatus(finding: ReviewFindingDto): ContractReviewItemStatus {
  if (["ATTENTION", "REVIEW", "NEEDS_REVIEW"].includes(finding.statusCode ?? "")
      || ["ATTENTION", "REVIEW"].includes(finding.attentionCode ?? "")
      || finding.riskLevel === "RED" || finding.riskLevel === "YELLOW") return "ATTENTION";
  if (finding.statusCode === "INSUFFICIENT" || finding.attentionCode === "INSUFFICIENT") return "INSUFFICIENT";
  if (finding.statusCode !== null
      && !["NORMAL", "INFO", "PASS", "MATCH"].includes(finding.statusCode)) return "INSUFFICIENT";
  return "NORMAL";
}

function evidenceViews(evidence: readonly ReviewEvidenceDto[], fileNames: ReadonlyMap<string, string>) {
  return evidence.slice().sort((left, right) => left.ordinal - right.ordinal).map((item) => ({
    evidenceId: item.evidenceId,
    fileId: item.fileId,
    fileName: fileNames.get(item.fileId) ?? "来源材料",
    excerpt: item.boundedExcerpt,
    locatorAvailable: item.locatorAvailable,
    pageNumber: item.locatorAvailable ? item.pageNumber : null,
    paragraphIndex: item.locatorAvailable ? item.paragraphIndex : null,
    segmentIndex: item.locatorAvailable ? item.segmentIndex : null,
    startOffset: item.locatorAvailable ? item.startOffset : null,
    endOffset: item.locatorAvailable ? item.endOffset : null,
  }));
}

function validateOutput(run: ReviewRunDto, output: ReviewStepOutputDto): void {
  if (output.run.runId !== run.runId || output.run.projectId !== run.projectId
      || output.run.contractId !== run.contractId || output.step.runId !== run.runId) conflict();
  if (output.findings.some((item) => item.runId !== run.runId || item.stepId !== output.step.stepId)
      || output.evidence.some((item) => item.runId !== run.runId || item.stepId !== output.step.stepId)) conflict();
}

function findingItems(run: ReviewRunDto, output: ReviewStepOutputDto,
  findings: readonly ReviewFindingDto[], fileNames: ReadonlyMap<string, string>): ContractReviewCategoryItemView[] {
  return findings.map((finding) => ({
    stableId: stableId(run.runId, output.step.stepType, finding.findingId),
    outputType: output.step.stepType,
    outputId: finding.findingId,
    title: finding.category,
    status: itemStatus(finding),
    summary: finding.summary,
    actualValue: null,
    expectedValue: null,
    recommendation: finding.recommendation,
    typedFacts: typedFacts(finding),
    source: finding.source,
    evidence: evidenceViews(output.evidence.filter((item) => item.findingId === finding.findingId), fileNames),
    primary: true,
  }));
}

function typedFacts(finding: ReviewFindingDto): string[] {
  if (!finding.typedComparison) return [];
  return finding.typedComparison.values.map((value, index) => {
    const side = value.sideKey.startsWith("CONTRACT_BODY:") ? "合同侧"
      : value.sideKey.startsWith("TENDER_DOCUMENT:") ? "采购依据侧"
        : value.sideKey.startsWith("BID_RESPONSE:") ? "响应依据侧" : `对照侧 ${index + 1}`;
    if (value.state === "NOT_EXTRACTED") return `${side}：未提取到可比较事实`;
    if (value.state === "NOT_APPLICABLE") return `${side}：该场景不适用`;
    if (value.state === "CONFLICT") return `${side}：存在事实冲突`;
    const parts = [
      value.responsibleParty ? `责任方 ${human(value.responsibleParty)}` : null,
      value.calculationBasis ? `基数 ${human(value.calculationBasis)}` : null,
      value.rate === null ? null : `费率 ${value.rate}${value.rateUnit ? human(value.rateUnit) : ""}`,
      value.frequency ? `频次 ${human(value.frequency)}` : null,
      value.period ? `周期 ${value.period}` : null,
      value.capRate === null ? null : `上限费率 ${value.capRate}`,
      value.capAmount === null ? null : `上限金额 ${value.capAmount}`,
      value.terminationThreshold ? `解除阈值 ${value.terminationThreshold}` : null,
      value.dateType ? `日期类型 ${human(value.dateType)}` : null,
      value.normalizedDate ? `日期 ${value.normalizedDate}` : null,
      value.termStart ? `期限起点 ${value.termStart}` : null,
      value.duration ? `期限 ${value.duration}` : null,
      value.effectiveCondition ? `生效条件 ${value.effectiveCondition}` : null,
      value.terminationCondition ? `终止条件 ${value.terminationCondition}` : null,
    ].filter((item): item is string => item !== null);
    return `${side}：${parts.length > 0 ? parts.join("；") : "已取得可比较事实"}`;
  });
}

function f02Items(run: ReviewRunDto, output: ReviewStepOutputDto,
  fileNames: ReadonlyMap<string, string>): ContractReviewCategoryItemView[] {
  const relation = output.findingCheckResultRelations ?? {};
  const carrierByCheck = new Map(Object.entries(relation).map(([findingId, checkId]) => [checkId, findingId]));
  return output.auditCheckResults.map((check) => {
    const findingId = carrierByCheck.get(check.id);
    const carrier = findingId ? output.findings.find((finding) => finding.findingId === findingId) : undefined;
    const sourceUnavailable = carrier?.attentionCode?.startsWith("SOURCE_UNAVAILABLE:") === true;
    const sourceExplanation = carrier?.attentionCode === "SOURCE_UNAVAILABLE:CONFIRMED_VALUE_DIFFERS_FROM_SOURCE"
      ? "人工确认值与原文不同，原文关联来源待核实"
      : "必要操作数的原文关联来源待核实";
    const evidence = findingId
      ? output.evidence.filter((item) => item.findingId === findingId) : [];
    return {
      stableId: stableId(run.runId, output.step.stepType, check.id),
      outputType: output.step.stepType,
      outputId: check.id,
      title: `${F02_LABELS[check.ruleCode] ?? check.ruleCode}${check.itemKey ? `（${check.itemKey}）` : ""}`,
      status: check.checkStatus === "FAIL" ? "ATTENTION"
        : check.checkStatus === "NOT_EVALUATED" ? "INSUFFICIENT" : "NORMAL",
      summary: RULE_DETAILS[check.detail] ?? check.detail,
      actualValue: ruleValue(check.ruleCode, check.actualValue),
      expectedValue: ruleValue(check.ruleCode, check.expectedValue),
      recommendation: carrier?.recommendation ?? (sourceUnavailable ? `${sourceExplanation}，请核实冻结原文与确认记录。`
        : check.checkStatus === "FAIL" ? "请核对对应业务事实和来源材料。"
          : check.checkStatus === "NOT_EVALUATED" ? "请补充缺失业务事实后重新审核。" : null),
      typedFacts: sourceUnavailable ? [`数值结论：${check.checkStatus === "PASS" ? "通过" : check.checkStatus === "FAIL" ? "存在差异" : "未能评估"}`,
        `来源说明：${sourceExplanation}`] : [],
      source: "DETERMINISTIC",
      evidence: evidenceViews(evidence, fileNames),
      primary: true,
    };
  });
}

function unique(items: readonly ContractReviewCategoryItemView[]): ContractReviewCategoryItemView[] {
  const values = new Map<string, ContractReviewCategoryItemView>();
  for (const item of items) values.set(item.stableId, item);
  return [...values.values()];
}

function unavailable(definition: CategoryDefinition, status: ReviewStepStatus): ContractReviewCategoryView {
  return {
    ...definition,
    coverage: "UNFINISHED",
    judgement: "UNAVAILABLE",
    statusLabel: status === "UNKNOWN" ? "结果状态待确认" : "审核环节未完成",
    attentionCount: 0,
    summary: status === "UNKNOWN" ? "本项审核结果状态待确认。" : "本项审核环节未完成。",
    uncoveredScopes: [],
    items: [],
  };
}

function judgement(items: readonly ContractReviewCategoryItemView[], coverage: ContractReviewCoverage) {
  if (items.some((item) => item.status === "ATTENTION")) return "ATTENTION" as const;
  if (coverage !== "COVERED" || items.some((item) => item.status === "INSUFFICIENT")) return "INSUFFICIENT" as const;
  return "NORMAL" as const;
}

function category(definition: CategoryDefinition, coverage: ContractReviewCoverage,
  values: readonly ContractReviewCategoryItemView[], summary: string,
  uncoveredScopes: readonly string[] = []): ContractReviewCategoryView {
  const items = unique(values);
  const result = judgement(items, coverage);
  const statusLabel = result === "ATTENTION"
    ? coverage === "COVERED" ? "发现需要关注的结果" : "发现差异，覆盖不完整"
    : result === "NORMAL" ? "本项已核对"
      : coverage === "NOT_COVERED" ? "当前审核未覆盖"
        : coverage === "WAITING_EXTERNAL" ? "等待外部主体资格核验" : "资料不足或存在未评估项";
  return {
    ...definition,
    coverage,
    judgement: result,
    statusLabel,
    attentionCount: new Set(items.filter((item) => item.status !== "NORMAL").map((item) => item.stableId)).size,
    summary,
    uncoveredScopes,
    items,
  };
}

function outputFor(run: ReviewRunDto, type: ReviewStepType): ReviewStepOutputDto | undefined {
  return run.outputs.find((output) => output.step.stepType === type);
}

function interrupted(run: ReviewRunDto, definition: CategoryDefinition,
  type: ReviewStepType): ContractReviewCategoryView | null {
  const output = outputFor(run, type);
  const step = output?.step ?? run.steps.find((item) => item.stepType === type);
  return step && step.status !== "COMPLETED"
    ? unavailable(definition, step.status) : null;
}

function semanticFindings(output: ReviewStepOutputDto | undefined, categories: readonly string[]) {
  return output?.findings.filter((finding) => categories.includes(finding.category)) ?? [];
}

function isAttachmentResult(finding: ReviewFindingDto): boolean {
  return finding.findingType.startsWith("ATTACHMENT_")
    && (finding.findingType.includes("REFERENCE") || finding.findingType.includes("MATCH"));
}

function isCopiesResult(finding: ReviewFindingDto): boolean {
  return finding.findingType === "CONTRACT_COPIES" || finding.findingType === "COPY_COUNT"
    || finding.findingType === "COPY_COUNT_REQUIREMENT_COMPARISON";
}

function isCopyRequirementComparison(finding: ReviewFindingDto): boolean {
  return finding.findingType === "COPY_COUNT_REQUIREMENT_COMPARISON";
}

function isEffectivenessResult(finding: ReviewFindingDto): boolean {
  return finding.findingType === "EFFECTIVE_EVENT" || finding.findingType === "EFFECTIVE_DATE";
}

function isTemplateStructureResult(finding: ReviewFindingDto): boolean {
  return finding.findingType === "TEMPLATE_STRUCTURE_COMPARISON";
}

export function createEightCategoryReviewProjection(run: ReviewRunDto,
  fileNames: ReadonlyMap<string, string>): ContractReviewCategoryView[] {
  const server = run.businessProjection;
  if (server) {
    try { assertReviewOpinionMetadata(server); } catch { conflict(); }
    if (server.version !== 1 || server.runId !== run.runId || server.runStatus !== run.status
      || server.categories.length !== 8 || server.categories.some((category, index) =>
        category.id !== DEFINITIONS[index]!.id || category.order !== index + 1)) conflict();
    return server.categories.map((category) => ({ ...category, projectionVersion: server.version,
      opinionVersion: server.opinionVersion ?? category.opinionVersion,
      overallAdvice: server.overallAdvice ?? category.overallAdvice,
      overallOpinion: server.overallOpinion,
      monetaryObservation: server.monetaryObservation,
      scopeStatement: server.scopeStatement }));
  }
  for (const output of run.outputs) validateOutput(run, output);
  const byId = new Map(DEFINITIONS.map((value) => [value.id, value]));
  const f02 = outputFor(run, "F02_DETERMINISTIC");
  const semantic = outputFor(run, "SEMANTIC_AI");
  const breach = outputFor(run, "BREACH");
  const term = outputFor(run, "TERM");
  const result = new Map<ContractReviewCategoryId, ContractReviewCategoryView>();

  const qUnavailable = interrupted(run, byId.get("QUANTITATIVE")!, "F02_DETERMINISTIC");
  const amountItems = f02 ? f02Items(run, f02, fileNames) : [];
  const unavailableSourceCount = amountItems.filter((item) => item.typedFacts.some((fact) => fact.startsWith("来源说明："))).length;
  const amountSourceGaps = unavailableSourceCount > 0
    ? [`${unavailableSourceCount}项量化检查的原文关联来源待核实；数值结论与来源完整性分别判断`] : [];
  result.set("QUANTITATIVE", qUnavailable ?? (f02?.step.status === "COMPLETED" && f02.auditCheckResults.length > 0
    ? category(byId.get("QUANTITATIVE")!, amountSourceGaps.length > 0 ? "PARTIALLY_COVERED" : "COVERED", amountItems,
      "按本次冻结合同事实展示全部确定性量化规则结果；多行分项逐行保留。", amountSourceGaps)
    : category(byId.get("QUANTITATIVE")!, "NOT_COVERED", [], "本次审核没有可验证的量化规则结果。")));

  const semanticUnavailable = interrupted(run, byId.get("DRAFT_STRUCTURE")!, "SEMANTIC_AI");
  const structure = semanticFindings(semantic, ["CONTRACT_STRUCTURE"]);
  const templateStructure = run.outputs.flatMap((value) => value.findings
    .filter(isTemplateStructureResult).map((finding) => ({ output: value, finding })));
  const templateStructureItems = templateStructure.flatMap(({ output, finding }) =>
    findingItems(run, output, [finding], fileNames));
  result.set("DRAFT_STRUCTURE", templateStructureItems.length > 0
    ? category(byId.get("DRAFT_STRUCTURE")!, "COVERED", templateStructureItems,
      "展示本次冻结合同与明确指定模板的结构对照结果。")
    : semanticUnavailable ?? (structure.length > 0
    ? category(byId.get("DRAFT_STRUCTURE")!, "PARTIALLY_COVERED",
      findingItems(run, semantic!, structure, fileNames), "已展示持久化的合同结构提示；未指定模板时不作模板符合性结论。",
      ["未指定合同模板，未完成模板结构对标"])
    : category(byId.get("DRAFT_STRUCTURE")!, "NOT_COVERED", [], "本次审核没有合同模板结构对标结果。",
      ["未指定合同模板，未完成模板结构对标"])));

  const breachUnavailable = interrupted(run, byId.get("BREACH")!, "BREACH");
  const breachPrimary = breach?.step.status === "COMPLETED" ? breach.findings : [];
  const breachReference = semanticFindings(semantic, ["BREACH_LIABILITY"]);
  const breachItems = [
    ...(breach ? findingItems(run, breach, breachPrimary, fileNames) : []),
    ...(semantic ? findingItems(run, semantic, breachReference, fileNames) : []),
  ];
  result.set("BREACH", breachUnavailable ?? (breachPrimary.length > 0
    ? category(byId.get("BREACH")!, "COVERED", breachItems, "展示已持久化的违约责任量化事实、差异和材料不足；不计算实际违约金额。")
    : category(byId.get("BREACH")!, "NOT_COVERED", breachItems, "本次审核没有完整的违约责任量化结果。")));

  const termUnavailable = interrupted(run, byId.get("TERM")!, "TERM");
  const termPrimary = term?.step.status === "COMPLETED"
    ? term.findings.filter((value) => !isEffectivenessResult(value) && !isCopiesResult(value)
      && !isTemplateStructureResult(value) && !isAttachmentResult(value)) : [];
  const termReference = semanticFindings(semantic, ["TERM_TERMINATION", "DELIVERY_TERMS"]);
  const termItems = [
    ...(term ? findingItems(run, term, termPrimary, fileNames) : []),
    ...(semantic ? findingItems(run, semantic, termReference, fileNames) : []),
  ];
  result.set("TERM", termUnavailable ?? (termPrimary.length > 0
    ? category(byId.get("TERM")!, "COVERED", termItems, "展示已持久化的期限、交付和终止条件结果。")
    : category(byId.get("TERM")!, "NOT_COVERED", termItems, "本次审核没有完整的期限与终止格式结果。")));

  const identityUnavailable = interrupted(run, byId.get("PARTY_QUALIFICATION")!, "SEMANTIC_AI");
  const identity = semanticFindings(semantic, ["PROJECT_IDENTITY", "SUPPLIER_IDENTITY"]);
  const qualification = semantic?.findings.filter((value) =>
    value.findingType === "PARTY_QUALIFICATION" || value.findingType === "SUPPLIER_QUALIFICATION") ?? [];
  result.set("PARTY_QUALIFICATION", identityUnavailable ?? (qualification.length > 0
    ? category(byId.get("PARTY_QUALIFICATION")!, "WAITING_EXTERNAL",
      findingItems(run, semantic!, [...identity, ...qualification], fileNames),
      "展示已有主体相关提示；当前输出协议不能证明外部主体资格已经完整核验。",
      ["缺少可验证的外部主体资格完整核验协议"])
    : identity.length > 0
      ? category(byId.get("PARTY_QUALIFICATION")!, "WAITING_EXTERNAL",
        findingItems(run, semantic!, identity, fileNames), "仅完成名称或项目身份基础核对，不能据此认定主体资格合格。",
        ["缺少外部主体资格核验结果"])
      : category(byId.get("PARTY_QUALIFICATION")!, "WAITING_EXTERNAL", [], "本次审核没有主体资格核验结果。",
        ["缺少外部主体资格核验结果"])));

  const attachmentFindings = run.outputs.flatMap((value) => value.findings.filter(isAttachmentResult)
    .map((finding) => ({ output: value, finding })));
  const attachmentItems = attachmentFindings.flatMap(({ output, finding }) =>
    findingItems(run, output, [finding], fileNames));
  const allAttachmentsMatched = attachmentItems.length > 0
    && attachmentItems.every((item) => item.status === "NORMAL");
  result.set("ATTACHMENT_COMPLETENESS", attachmentItems.length > 0
    ? category(byId.get("ATTACHMENT_COMPLETENESS")!,
      allAttachmentsMatched ? "COVERED" : "PARTIALLY_COVERED", attachmentItems,
      allAttachmentsMatched
        ? "合同正文可识别的附件引用均在本次冻结材料或可证明的内嵌附件中取得匹配。"
        : "已保留附件引用匹配结果；仍有引用无法从本次冻结材料证明完整。",
      allAttachmentsMatched ? [] : ["存在尚未证明匹配的附件引用"])
    : category(byId.get("ATTACHMENT_COMPLETENESS")!, "NOT_COVERED", [],
      "材料清单和服务范围描述不能证明附件完整性。", ["缺少附件引用与实际附件匹配结果"]));

  const effectUnavailable = interrupted(run, byId.get("EFFECTIVENESS_COPIES")!, "TERM");
  const effectiveness = term?.findings.filter(isEffectivenessResult) ?? [];
  const copies = run.outputs.flatMap((value) => value.findings.filter(isCopiesResult)
    .map((finding) => ({ output: value, finding })));
  const copyFacts = copies.filter(({ finding }) => !isCopyRequirementComparison(finding));
  const copyRequirements = copies.filter(({ finding }) => isCopyRequirementComparison(finding));
  const effectivenessItems = [
    ...(term ? findingItems(run, term, effectiveness, fileNames) : []),
    ...copies.flatMap(({ output, finding }) => findingItems(run, output, [finding], fileNames)),
  ];
  result.set("EFFECTIVENESS_COPIES", effectUnavailable
    ?? (effectiveness.length > 0 && copyFacts.length > 0 && copyRequirements.length > 0
    ? category(byId.get("EFFECTIVENESS_COPIES")!, "COVERED", effectivenessItems,
      "分别展示已持久化的生效条件、合同份数事实及其权威要求对照。")
    : effectivenessItems.length > 0
      ? category(byId.get("EFFECTIVENESS_COPIES")!, "PARTIALLY_COVERED", effectivenessItems,
        "仅展示已有的生效条件或合同份数结果。",
        [...(effectiveness.length === 0 ? ["合同生效条件未检查"] : []),
          ...(copyFacts.length === 0 ? ["合同份数未检查"] : []),
          ...(copyFacts.length > 0 && copyRequirements.length === 0
            ? ["缺少权威合同份数要求，当前仅展示原文份数事实"] : [])])
      : category(byId.get("EFFECTIVENESS_COPIES")!, "NOT_COVERED", [],
        "本次审核没有生效条件与合同份数的完整检查结果。", ["合同生效条件未检查", "合同份数未检查"])));

  const riskUnavailable = interrupted(run, byId.get("CLAUSE_RISK")!, "SEMANTIC_AI");
  const excludedSemantic = new Set(["CONTRACT_STRUCTURE", "PROJECT_IDENTITY", "SUPPLIER_IDENTITY",
    "BREACH_LIABILITY", "TERM_TERMINATION", "DELIVERY_TERMS"]);
  const risks = semantic?.findings.filter((value) => !excludedSemantic.has(value.category)
    && !isAttachmentResult(value) && !isCopiesResult(value)) ?? [];
  result.set("CLAUSE_RISK", riskUnavailable ?? (risks.length > 0
    ? category(byId.get("CLAUSE_RISK")!, "COVERED", findingItems(run, semantic!, risks, fileNames),
      "展示本次审核已持久化的其他条款风险提示；模型辅助内容需人工核验。")
    : category(byId.get("CLAUSE_RISK")!, "NOT_COVERED", [], "本次审核没有独立的其他条款风险提示结果。")));

  return DEFINITIONS.map((definition) => result.get(definition.id)!);
}

export function hasIncompleteCategory(categories: readonly ContractReviewCategoryView[]): boolean {
  return categories.some((value) => value.coverage !== "COVERED" || value.judgement !== "NORMAL");
}

export type { ReviewRunStatus };
