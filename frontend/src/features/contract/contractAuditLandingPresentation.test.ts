import assert from "node:assert/strict";
import test from "node:test";
import { contractAuditLandingPresentation } from "./contractAuditLandingPresentation.ts";
import type {
  ContractAuditTaskItem,
  ContractAuditTaskPage,
  ContractAuditTaskSummary,
} from "./data/contractAuditTaskDataSource.ts";

function task(overrides: Partial<ContractAuditTaskItem> = {}): ContractAuditTaskItem {
  return {
    taskId: "task-real-001",
    contract: {
      id: "contract-real-001", contractNo: "HT-REAL-001", contractName: "真实合同甲",
      contractType: "GOODS", partyA: "甲方", partyB: "供应商甲", partyBCreditCode: null,
      contractAmount: 120000, amountInWords: null, status: "ACTIVE", versionNo: 4,
      createdAt: "2026-09-10T08:00:00.000Z", updatedAt: "2026-09-10T08:05:00.000Z",
    },
    project: { projectId: "project-real-001", projectName: "真实项目甲", projectNo: "XM-REAL-001" },
    taskStatus: "NEEDS_MANUAL_REVIEW",
    createdAt: "2026-09-10T08:10:00.000Z",
    startedAt: "2026-09-10T08:11:00.000Z",
    finishedAt: null,
    reviewProgress: { current: 3, total: 5, complete: false, pendingAcknowledgement: true },
    risk: { riskCount: 3, materialInsufficient: false, riskSummary: "高风险" },
    ...overrides,
  };
}

const summary: ContractAuditTaskSummary = {
  total: 17, pendingOrProcessing: 5, completed: 9, needsReview: 3,
};

const attentionPage: ContractAuditTaskPage = {
  items: [task()], page: 1, size: 6, total: 13, totalPages: 3,
};

const completedPage: ContractAuditTaskPage = {
  items: [task({
    taskId: "task-real-002",
    project: null,
    taskStatus: "DONE",
    reviewProgress: { current: 5, total: 5, complete: true, pendingAcknowledgement: false },
    risk: { riskCount: 0, materialInsufficient: false, riskSummary: "无风险" },
  })], page: 1, size: 6, total: 9, totalPages: 2,
};

test("contract audit landing preserves direct task identities and server totals", () => {
  const view = contractAuditLandingPresentation(summary, attentionPage, completedPage, 4);

  assert.deepEqual(view.summary, summary);
  assert.equal(view.attentionTotal, attentionPage.total);
  assert.equal(view.completedTotal, completedPage.total);
  assert.equal(view.todayCreatedTotal, 4);
  assert.equal(view.loadedRiskSummary.riskCount, 3);
  assert.equal(view.loadedRiskSummary.loadedTaskCount, 1);
  assert.equal(view.attention[0]?.taskId, "task-real-001");
  assert.equal(view.attention[0]?.contract.id, "contract-real-001");
  assert.equal(view.attention[0]?.contractNo, "HT-REAL-001");
  assert.deepEqual(view.attention[0]?.project, attentionPage.items[0]?.project);
  assert.equal(view.completed[0]?.project, null);
  assert.equal(view.attention[0]?.riskLabel, "高风险");
  assert.equal(view.attention[0]?.statusLabel, "待人工复核");
  assert.equal(view.attention[0]?.actionLabel, "查看并复核");
  assert.equal(view.completed[0]?.statusLabel, "审核完成");
  assert.equal(view.completed[0]?.actionLabel, "查看详情");
  assert.doesNotMatch(JSON.stringify(view), /showcase|mock|design/i);
});

test("unavailable attention collection preserves unknown risk facts while successful empty is zero", () => {
  const unavailable = contractAuditLandingPresentation(null, null, null, null);
  assert.deepEqual(unavailable.loadedRiskSummary, {
    scopeLabel: "当前已加载关注任务", loadedTaskCount: null, riskCount: null, materialInsufficientTaskCount: null,
  });
  const empty = contractAuditLandingPresentation(summary, { ...attentionPage, items: [], total: 0 }, completedPage, 0);
  assert.deepEqual(empty.loadedRiskSummary, {
    scopeLabel: "当前已加载关注任务", loadedTaskCount: 0, riskCount: 0, materialInsufficientTaskCount: 0,
  });
});

test("contract audit landing risk summary uses the loaded attention scope without double-counting overlap", () => {
  const durableDoneTask = task({
    taskId: "task-durable-done-001",
    taskStatus: "DONE",
    reviewProgress: { current: 5, total: 5, complete: true, pendingAcknowledgement: true },
    risk: { riskCount: 3, materialInsufficient: false, riskSummary: "高风险" },
  });
  const attention = { items: [durableDoneTask], page: 1, size: 6, total: 1, totalPages: 1 };
  const completed = { items: [durableDoneTask], page: 1, size: 6, total: 1, totalPages: 1 };

  const view = contractAuditLandingPresentation(summary, attention, completed, 0);

  assert.equal(view.loadedRiskSummary.scopeLabel, "当前已加载关注任务");
  assert.equal(view.loadedRiskSummary.loadedTaskCount, 1);
  assert.equal(view.loadedRiskSummary.riskCount, 3);
});

test("contract audit landing maps direct task states to actionable labels without approval claims", () => {
  const page = (item: ContractAuditTaskItem): ContractAuditTaskPage => ({
    items: [item], page: 1, size: 1, total: 1, totalPages: 1,
  });

  assert.equal(contractAuditLandingPresentation(summary, page(task({ taskStatus: "PENDING" })), completedPage, 0)
    .attention[0]?.actionLabel, "开始审核");
  assert.equal(contractAuditLandingPresentation(summary, page(task({ taskStatus: "FAILED" })), completedPage, 0)
    .attention[0]?.actionLabel, "开始审核");
  assert.equal(contractAuditLandingPresentation(summary, page(task({
    taskStatus: "PROCESSING", reviewProgress: { current: 2, total: 5, complete: false, pendingAcknowledgement: false },
  })), completedPage, 0).attention[0]?.actionLabel, "继续审核");
  assert.doesNotMatch(JSON.stringify(contractAuditLandingPresentation(summary, attentionPage, completedPage, 0)), /总体.*通过|整体.*通过|最终.*批准/);
});
