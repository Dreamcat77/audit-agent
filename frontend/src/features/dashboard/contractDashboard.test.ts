import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { contractDashboardPresentation } from "./contractDashboard.ts";

const task = {
  taskId: "task-real-1",
  contract: {
    id: "contract-real-1", contractNo: "HT-001", contractName: "智慧维修合同",
    contractType: "SERVICE", partyA: "采购人", partyB: "真实供应商",
    partyBCreditCode: "91350000REAL", contractAmount: 67000,
    amountInWords: null, status: "READY_FOR_REVIEW", versionNo: 1,
    createdAt: "2026-09-01T08:00:00Z", updatedAt: "2026-09-01T08:30:00Z",
  },
  project: { projectId: "project-real-1", projectName: "智慧维修项目", projectNo: "XM-001" },
  taskStatus: "DONE" as const,
  createdAt: "2026-09-01T09:00:00Z",
  startedAt: "2026-09-01T09:00:01Z",
  finishedAt: "2026-09-01T09:00:02Z",
  reviewProgress: { current: 6, total: 6, complete: true, pendingAcknowledgement: false },
  risk: { riskCount: 1, materialInsufficient: false, riskSummary: "低风险" },
};

test("dashboard presents only real contract task summary and recent durable rows", () => {
  const view = contractDashboardPresentation(
    { total: 7, pendingOrProcessing: 2, completed: 5, needsReview: 1 },
    [task],
  );

  assert.deepEqual(view.kpis, [
    { key: "TOTAL", label: "合同内审任务", value: 7 },
    { key: "PENDING", label: "待审核 / 审核中", value: 2 },
    { key: "COMPLETED", label: "审核完成", value: 5 },
    { key: "REVIEW", label: "需复核", value: 1 },
  ]);
  assert.equal(view.rows[0]?.projectName, "智慧维修项目");
  assert.equal(view.rows[0]?.contractName, "智慧维修合同");
  assert.equal(view.rows[0]?.supplier, "真实供应商");
  assert.equal(view.rows[0]?.statusLabel, "审核完成");
  assert.equal(view.rows[0]?.riskLabel, "建议复核");
  assert.equal(view.rows[0]?.taskId, "task-real-1");
});

test("dashboard never manufactures fallback metrics when real data is unavailable", () => {
  const view = contractDashboardPresentation(null, []);
  assert.deepEqual(view.kpis.map((item) => item.value), [null, null, null, null]);
  assert.deepEqual(view.rows, []);
});

test("dashboard customer copy describes business facts without storage implementation terms", () => {
  const page = readFileSync(new URL("./ContractDashboardPage.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(page, /数据库|DM8|API|requestId|Request ID/);
});
