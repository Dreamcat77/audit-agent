import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";
import {
  aggregateFindingPresentation,
  createFindingComparisonPresentation,
  createF02CompletionPresentation,
  displayChineseUppercaseAmount,
  findingBusinessStatus,
  findingPresentationStatus,
  formatFindingComparisonValue,
  formatRmbAmount,
  isCurrentPendingAcknowledgementItem,
  presentFindingSource,
  progressRiskNodePresentation,
  groupF02FindingsForPresentation,
  reviewFindingDetailPresentation,
  reviewCompletionStatusText,
  reviewSummaryBlocks,
  summarizeFindingPresentation,
} from "./reviewFindingPresentation.ts";
import type { Finding, ReviewProgress, ReviewResult } from "./types.ts";
import type { ProgressiveReplayState } from "./reviewProgressState.ts";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
});

const [{ createReviewExecutionSnapshot }, {
  orderF02FindingsForView,
  selectDurablyRevealedF02Findings,
}] = await Promise.all([
  import("./reviewExecutionPresentation.ts"),
  import("./reviewFindingsViewModel.ts"),
]).finally(() => hooks.deregister());

test("maps each finding from check status and real risk semantics", () => {
  assert.equal(
    findingPresentationStatus({ checkStatus: "PASS", riskLevel: "GREEN" }),
    "PASS",
  );
  assert.equal(
    findingPresentationStatus({ checkStatus: "FAIL", riskLevel: "RED" }),
    "FAIL",
  );
  assert.equal(
    findingPresentationStatus({ checkStatus: "FAIL", riskLevel: "YELLOW" }),
    "WARNING",
  );
});

test("presents stored FAIL and NOT_EVALUATED as human review assistance without changing enums", () => {
  assert.deepEqual(
    findingBusinessStatus({ checkStatus: "FAIL", riskLevel: "RED", blockSign: false }),
    { label: "需复核", prompt: "建议重点复核", tone: "ATTENTION" },
  );
  assert.deepEqual(
    findingBusinessStatus({ checkStatus: "NOT_EVALUATED", riskLevel: "YELLOW", blockSign: false }),
    { label: "材料不足", prompt: "暂无法判断", tone: "NEUTRAL" },
  );
  assert.deepEqual(
    findingBusinessStatus({ checkStatus: "PASS", riskLevel: "GREEN", blockSign: false }),
    { label: "核对一致", prompt: "核对一致", tone: "SUCCESS" },
  );
});

test("maps real backend English finding details to one complete Chinese product presentation", () => {
  const cases = [
    ["F02_AMOUNT_WORDS_MATCH", "PASS", null, "Amount in words matches numeric contract amount", "合同金额数字与金额大写一致。"],
    ["F02_AMOUNT_WORDS_MATCH", "FAIL", null, "Amount in words does not match numeric contract amount", "合同金额数字与金额大写不一致。"],
    ["F02_AMOUNT_WORDS_MATCH", "NOT_EVALUATED", null, "Amount in words is missing", "缺少可靠的金额大写信息，暂无法完成核对。"],
    ["F02_LINE_ITEM_ARITHMETIC", "PASS", "ITEM-1", "Line quantity multiplied by unit price matches declared amount", "分项 ITEM-1 的数量 × 单价与申报金额一致。"],
    ["F02_LINE_ITEM_ARITHMETIC", "FAIL", "ITEM-2", "Line item arithmetic differs", "分项 ITEM-2 的数量 × 单价与申报金额不一致。"],
    ["F02_LINE_SUM_MATCH", "FAIL", null, "Line item sum differs from contract amount", "合同分项申报金额合计与合同总额不一致。"],
    ["F02_AWARD_AMOUNT_MATCH", "FAIL", null, "Contract amount differs from award amount", "合同金额与中标金额不一致。"],
    ["F02_AWARD_SUPPLIER_MATCH", "PASS", null, "Contract supplier matches award supplier by credit code", "合同乙方与中标供应商一致。"],
  ] as const;
  const presented = cases.map(([ruleCode, checkStatus, itemKey, rawDetail, expected]) => {
    const detail = reviewFindingDetailPresentation({
      ruleCode, checkStatus, riskLevel: checkStatus === "PASS" ? "GREEN" : "RED",
      itemKey, actualValue: null, expectedValue: null,
    });
    assert.equal(detail, expected);
    assert.notEqual(detail, rawDetail);
    return detail;
  });
  assert.doesNotMatch(JSON.stringify(presented), /Amount in words|Line item|Contract supplier|Contract amount|matches|differs/i);
});

