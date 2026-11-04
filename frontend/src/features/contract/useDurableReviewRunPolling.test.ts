import assert from "node:assert/strict";
import test from "node:test";
import { scopedReviewRunLoader } from "./useDurableReviewRunPolling.ts";

test("saved pointer reopens exact project/contract/run with cancellation; actor is not a request field", async () => {
  const pointer = { actorId: "viewer", projectId: "project", contractId: "contract", runId: "run" };
  const calls: unknown[][] = [];
  const projection = { projectId: "project", contractId: "contract", runId: "run", status: "RUNNING" };
  const source = { getReviewRun: async (...args: unknown[]) => { calls.push(args); return projection; } };
  const signal = new AbortController().signal;
  const result = await scopedReviewRunLoader(pointer, source as never)(signal);
  assert.equal(result, projection);
  assert.deepEqual(calls, [["project", "contract", "run", { signal }]]);
});

test("foreign run response is rejected, never adopted after scope change", async () => {
  const pointer = { actorId: "viewer", projectId: "project", contractId: "contract", runId: "run" };
  for (const field of ["projectId", "contractId", "runId"]) {
    const source = { getReviewRun: async () => ({ ...pointer, [field]: "foreign" }) };
    await assert.rejects(scopedReviewRunLoader(pointer, source as never)(new AbortController().signal));
  }
});
