import assert from "node:assert/strict";
import test from "node:test";

import {
  presentReviewRun,
  reviewFindingSourceLabel,
  reviewSafeReasonText,
  reviewStepLabel,
} from "./reviewRunPresentation.ts";
import type { ReviewRunDto, ReviewStepDto, ReviewStepStatus, ReviewStepType } from "./reviewRunTypes.ts";

const TYPES: readonly ReviewStepType[] = [
  "MATERIAL_VALIDATION", "F02_DETERMINISTIC", "SEMANTIC_AI", "BREACH", "TERM",
];

function step(stepType: ReviewStepType, status: ReviewStepStatus): ReviewStepDto {
  return {
    stepId: `id-${stepType}`,
    runId: "run-1",
    stepType,
    required: true,
    status,
    stepPolicyVersion: "hidden-policy",
    currentAttemptNo: 1,
    outputResourceType: null,
    outputResourceId: null,
    safeReasonCode: status === "FAILED" ? "MODEL_UNAVAILABLE" : status === "UNKNOWN" ? "OUTCOME_UNCERTAIN" : null,
    startedAt: null,
    completedAt: status === "COMPLETED" ? "2026-09-16T00:00:00Z" : null,
    versionNo: 0,
  };
}

function run(statuses: Partial<Record<ReviewStepType, ReviewStepStatus>> = {}): ReviewRunDto {
  const steps = TYPES.map((type) => step(type, statuses[type] ?? (type === "MATERIAL_VALIDATION" ? "COMPLETED" : "PENDING")));
  return {
    runId: "run-1", projectId: "project-1", contractId: "contract-1", contractVersion: 1,
    executionMode: "CLIENT_COMPATIBILITY",
    sourceCandidateId: "candidate-1", sourceConfirmationId: null, runSchemaVersion: 1,
    contractSnapshotCanonicalJson: "{}", contractSnapshotSha256: "hash", reviewProfileVersion: "profile",
    ruleVersion: "rule", modelPolicyVersion: "model-policy", inputSnapshotIdentity: "identity",
    requestedBy: "actor", status: "PENDING", requestedAt: "2026-09-16T00:00:00Z",
    startedAt: null, completedAt: null, failedAt: null, unknownAt: null, safeReasonCode: null,
    versionNo: 0, materials: [], steps, attempts: [], outputs: [], replayed: false,
  };
}

test("durable steps map to the existing five customer-facing stages", () => {
  const view = presentReviewRun(run());
  assert.deepEqual(view.steps.map((item) => item.label), [
    "审核材料", "材料读取", "精确校验", "智能语义审查", "审核建议",
  ]);
  assert.deepEqual(view.steps.map((item) => item.status), [
    "COMPLETED", "COMPLETED", "PENDING", "PENDING", "PENDING",
  ]);
  assert.equal(view.canContinue, true);
  assert.equal(view.selectionFrozen, true);
});

test("semantic, breach and term terminal outcomes aggregate without hiding completed siblings", () => {
  const view = presentReviewRun(run({
    F02_DETERMINISTIC: "COMPLETED",
    SEMANTIC_AI: "COMPLETED",
    BREACH: "FAILED",
    TERM: "UNKNOWN",
  }));
  assert.equal(view.steps[2]?.status, "COMPLETED");
  assert.equal(view.steps[3]?.status, "ATTENTION");
  assert.match(view.steps[3]?.detail ?? "", /已完成 1 项/);
  assert.match(view.steps[3]?.detail ?? "", /未完成 1 项/);
  assert.match(view.steps[3]?.detail ?? "", /结果待确认 1 项/);
  assert.equal(view.steps[4]?.status, "ATTENTION");
  assert.match(view.steps[4]?.detail ?? "", /结果暂无法确认/);
  assert.equal(view.canContinue, false);
});

test("failed or unknown semantic branches remain attention even when a sibling is pending", () => {
  const failed = presentReviewRun(run({ SEMANTIC_AI: "COMPLETED", BREACH: "FAILED" }));
  const unknown = presentReviewRun(run({ SEMANTIC_AI: "COMPLETED", BREACH: "UNKNOWN" }));
  assert.equal(failed.steps[3]?.status, "ATTENTION");
  assert.match(failed.steps[3]?.detail ?? "", /未完成 1 项/);
  assert.equal(failed.steps[4]?.status, "ATTENTION");
  assert.equal(unknown.steps[3]?.status, "ATTENTION");
  assert.match(unknown.steps[3]?.detail ?? "", /结果待确认 1 项/);
  assert.equal(unknown.steps[4]?.status, "ATTENTION");
});