test("amount-only arithmetic explains unavailable quantity and price while retaining declared amount", () => {
  const finding: Finding = {
    id: "amount-only", ruleCode: "F02_LINE_ITEM_ARITHMETIC", itemKey: "SERVICE-1",
    checkStatus: "NOT_EVALUATED", riskLevel: "YELLOW", blockSign: false,
    actualValue: null, expectedValue: null,
    detail: "Line item has declared amount only; quantity and unit price are unavailable",
    ruleVersion: 1, createdAt: "2026-09-23T00:00:00Z",
  };
  const contract = {
    id: "contract", contractNo: "C-1", contractName: "Service", contractType: "SERVICE",
    partyA: "Buyer", partyB: "Supplier", partyBCreditCode: null, contractAmount: 28000,
    amountInWords: null, status: "READY_FOR_REVIEW", versionNo: 1,
    createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
    lineItems: [{ id: "line", itemKey: "SERVICE-1", itemName: "Service fee",
      basis: "AMOUNT_ONLY" as const, quantity: null, unitPrice: null,
      declaredAmount: 28000, sortNo: 1 }], awardNotice: null,
  };

  assert.equal(reviewFindingDetailPresentation(finding),
    "分项 SERVICE-1 未提供可核对的数量和单价，行乘法暂无法完成核对。");
  assert.deepEqual(createFindingComparisonPresentation(finding, contract).cards, [{
    label: "申报金额（数量、单价未提供）", value: "¥28,000.00",
  }]);
});

test("never presents NOT_EVALUATED as a warning even when its stored risk is YELLOW", () => {
  const finding = { checkStatus: "NOT_EVALUATED", riskLevel: "YELLOW" } as const;
  assert.equal(findingPresentationStatus(finding), "UNDETERMINED");
  assert.equal(aggregateFindingPresentation([finding]), "UNDETERMINED");
  assert.deepEqual(summarizeFindingPresentation([finding]), {
    pass: 0,
    warning: 0,
    fail: 0,
    undetermined: 1,
  });
});

const FINDINGS: Finding[] = [
  {
    id: "finding-award",
    ruleCode: "F02_AWARD_AMOUNT_MATCH",
    itemKey: null,
    checkStatus: "PASS",
    riskLevel: "GREEN",
    blockSign: false,
    actualValue: "100.00",
    expectedValue: "100.00",
    detail: "金额一致",
    ruleVersion: 1,
    createdAt: "2026-08-25T09:02:00Z",
  },
  {
    id: "finding-words",
    ruleCode: "F02_AMOUNT_WORDS_MATCH",
    itemKey: null,
    checkStatus: "PASS",
    riskLevel: "GREEN",
    blockSign: false,
    actualValue: "100.00",
    expectedValue: "100.00",
    detail: "金额一致",
    ruleVersion: 1,
    createdAt: "2026-08-25T09:01:00Z",
  },
];

const RESULT: ReviewResult = {
  taskId: "task-1",
  status: "DONE",
  reviewType: "F02_DETERMINISTIC",
  overallRisk: "GREEN",
  blockSign: false,
  replayed: false,
  findings: FINDINGS,
};

