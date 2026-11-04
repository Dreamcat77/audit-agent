import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";
import type {
  Finding,
  FindingAcknowledgement,
  ReviewProgress,
  ReviewProgressItem,
  ReviewResult,
} from "./types.ts";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
});

const {
  buildReviewHistoryTimeline,
  formatReviewFactTime,
  reviewHistoryAccessPresentation,
} = await import("./reviewHistoryViewModel.ts").finally(() => hooks.deregister());

const SHARED_TIME = "2026-08-25T09:00:00Z";

function acknowledgement(
  id: string,
  checkResultId: string,
  actorDisplayName: string,
  acknowledgedAt = SHARED_TIME,
): FindingAcknowledgement {
  return {
    id,
    checkResultId,
    acknowledgedBy: `actor-${id}`,
    acknowledgedByDisplayName: actorDisplayName,
    acknowledgedAt,
    requestId: `request-${id}`,
    traceId: `trace-${id}`,
    ruleVersion: 1,
  };
}

function finding(id: string, detail: string, createdAt = SHARED_TIME): Finding {
  return {
    id,
    ruleCode: "F02_AWARD_AMOUNT_MATCH",
    itemKey: null,
    checkStatus: "FAIL",
    riskLevel: "RED",
    blockSign: true,
    actualValue: "128000",
    expectedValue: "120000",
    detail,
    ruleVersion: 1,
    createdAt,
  };
}

function progressItem(
  resultId: string,
  displayOrder: number,
  ack: FindingAcknowledgement | null,
): ReviewProgressItem {
  return {
    type: "FINDING",
    checkResultId: resultId,
    title: `服务端标题 ${resultId}`,
    displayOrder,
    status: "FAIL",
    risk: "RED",
    completedAt: "2099-01-01T00:00:00Z",
    acknowledgement: ack,
  };
}

function projection(items: ReviewProgressItem[]): ReviewProgress {
  return { total: items.length, items, riskSummary: "低风险" };
}

function result(findings: Finding[]): ReviewResult {
  return {
    taskId: "task-history",
    status: "DONE",
    reviewType: "F02_DETERMINISTIC",
    overallRisk: "RED",
    blockSign: true,
    replayed: false,
    findings,
  };
}

test("history access distinguishes loading and read failure from an empty history", () => {
  assert.deepEqual(reviewHistoryAccessPresentation(true, null, null), {
    state: "LOADING",
    message: "正在读取审核记录…",
  });
  assert.deepEqual(reviewHistoryAccessPresentation(false, "progress unavailable", null), {
    state: "UNAVAILABLE",
    message: "审核记录暂时无法读取，请稍后重试。",
  });
  assert.deepEqual(reviewHistoryAccessPresentation(false, null, projection([])), {
    state: "READY",
    message: null,
  });
  assert.deepEqual(reviewHistoryAccessPresentation(false, null, null), {
    state: "UNAVAILABLE",
    message: "审核记录暂时无法读取，请稍后重试。",
  });
});

test("merges source, multiple result, and multiple acknowledgement facts with deterministic equal-time tie breaks", () => {
  const progress = projection([
    progressItem("result-b", 2, acknowledgement("ack-b", "result-b", "审核员乙")),
    {
      type: "SOURCE_PREPARATION",
      checkResultId: null,
      title: "读取合同及比对来源",
      displayOrder: 0,
      status: "DONE",
      risk: null,
      completedAt: SHARED_TIME,
      acknowledgement: null,
    },
    progressItem("result-a", 2, acknowledgement("ack-a", "result-a", "审核员甲")),
  ]);

  const events = buildReviewHistoryTimeline(
    progress,
    result([finding("result-b", "风险事实乙"), finding("result-a", "风险事实甲")]),
  );

  assert.deepEqual(
    events.map((event) => event.key),
    [
      "source-preparation:0",
      "finding-result:result-a",
      "finding-result:result-b",
      "finding-acknowledgement:ack-a",
      "finding-acknowledgement:ack-b",
    ],
    "equal times sort by displayOrder, event type, then immutable fact ID",
  );
  assert.equal(events[0]?.title, "资料读取与来源核对");
  assert.equal(events[1]?.timeLabel, "核对完成时间");
  assert.equal(events[1]?.detail, "合同金额与中标金额不一致。");
  assert.equal(events[3]?.actorDisplayName, "审核员甲");
  assert.equal(events[3]?.timeLabel, "确认时间");
});

test("uses finding createdAt rather than the progress item timestamp for result history", () => {
  const progress = projection([
    progressItem("result-real-time", 1, null),
  ]);
  const events = buildReviewHistoryTimeline(
    progress,
    result([finding("result-real-time", "真实结果明细", "2026-08-25T08:30:00Z")]),
  );

  assert.equal(events[0]?.occurredAt, "2026-08-25T08:30:00Z");
  assert.notEqual(events[0]?.occurredAt, progress.items[0]?.completedAt);
  assert.equal(events[0]?.detail, "合同金额与中标金额不一致。");
});

