import assert from "node:assert/strict";
import test from "node:test";

import { startIntelligentReviewBranches } from "./intelligentReviewOperation.ts";

test("one start invokes semantic AI and deterministic F02 exactly once", async () => {
  let aiCalls = 0;
  let deterministicCalls = 0;

  const result = await startIntelligentReviewBranches({
    startSemanticReview: async () => { aiCalls += 1; },
    startDeterministicReview: () => { deterministicCalls += 1; },
  });

  assert.equal(aiCalls, 1);
  assert.equal(deterministicCalls, 1);
  assert.equal(result.semantic.status, "fulfilled");
  assert.equal(result.deterministic.status, "fulfilled");
});

test("an AI rejection does not prevent or overwrite the deterministic branch", async () => {
  let deterministicCalls = 0;
  const aiFailure = new Error("AI unavailable");

  const result = await startIntelligentReviewBranches({
    startSemanticReview: async () => { throw aiFailure; },
    startDeterministicReview: () => { deterministicCalls += 1; },
  });

  assert.equal(deterministicCalls, 1);
  assert.deepEqual(result.semantic, { status: "rejected", reason: aiFailure });
  assert.equal(result.deterministic.status, "fulfilled");
});

test("a deterministic start failure does not cancel the semantic request", async () => {
  let semanticCompleted = false;
  const deterministicFailure = new Error("F02 start failed");

  const result = await startIntelligentReviewBranches({
    startSemanticReview: async () => { semanticCompleted = true; },
    startDeterministicReview: () => { throw deterministicFailure; },
  });

  assert.equal(semanticCompleted, true);
  assert.equal(result.semantic.status, "fulfilled");
  assert.deepEqual(result.deterministic, {
    status: "rejected",
    reason: deterministicFailure,
  });
});