test("UNKNOWN and FAILED use distinct safe Chinese language", () => {
  assert.equal(reviewSafeReasonText("F02_RULE_POLICY_DRIFT", "FAILED"), "审核规则已更新，请重新发起本次审核。" );
  assert.equal(reviewSafeReasonText("SEMANTIC_UNAVAILABLE", "FAILED"), "智能分析能力当前暂不可用。" );
  assert.equal(reviewSafeReasonText("SEMANTIC_RESPONSE_INVALID", "FAILED"), "智能分析结果未能通过系统校验。" );
  assert.equal(reviewSafeReasonText("SEMANTIC_OUTCOME_UNKNOWN", "UNKNOWN"), "智能分析请求已发出，但当前无法确认处理结果。" );
  assert.equal(reviewSafeReasonText("STEP_EXECUTOR_UNAVAILABLE", "FAILED"), "该审核能力当前暂不可用。" );
  assert.equal(reviewSafeReasonText("STEP_COMPUTATION_INVALID", "FAILED"), "本次审核数据未能通过系统校验。" );
  assert.equal(reviewSafeReasonText("STEP_EXECUTION_FAILED", "FAILED"), "该审核环节未成功完成。" );
  assert.equal(reviewSafeReasonText("UNMAPPED_TECH_CODE", "FAILED"), "该审核环节暂无法正常完成，请稍后重试或联系管理员。" );
  assert.equal(reviewSafeReasonText("UNMAPPED_TECH_CODE", "UNKNOWN"), "本次请求已发出，但系统暂时无法确认处理结果，请稍后查看。" );
});

test("run status uses the approved business wording", () => {
  const failed = { ...run(), status: "FAILED" as const };
  const unknown = { ...run(), status: "UNKNOWN" as const };
  assert.equal(presentReviewRun(failed).statusLabel, "审核未完整完成");
  assert.equal(presentReviewRun(unknown).statusLabel, "结果状态待确认");
});

test("only proven terminal runs allow a new review and incomplete manifests fail closed", () => {
  for (const status of ["PENDING", "RUNNING", "UNKNOWN"] as const) {
    assert.equal(presentReviewRun({ ...run(), status }).canStartNew, false, status);
  }
  for (const status of ["COMPLETED", "PARTIALLY_COMPLETED", "FAILED"] as const) {
    assert.equal(presentReviewRun({ ...run(), status }).canStartNew, true, status);
  }
  const incomplete = { ...run(), steps: run().steps.filter((item) => item.stepType !== "TERM") };
  const view = presentReviewRun(incomplete);
  assert.equal(view.steps[3]?.status, "ATTENTION");
  assert.match(view.steps[3]?.detail ?? "", /记录不完整/);
});

test("worker-owned pending runs are observed instead of offered for browser continuation", () => {
  const view = presentReviewRun({ ...run(), executionMode: "SERVER_WORKER" });
  assert.equal(view.canContinue, false);
});

test("customer labels never expose backend vocabulary", () => {
  assert.equal(reviewStepLabel("MATERIAL_VALIDATION"), "材料确认");
  assert.equal(reviewStepLabel("F02_DETERMINISTIC"), "精确校验");
  assert.equal(reviewStepLabel("SEMANTIC_AI"), "智能语义审查");
  assert.equal(reviewStepLabel("BREACH"), "违约条款审查");
  assert.equal(reviewStepLabel("TERM"), "履约期限与终止条件审查");
  assert.equal(reviewFindingSourceLabel("MODEL_ASSISTED"), "智能分析提示");
  assert.equal(reviewFindingSourceLabel("DETERMINISTIC"), "规则校验结果");

  const view = presentReviewRun(run({ F02_DETERMINISTIC: "FAILED", SEMANTIC_AI: "UNKNOWN" }));
  const customerCopy = [view.statusLabel, view.statusDetail,
    ...view.steps.flatMap((item) => [item.label, item.detail])].join(" ");
  assert.doesNotMatch(customerCopy, /ReviewRun|ReviewStep|Finding|Evidence|MATERIAL_|F02_|SEMANTIC_|MODEL_ASSISTED|UNKNOWN|FAILED|[0-9a-f]{8}-[0-9a-f-]{27}/i);
});