const PROGRESS: ReviewProgress = {
  total: 3,
  riskSummary: "无风险",
  items: [
    {
      type: "SOURCE_PREPARATION",
      checkResultId: null,
      title: "读取合同及比对来源",
      displayOrder: 0,
      status: "DONE",
      risk: null,
      completedAt: "2026-08-25T09:00:00Z",
      acknowledgement: null,
    },
    {
      type: "FINDING",
      checkResultId: "finding-words",
      title: "服务端标题：大写金额",
      displayOrder: 2,
      status: "PASS",
      risk: "GREEN",
      completedAt: "2026-08-25T09:01:00Z",
      acknowledgement: null,
    },
    {
      type: "FINDING",
      checkResultId: "finding-award",
      title: "服务端标题：中标金额",
      displayOrder: 1,
      status: "PASS",
      risk: "GREEN",
      completedAt: "2026-08-25T09:02:00Z",
      acknowledgement: null,
    },
  ],
};

test("groups the four monetary checks separately from exact supplier identity", () => {
  const grouped = groupF02FindingsForPresentation([
    { ...FINDINGS[0]!, ruleCode: "F02_AWARD_AMOUNT_MATCH" },
    { ...FINDINGS[1]!, ruleCode: "F02_AMOUNT_WORDS_MATCH" },
    { ...FINDINGS[0]!, id: "line-arithmetic", ruleCode: "F02_LINE_ITEM_ARITHMETIC" },
    { ...FINDINGS[0]!, id: "line-sum", ruleCode: "F02_LINE_SUM_MATCH" },
    { ...FINDINGS[0]!, id: "supplier", ruleCode: "F02_AWARD_SUPPLIER_MATCH" },
  ]);

  assert.deepEqual(grouped.map((group) => [group.key, group.title, group.findings.length]), [
    ["AMOUNT_AND_LINE", "金额与明细精确校验", 4],
    ["EXACT_IDENTITY", "主体身份精确核对", 1],
  ]);
  assert.match(grouped[0]?.description ?? "", /金额、数量×单价和分项合计进行精确核对/);
  assert.doesNotMatch(grouped[0]?.description ?? "", /AI|Java|BigDecimal/);
  assert.match(grouped[1]?.description ?? "", /统一社会信用代码/);
});

test("review summary uses assistance language and separates insufficient evidence", () => {
  const progress: ReviewProgress = {
    ...PROGRESS,
    total: 4,
    items: [
      PROGRESS.items[0]!,
      PROGRESS.items[1]!,
      { ...PROGRESS.items[2]!, status: "FAIL", risk: "RED" },
      {
        ...PROGRESS.items[2]!,
        checkResultId: "insufficient",
        displayOrder: 3,
        status: "NOT_EVALUATED",
        risk: "YELLOW",
      },
    ],
  };
  assert.deepEqual(reviewSummaryBlocks(progress), [
    { label: "一致项", value: 1 },
    { label: "需复核项", value: 1 },
    { label: "材料不足项", value: 1 },
  ]);
});

test("orders findings only from server displayOrder instead of the removed local F02 map", () => {
  assert.deepEqual(
    orderF02FindingsForView(FINDINGS, PROGRESS).map((finding) => finding.id),
    ["finding-award", "finding-words"],
  );
});

test("reveals no finding detail beyond the first unacknowledged risk", () => {
  const risk: Finding = {
    ...FINDINGS[0]!,
    id: "risk-first",
    checkStatus: "FAIL",
    riskLevel: "RED",
    detail: "first durable risk",
  };
  const laterGreen: Finding = {
    ...FINDINGS[1]!,
    id: "green-later",
    detail: "later green fact",
  };
  const acknowledgement = {
    id: "ack-risk-first",
    checkResultId: risk.id,
    acknowledgedBy: "auditor-1",
    acknowledgedByDisplayName: "审核员甲",
    acknowledgedAt: "2026-08-25T09:03:00Z",
    requestId: "request-risk-first",
    traceId: "trace-risk-first",
    ruleVersion: 1,
  };
  const pendingProgress: ReviewProgress = {
    total: 3,
    riskSummary: "低风险",
    items: [
      PROGRESS.items[0]!,
      {
        ...PROGRESS.items[1]!,
        checkResultId: risk.id,
        displayOrder: 1,
        status: "FAIL",
        risk: "RED",
        acknowledgement: null,
      },
      {
        ...PROGRESS.items[2]!,
        checkResultId: laterGreen.id,
        displayOrder: 2,
        status: "PASS",
        risk: "GREEN",
        acknowledgement: null,
      },
    ],
  };

  assert.deepEqual(
    selectDurablyRevealedF02Findings([risk, laterGreen], pendingProgress)
      .map((finding) => finding.id),
    [risk.id],
  );

  const refreshedProgress: ReviewProgress = {
    ...pendingProgress,
    items: pendingProgress.items.map((item) => item.checkResultId === risk.id
      ? { ...item, acknowledgement }
      : item),
  };
  assert.deepEqual(
    selectDurablyRevealedF02Findings([risk, laterGreen], refreshedProgress)
      .map((finding) => finding.id),
    [risk.id, laterGreen.id],
  );
});

