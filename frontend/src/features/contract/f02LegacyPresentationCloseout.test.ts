import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import * as presentationModule from "./reviewFindingPresentation.ts";
import { locateProjectMaterialText } from "./projectMaterialViewerPresentation.ts";
import type {
  Contract,
  Finding,
  ReviewProgress,
  ReviewProgressItem,
} from "./types.ts";

type CloseoutPresentationModule = typeof presentationModule & {
  createFindingComparisonPresentation(
    finding: Finding,
    contract: Contract | null,
    options?: { projectedSourceAvailable?: boolean },
  ): { cards: Array<{ label: string; value: string; detail?: string }> };
  deriveRiskAcknowledgementPresentation(
    progress: ReviewProgress,
    pendingCheckResultId: string | null,
  ): { total: number; current: number | null; remaining: number; statusText: string | null };
  nextExpandedFindingId(
    current: string | null,
    previousPendingCheckResultId: string | null,
    pendingCheckResultId: string | null,
  ): string | null;
  handleAcknowledgementActionClick(
    event: { stopPropagation(): void },
    findingId: string,
    acknowledge: (findingId: string) => void,
  ): void;
};

const closeout = presentationModule as CloseoutPresentationModule;

const BASE_FINDING: Finding = {
  id: "finding-1",
  ruleCode: "F02_AMOUNT_WORDS_MATCH",
  itemKey: null,
  checkStatus: "NOT_EVALUATED",
  riskLevel: "YELLOW",
  blockSign: false,
  actualValue: null,
  expectedValue: null,
  detail: "Amount in words is missing or invalid",
  ruleVersion: 1,
  createdAt: "2026-09-03T00:00:00Z",
};

const CONTRACT: Contract = {
  id: "contract-1",
  contractNo: "PM44-05",
  contractName: "合成测试合同",
  contractType: "PROCUREMENT",
  partyA: "甲方公司",
  partyB: "合同乙方公司",
  partyBCreditCode: "CREDIT-CONTRACT",
  contractAmount: 407000,
  amountInWords: "人民币肆拾万整",
  status: "DRAFT",
  versionNo: 1,
  createdAt: "2026-09-03T00:00:00Z",
  updatedAt: "2026-09-03T00:00:00Z",
  lineItems: [
    {
      id: "line-desktop",
      itemKey: "DESKTOP",
      itemName: "桌面终端",
      quantity: 100,
      unitPrice: 5000,
      declaredAmount: 490000,
      sortNo: 1,
    },
    {
      id: "line-laptop",
      itemKey: "LAPTOP",
      itemName: "便携终端",
      quantity: 20,
      unitPrice: 7000,
      declaredAmount: 140000,
      sortNo: 2,
    },
  ],
  awardNotice: {
    id: "award-1",
    noticeNo: "AWARD-1",
    projectName: "合成项目",
    winningBidder: "中标供应商公司",
    winningBidderCreditCode: "CREDIT-AWARD",
    winningAmount: 400000,
    winningDate: "2026-08-01",
    sourceRef: null,
  },
};

function progressItem({
  id,
  order,
  status,
  risk,
  acknowledged = false,
}: {
  id: string;
  order: number;
  status: ReviewProgressItem["status"];
  risk: ReviewProgressItem["risk"];
  acknowledged?: boolean;
}): ReviewProgressItem {
  return {
    type: "FINDING",
    checkResultId: id,
    title: id,
    displayOrder: order,
    status,
    risk,
    completedAt: "2026-09-03T00:00:00Z",
    acknowledgement: acknowledged ? {
      id: `ack-${id}`,
      checkResultId: id,
      acknowledgedBy: "auditor-1",
      acknowledgedByDisplayName: "审核员甲",
      acknowledgedAt: "2026-09-03T00:01:00Z",
      requestId: `request-${id}`,
      traceId: `trace-${id}`,
      ruleVersion: 1,
    } : null,
  };
}

test("amount words with a projected source is presented as format review, not missing material", () => {
  assert.deepEqual(
    presentationModule.findingBusinessStatus(BASE_FINDING, { projectedSourceAvailable: true }),
    { label: "格式待复核", prompt: "请人工复核", tone: "ATTENTION" },
  );
  assert.equal(
    presentationModule.reviewFindingDetailPresentation(BASE_FINDING, { projectedSourceAvailable: true }),
    "已定位金额大写原文，但当前格式无法由系统可靠解析，请人工复核。",
  );
});

test("amount words without a projected source remains material insufficient", () => {
  assert.deepEqual(
    presentationModule.findingBusinessStatus(BASE_FINDING, { projectedSourceAvailable: false }),
    { label: "材料不足", prompt: "暂无法判断", tone: "NEUTRAL" },
  );
  assert.match(
    presentationModule.reviewFindingDetailPresentation(BASE_FINDING, { projectedSourceAvailable: false }),
    /缺少可靠的金额大写信息/,
  );
});

test("format review is counted separately from material insufficiency", () => {
  const progress: ReviewProgress = {
    total: 1,
    riskSummary: "低风险",
    items: [progressItem({ id: BASE_FINDING.id, order: 1, status: "NOT_EVALUATED", risk: "YELLOW" })],
  };
  assert.deepEqual(
    presentationModule.reviewSummaryBlocks(progress, {
      formatReviewFindingIds: new Set([BASE_FINDING.id]),
    }),
    [
      { label: "一致项", value: 0 },
      { label: "需复核项", value: 0 },
      { label: "格式待复核项", value: 1 },
      { label: "材料不足项", value: 0 },
    ],
  );
});

