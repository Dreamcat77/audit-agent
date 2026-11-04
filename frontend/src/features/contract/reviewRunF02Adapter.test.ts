import assert from "node:assert/strict";
import test from "node:test";

import { adaptDurableF02Output } from "./reviewRunF02Adapter.ts";
import type { ReviewRunMaterialDto, ReviewStepOutputDto } from "./reviewRunTypes.ts";

function f02Output(): ReviewStepOutputDto {
  const run = {
    runId: "run-1", projectId: "project-1", contractId: "contract-1", contractVersion: 2,
    sourceCandidateId: "candidate", sourceConfirmationId: null, runSchemaVersion: 1,
    contractSnapshotCanonicalJson: "{}", contractSnapshotSha256: "hash", reviewProfileVersion: "profile",
    ruleVersion: "rules", modelPolicyVersion: "models", inputSnapshotIdentity: "snapshot",
    requestedBy: "actor", status: "COMPLETED" as const, requestedAt: "2026-09-16T00:00:00Z",
    startedAt: "2026-09-16T00:00:01Z", completedAt: "2026-09-16T00:00:03Z",
    failedAt: null, unknownAt: null, safeReasonCode: null, versionNo: 2,
  };
  return {
    run,
    step: { stepId: "step-f02", runId: run.runId, stepType: "F02_DETERMINISTIC", required: true,
      status: "COMPLETED", stepPolicyVersion: "rules", currentAttemptNo: 1,
      outputResourceType: "AUDIT_TASK", outputResourceId: "task-1", safeReasonCode: null,
      startedAt: run.startedAt, completedAt: run.completedAt, versionNo: 2 },
    attempts: [], replayed: false, findings: [], evidence: [],
    auditTask: { id: "task-1", sourceType: "CONTRACT", sourceId: run.contractId, sourceVersion: 2,
      reviewType: "F02_DETERMINISTIC", status: "DONE", createdBy: "actor", requestId: "request",
      traceId: "trace", createdAt: run.requestedAt, startedAt: run.startedAt,
      finishedAt: run.completedAt, versionNo: 2 },
    auditCheckResults: [
      { id: "check-2", taskId: "task-1", ruleCode: "F02_LINE_AMOUNT", itemKey: "line-1",
        checkStatus: "FAIL", riskLevel: "RED", blockSign: true, actualValue: "101.00",
        expectedValue: "100.00", detail: "金额不一致", ruleVersion: 1, createdAt: run.completedAt },
      { id: "check-1", taskId: "task-1", ruleCode: "F02_CONTRACT_AMOUNT", itemKey: null,
        checkStatus: "PASS", riskLevel: "GREEN", blockSign: false, actualValue: "100.00",
        expectedValue: "100.00", detail: "金额一致", ruleVersion: 1, createdAt: run.completedAt },
    ],
  };
}

test("durable F02 task and checks adapt exactly to the existing panel facts", () => {
  const adapted = adaptDurableF02Output(f02Output());
  assert.ok(adapted);
  assert.equal(adapted.result.taskId, "task-1");
  assert.equal(adapted.result.status, "DONE");
  assert.equal(adapted.result.overallRisk, "RED");
  assert.equal(adapted.result.blockSign, true);
  assert.deepEqual(adapted.result.findings.map((finding) => finding.id), ["check-2", "check-1"]);
  assert.equal(adapted.result.findings[0]?.actualValue, "101.00");
  assert.equal(adapted.progress.items[0]?.type, "SOURCE_PREPARATION");
  assert.deepEqual(adapted.progress.items.slice(1).map((item) => item.checkResultId), ["check-2", "check-1"]);
  assert.equal(adapted.progress.items.every((item) => item.acknowledgement === null), true);
});

test("non-completed or unproven F02 output never fabricates a result", () => {
  const failed = f02Output();
  failed.step = { ...failed.step, status: "FAILED", outputResourceType: null, outputResourceId: null };
  failed.auditTask = null;
  failed.auditCheckResults = [];
  assert.equal(adaptDurableF02Output(failed), null);

  const wrongTask = f02Output();
  wrongTask.auditTask = { ...wrongTask.auditTask!, id: "another-task" };
  assert.throws(() => adaptDurableF02Output(wrongTask), /无法验证精确校验结果/);
});

test("GET ReviewRun embedded output without replay metadata normalizes to a non-replay result", () => {
  const embedded = f02Output();
  delete (embedded as { replayed?: boolean }).replayed;
  const adapted = adaptDurableF02Output(embedded);
  assert.ok(adapted);
  assert.equal(adapted.result.replayed, false);
});

