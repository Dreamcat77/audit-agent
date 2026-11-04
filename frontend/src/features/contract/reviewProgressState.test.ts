import assert from "node:assert/strict";
import test from "node:test";
import {
  createFindingAcknowledgementOperationState,
  createSessionStorageFindingAcknowledgementOperationPersistence,
  deriveProgressiveReplayState,
} from "./reviewProgressState.ts";
import { createAcknowledgementControl } from "./reviewFindingPresentation.ts";
import type {
  FindingAcknowledgement,
  ReviewProgress,
  ReviewProgressItem,
} from "./types.ts";

const ACKNOWLEDGEMENT: FindingAcknowledgement = {
  id: "00000000-0000-0000-0000-000000000901",
  checkResultId: "result-risk-1",
  acknowledgedBy: "auditor-1",
  acknowledgedByDisplayName: "审核员甲",
  acknowledgedAt: "2026-08-25T09:10:00Z",
  requestId: "request-ack-1",
  traceId: "trace-ack-1",
  ruleVersion: 1,
};

function source(displayOrder = 0): ReviewProgressItem {
  return {
    type: "SOURCE_PREPARATION",
    checkResultId: null,
    title: "读取合同及比对来源",
    displayOrder,
    status: "DONE",
    risk: null,
    completedAt: "2026-08-25T09:00:00Z",
    acknowledgement: null,
  };
}

function finding({
  id,
  displayOrder,
  risk = "GREEN",
  acknowledgement = null,
}: {
  id: string;
  displayOrder: number;
  risk?: "GREEN" | "YELLOW" | "RED";
  acknowledgement?: FindingAcknowledgement | null;
}): ReviewProgressItem {
  return {
    type: "FINDING",
    checkResultId: id,
    title: `服务端标题 ${id}`,
    displayOrder,
    status: risk === "GREEN" ? "PASS" : risk === "YELLOW" ? "NOT_EVALUATED" : "FAIL",
    risk,
    completedAt: `2026-08-25T09:0${displayOrder}:00Z`,
    acknowledgement,
  };
}

function progress(items: ReviewProgressItem[]): ReviewProgress {
  return {
    total: items.length,
    items,
    riskSummary: "无风险",
  };
}

test("reconstructs refresh state from server order and pauses at the earliest unacknowledged non-green finding", () => {
  const durableProgress = progress([
    finding({
      id: "result-risk-2",
      displayOrder: 3,
      risk: "RED",
      acknowledgement: { ...ACKNOWLEDGEMENT, id: "ack-2", checkResultId: "result-risk-2" },
    }),
    finding({ id: "result-green", displayOrder: 1 }),
    source(),
    finding({ id: "result-risk-1", displayOrder: 2, risk: "YELLOW" }),
  ]);

  const state = deriveProgressiveReplayState(durableProgress);

  assert.deepEqual(
    state.orderedItems.map((item) => item.checkResultId),
    [null, "result-green", "result-risk-1", "result-risk-2"],
    "displayOrder from the server must win over arrival order and any local rule map",
  );
  assert.equal(state.total, 4);
  assert.equal(state.current, 3);
  assert.equal(state.completedPrefixCount, 2);
  assert.equal(state.pendingAcknowledgementItem?.checkResultId, "result-risk-1");
  assert.equal(state.complete, false);
  assert.equal(state.riskCount, 2);
  assert.equal(state.riskSummary, "低风险");
});

test("out-of-order later acknowledgement cannot skip an earlier unacknowledged risk", () => {
  const state = deriveProgressiveReplayState(progress([
    source(),
    finding({ id: "result-risk-1", displayOrder: 1, risk: "YELLOW" }),
    finding({
      id: "result-risk-2",
      displayOrder: 2,
      risk: "RED",
      acknowledgement: { ...ACKNOWLEDGEMENT, id: "ack-later", checkResultId: "result-risk-2" },
    }),
  ]));

  assert.equal(state.completedPrefixCount, 1);
  assert.equal(state.current, 2);
  assert.equal(state.pendingAcknowledgementItem?.checkResultId, "result-risk-1");
  assert.equal(state.complete, false);
});