test("collapsed pending finding keeps a visible sibling acknowledgement action without nested buttons", () => {
  const source = readFileSync(new URL("./components/F02ReviewResultPanel.tsx", import.meta.url), "utf8");
  const toggleStart = source.indexOf('data-testid="finding-accordion-toggle"');
  const toggleEnd = source.indexOf("</button>", toggleStart);
  const action = source.indexOf('data-testid="finding-acknowledgement-action"');
  const expandedBody = source.indexOf("{open && (");
  assert.ok(toggleStart >= 0, "accordion toggle must be explicit");
  assert.ok(toggleEnd > toggleStart, "accordion toggle must close");
  assert.ok(action > toggleEnd, "acknowledgement action must be a sibling, not a nested button");
  assert.ok(action < expandedBody, "pending acknowledgement action must remain visible while collapsed");
});

test("acknowledgement click stops propagation and invokes only the acknowledgement action", () => {
  let stopped = 0;
  const acknowledged: string[] = [];
  closeout.handleAcknowledgementActionClick(
    { stopPropagation: () => { stopped += 1; } },
    "risk-1",
    (findingId) => acknowledged.push(findingId),
  );
  assert.equal(stopped, 1);
  assert.deepEqual(acknowledged, ["risk-1"]);
});

test("a new pending result becomes the auto-expanded finding", () => {
  assert.equal(closeout.nextExpandedFindingId("risk-1", "risk-1", "risk-2"), "risk-2");
});

test("manual collapse of the same pending result is preserved across renders", () => {
  assert.equal(closeout.nextExpandedFindingId(null, "risk-1", "risk-1"), null);
});

test("risk acknowledgement X/N excludes source preparation and PASS findings", () => {
  const progress: ReviewProgress = {
    total: 5,
    riskSummary: "低风险",
    items: [
      {
        type: "SOURCE_PREPARATION",
        checkResultId: null,
        title: "读取材料",
        displayOrder: 0,
        status: "DONE",
        risk: null,
        completedAt: "2026-09-03T00:00:00Z",
        acknowledgement: null,
      },
      progressItem({ id: "pass-1", order: 1, status: "PASS", risk: "GREEN" }),
      progressItem({ id: "risk-1", order: 2, status: "FAIL", risk: "RED", acknowledged: true }),
      progressItem({ id: "risk-2", order: 3, status: "NOT_EVALUATED", risk: "YELLOW" }),
      progressItem({ id: "risk-3", order: 4, status: "FAIL", risk: "RED" }),
    ],
  };
  assert.deepEqual(closeout.deriveRiskAcknowledgementPresentation(progress, "risk-2"), {
    total: 3,
    current: 2,
    remaining: 2,
    statusText: "当前第 2/3 项复核提示 · 还有 2 项需要逐项知悉",
  });
});

test("line-item presentation explains quantity times unit price while preserving raw finding authority", () => {
  const finding: Finding = {
    ...BASE_FINDING,
    id: "desktop-risk",
    ruleCode: "F02_LINE_ITEM_ARITHMETIC",
    itemKey: "DESKTOP",
    checkStatus: "FAIL",
    riskLevel: "RED",
    actualValue: "490000",
    expectedValue: "500000",
  };
  const raw = structuredClone(finding);
  assert.deepEqual(closeout.createFindingComparisonPresentation(finding, CONTRACT).cards, [
    { label: "数量 × 单价计算金额", value: "¥500,000.00", detail: "100 × ¥5,000.00" },
    { label: "申报金额", value: "¥490,000.00" },
  ]);
  assert.deepEqual(finding, raw);
});

test("line-sum presentation uses declared sum 630000 against contract total 407000", () => {
  const finding: Finding = {
    ...BASE_FINDING,
    id: "line-sum-risk",
    ruleCode: "F02_LINE_SUM_MATCH",
    checkStatus: "FAIL",
    riskLevel: "RED",
    actualValue: "630000",
    expectedValue: "407000",
  };
  assert.deepEqual(closeout.createFindingComparisonPresentation(finding, CONTRACT).cards, [
    { label: "分项申报金额合计", value: "¥630,000.00" },
    { label: "合同总金额", value: "¥407,000.00" },
  ]);
});

test("supplier presentation uses loaded contract names without rewriting MATCH/DIFFERENT raw values", () => {
  const finding: Finding = {
    ...BASE_FINDING,
    id: "supplier-risk",
    ruleCode: "F02_AWARD_SUPPLIER_MATCH",
    checkStatus: "FAIL",
    riskLevel: "RED",
    actualValue: "DIFFERENT",
    expectedValue: "MATCH",
  };
  const raw = structuredClone(finding);
  assert.deepEqual(closeout.createFindingComparisonPresentation(finding, CONTRACT).cards, [
    { label: "合同乙方", value: "合同乙方公司" },
    { label: "中标供应商", value: "中标供应商公司" },
  ]);
  assert.deepEqual(finding, raw);
});

test("format-review amount words does not render duplicate empty comparison cards", () => {
  assert.deepEqual(closeout.createFindingComparisonPresentation(
    BASE_FINDING,
    CONTRACT,
    { projectedSourceAvailable: true },
  ).cards, []);
});

test("ambiguous excerpt never highlights the first occurrence", () => {
  const location = locateProjectMaterialText(
    "金额 40000.00\n其他条款\n金额 40000.00",
    { excerpt: "40000.00", paragraphIndex: null },
  );
  assert.equal(location.kind, "AMBIGUOUS");
  assert.equal(location.statusText, "已打开来源材料，但存在多个相同值，无法唯一高亮。");
  assert.equal("exactMatch" in location, false);
});