test("V2 F02 output joins locator carriers by explicit relation without duplicating F02 results", () => {
  const output = f02Output();
  output.step = { ...output.step, outputResourceType: "AUDIT_TASK_F02_NAVIGATION_V2" };
  const check = { ...output.auditCheckResults[0]!, ruleCode: "F02_AWARD_AMOUNT_MATCH" };
  output.auditCheckResults = [check];
  const carrier = {
    findingId: "finding-for-check-2", runId: output.run.runId, stepId: output.step.stepId,
    attemptId: "attempt-1", ordinal: 1, findingType: "F02_AUDIT_CHECK_RESULT",
    category: check.ruleCode, statusCode: check.checkStatus, attentionCode: null,
    riskLevel: check.riskLevel, summary: check.detail, recommendation: null,
    findingSchemaVersion: 2, policyVersion: output.step.stepPolicyVersion,
    source: "DETERMINISTIC" as const, modelProvider: null, modelName: null,
    modelRevision: null, promptVersion: null, modelSchemaVersion: null,
    createdAt: check.createdAt, versionNo: 0,
  };
  const evidence = (id: string, fileId: string, ordinal: number, locatorAvailable: boolean) => ({
    evidenceId: id, findingId: carrier.findingId, runId: output.run.runId,
    stepId: output.step.stepId, attemptId: carrier.attemptId, ordinal, fileId,
    fileSha256: "a".repeat(64), parseResultId: `parse-${ordinal}`, parseVersion: 1,
    canonicalTextSha256: "b".repeat(64), evidenceRole: "COMPARISON" as const,
    validationStatus: locatorAvailable ? "VALIDATED" as const : "LOCATOR_UNAVAILABLE" as const,
    validationPolicyVersion: "f02-frozen-evidence-v1", pageNumber: locatorAvailable ? ordinal : null,
    paragraphIndex: null, tableIndex: null, rowIndex: null, cellIndex: null, segmentIndex: null,
    locatorAvailable, startOffset: locatorAvailable ? 0 : null,
    endOffset: locatorAvailable ? 4 : null, excerptSha256: "c".repeat(64),
    boundedExcerpt: ordinal === 1 ? "合同金额" : "中标金额", locatorSchemaVersion: 1,
    createdAt: check.createdAt, versionNo: 0,
  });
  output.findings = [carrier];
  output.evidence = [evidence("evidence-contract", "file-contract", 1, true),
    evidence("evidence-award", "file-award", 2, true)];
  output.findingCheckResultRelations = { [carrier.findingId]: check.id };
  const materials: ReviewRunMaterialDto[] = [
    { runMaterialId: "material-contract", runId: output.run.runId, ordinal: 1,
      projectId: output.run.projectId, fileId: "file-contract", fileSha256: "a".repeat(64),
      parseResultId: "parse-1", parseVersion: 1, canonicalTextSha256: "b".repeat(64),
      effectiveRole: "CONTRACT_BODY", roleDecisionId: "role-contract", identityDecisionId: "identity-contract",
      sourceRoleDispositionId: null, selectedBy: "actor", snapshottedAt: check.createdAt },
    { runMaterialId: "material-award", runId: output.run.runId, ordinal: 2,
      projectId: output.run.projectId, fileId: "file-award", fileSha256: "a".repeat(64),
      parseResultId: "parse-2", parseVersion: 1, canonicalTextSha256: "b".repeat(64),
      effectiveRole: "AWARD_NOTICE", roleDecisionId: "role-award", identityDecisionId: "identity-award",
      sourceRoleDispositionId: null, selectedBy: "actor", snapshottedAt: check.createdAt },
  ];

  const adapted = adaptDurableF02Output(output, materials);

  assert.ok(adapted);
  assert.equal(adapted.result.findings.length, 1, "carrier must not become a second result");
  const source = adapted.progress.items[1]?.source;
  assert.ok(source?.available);
  assert.deepEqual(source.sources.map((item) => [item.role, item.fileId, item.locatorAvailable]), [
    ["CONTRACT", "file-contract", true],
    ["AWARD_NOTICE", "file-award", true],
  ]);
});

test("V2 F02 output rejects evidence that is not in the same exact run material snapshot", () => {
  const output = f02Output();
  output.step = { ...output.step, outputResourceType: "AUDIT_TASK_F02_NAVIGATION_V2" };
  const check = output.auditCheckResults[0]!;
  output.auditCheckResults = [check];
  const findingId = "finding-for-check";
  output.findings = [{
    findingId, runId: output.run.runId, stepId: output.step.stepId, attemptId: "attempt-1",
    ordinal: 1, findingType: "F02_AUDIT_CHECK_RESULT", category: check.ruleCode,
    statusCode: check.checkStatus, attentionCode: null, riskLevel: check.riskLevel,
    summary: check.detail, recommendation: null, findingSchemaVersion: 2,
    policyVersion: output.step.stepPolicyVersion, source: "DETERMINISTIC", modelProvider: null,
    modelName: null, modelRevision: null, promptVersion: null, modelSchemaVersion: null,
    createdAt: check.createdAt, versionNo: 0,
  }];
  output.evidence = [{
    evidenceId: "evidence-live", findingId, runId: output.run.runId, stepId: output.step.stepId,
    attemptId: "attempt-1", ordinal: 1, fileId: "file-not-frozen", fileSha256: "a".repeat(64),
    parseResultId: "parse-live", parseVersion: 1, canonicalTextSha256: "b".repeat(64),
    evidenceRole: "COMPARISON", validationStatus: "VALIDATED",
    validationPolicyVersion: "f02-frozen-evidence-v1", pageNumber: 1, paragraphIndex: null,
    tableIndex: null, rowIndex: null, cellIndex: null, segmentIndex: null, locatorAvailable: true,
    startOffset: 0, endOffset: 4, excerptSha256: "c".repeat(64), boundedExcerpt: "amount",
    locatorSchemaVersion: 1, createdAt: check.createdAt, versionNo: 0,
  }];
  output.findingCheckResultRelations = { [findingId]: check.id };

  assert.throws(() => adaptDurableF02Output(output, []), /无法验证精确校验结果/);
});

test("V2 F02 output rejects a missing explicit checkResult-to-finding relation", () => {
  const output = f02Output();
  output.step = { ...output.step, outputResourceType: "AUDIT_TASK_F02_NAVIGATION_V2" };
  assert.throws(() => adaptDurableF02Output(output), /无法验证精确校验结果/);
});
