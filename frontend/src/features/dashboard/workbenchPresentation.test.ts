import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import { build } from "esbuild";

import { workbenchPresentation } from "./workbenchPresentation.ts";

const safeTask = {
  taskId: "safe-task",
  contract: {
    id: "contract-safe", contractNo: "HT-2026-001", contractName: "办公设备采购合同",
    contractType: "GOODS", partyA: "采购单位", partyB: "供应商甲", partyBCreditCode: null,
    contractAmount: 86000, amountInWords: null, status: "READY_FOR_REVIEW", versionNo: 1,
    createdAt: "2026-09-10T08:00:00Z", updatedAt: "2026-09-10T08:00:00Z",
  },
  project: { projectId: "project-safe", projectName: "办公设备更新项目", projectNo: "XM-2026-001" },
  taskStatus: "PROCESSING" as const,
  createdAt: "2026-09-10T08:01:00Z",
  startedAt: "2026-09-10T08:02:00Z",
  finishedAt: null,
  reviewProgress: { current: 2, total: 5, complete: false, pendingAcknowledgement: false },
  risk: { riskCount: 0, materialInsufficient: false, riskSummary: "无风险" as const },
};

const attentionTask = {
  ...safeTask,
  taskId: "attention-task",
  contract: { ...safeTask.contract, id: "contract-attention", contractNo: "HT-2026-002", contractName: "设备维护服务合同" },
  project: { projectId: "project-attention", projectName: "设备维护项目", projectNo: "XM-2026-002" },
  taskStatus: "DONE" as const,
  createdAt: "2026-09-10T09:00:00Z",
  startedAt: "2026-09-10T09:01:00Z",
  finishedAt: "2026-09-10T09:02:00Z",
  reviewProgress: { current: 5, total: 5, complete: true, pendingAcknowledgement: true },
  risk: { riskCount: 1, materialInsufficient: false, riskSummary: "低风险" as const },
};

const completedTask = {
  ...safeTask,
  taskId: "completed-task",
  contract: { ...safeTask.contract, id: "contract-completed", contractNo: "HT-2026-000", contractName: "已完成的无风险合同" },
  project: { projectId: "project-completed", projectName: "已完成项目", projectNo: "XM-2026-000" },
  taskStatus: "DONE" as const,
  createdAt: "2026-09-10T07:00:00Z",
  startedAt: "2026-09-10T07:01:00Z",
  finishedAt: "2026-09-10T07:02:00Z",
  reviewProgress: { current: 5, total: 5, complete: true, pendingAcknowledgement: false },
  risk: { riskCount: 0, materialInsufficient: false, riskSummary: "无风险" as const },
};

test("workbench maps only direct F02 summary, attention, progress and durable activity facts", () => {
  const view = workbenchPresentation(
    { total: 7, pendingOrProcessing: 2, completed: 5, needsReview: 1 },
    [completedTask, safeTask, attentionTask],
    3,
  );

  assert.equal(view.attention?.taskId, "attention-task");
  assert.notEqual(view.attention?.taskId, "completed-task");
  assert.equal(view.today.createdTotal, 3);
  assert.deepEqual(view.stats.map((item) => item.value), [7, 2, 5, 1]);
  assert.deepEqual(view.pending.map((item) => [item.taskId, item.statusLabel, item.progress]), [
    ["safe-task", "审核中", "2 / 5"],
    ["attention-task", "待确认", "5 / 5"],
  ]);
  assert.equal(view.pending.some((item) => item.taskId === "completed-task"), false);
  assert.deepEqual(view.recent.map((item) => [item.taskId, item.contractName, item.projectName, item.occurredAt]), [
    ["completed-task", "已完成的无风险合同", "已完成项目", "2026-09-10T07:02:00Z"],
    ["safe-task", "办公设备采购合同", "办公设备更新项目", "2026-09-10T08:02:00Z"],
    ["attention-task", "设备维护服务合同", "设备维护项目", "2026-09-10T09:02:00Z"],
  ]);
  assert.doesNotMatch(JSON.stringify(view), /528000|设计稿项目/);
});

test("workbench preserves unavailable summary values and does not manufacture today completions", () => {
  const view = workbenchPresentation(null, [], 3);

  assert.deepEqual(view.stats.map((item) => item.value), [null, null, null, null]);
  assert.deepEqual(view.pending, []);
  assert.deepEqual(view.recent, []);
  assert.deepEqual(view.today, { createdTotal: 3 });
});

async function loadWorkbenchHarness(): Promise<{
  load: (dataSource: object) => Promise<object>;
  render: (props: object) => string;
}> {
  const bundled = await build({
    bundle: true,
    define: {
      "import.meta.env.DEV": "false",
      "import.meta.env.VITE_API_BASE_URL": '""',
      "import.meta.env.VITE_AUTH_MODE": '""',
    },
    format: "cjs",
    jsx: "automatic",
    loader: { ".css": "empty" },
    platform: "node",
    write: false,
    stdin: {
      contents: `
        import React from "react";
        import { renderToStaticMarkup } from "react-dom/server";
        import {
          DeliveryWorkbenchContent,
          loadDeliveryWorkbench,
        } from "./src/features/dashboard/DeliveryWorkbenchPage.tsx";

        export function load(dataSource) {
          return loadDeliveryWorkbench(dataSource, new Date("2026-09-10T10:00:00Z"), new AbortController().signal);
        }

        export function render(props) {
          return renderToStaticMarkup(React.createElement(DeliveryWorkbenchContent, props));
        }
      `,
      loader: "tsx",
      resolveDir: process.cwd(),
      sourcefile: "delivery-workbench-harness.tsx",
    },
  });
  const compiled = { exports: {} as { load: (dataSource: object) => Promise<object>; render: (props: object) => string } };
  const require = createRequire(import.meta.url);
  new Function("require", "module", "exports", bundled.outputFiles[0].text)(
    require, compiled, compiled.exports,
  );
  return compiled.exports;
}