test("uses code-unit ordinal fact IDs as the final equal-time tie break", () => {
  const resultA = {
    ...finding("result-a", "lowercase fact"),
    checkStatus: "PASS" as const,
    riskLevel: "GREEN" as const,
    blockSign: false,
  };
  const resultZ = {
    ...finding("result-Z", "uppercase fact"),
    checkStatus: "PASS" as const,
    riskLevel: "GREEN" as const,
    blockSign: false,
  };
  const events = buildReviewHistoryTimeline(
    projection([
      { ...progressItem("result-a", 1, null), status: "PASS", risk: "GREEN" },
      { ...progressItem("result-Z", 1, null), status: "PASS", risk: "GREEN" },
    ]),
    result([resultA, resultZ]),
  );

  assert.deepEqual(
    events.map((event) => event.key),
    ["finding-result:result-Z", "finding-result:result-a"],
    "code-unit Z precedes a regardless of the host locale collation",
  );
});

test("renders explicit neutral time fallback without inventing a source timestamp", () => {
  const progress = projection([{
    type: "SOURCE_PREPARATION",
    checkResultId: null,
    title: "读取合同及比对来源",
    displayOrder: 0,
    status: "DONE",
    risk: null,
    completedAt: null,
    acknowledgement: null,
  }]);

  const [source] = buildReviewHistoryTimeline(progress, result([]));
  assert.equal(source?.title, "资料读取与来源核对");
  assert.equal(source?.occurredAt, null);
  assert.equal(source?.timeText, "暂无可靠时间");
  assert.equal(formatReviewFactTime(null), "暂无可靠时间");
  assert.equal(formatReviewFactTime("not-a-time"), "暂无可靠时间");
});

test("ignores unmatched result data instead of inventing timeline facts", () => {
  const events = buildReviewHistoryTimeline(
    projection([progressItem("progress-result", 1, null)]),
    result([finding("unmatched-result", "不应进入记录")]),
  );

  assert.deepEqual(events, []);
});

test("history omits later result facts until refreshed progress proves the preceding acknowledgement", () => {
  const source: ReviewProgressItem = {
    type: "SOURCE_PREPARATION",
    checkResultId: null,
    title: "读取合同及比对来源",
    displayOrder: 0,
    status: "DONE",
    risk: null,
    completedAt: "2026-08-25T09:00:00Z",
    acknowledgement: null,
  };
  const firstRisk = finding("history-risk-first", "visible first risk detail", "2026-08-25T09:01:00Z");
  const laterGreen: Finding = {
    ...finding("history-green-later", "hidden later green detail", "2026-08-25T09:02:00Z"),
    checkStatus: "PASS",
    riskLevel: "GREEN",
    blockSign: false,
  };
  const laterRisk = finding("history-risk-later", "hidden later risk detail", "2026-08-25T09:03:00Z");
  const pending = projection([
    source,
    { ...progressItem(firstRisk.id, 1, null), title: "first risk title" },
    {
      ...progressItem(laterGreen.id, 2, null),
      title: "hidden later green title",
      status: "PASS",
      risk: "GREEN",
    },
    { ...progressItem(laterRisk.id, 3, null), title: "hidden later risk title" },
  ]);
  const reviewResult = result([laterRisk, laterGreen, firstRisk]);

  const before = buildReviewHistoryTimeline(pending, reviewResult);
  assert.deepEqual(
    before.map((event) => event.key),
    ["source-preparation:0", `finding-result:${firstRisk.id}`],
  );
  assert.doesNotMatch(JSON.stringify(before), /hidden later green|hidden later risk/);

  const firstAcknowledgement = acknowledgement(
    "history-ack-first",
    firstRisk.id,
    "审核员甲",
    "2026-08-25T09:01:30Z",
  );
  const refreshed = {
    ...pending,
    items: pending.items.map((item) => item.checkResultId === firstRisk.id
      ? { ...item, acknowledgement: firstAcknowledgement }
      : item),
  };
  const after = buildReviewHistoryTimeline(refreshed, reviewResult);
  assert.deepEqual(
    after.map((event) => event.key),
    [
      "source-preparation:0",
      `finding-result:${firstRisk.id}`,
      `finding-acknowledgement:${firstAcknowledgement.id}`,
      `finding-result:${laterGreen.id}`,
      `finding-result:${laterRisk.id}`,
    ],
  );
  assert.equal(after[2]?.actorDisplayName, "审核员甲");
  assert.equal(after[2]?.occurredAt, "2026-08-25T09:01:30Z");
  assert.equal(after[3]?.detail, "合同金额与中标金额一致。");
  assert.equal(after[4]?.detail, "合同金额与中标金额不一致。");
  assert.doesNotMatch(JSON.stringify(after), /hidden later green|hidden later risk/);
});
