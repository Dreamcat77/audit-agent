import assert from "node:assert/strict";
import test from "node:test";
import { loadPersistedRiskAssessment } from "./riskAssessmentProjection.ts";

const baseTask = {
  taskId: "task-1",
  contract: { id: "contract-1", contractNo: "HT-1", contractName: "合同一", contractType: "SERVICE",
    partyA: "甲方", partyB: "乙方", partyBCreditCode: null, contractAmount: 100, amountInWords: null,
    status: "ACTIVE", versionNo: 1, createdAt: "2026-09-28T00:00:00Z", updatedAt: "2026-09-28T00:00:00Z" },
  project: { projectId: "project-1", projectName: "项目一", projectNo: "XM-1" },
  taskStatus: "NEEDS_MANUAL_REVIEW",
  createdAt: "2026-09-28T00:00:00Z", startedAt: null, finishedAt: null,
  reviewProgress: { current: 3, total: 5, complete: false, pendingAcknowledgement: false },
  risk: { riskCount: 2, materialInsufficient: false, riskSummary: "ATTENTION" },
  reviewRun: { kind: "INTEGRATED", projectId: "project-1", runId: "run-exact-1", status: "PARTIALLY_COMPLETED" },
} as any;

test("loads persisted review and insufficiency records and deduplicates by durable task id", async () => {
  const calls: any[] = [];
  const insufficient = { ...baseTask, risk: { ...baseTask.risk, materialInsufficient: true } };
  const source = {
    async list(input: any) {
      calls.push(input);
      return { items: input.risk === "REVIEW" ? [baseTask] : [insufficient], page: 1, size: 100, total: 1, totalPages: 1 };
    },
  } as any;

  const result = await loadPersistedRiskAssessment(source, new AbortController().signal);

  assert.deepEqual(calls.map((call) => ({ risk: call.risk, includeHistory: call.includeHistory })), [
    { risk: "REVIEW", includeHistory: true },
    { risk: "INSUFFICIENT", includeHistory: true },
  ]);
  assert.equal(result.items.length, 1);
  assert.equal(result.reviewRequiredCount, 1);
  assert.equal(result.materialInsufficientCount, 1);
  assert.equal(result.items[0].task.reviewRun.runId, "run-exact-1");
  assert.match(result.scopeLabel, /当前用户可访问.*持久化/);
});

test("does not invent a risk score and marks unfinished coverage explicitly", async () => {
  const source = {
    async list(input: any) {
      return { items: input.risk === "REVIEW" ? [baseTask] : [], page: 1, size: 100, total: 1, totalPages: 1 };
    },
  } as any;

  const result = await loadPersistedRiskAssessment(source, new AbortController().signal);

  assert.deepEqual(result.items[0].coverage, {
    label: "已覆盖 3/5 项",
    unassessed: true,
  });
  assert.equal("score" in result.items[0], false);
});

test("loads all pages within each persisted risk filter", async () => {
  const second = { ...baseTask, taskId: "task-2", reviewRun: { ...baseTask.reviewRun, runId: "run-2" } };
  const calls: Array<{ risk: string; page: number }> = [];
  const source = {
    async list(input: any) {
      calls.push({ risk: input.risk, page: input.page });
      if (input.risk === "REVIEW") return { items: input.page === 1 ? [baseTask] : [second], page: input.page, size: 100, total: 2, totalPages: 2 };
      return { items: [], page: 1, size: 100, total: 0, totalPages: 0 };
    },
  } as any;

  const result = await loadPersistedRiskAssessment(source, new AbortController().signal);

  assert.deepEqual(calls, [
    { risk: "REVIEW", page: 1 }, { risk: "INSUFFICIENT", page: 1 }, { risk: "REVIEW", page: 2 },
  ]);
  assert.deepEqual(result.items.map((item) => item.task.taskId), ["task-1", "task-2"]);
});
