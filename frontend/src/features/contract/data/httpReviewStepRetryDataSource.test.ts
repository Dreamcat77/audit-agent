import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";
registerHooks({ load(url, context, next) {
  const result = next(url, context);
  if (!url.endsWith("/src/api/client.ts")) return result;
  return { ...result, source: `import.meta.env ??= {};\n${typeof result.source === "string" ? result.source : Buffer.from(result.source ?? []).toString("utf8")}` };
} });
const { HttpReviewStepRetryDataSource } = await import("./httpReviewStepRetryDataSource.ts");
const command = { expectedRunVersion: 4, expectedStepVersion: 3, previousAttemptId: "previous",
  previousAttemptNo: 1, previousAttemptVersion: 2, previousStatus: "FAILED" as const,
  previousExternalCallState: "NOT_STARTED" as const, reason: "explicit retry" };
const attempt = { attemptId: "new", runId: "run", stepId: "step", attemptNo: 2, status: "PENDING", versionNo: 0,
  operationIdentity: "a".repeat(64), leaseOwner: null, leaseUntil: null, claimedAt: null, externalCallState: "NOT_STARTED",
  externalRequestFingerprint: null, providerRequestId: null, provider: null, model: null, startedAt: null,
  completedAt: null, safeReasonCode: null, outputResourceType: null, outputResourceId: null };
const envelope = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 200, message: "success", data }), { status });

test("retry uses shared CSRF and immutable caller key; uncertain outcome does not trigger automatic POST", async t => {
  const original = globalThis.fetch; t.after(() => { globalThis.fetch = original; });
  const calls: Array<{ url: string; init: RequestInit }> = [];
  let fail = true;
  globalThis.fetch = (async (url, init = {}) => {
    calls.push({ url: String(url), init });
    if (String(url).endsWith("/csrf")) return envelope({ headerName: "X-CSRF-TOKEN", token: "test-csrf" });
    if (fail) throw new Error("lost response");
    return envelope({ attempt, replayed: true }, 202);
  }) as typeof fetch;
  const source = new HttpReviewStepRetryDataSource();
  const signal = new AbortController().signal;
  await assert.rejects(source.retry("project/a", "contract", "run", "step", command, "retry-key", { signal }));
  assert.equal(calls.length, 2);
  fail = false;
  assert.equal((await source.retry("project/a", "contract", "run", "step", command, "retry-key", { signal })).attempt.attemptNo, 2);
  for (const { url, init } of calls.filter(c => c.init.method === "POST")) {
    assert.equal(url, "/api/v1/projects/project%2Fa/contracts/contract/review-runs/run/steps/step/retries");
    assert.equal((init.headers as Record<string, string>)["Idempotency-Key"], "retry-key");
    assert.equal((init.headers as Record<string, string>)["X-CSRF-TOKEN"], "test-csrf");
    assert.equal(init.signal, signal); assert.deepEqual(JSON.parse(String(init.body)), command);
  }
});

test("foreign or non-append retry response fails closed", async t => {
  const original = globalThis.fetch; t.after(() => { globalThis.fetch = original; });
  for (const change of [{ runId: "foreign" }, { stepId: "foreign" }, { attemptNo: 1 }, { attemptId: "previous" }, { status: "invented" }]) {
    globalThis.fetch = (async url => String(url).endsWith("/csrf")
      ? envelope({ headerName: "X-CSRF-TOKEN", token: "test-csrf" })
      : envelope({ attempt: { ...attempt, ...change }, replayed: false })) as typeof fetch;
    await assert.rejects(new HttpReviewStepRetryDataSource().retry("project", "contract", "run", "step", command, "key"));
  }
});
