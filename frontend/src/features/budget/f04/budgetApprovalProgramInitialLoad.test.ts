import assert from "node:assert/strict";
import test from "node:test";

const initialLoad = await import("./budgetApprovalProgramInitialLoad.ts").catch(() => ({}));

test("core review and rules become usable without waiting for secondary history", async () => {
  assert.equal(typeof initialLoad.startBudgetApprovalProgramInitialLoad, "function");
  let rejectHistory: ((reason: Error) => void) | undefined;
  const pendingHistory = new Promise<string[]>((_, reject) => { rejectHistory = reject; });
  const events: string[] = [];
  let coreData: { review: string; rules: string } | null = null;
  let markCoreLoaded: (() => void) | undefined;
  const coreLoaded = new Promise<void>((resolve) => { markCoreLoaded = resolve; });

  const completed = initialLoad.startBudgetApprovalProgramInitialLoad(
    {
      loadReview: async () => "durable-review",
      loadRules: async () => "database-rules",
      loadHistory: () => pendingHistory,
    },
    {
      onCoreLoaded: (review: string, rules: string) => {
        coreData = { review, rules };
        events.push("core-loaded");
        markCoreLoaded?.();
      },
      onCoreError: () => events.push("core-error"),
      onHistoryLoaded: () => events.push("history-loaded"),
      onHistoryWarning: () => events.push("history-warning"),
    },
  );

  await coreLoaded;
  assert.deepEqual(coreData, { review: "durable-review", rules: "database-rules" });
  assert.deepEqual(events, ["core-loaded"]);

  rejectHistory?.(new Error("history temporarily unavailable"));
  await completed;
  assert.deepEqual(coreData, { review: "durable-review", rules: "database-rules" });
  assert.deepEqual(events, ["core-loaded", "history-warning"]);
});

test("core load failures stay fatal even when secondary history is available", async () => {
  assert.equal(typeof initialLoad.startBudgetApprovalProgramInitialLoad, "function");
  const events: string[] = [];

  await initialLoad.startBudgetApprovalProgramInitialLoad(
    {
      loadReview: async () => { throw new Error("review unavailable"); },
      loadRules: async () => "database-rules",
      loadHistory: async () => ["historical-validation"],
    },
    {
      onCoreLoaded: () => events.push("core-loaded"),
      onCoreError: () => events.push("core-error"),
      onHistoryLoaded: () => events.push("history-loaded"),
      onHistoryWarning: () => events.push("history-warning"),
    },
  );

  assert.deepEqual(events.sort(), ["core-error", "history-loaded"]);
});
