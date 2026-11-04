import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import { build } from "esbuild";
import { contractAuditLandingPresentation } from "./contractAuditLandingPresentation.ts";
import type { ContractAuditTaskItem, ContractAuditTaskPage } from "./data/contractAuditTaskDataSource.ts";

async function loadLandingHarness(): Promise<{
  render: (props: object) => string;
  load: (dataSource: object, now: Date, signal: AbortSignal) => Promise<{ todayTotal: number }>;
}> {
  const bundled = await build({
    bundle: true,
    define: {
      "import.meta.env.DEV": "false",
      "import.meta.env.VITE_API_BASE_URL": '\"\"',
      "import.meta.env.VITE_CONTRACT_DATA_SOURCE": '"http"',
    },
    format: "cjs",
    jsx: "automatic",
    platform: "node",
    write: false,
    stdin: {
      contents: `
        import React from "react";
        import { renderToStaticMarkup } from "react-dom/server";
        import { ContractAuditLandingContent, loadContractAuditLanding } from "./src/features/contract/components/ContractAuditLandingPage.tsx";
        export function render(props) { return renderToStaticMarkup(React.createElement(ContractAuditLandingContent, props)); }
        export const load = loadContractAuditLanding;
      `,
      loader: "tsx",
      resolveDir: process.cwd(),
      sourcefile: "contract-audit-landing-harness.tsx",
    },
  });
  const compiled = { exports: {} as {
    render: (props: object) => string;
    load: (dataSource: object, now: Date, signal: AbortSignal) => Promise<{ todayTotal: number }>;
  } };
  const require = createRequire(import.meta.url);
  new Function("require", "module", "exports", bundled.outputFiles[0].text)(require, compiled, compiled.exports);
  return compiled.exports;
}

const attention: ContractAuditTaskItem = {
  taskId: "task-live-1",
  contract: {
    id: "contract-live-1", contractNo: "HT-LIVE-001", contractName: "线上真实合同",
    contractType: "SERVICE", partyA: "甲方", partyB: "供应商", partyBCreditCode: null,
    contractAmount: 100, amountInWords: null, status: "ACTIVE", versionNo: 1,
    createdAt: "2026-09-10T08:00:00.000Z", updatedAt: "2026-09-10T08:00:00.000Z",
  },
  project: { projectId: "project-live-1", projectName: "线上真实项目", projectNo: "XM-LIVE-001" },
  taskStatus: "NEEDS_MANUAL_REVIEW",
  createdAt: "2026-09-10T08:00:00.000Z", startedAt: null, finishedAt: null,
  reviewProgress: { current: 2, total: 3, complete: false, pendingAcknowledgement: true },
  risk: { riskCount: 2, materialInsufficient: false, riskSummary: "低风险" },
};

const completed: ContractAuditTaskItem = {
  ...attention,
  taskId: "task-live-2",
  contract: { ...attention.contract, id: "contract-live-2", contractNo: "HT-LIVE-002", contractName: "线上已完成合同" },
  project: null,
  taskStatus: "DONE",
  reviewProgress: { current: 3, total: 3, complete: true, pendingAcknowledgement: false },
  risk: { riskCount: 0, materialInsufficient: false, riskSummary: "无风险" },
};

const page = (items: ContractAuditTaskItem[], total = items.length): ContractAuditTaskPage => ({
  items, page: 1, size: 6, total, totalPages: 1,
});

const callbacks = {
  onAnalyze: () => {}, onCreateContract: () => {}, onOpenList: () => {}, onOpenTask: () => {},
};

