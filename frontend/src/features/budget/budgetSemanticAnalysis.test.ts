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

const { budgetSemanticAnalysisPresentation, requestBudgetSemanticAnalysis } =
  await import("./budgetSemanticAnalysis.ts").finally(() => hooks.deregister());

function envelope<T>(data: T): Response {
  return new Response(JSON.stringify({ code: 200, message: "success", data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

test("budget semantic request uses the typed task endpoint with shared CSRF and cookies", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const calls: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(input), init });
    if (calls.length === 1) {
      return envelope({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf-budget-ai" });
    }
    return envelope({
      taskId: "task-1", operation: "BUDGET_SEMANTIC_ANALYSIS", requestId: "request-budget-ai",
      available: true,
      items: [{ itemId: "item-1", suggestedCategory: "信息化设备", hint: "建议人工核对科目归类。" }],
      model: "qwen-plus", durationMs: 28,
    });
  }) as typeof fetch;

  const result = await requestBudgetSemanticAnalysis("task-1");
  assert.equal(result.operation, "BUDGET_SEMANTIC_ANALYSIS");
  assert.deepEqual(calls.map((call) => [call.init.method, call.url]), [
    ["GET", "/api/v1/auth/csrf"],
    ["POST", "/api/v1/budget/review-tasks/task-1/ai/semantic-analysis"],
  ]);
  assert.equal(calls[1]?.init.credentials, "include");
  assert.equal(new Headers(calls[1]?.init.headers).get("X-CSRF-TOKEN"), "csrf-budget-ai");
  assert.equal(calls[1]?.init.body, undefined);
});

test("budget presentation separates AI semantics from deterministic money authority", () => {
  const view = budgetSemanticAnalysisPresentation({
    taskId: "task-1", operation: "BUDGET_SEMANTIC_ANALYSIS", requestId: "request-budget-ai",
    available: true,
    items: [{ itemId: "item-1", suggestedCategory: "信息化设备", hint: "建议人工核对科目归类。" }],
    model: "qwen-plus", durationMs: 28,
  }, false, null);
  assert.equal(view.sourceLabel, "AI语义来源");
  assert.equal(view.deterministicSourceLabel, "确定性金额来源 · Java BigDecimal");
  assert.equal(view.modelLabel, null);
  assert.deepEqual(view.items, [{ itemId: "item-1", suggestedCategory: "信息化设备", hint: "建议人工核对科目归类。" }]);
  assert.doesNotMatch(JSON.stringify(view.items), /amount|金额|差额|单价|数量/i);
});

test("unavailable budget AI is explicit and never creates fake suggestions", () => {
  const view = budgetSemanticAnalysisPresentation({
    taskId: "task-1", operation: "BUDGET_SEMANTIC_ANALYSIS", requestId: "request-unavailable",
    available: false, items: [], model: "UNAVAILABLE_LOCAL_FAKE", durationMs: 0,
  }, false, null);
  assert.equal(view.statusLabel, "当前不可用");
  assert.equal(view.verificationLabel, "NOT VERIFIED / BLOCKED_BY_RUNTIME_CONFIG");
  assert.deepEqual(view.items, []);
});