test("timeline presentation hides every later finding until refreshed progress proves the preceding acknowledgement", () => {
  const firstRisk: Finding = {
    ...FINDINGS[0]!,
    id: "timeline-risk-first",
    checkStatus: "FAIL",
    riskLevel: "RED",
    detail: "visible first risk detail",
  };
  const laterGreen: Finding = {
    ...FINDINGS[1]!,
    id: "timeline-green-later",
    detail: "hidden later green detail",
  };
  const laterRisk: Finding = {
    ...FINDINGS[0]!,
    id: "timeline-risk-later",
    checkStatus: "FAIL",
    riskLevel: "RED",
    detail: "hidden later risk detail",
  };
  const source = PROGRESS.items[0]!;
  const firstRiskItem = {
    ...PROGRESS.items[1]!,
    checkResultId: firstRisk.id,
    title: "first risk title",
    displayOrder: 1,
    status: "FAIL" as const,
    risk: "RED" as const,
    acknowledgement: null,
  };
  const laterGreenItem = {
    ...PROGRESS.items[2]!,
    checkResultId: laterGreen.id,
    title: "hidden later green title",
    displayOrder: 2,
    status: "PASS" as const,
    risk: "GREEN" as const,
    acknowledgement: null,
  };
  const laterRiskItem = {
    ...PROGRESS.items[1]!,
    checkResultId: laterRisk.id,
    title: "hidden later risk title",
    displayOrder: 3,
    status: "FAIL" as const,
    risk: "RED" as const,
    acknowledgement: null,
  };
  const result: ReviewResult = {
    ...RESULT,
    findings: [laterRisk, laterGreen, firstRisk],
  };
  const pendingProgress: ReviewProgress = {
    total: 4,
    riskSummary: "低风险",
    items: [source, firstRiskItem, laterGreenItem, laterRiskItem],
  };

  const before = createReviewExecutionSnapshot({ result, progress: pendingProgress });
  assert.equal(before.steps[1]?.detail, "合同金额与中标金额不一致。");
  assert.deepEqual(
    before.steps.slice(2).map((step) => ({ label: step.label, detail: step.detail, status: step.status })),
    [
      { label: "后续检查项", detail: "待前序复核提示知悉后展示", status: "PENDING" },
      { label: "后续检查项", detail: "待前序复核提示知悉后展示", status: "PENDING" },
    ],
  );
  assert.doesNotMatch(
    JSON.stringify(before.steps.slice(2)),
    /hidden later green|hidden later risk/,
  );

  const acknowledgement = {
    id: "ack-timeline-risk-first",
    checkResultId: firstRisk.id,
    acknowledgedBy: "auditor-timeline",
    acknowledgedByDisplayName: "审核员时间线",
    acknowledgedAt: "2026-08-25T09:03:00Z",
    requestId: "request-timeline",
    traceId: "trace-timeline",
    ruleVersion: 1,
  };
  const refreshedProgress: ReviewProgress = {
    ...pendingProgress,
    items: pendingProgress.items.map((item) => item.checkResultId === firstRisk.id
      ? { ...item, acknowledgement }
      : item),
  };
  const after = createReviewExecutionSnapshot({ result, progress: refreshedProgress });
  assert.deepEqual(
    after.steps.slice(2).map((step) => ({ label: step.label, detail: step.detail })),
    [
      { label: "合同金额大小写一致性核对", detail: "合同金额数字与金额大写一致。" },
      { label: "合同金额与中标金额一致性核对", detail: "合同金额与中标金额不一致。" },
    ],
  );
  assert.doesNotMatch(JSON.stringify(after.steps), /visible first risk|hidden later green|hidden later risk/);
});

