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

const api = await import("./budgetApprovalProgramApi.ts").catch(() => ({})).finally(() => hooks.deregister());

function envelope<T>(data: T): Response {
  return new Response(JSON.stringify({ code: 200, message: "success", data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

test("F04 API loads backend rules and sends the caller-retained Idempotency-Key", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const validation = {
    validationId: "validation-1", ruleSetCode: "PM_DEMO_20260828",
    taskId: "task-1", itemId: "item-1", sourceRowNumber: 2, declaredAmount: 50000,
    requiredProcedureSteps: [], requiredAuthorityStep: { code: "FINANCE_REVIEW", displayName: "财处审批" },
    actualSteps: [], missingProcedureSteps: [], procedureOrderValid: true,
    authorityMatched: false, passed: false, resultSummary: "审批权限层级不符合当前规则",
    actualSource: "MANUAL_TEST", createdAt: "2026-08-28T10:00:00Z",
  };
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(input), init });
    if (calls.length === 1) return envelope({ ruleSetCode: "PM_DEMO_20260828", ruleGroups: [] });
    if (calls.length === 2) return envelope({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf" });
    if (calls.length === 3) return envelope(validation);
    throw new Error(`unexpected fetch ${calls.length}`);
  }) as typeof fetch;

  await api.getBudgetApprovalRules();
  await api.createBudgetApprovalValidation({
    taskId: "task-1", itemId: "item-1", actualSteps: [], idempotencyKey: "retained-f04-key",
  });

  assert.deepEqual(calls.map((call) => [call.init.method ?? "GET", call.url]), [
    ["GET", "/api/v1/budget/approval-program/rules"],
    ["GET", "/api/v1/auth/csrf"],
    ["POST", "/api/v1/budget/approval-program/validations"],
  ]);
  assert.equal(new Headers(calls[2]?.init.headers).get("Idempotency-Key"), "retained-f04-key");
  assert.equal(new Headers(calls[2]?.init.headers).get("X-CSRF-TOKEN"), "csrf");
  assert.equal(calls[2]?.init.credentials, "include");
  assert.deepEqual(JSON.parse(String(calls[2]?.init.body)), {
    taskId: "task-1", itemId: "item-1", actualSteps: [],
  });
});
