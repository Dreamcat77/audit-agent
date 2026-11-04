import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";
registerHooks({ load(url, context, nextLoad) {
  const result = nextLoad(url, context);
  if (url.endsWith("/api/client.ts")) return { ...result, source: `import.meta.env ??= {};\n${result.source}` };
  return result;
} });
const { CloseoutDataSource } = await import("./closeoutDataSource.ts");
test("exact transport paths, body and immutable key; GET remains key free", async () => {
  const calls: Array<{ path: string; options: any }> = [];
  const source = new CloseoutDataSource(async (path: string, options?: any) => { calls.push({ path, options }); return [] as any; });
  const context = { actorId: "actor", projectId: "project", contractId: "contract", runId: "run" };
  await source.acknowledge(context, "finding", 2, "处理意见", "ack-key");
  await source.generate(context, "report-key");
  await source.acknowledgements(context, "finding");
  await source.reports(context);
  await source.report(context, "report");
  assert.equal(calls[0].path, "/api/v1/projects/project/contracts/contract/review-runs/run/findings/finding/acknowledgements");
  assert.deepEqual(calls[0].options.body, { findingVersionNo: 2, handlingNote: "处理意见" });
  assert.equal(calls[0].options.headers["Idempotency-Key"], "ack-key");
  assert.deepEqual(calls[1].options.body, {});
  assert.equal(calls[1].options.headers["Idempotency-Key"], "report-key");
  for (const call of calls.slice(2)) assert.equal(call.options?.headers?.["Idempotency-Key"], undefined);
  assert.match(source.artifactUrl(context, "report"), /\/reports\/report\/artifact$/);
});