test("builds dynamic progress steps from the server projection without a fixed conclusion step", () => {
  const snapshot = createReviewExecutionSnapshot({ result: RESULT, progress: PROGRESS });
  assert.equal(snapshot.steps.length, PROGRESS.total);
  assert.deepEqual(
    snapshot.steps.map((step) => step.label),
    ["资料读取与来源核对", "合同金额与中标金额一致性核对", "合同金额大小写一致性核对"],
  );
  assert.equal(snapshot.steps.some((step) => step.label === "生成审核结论"), false);
});

test("formats numeric RMB values with a yen sign and fixed decimals while preserving uppercase text", () => {
  assert.equal(formatRmbAmount("128000"), "¥128,000.00");
  assert.equal(formatRmbAmount("128000.5"), "¥128,000.50");
  assert.equal(
    formatFindingComparisonValue("F02_AWARD_AMOUNT_MATCH", "128000"),
    "¥128,000.00",
  );
  assert.equal(
    formatFindingComparisonValue("F02_AWARD_SUPPLIER_MATCH", "供应商 128000"),
    "供应商 128000",
  );
  const uppercase = "人民币壹拾贰万捌仟元整";
  assert.equal(displayChineseUppercaseAmount(uppercase), uppercase);
});

test("formats the maximum DECIMAL(18,2) value exactly without binary floating conversion", () => {
  assert.equal(
    formatRmbAmount("9999999999999999.99"),
    "¥9,999,999,999,999,999.99",
  );
  assert.equal(
    formatFindingComparisonValue("F02_AWARD_AMOUNT_MATCH", "9999999999999999.99"),
    "¥9,999,999,999,999,999.99",
  );
});

test("rounds canonical DECIMAL(18,4) strings to fixed-two RMB with exact HALF_UP semantics", () => {
  assert.equal(formatRmbAmount("1.2345"), "¥1.23");
  assert.equal(formatRmbAmount("1.2350"), "¥1.24");
  assert.equal(formatRmbAmount("999.9999"), "¥1,000.00");
  assert.equal(formatRmbAmount("-1.2350"), "-¥1.24");
  assert.equal(formatRmbAmount("-0.0049"), "¥0.00");
  assert.equal(
    formatRmbAmount("99999999999999.9999"),
    "¥100,000,000,000,000.00",
  );
});

test("uses neutral amount fallbacks for blank or invalid decimals and preserves nonblank uppercase text exactly", () => {
  for (const value of [
    "",
    "   ",
    "not-a-decimal",
    "99999999999999999.99",
    "999999999999999.9999",
    "1.23456",
    "+1.00",
    "01.00",
    ".50",
    "1.",
  ]) {
    assert.equal(formatRmbAmount(value), "暂无可靠金额", `value ${JSON.stringify(value)}`);
  }
  assert.equal(displayChineseUppercaseAmount(null), "暂无可靠金额大写");
  assert.equal(displayChineseUppercaseAmount(""), "暂无可靠金额大写");
  assert.equal(displayChineseUppercaseAmount(" \t "), "暂无可靠金额大写");
  const uppercaseWithWhitespace = "  人民币壹拾贰万捌仟元整  ";
  assert.equal(
    displayChineseUppercaseAmount(uppercaseWithWhitespace),
    uppercaseWithWhitespace,
  );
});