test("delivery workbench loads direct F02 facts and renders their business identities", async () => {
  const { load, render } = await loadWorkbenchHarness();
  const calls: Array<{ kind: string; input: Record<string, unknown> }> = [];
  const dataSource = {
    summary: async (input: Record<string, unknown> = {}) => {
      calls.push({ kind: "summary", input });
      return { total: 7, pendingOrProcessing: 2, completed: 5, needsReview: 1 };
    },
    list: async (input: Record<string, unknown>) => {
      calls.push({ kind: "list", input });
      return { items: [safeTask, attentionTask], page: 1, size: Number(input.size), total: 3, totalPages: 1 };
    },
  };

  const loaded = await load(dataSource) as { summary: object; recentTasks: object[]; todayTotal: number };
  const markup = render({
    view: workbenchPresentation(loaded.summary as never, loaded.recentTasks as never, loaded.todayTotal),
    loading: false,
    error: null,
    onOpenContract: () => {},
    onOpenProcurement: () => {},
    onOpenBudget: () => {},
  });

  assert.equal(calls.length, 3);
  assert.deepEqual(calls.map((call) => [call.kind, call.input.page, call.input.size]), [
    ["summary", undefined, undefined], ["list", 1, 20], ["list", 1, 1],
  ]);
  assert.equal(typeof calls[2]?.input.createdFrom, "string");
  assert.equal(typeof calls[2]?.input.createdTo, "string");
  assert.match(markup, /预算审查/);
  assert.match(markup, /采购审查/);
  assert.match(markup, /合同审核/);
  assert.match(markup, /合同审核数据概览/);
  assert.match(markup, /辅助工具/);
  assert.match(markup, /黑名单/);
  assert.match(markup, /风险预估/);
  assert.match(markup, />7</);
  assert.doesNotMatch(markup, /办公设备采购合同|办公设备更新项目|设备维护服务合同|设备维护项目/);
});

test("delivery workbench renders explicit empty and error states", async () => {
  const { render } = await loadWorkbenchHarness();
  const commonProps = {
    loading: false,
    onOpenContract: () => {},
    onOpenProcurement: () => {},
    onOpenBudget: () => {},
  };

  const empty = render({
    ...commonProps,
    error: null,
    view: workbenchPresentation({ total: 0, pendingOrProcessing: 0, completed: 0, needsReview: 0 }, [], 0),
  });
  const failed = render({
    ...commonProps,
    error: "合同内审数据暂时无法读取",
    view: workbenchPresentation(null, [], null),
  });

  assert.match(empty, /业务工具/);
  assert.match(empty, /合同审核数据概览/);
  assert.match(empty, />0</);
  assert.doesNotMatch(empty, /待处理任务|最近动态|项目管理/);
  assert.match(failed, /role="alert"/);
  assert.match(failed, /合同内审数据暂时无法读取/);
  assert.match(failed, /重新读取/);
});

test("shared workbench shows only each role's permitted module links and retains procurement blacklist", async () => {
  const { render } = await loadWorkbenchHarness();
  const view = workbenchPresentation(null, [], null);
  const renderRole = (roles: string[]) => render({
    view,
    loading: false,
    error: null,
    roles,
    onOpenContract() {},
    onOpenProcurement() {},
    onOpenBudget() {},
    onOpenBlacklist() {},
    onOpenRisk() {},
  });

  const budget = renderRole(["BUDGET_USER"]);
  assert.match(budget, /预算审查/);
  assert.match(budget, /黑名单/);
  assert.doesNotMatch(budget, /采购审查|合同审核|风险预估|合同审核数据概览/);

  const procurement = renderRole(["PROCUREMENT_USER"]);
  assert.match(procurement, /采购审查/);
  assert.match(procurement, /黑名单/);
  assert.doesNotMatch(procurement, /预算审查|合同审核|风险预估|合同审核数据概览/);

  const contract = renderRole(["CONTRACT_USER"]);
  assert.match(contract, /合同审核/);
  assert.match(contract, /黑名单/);
  assert.match(contract, /合同审核数据概览/);
  assert.doesNotMatch(contract, /预算审查|采购审查|风险预估/);

  const admin = renderRole(["SYSTEM_ADMIN"]);
  for (const label of ["预算审查", "采购审查", "合同审核", "黑名单", "风险预估"]) {
    assert.match(admin, new RegExp(label));
  }
});

test("workbench scopes selections and empty claims when global pending and review totals are positive", async () => {
  const { render } = await loadWorkbenchHarness();
  const view = workbenchPresentation(
    { total: 30, pendingOrProcessing: 8, completed: 22, needsReview: 4 }, [completedTask], 0,
  );
  const markup = render({ view, loading: false, error: null,
    onOpenContract() {}, onOpenProcurement() {}, onOpenBudget() {},
  });
  assert.deepEqual(view.stats.map((item) => item.value), [30, 8, 22, 4]);
  assert.match(markup, /合同审核数据概览/);
  assert.match(markup, /辅助工具/);
  assert.match(markup, /黑名单/);
  assert.match(markup, /风险预估/);
  assert.doesNotMatch(markup, /已完成的无风险合同|待处理任务|最近动态/);
});
