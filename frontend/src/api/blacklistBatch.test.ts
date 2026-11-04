import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

registerHooks({
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

const { checkBlacklistBatch, uploadBlacklistBatch } = await import("./audit.ts");

function envelope<T>(data: T): Response {
  return new Response(JSON.stringify({ code: 200, message: "success", data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

test("checkBlacklistBatch sends the typed JSON batch request with CSRF", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const calls: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(input), init });
    if (calls.length === 1) {
      return envelope({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf-batch" });
    }
    return envelope({
      batchId: "BATCH-001", totalCount: 2, hitCount: 1, missCount: 1,
      hits: [{
        rowNumber: 1, supplierName: "供应商A", supplierCode: "CODE-A",
        blacklistId: "blacklist-1", blacklistType: "供应商黑名单",
        blacklistReason: "存在失信记录", matchType: "EXACT", matchScore: 100, alertId: "alert-1",
      }],
      misses: [{ rowNumber: 2, supplierName: "供应商B", supplierCode: null }],
    });
  }) as typeof fetch;

  const result = await checkBlacklistBatch({
    batchId: "BATCH-001",
    suppliers: [{ rowNumber: 1, supplierName: "供应商A", supplierCode: "CODE-A" }],
  });

  assert.deepEqual(calls.map((call) => [call.init.method, call.url]), [
    ["GET", "/api/v1/auth/csrf"],
    ["POST", "/api/v1/blacklist/check-batch"],
  ]);
  assert.equal(new Headers(calls[1]?.init.headers).get("X-CSRF-TOKEN"), "csrf-batch");
  assert.deepEqual(JSON.parse(String(calls[1]?.init.body)), {
    batchId: "BATCH-001",
    suppliers: [{ rowNumber: 1, supplierName: "供应商A", supplierCode: "CODE-A" }],
  });
  assert.equal(result.hitCount, 1);
  assert.equal(result.hits[0]?.alertId, "alert-1");
  assert.equal(result.misses[0]?.supplierName, "供应商B");
});

test("uploadBlacklistBatch sends Excel FormData with batchId", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const calls: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(input), init });
    if (calls.length === 1) {
      return envelope({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf-upload" });
    }
    return envelope({
      batchId: "BATCH-UPLOAD", totalCount: 1, hitCount: 0, missCount: 1,
      hits: [], misses: [{ rowNumber: 1, supplierName: "供应商B", supplierCode: null }],
    });
  }) as typeof fetch;

  const file = new Blob(["supplierName\n供应商B"], {
    type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  }) as unknown as File;
  const result = await uploadBlacklistBatch(file, "BATCH-UPLOAD");
  const form = calls[1]?.init.body as FormData;

  assert.equal(calls[1]?.url, "/api/v1/blacklist/check-batch-upload");
  assert.equal(calls[1]?.init.method, "POST");
  assert.equal(new Headers(calls[1]?.init.headers).get("X-CSRF-TOKEN"), "csrf-upload");
  assert.equal(new Headers(calls[1]?.init.headers).has("Content-Type"), false);
  assert.equal(form.get("batchId"), "BATCH-UPLOAD");
  assert.ok(form.get("file") instanceof Blob);
  assert.equal(result.totalCount, 1);
});
