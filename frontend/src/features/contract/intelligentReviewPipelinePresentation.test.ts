import assert from "node:assert/strict";
import test from "node:test";

import { createIntelligentReviewPipeline } from "./intelligentReviewPipelinePresentation.ts";

const completeInput = {
  selectedFileCount: 4,
  aiLoading: false,
  aiAvailable: true,
  aiFailed: false,
  deterministicLoading: false,
  deterministicCompleted: true,
  deterministicFailed: false,
} as const;

test("uses exactly five top-level stages without independent clause-engine branches", () => {
  const pipeline = createIntelligentReviewPipeline(completeInput);

  assert.deepEqual(pipeline.steps.map((step) => step.key), [
    "MATERIAL_SELECTION", "MATERIAL_READING", "DETERMINISTIC", "AI_REVIEW_GROUP", "ASSISTANCE",
  ]);
  assert.deepEqual(pipeline.steps.map((step) => step.label), [
    "审核材料", "材料读取", "精确校验", "智能语义审查", "审核建议",
  ]);
  assert.equal(pipeline.steps[3]?.branches, undefined);
  assert.deepEqual(pipeline.steps.map((step) => step.status), [
    "COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED",
  ]);
});

test("keeps step four running solely from the one AI request even if helpers already completed", () => {
  const pipeline = createIntelligentReviewPipeline({
    ...completeInput,
    aiLoading: true,
    aiAvailable: false,
    breachCompleted: true,
    termCompleted: true,
  } as typeof completeInput);

  assert.equal(pipeline.steps[3]?.status, "RUNNING");
  assert.equal(pipeline.steps[3]?.branches, undefined);
  assert.equal(pipeline.steps[4]?.status, "PENDING");
});

test("AI failure remains attention and helpers cannot make assistance complete", () => {
  const pipeline = createIntelligentReviewPipeline({
    ...completeInput,
    aiAvailable: false,
    aiFailed: true,
    breachCompleted: true,
    termCompleted: true,
  } as typeof completeInput);

  assert.equal(pipeline.steps[2]?.status, "COMPLETED");
  assert.equal(pipeline.steps[3]?.status, "ATTENTION");
  assert.equal(pipeline.steps[4]?.status, "ATTENTION");
  assert.match(pipeline.steps[4]?.detail ?? "", /智能语义分析本次未生成完整结果/);
});

test("a failed deterministic branch cannot be described as complete assistance", () => {
  const pipeline = createIntelligentReviewPipeline({
    ...completeInput,
    deterministicCompleted: false,
    deterministicFailed: true,
  });

  assert.equal(pipeline.steps[2]?.status, "ATTENTION");
  assert.equal(pipeline.steps[3]?.status, "COMPLETED");
  assert.equal(pipeline.steps[4]?.status, "ATTENTION");
  assert.match(pipeline.steps[4]?.detail ?? "", /精确校验未完成/);
});
