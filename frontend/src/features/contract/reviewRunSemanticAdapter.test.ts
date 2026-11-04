import assert from "node:assert/strict";
import test from "node:test";

import { adaptDurableSemanticReview } from "./reviewRunSemanticAdapter.ts";
import type { ReviewFindingDto, ReviewStepOutputDto, ReviewStepType } from "./reviewRunTypes.ts";

function output(stepType: ReviewStepType, status: "COMPLETED" | "FAILED" | "UNKNOWN", ordinal: number): ReviewStepOutputDto {
  const run = {
    runId: "run-1", projectId: "project-1", contractId: "contract-1", contractVersion: 1,
    sourceCandidateId: "candidate", sourceConfirmationId: null, runSchemaVersion: 1,
    contractSnapshotCanonicalJson: "{}", contractSnapshotSha256: "hash", reviewProfileVersion: "profile",
    ruleVersion: "rule", modelPolicyVersion: "model", inputSnapshotIdentity: "identity",
    requestedBy: "actor", status: "PARTIALLY_COMPLETED" as const, requestedAt: "2026-09-16T00:00:00Z",
    startedAt: null, completedAt: null, failedAt: null, unknownAt: null, safeReasonCode: null, versionNo: 1,
  };
  const stepId = `step-${stepType}`;
  const attemptId = `attempt-${stepType}`;
  const finding: ReviewFindingDto = {
    findingId: `finding-${ordinal}`, runId: run.runId, stepId, attemptId, ordinal,
    findingType: `${stepType}_RESULT`, category: stepType === "SEMANTIC_AI" ? "DELIVERY_TERMS" : stepType,
    statusCode: stepType === "TERM" ? "INSUFFICIENT" : "REVIEW", attentionCode: stepType === "SEMANTIC_AI" ? "REVIEW" : null,
    riskLevel: stepType === "BREACH" ? "YELLOW" : null,
    summary: `${stepType} 摘要`, recommendation: "请人工核对来源材料。", findingSchemaVersion: 1,
    policyVersion: "hidden-policy", source: stepType === "SEMANTIC_AI" ? "MODEL_ASSISTED" : "DETERMINISTIC",
    modelProvider: null, modelName: null, modelRevision: null, promptVersion: null,
    modelSchemaVersion: null, createdAt: "2026-09-16T00:01:00Z", versionNo: 0,
  };
  return {
    run,
    step: { stepId, runId: run.runId, stepType, required: true, status,
      stepPolicyVersion: "hidden", currentAttemptNo: 1,
      outputResourceType: status === "COMPLETED" ? "REVIEW_FINDING_SET" : null,
      outputResourceId: status === "COMPLETED" ? attemptId : null,
      safeReasonCode: status === "FAILED" ? "MODEL_UNAVAILABLE" : status === "UNKNOWN" ? "OUTCOME_UNCERTAIN" : null,
      startedAt: null, completedAt: status === "COMPLETED" ? "2026-09-16T00:01:00Z" : null, versionNo: 1 },
    attempts: [], auditTask: null, auditCheckResults: [], replayed: false,
    findings: status === "COMPLETED" ? [finding] : [],
    evidence: status === "COMPLETED" ? [{
      evidenceId: `evidence-${ordinal}`, findingId: finding.findingId, runId: run.runId,
      stepId, attemptId, ordinal: 1, fileId: `file-${ordinal}`, fileSha256: "file-hash",
      parseResultId: `parse-${ordinal}`, parseVersion: 1, canonicalTextSha256: "text-hash",
      evidenceRole: "PROBLEM", validationStatus: ordinal === 1 ? "VALIDATED" : "LOCATOR_UNAVAILABLE",
      validationPolicyVersion: "hidden", pageNumber: ordinal === 1 ? 2 : null,
      paragraphIndex: ordinal === 1 ? 3 : null, tableIndex: null, rowIndex: null, cellIndex: null,
      segmentIndex: null, locatorAvailable: ordinal === 1, startOffset: ordinal === 1 ? 10 : null,
      endOffset: ordinal === 1 ? 20 : null, excerptSha256: "excerpt-hash", boundedExcerpt: "来源摘录",
      locatorSchemaVersion: 1, createdAt: "2026-09-16T00:01:00Z", versionNo: 0,
    }] : [],
  };
}

