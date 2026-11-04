import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    if (!url.endsWith("/src/api/client.ts")) return loaded;
    const source = typeof loaded.source === "string"
      ? loaded.source
      : Buffer.from(loaded.source ?? []).toString("utf8");
    return { ...loaded, source: `import.meta.env ??= {};\n${source}` };
  },
});

const [{ HttpContractAuditTaskDataSource }, { directF02AuditTaskRow, directF02TaskStatusPresentation }] = await Promise.all([
  import("./contractAuditTaskDataSource.ts"),
  import("../contractAuditTaskPresentation.ts"),
]).finally(() => hooks.deregister());

const DIRECT_TASK = {
  taskId: "task-direct-1",
  contract: {
    id: "contract-real-1", contractNo: "HT-001", contractName: "智慧办公终端采购合同",
    contractType: "GOODS", partyA: "采购单位", partyB: "供应商A",
    partyBCreditCode: "91350000REAL", contractAmount: 528000,
    amountInWords: "人民币伍拾贰万捌仟元整", status: "READY_FOR_REVIEW", versionNo: 1,
    createdAt: "2026-08-26T10:00:00Z", updatedAt: "2026-08-26T10:00:01Z",
  },
  project: { projectId: "project-real-1", projectName: "智慧办公终端采购", projectNo: "XM-001" },
  taskStatus: "DONE",
  createdAt: "2026-08-26T10:10:00Z",
  startedAt: "2026-08-26T10:10:01Z",
  finishedAt: "2026-08-26T10:10:02Z",
  reviewProgress: { current: 5, total: 8, complete: false, pendingAcknowledgement: true },
  risk: { riskCount: 1, materialInsufficient: false, riskSummary: "低风险" },
} as const;

test("HTTP task center reads the direct F02 AuditTask endpoint rather than ReviewSession", async () => {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(input), init });
    return new Response(JSON.stringify({
      code: 200,
      message: "success",
      data: { items: [DIRECT_TASK], page: 1, size: 20, total: 1, totalPages: 1 },
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  }) as typeof fetch;
  try {
    const page = await new HttpContractAuditTaskDataSource().list({ page: 1, size: 20 });
    assert.equal(page.total, 1);
    assert.equal(page.items[0]?.taskId, "task-direct-1");
    assert.equal(calls.length, 1);
    assert.match(calls[0]!.url, /\/api\/v1\/contract-audit-tasks\?page=1&size=20$/);
    assert.doesNotMatch(calls[0]!.url, /contract-review-sessions/);
    assert.equal(calls[0]!.init?.credentials, "include");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("HTTP task center sends real filters and reads the durable summary endpoint", async () => {
  const calls: string[] = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: string | URL | Request) => {
    const url = String(input);
    calls.push(url);
    const data = url.endsWith("/summary")
      ? { total: 7, pendingOrProcessing: 2, completed: 5, needsReview: 1 }
      : { items: [DIRECT_TASK], page: 1, size: 20, total: 1, totalPages: 1 };
    return new Response(JSON.stringify({ code: 200, message: "success", data }), {
      status: 200, headers: { "Content-Type": "application/json" },
    });
  }) as typeof fetch;
  try {
    const source = new HttpContractAuditTaskDataSource();
    const summary = await source.summary();
    await source.list({
      page: 1,
      size: 20,
      includeHistory: true,
      keyword: "智慧维修",
      createdFrom: "2026-09-01T00:00:00.000Z",
      createdTo: "2026-09-01T23:59:59.999Z",
      risk: "INSUFFICIENT",
      status: "NEEDS_REVIEW",
    });
    assert.equal(summary.total, 7);
    assert.match(calls[0]!, /\/api\/v1\/contract-audit-tasks\/summary$/);
    assert.match(calls[1]!, /keyword=/);
    assert.match(calls[1]!, /createdFrom=/);
    assert.match(calls[1]!, /risk=INSUFFICIENT/);
    assert.match(calls[1]!, /status=NEEDS_REVIEW/);
    assert.match(calls[1]!, /includeHistory=true/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("direct F02 task row preserves the real task, project, progress and risk facts", () => {
  const row = directF02AuditTaskRow(DIRECT_TASK);
  assert.equal(row.source, "DIRECT_F02");
  assert.equal(row.taskId, "task-direct-1");
  assert.equal(row.contractApiId, "contract-real-1");
  assert.equal(row.projectId, "project-real-1");
  assert.equal(row.projectName, "智慧办公终端采购");
  assert.deepEqual(row.reviewProgress, DIRECT_TASK.reviewProgress);
  assert.deepEqual(row.risk, DIRECT_TASK.risk);
  assert.equal(row.updatedAt, DIRECT_TASK.finishedAt);
  assert.equal(directF02TaskStatusPresentation(row), "量化复核待知悉");
});

test("a completed F02 task cannot turn a failed ReviewRun into completed contract review", () => {
  const row = directF02AuditTaskRow({
    ...DIRECT_TASK,
    reviewProgress: { ...DIRECT_TASK.reviewProgress, pendingAcknowledgement: false, complete: true },
    reviewRun: {
      kind: "INTEGRATED", projectId: "project-real-1", runId: "historical-run-1", status: "FAILED",
    },
  });
  assert.equal(directF02TaskStatusPresentation(row), "处理失败");
});

test("an independent completed F02 task is labelled as a quantitative check only", () => {
  const row = directF02AuditTaskRow({
    ...DIRECT_TASK,
    reviewProgress: { ...DIRECT_TASK.reviewProgress, pendingAcknowledgement: false, complete: true },
    reviewRun: { kind: "INDEPENDENT_F02", projectId: null, runId: null, status: null },
  });
  assert.equal(directF02TaskStatusPresentation(row), "量化核对完成");
});