test("no unacknowledged risk means the durable projection is complete", () => {
  const state = deriveProgressiveReplayState(progress([
    source(),
    finding({ id: "result-green", displayOrder: 1 }),
    finding({
      id: "result-risk-1",
      displayOrder: 2,
      risk: "RED",
      acknowledgement: ACKNOWLEDGEMENT,
    }),
  ]));

  assert.equal(state.completedPrefixCount, 3);
  assert.equal(state.current, 3);
  assert.equal(state.pendingAcknowledgementItem, null);
  assert.equal(state.complete, true);
});

test("derives exact durable risk-summary boundaries for zero, one, two, and three risks", () => {
  const cases = [
    { risks: [] as const, expected: "无风险" },
    { risks: ["YELLOW"] as const, expected: "低风险" },
    { risks: ["YELLOW", "RED"] as const, expected: "低风险" },
    { risks: ["YELLOW", "RED", "RED"] as const, expected: "高风险" },
  ];

  for (const { risks, expected } of cases) {
    const items = [
      source(),
      ...risks.map((risk, index) => finding({
        id: `risk-${index}`,
        displayOrder: index + 1,
        risk,
        acknowledgement: {
          ...ACKNOWLEDGEMENT,
          id: `ack-${index}`,
          checkResultId: `risk-${index}`,
        },
      })),
    ];
    const state = deriveProgressiveReplayState(progress(items));
    assert.equal(state.riskSummary, expected, `${risks.length} non-green findings`);
    assert.equal(state.riskCount, risks.length);
  }
});

test("uses dynamic 1 plus N totals without a fixed six-step or conclusion item", () => {
  for (const findingCount of [0, 1, 4, 7]) {
    const items = [
      source(),
      ...Array.from({ length: findingCount }, (_, index) => finding({
        id: `green-${index}`,
        displayOrder: index + 1,
      })),
    ];
    const state = deriveProgressiveReplayState(progress(items));
    assert.equal(state.total, 1 + findingCount);
    assert.equal(state.current, 1 + findingCount);
    assert.equal(state.orderedItems.some((item) => item.title === "生成审核结论"), false);
  }
});

test("shows the real acknowledgement control only for the earliest eligible pending risk", () => {
  const sourceItem = source();
  const greenItem = finding({ id: "green", displayOrder: 1 });
  const pendingRisk = finding({ id: "risk-pending", displayOrder: 2, risk: "YELLOW" });
  const laterRisk = finding({ id: "risk-later", displayOrder: 3, risk: "RED" });
  const state = deriveProgressiveReplayState(progress([
    sourceItem,
    greenItem,
    pendingRisk,
    laterRisk,
  ]));

  assert.equal(createAcknowledgementControl(sourceItem, state, null, null), null);
  assert.equal(createAcknowledgementControl(greenItem, state, null, null), null);
  assert.equal(createAcknowledgementControl(laterRisk, state, null, null), null);
  assert.deepEqual(createAcknowledgementControl(pendingRisk, state, null, null), {
    label: "我已知悉",
    busy: false,
    disabled: false,
    statusText: null,
  });
  assert.deepEqual(createAcknowledgementControl(
    pendingRisk,
    state,
    "risk-pending",
    null,
  ), {
    label: "我已知悉",
    busy: true,
    disabled: true,
    statusText: "正在提交复核提示知悉…",
  });
  assert.equal(
    createAcknowledgementControl(pendingRisk, state, null, "网络异常")?.statusText,
    "网络异常",
  );
});