test("contract audit landing renders injected direct-task labels for populated, empty, and error states", async () => {
  const { render } = await loadLandingHarness();
  const populated = render({
    ...callbacks,
    loading: false,
    error: null,
    view: contractAuditLandingPresentation(
      { total: 2, pendingOrProcessing: 1, completed: 1, needsReview: 1 }, page([attention], 8), page([completed], 12), 4,
    ),
  });
  assert.match(populated, /合同审核中心/);
  assert.match(populated, /待处理/);
  assert.match(populated, /审核记录/);
  assert.match(populated, /新建合同/);
  assert.match(populated, /今日新建任务/);
  assert.match(populated, /今日审核概要/);
  assert.match(populated, /查看今天需要重点审核的合同/);
  assert.match(populated, /证据来源/);
  assert.doesNotMatch(populated, /Evidence\s*来源/);
  assert.match(populated, /当前需继续核验或审核的对象共 8 条/);
  assert.match(populated, /风险摘要/);
  assert.match(populated, /当前需关注任务/);
  assert.match(populated, /关注任务/);
  assert.doesNotMatch(populated, /已完成审核|审核任务共 12 条/);
  assert.doesNotMatch(populated, /服务器共|已加载(?:关注)?任务|service/i);
  assert.match(populated, /线上真实项目/);
  assert.match(populated, /HT-LIVE-001/);
  assert.match(populated, /合同金额：[^<]*100/);
  assert.match(populated, /合同状态：.*已生效/s);
  assert.doesNotMatch(populated, /\bACTIVE\b/);
  assert.match(populated, /审核进度：2 \/ 3/);
  assert.match(populated, /查看并复核/);
  assert.match(populated, /查看详情/);
  assert.doesNotMatch(populated, /总体.*通过|整体.*通过|最终.*批准/);

  const empty = render({
    ...callbacks, loading: false, error: null,
    view: contractAuditLandingPresentation({ total: 0, pendingOrProcessing: 0, completed: 0, needsReview: 0 }, page([]), page([]), 0),
  });
  assert.match(empty, /当前没有需关注的审核任务/);
  assert.match(empty, /今日审核概要/);
  assert.match(empty, /查看今天需要重点审核的合同/);
  assert.match(empty, /待处理/);
  assert.match(empty, /风险摘要/);
  assert.doesNotMatch(empty, /已完成审核/);
  assert.equal((empty.match(/<dd[^>]*>0<\/dd>/g) ?? []).length, 3);

  const error = render({ ...callbacks, loading: false, error: "加载失败",
    view: contractAuditLandingPresentation(null, null, null, null), onRetry: () => {} });
  assert.match(error, /加载失败/);
  assert.match(error, /重新加载/);
  assert.equal((error.match(/<dd[^>]*>--<\/dd>/g) ?? []).length, 3);
  assert.doesNotMatch(error, /<dd[^>]*>0<\/dd>/);
});

test("contract audit landing makes unavailable review progress explicit", async () => {
  const { render } = await loadLandingHarness();
  const unavailable = render({
    ...callbacks,
    loading: false,
    error: null,
    view: contractAuditLandingPresentation(
      { total: 1, pendingOrProcessing: 1, completed: 0, needsReview: 1 },
      page([{ ...attention, reviewProgress: { current: 0, total: 0, complete: false, pendingAcknowledgement: false } }]),
      page([]),
      0,
    ),
  });
  assert.match(unavailable, /审核进度：暂不可用/);
});

test("contract audit landing loads all active states, history and a bounded createdAt today total", async () => {
  const { load } = await loadLandingHarness();
  const calls: Array<{ kind: "summary" | "list"; input: object }> = [];
  const dataSource = {
    summary: async (input: object) => {
      calls.push({ kind: "summary", input });
      return { total: 3, pendingOrProcessing: 1, completed: 1, needsReview: 1 };
    },
    list: async (input: Record<string, unknown>) => {
      calls.push({ kind: "list", input });
      return page([], input.createdFrom ? 7 : 0);
    },
  };
  const controller = new AbortController();
  const now = new Date("2026-09-10T12:30:00.000Z");
  const loaded = await load(dataSource, now, controller.signal);

  assert.equal(calls.length, 6);
  assert.deepEqual(calls.map((call) => call.kind), ["summary", "list", "list", "list", "list", "list"]);
  assert.deepEqual(calls.slice(1, 5).map((call) => (call.input as { status?: string }).status),
    ["PENDING", "PROCESSING", "NEEDS_REVIEW", "COMPLETED"]);
  const createdFrom = new Date((calls[5]?.input as { createdFrom?: string }).createdFrom!);
  const createdTo = new Date((calls[5]?.input as { createdTo?: string }).createdTo!);
  assert.deepEqual(
    [createdFrom.getFullYear(), createdFrom.getMonth(), createdFrom.getDate(), createdFrom.getHours(), createdFrom.getMinutes()],
    [now.getFullYear(), now.getMonth(), now.getDate(), 0, 0],
  );
  assert.deepEqual(
    [createdTo.getFullYear(), createdTo.getMonth(), createdTo.getDate(), createdTo.getHours(), createdTo.getMinutes(), createdTo.getSeconds(), createdTo.getMilliseconds()],
    [now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59, 999],
  );
  assert.equal(loaded.todayTotal, 7);
});