test("durable semantic, breach and term outputs remain distinct business sections", () => {
  const view = adaptDurableSemanticReview([
    output("SEMANTIC_AI", "COMPLETED", 1),
    output("BREACH", "COMPLETED", 2),
    output("TERM", "COMPLETED", 3),
  ], new Map([["file-1", "合同正文.pdf"], ["file-2", "采购材料.pdf"], ["file-3", "补充材料.pdf"]]));
  assert.deepEqual(view.sections.map((section) => section.label), [
    "智能语义审查", "违约条款审查", "履约期限与终止条件审查",
  ]);
  assert.deepEqual(view.sections.map((section) => section.issues.length), [1, 1, 1]);
  assert.equal(view.sections[0]?.issues[0]?.sourceLabel, "智能分析提示");
  assert.equal(view.sections[0]?.issues[0]?.statusLabel, "建议复核");
  assert.equal(view.sections[0]?.issues[0]?.versionNo, 0);
  assert.equal(view.sections[1]?.issues[0]?.sourceLabel, "规则校验结果");
  assert.equal(view.sections[0]?.issues[0]?.evidence[0]?.fileName, "合同正文.pdf");
  assert.equal(view.sections[0]?.issues[0]?.evidence[0]?.locatorLabel, "第 2 页 · 第 3 段");
  assert.equal(view.sections[1]?.issues[0]?.evidence[0]?.locatorLabel, "暂无法精确定位原文位置，可查看来源材料。");
});

test("semantic attention codes use business labels instead of transport enums", () => {
  const info = output("SEMANTIC_AI", "COMPLETED", 1);
  const infoFinding = { ...info.findings[0]!, statusCode: "INFO", attentionCode: "INFO", riskLevel: null };
  const insufficient = output("TERM", "COMPLETED", 2);
  const view = adaptDurableSemanticReview([
    { ...info, findings: [infoFinding] },
    insufficient,
  ], new Map());
  assert.equal(view.sections[0]?.issues[0]?.statusLabel, "信息提示");
  assert.equal(view.sections[2]?.issues[0]?.statusLabel, "关键事实暂无法确认");
});

test("completed siblings remain visible when another durable step failed or is unknown", () => {
  const view = adaptDurableSemanticReview([
    output("SEMANTIC_AI", "COMPLETED", 1),
    output("BREACH", "FAILED", 2),
    output("TERM", "UNKNOWN", 3),
  ], new Map());
  assert.equal(view.sections[0]?.issues.length, 1);
  assert.equal(view.sections[1]?.statusLabel, "未完成");
  assert.equal(view.sections[2]?.statusLabel, "结果状态待确认");
  assert.equal(view.sections[1]?.statusTone, "danger");
  assert.equal(view.sections[2]?.statusTone, "uncertain");
  assert.match(view.sections[1]?.detail ?? "", /暂不可用/);
  assert.match(view.sections[2]?.detail ?? "", /暂无法确认/);
});

test("new human-readable clause summary retains known values while old serialized findings stay conservative", () => {
  const breach = output("BREACH", "COMPLETED", 2);
  const item = { ...breach.findings[0]!, summary: "逾期交付；违约金比例 2%；计算周期 每周；累计上限比例 20%。" };
  const view = adaptDurableSemanticReview([{ ...breach, findings: [item] }], new Map());
  assert.equal(view.sections[1]?.issues[0]?.summary, item.summary);
  assert.equal(view.sections[1]?.issues[0]?.statusTone, "review");
});

test("adapter preserves semantic prose but replaces serialized clause fields with safe business copy", () => {
  const source = output("BREACH", "COMPLETED", 2);
  const semantic = output("SEMANTIC_AI", "COMPLETED", 1);
  const technicalFinding = {
    ...source.findings[0]!,
    summary: "scenario=OVERDUE_DELIVERY; rate=1 PERCENT; frequency=DAY; calculationBasis=CONTRACT_AMOUNT",
  };
  const view = adaptDurableSemanticReview([
    semantic,
    { ...source, findings: [technicalFinding] },
  ], new Map());
  const issue = view.sections.find((section) => section.key === "BREACH")?.issues[0];
  assert.equal(view.sections.find((section) => section.key === "SEMANTIC")?.issues[0]?.summary, "SEMANTIC_AI 摘要");
  assert.equal(issue?.summary, "系统已识别一项违约条款核对结果，请结合来源依据复核。");
  assert.equal(issue?.recommendation, "请人工核对来源材料。");
  assert.equal("rate" in (issue ?? {}), false);
  assert.equal("frequency" in (issue ?? {}), false);
  assert.doesNotMatch(JSON.stringify(view), /scenario=|OVERDUE_DELIVERY|PERCENT|CONTRACT_AMOUNT|hidden-policy|file-hash|text-hash|excerpt-hash/);
});