test("uses explicit neutral states for missing amount and source facts", () => {
  assert.equal(formatRmbAmount(null), "暂无可靠金额");
  assert.deepEqual(presentFindingSource(null), {
    available: false,
    label: "暂无可靠来源定位",
  });
  assert.deepEqual(presentFindingSource({ label: "中标通知书第 2 页" }), {
    available: true,
    label: "中标通知书第 2 页",
  });
});

test("completion presentation is factual, has no overall decision copy, and exposes no report export", () => {
  const pendingRiskProgress: ReviewProgress = {
    total: 3,
    riskSummary: "低风险",
    items: [
      PROGRESS.items[0]!,
      PROGRESS.items[1]!,
      {
        ...PROGRESS.items[2]!,
        checkResultId: "risk-result",
        status: "FAIL",
        risk: "RED",
      },
    ],
  };
  const acknowledgedRiskProgress: ReviewProgress = {
    ...pendingRiskProgress,
    items: pendingRiskProgress.items.map((item) => item.checkResultId === "risk-result"
      ? {
          ...item,
          acknowledgement: {
            id: "ack-risk",
            checkResultId: "risk-result",
            acknowledgedBy: "auditor-1",
            acknowledgedByDisplayName: "审核员甲",
            acknowledgedAt: "2026-08-25T09:03:00Z",
            requestId: "request-risk",
            traceId: "trace-risk",
            ruleVersion: 1,
          },
        }
      : item),
  };

  const before = createF02CompletionPresentation(pendingRiskProgress);
  const after = createF02CompletionPresentation(acknowledgedRiskProgress);
  assert.equal(
    Object.hasOwn(before, "title"),
    false,
    "a risk summary must not claim completion independently of replay state",
  );
  assert.equal(before.riskCount, 1);
  assert.equal(before.riskSummary, "低风险");
  assert.deepEqual(before.availableActions, ["OPEN_REVIEW_HISTORY"]);
  assert.deepEqual(after, before, "acknowledgement cannot rewrite status or risk counts");
  assert.doesNotMatch(
    JSON.stringify(before),
    /审核通过|项目通过|项目不通过|批准|不批准|accepted|approved|rejected|passed|failed/i,
  );
});

test("red progress uses an exclamation and non-color status text instead of a failure cross", () => {
  assert.deepEqual(progressRiskNodePresentation("RED", false), {
    icon: "EXCLAMATION",
    statusText: "待知悉",
  });
  assert.deepEqual(progressRiskNodePresentation("YELLOW", false), {
    icon: "EXCLAMATION",
    statusText: "待知悉",
  });
  assert.deepEqual(progressRiskNodePresentation("GREEN", false), {
    icon: "CHECK",
    statusText: "核对完成",
  });
});

test("completion status copy stays neutral until server progress proves completion or a pending risk", () => {
  const completeState = {
    orderedItems: [],
    total: 0,
    current: 0,
    completedPrefixCount: 0,
    pendingAcknowledgementItem: null,
    complete: true,
    riskCount: 0,
    riskSummary: "无风险",
  } satisfies ProgressiveReplayState;
  const pendingState = {
    ...completeState,
    complete: false,
    riskCount: 1,
    riskSummary: "低风险",
  } satisfies ProgressiveReplayState;

  assert.equal(reviewCompletionStatusText(null), "审核进行中");
  assert.equal(reviewCompletionStatusText(completeState), "审核完成");
  assert.equal(reviewCompletionStatusText(pendingState), "复核提示待知悉");
});

test("absent progress identities never mark a timeline item as current", () => {
  assert.equal(isCurrentPendingAcknowledgementItem(undefined, undefined), false);
  assert.equal(isCurrentPendingAcknowledgementItem(undefined, null), false);
  assert.equal(isCurrentPendingAcknowledgementItem(PROGRESS.items[0], undefined), false);
  assert.equal(isCurrentPendingAcknowledgementItem(PROGRESS.items[1], undefined), false);
  assert.equal(
    isCurrentPendingAcknowledgementItem(PROGRESS.items[1], PROGRESS.items[1]),
    true,
  );
});