test("rejects acknowledgement controls for inconsistent or already acknowledged projections", () => {
  const inconsistent = {
    ...finding({ id: "risk-inconsistent", displayOrder: 1, risk: "RED" }),
    status: "NOT_EVALUATED" as const,
  };
  const acknowledged = finding({
    id: "risk-acknowledged",
    displayOrder: 2,
    risk: "RED",
    acknowledgement: {
      ...ACKNOWLEDGEMENT,
      checkResultId: "risk-acknowledged",
    },
  });
  const inconsistentState = deriveProgressiveReplayState(progress([source(), inconsistent]));
  const acknowledgedState = deriveProgressiveReplayState(progress([source(), acknowledged]));

  assert.equal(createAcknowledgementControl(inconsistent, inconsistentState, null, null), null);
  assert.equal(createAcknowledgementControl(acknowledged, acknowledgedState, null, null), null);
});

test("acknowledgement changes replay progress but never risk count or summary", () => {
  const pending = progress([
    source(),
    finding({ id: "risk", displayOrder: 1, risk: "RED" }),
  ]);
  const acknowledged = progress([
    source(),
    finding({
      id: "risk",
      displayOrder: 1,
      risk: "RED",
      acknowledgement: { ...ACKNOWLEDGEMENT, checkResultId: "risk" },
    }),
  ]);

  const before = deriveProgressiveReplayState(pending);
  const after = deriveProgressiveReplayState(acknowledged);
  assert.equal(before.complete, false);
  assert.equal(after.complete, true);
  assert.deepEqual(
    { riskCount: after.riskCount, riskSummary: after.riskSummary },
    { riskCount: before.riskCount, riskSummary: before.riskSummary },
  );
});

test("retains one immutable per-result operation key until a GET projection proves acknowledgement", () => {
  let sequence = 0;
  const operations = createFindingAcknowledgementOperationState({
    createIdempotencyKey: () => `ack-ui-${++sequence}`,
  });

  const first = operations.keyFor("result-risk-1");
  assert.equal(operations.keyFor("result-risk-1"), first);
  operations.reconcile(progress([source(), finding({
    id: "result-risk-1",
    displayOrder: 1,
    risk: "YELLOW",
  })]));
  assert.equal(operations.retainedKey("result-risk-1"), first);

  operations.reconcile(progress([source(), finding({
    id: "result-risk-1",
    displayOrder: 1,
    risk: "YELLOW",
    acknowledgement: ACKNOWLEDGEMENT,
  })]));
  assert.equal(operations.retainedKey("result-risk-1"), null);
});

test("reloads the same per-result key from session persistence after workspace reconstruction", () => {
  const values = new Map<string, string>();
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); },
  } as Storage;
  const persistence = createSessionStorageFindingAcknowledgementOperationPersistence(
    "auditor-1",
    "contract-1",
    storage,
  );
  let sequence = 0;
  const firstWorkspace = createFindingAcknowledgementOperationState({
    createIdempotencyKey: () => `ack-ui-${++sequence}`,
    persistence,
  });
  const key = firstWorkspace.keyFor("result-risk-1");

  const reconstructedWorkspace = createFindingAcknowledgementOperationState({
    createIdempotencyKey: () => `ack-ui-${++sequence}`,
    persistence,
  });
  assert.equal(reconstructedWorkspace.keyFor("result-risk-1"), key);
  assert.equal(sequence, 1, "refresh must not silently create a second operation identity");
});

test("a mismatched acknowledgement identity is not durable proof and cannot clear the operation key", () => {
  const operations = createFindingAcknowledgementOperationState({
    createIdempotencyKey: () => "ack-ui-retained",
  });
  operations.keyFor("result-risk-1");
  const mismatched = progress([source(), finding({
    id: "result-risk-1",
    displayOrder: 1,
    risk: "RED",
    acknowledgement: { ...ACKNOWLEDGEMENT, checkResultId: "different-result" },
  })]);

  operations.reconcile(mismatched);
  assert.equal(operations.retainedKey("result-risk-1"), "ack-ui-retained");
  assert.equal(deriveProgressiveReplayState(mismatched).pendingAcknowledgementItem?.checkResultId, "result-risk-1");
});
