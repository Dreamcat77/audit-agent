import assert from "node:assert/strict";
import test from "node:test";

import { createEightCategoryReviewProjection } from "./eightCategoryReviewProjection.ts";
import { buildOverallOpinion } from "./contractReviewOpinion.ts";
import type {
  ReviewAuditCheckResultDto,
  ReviewFindingDto,
  ReviewRunDto,
  ReviewStepOutputDto,
  ReviewStepType,
} from "./reviewRunTypes.ts";

const NOW = "2026-09-23T08:00:00Z";

function finding(id: string, category: string, attentionCode: string | null,
  summary = `${category} persisted summary`, findingType = "SEMANTIC_HINT"): ReviewFindingDto {
  return {
    findingId: id, runId: "run-1", stepId: "step-semantic", attemptId: "attempt-semantic",
    ordinal: 1, findingType, category, statusCode: null, attentionCode, riskLevel: null,
    summary, recommendation: attentionCode === "REVIEW" ? "复核已持久化差异" : null,
    findingSchemaVersion: 1, policyVersion: "policy", source: "MODEL_ASSISTED",
    modelProvider: "private-provider", modelName: "model", modelRevision: null,
    promptVersion: "prompt", modelSchemaVersion: "1", createdAt: NOW, versionNo: 0,
  };
}

function check(id: string, ruleCode: string, status: ReviewAuditCheckResultDto["checkStatus"],
  itemKey: string | null): ReviewAuditCheckResultDto {
  return {
    id, taskId: "task-f02", ruleCode, itemKey, checkStatus: status,
    riskLevel: status === "FAIL" ? "RED" : status === "NOT_EVALUATED" ? "YELLOW" : "GREEN",
    blockSign: status === "FAIL", actualValue: status === "NOT_EVALUATED" ? null : "100.00",
    expectedValue: status === "NOT_EVALUATED" ? null : "100.00",
    detail: `${ruleCode} ${itemKey ?? "contract"} persisted detail`, ruleVersion: 1, createdAt: NOW,
  };
}

function output(stepType: ReviewStepType, values: Partial<ReviewStepOutputDto> = {}): ReviewStepOutputDto {
  return {
    run: null as never,
    step: {
      stepId: `step-${stepType}`, runId: "run-1", stepType, required: true, status: "COMPLETED",
      stepPolicyVersion: "policy", currentAttemptNo: 1, outputResourceType: "REVIEW_FINDING_SET",
      outputResourceId: `output-${stepType}`, safeReasonCode: null, startedAt: NOW,
      completedAt: NOW, versionNo: 1,
    },
    attempts: [], auditTask: null, auditCheckResults: [], findings: [], evidence: [], ...values,
  };
}

function completeRun(): ReviewRunDto {
  const f02 = output("F02_DETERMINISTIC", {
    auditTask: {
      id: "task-f02", sourceType: "CONTRACT", sourceId: "contract-1", sourceVersion: 1,
      reviewType: "F02_DETERMINISTIC", status: "DONE", createdBy: "actor", requestId: "request",
      traceId: "trace", createdAt: NOW, startedAt: NOW, finishedAt: NOW, versionNo: 1,
    },
    auditCheckResults: [
      check("check-line-1", "F02_LINE_ITEM_ARITHMETIC", "PASS", "line-1"),
      check("check-line-2", "F02_LINE_ITEM_ARITHMETIC", "FAIL", "line-2"),
      check("check-words", "F02_AMOUNT_WORDS_MATCH", "NOT_EVALUATED", null),
    ],
  });
  const semantic = output("SEMANTIC_AI", { findings: [
    finding("semantic-structure", "CONTRACT_STRUCTURE", "REVIEW", "结构差异已持久化"),
    finding("semantic-supplier", "SUPPLIER_IDENTITY", "INFO", "供应商名称基础核对"),
    finding("semantic-scope", "SCOPE_AND_SUBCONTRACTING", "INFO", "材料包含服务范围描述"),
    finding("semantic-risk", "SERVICE_WARRANTY", "REVIEW", "质保条款差异"),
    finding("semantic-breach", "BREACH_LIABILITY", "REVIEW", "违约责任语义差异"),
    finding("semantic-term", "TERM_TERMINATION", "INFO", "终止条件语义提示"),
  ] });
  const breach = output("BREACH", { findings: [
    { ...finding("breach-1", "BREACH", null, "每日违约比例存在差异", "BREACH_COMPARISON"),
      statusCode: "REVIEW", source: "DETERMINISTIC" },
  ] });
  const term = output("TERM", { findings: [
    { ...finding("term-effective", "TERM", null, "双方签章后生效", "EFFECTIVE_EVENT"),
      statusCode: "NORMAL", source: "DETERMINISTIC" },
    { ...finding("term-end", "TERM", null, "解除条件存在差异", "TERMINATION"),
      statusCode: "REVIEW", source: "DETERMINISTIC" },
  ] });
  const run: ReviewRunDto = {
    runId: "run-1", projectId: "project-1", contractId: "contract-1", contractVersion: 1,
    executionMode: "SERVER_WORKER", sourceCandidateId: "candidate-1", sourceConfirmationId: "confirmation-1",
    runSchemaVersion: 2, contractSnapshotCanonicalJson: "{}", contractSnapshotSha256: "a".repeat(64),
    reviewProfileVersion: "profile", ruleVersion: "rules", modelPolicyVersion: "models",
    inputSnapshotIdentity: "snapshot", requestedBy: "actor", status: "COMPLETED",
    requestedAt: NOW, startedAt: NOW, completedAt: NOW, failedAt: null, unknownAt: null,
    safeReasonCode: null, versionNo: 1, materials: [],
    steps: [f02.step, semantic.step, breach.step, term.step], attempts: [],
    outputs: [], replayed: false,
  };
  run.outputs = [f02, semantic, breach, term].map((item) => ({
    ...item,
    run,
    findings: item.findings.map((value) => ({
      ...value,
      stepId: item.step.stepId,
      attemptId: `attempt-${item.step.stepType}`,
    })),
  }));
  return run;
}

test("projects the fixed eight business categories and preserves every F02 line result", () => {
  const categories = createEightCategoryReviewProjection(completeRun(), new Map());

  assert.deepEqual(categories.map((item) => item.id), [
    "QUANTITATIVE", "DRAFT_STRUCTURE", "BREACH", "TERM", "PARTY_QUALIFICATION",
    "ATTACHMENT_COMPLETENESS", "EFFECTIVENESS_COPIES", "CLAUSE_RISK",
  ]);
  assert.deepEqual(categories.map((item) => item.order), [1, 2, 3, 4, 5, 6, 7, 8]);
  const quantitative = categories[0]!;
  assert.equal(quantitative.items.length, 3);
  assert.deepEqual(quantitative.items.map((item) => item.stableId), [
    "run-1:F02_DETERMINISTIC:check-line-1",
    "run-1:F02_DETERMINISTIC:check-line-2",
    "run-1:F02_DETERMINISTIC:check-words",
  ]);
  assert.equal(quantitative.judgement, "ATTENTION", "persisted difference must remain prominent");
});

test("does not overclaim template, qualification, attachment, effectiveness or copies coverage", () => {
  const byId = new Map(createEightCategoryReviewProjection(completeRun(), new Map())
    .map((item) => [item.id, item]));

  assert.equal(byId.get("DRAFT_STRUCTURE")?.coverage, "PARTIALLY_COVERED");
  assert.equal(byId.get("DRAFT_STRUCTURE")?.judgement, "ATTENTION");
  assert.ok(byId.get("DRAFT_STRUCTURE")?.uncoveredScopes.includes("未指定合同模板，未完成模板结构对标"));
  assert.equal(byId.get("PARTY_QUALIFICATION")?.coverage, "WAITING_EXTERNAL");
  assert.equal(byId.get("PARTY_QUALIFICATION")?.judgement, "INSUFFICIENT");
  assert.equal(byId.get("ATTACHMENT_COMPLETENESS")?.coverage, "NOT_COVERED");
  assert.equal(byId.get("ATTACHMENT_COMPLETENESS")?.items.length, 0,
    "scope/material list is not attachment matching evidence");
  assert.equal(byId.get("EFFECTIVENESS_COPIES")?.coverage, "PARTIALLY_COVERED");
  assert.ok(byId.get("EFFECTIVENESS_COPIES")?.uncoveredScopes.includes("合同份数未检查"));
});

test("marks external qualification as waiting and keeps effectiveness separate from persisted copy facts", () => {
  const run = completeRun();
  const term = run.outputs.find((item) => item.step.stepType === "TERM")!;
  term.findings.push({
    ...finding("term-copies", "TERM", null, "合同份数：原文约定一式 6 份。", "CONTRACT_COPIES"),
    stepId: term.step.stepId, attemptId: "attempt-TERM", statusCode: "INSUFFICIENT",
    source: "DETERMINISTIC",
  });

  const byId = new Map(createEightCategoryReviewProjection(run, new Map())
    .map((item) => [item.id, item]));

  assert.equal(byId.get("PARTY_QUALIFICATION")?.coverage, "WAITING_EXTERNAL");
  assert.equal(byId.get("PARTY_QUALIFICATION")?.statusLabel, "等待外部主体资格核验");
  assert.deepEqual(byId.get("EFFECTIVENESS_COPIES")?.items.map((item) => item.outputId),
    ["term-effective", "term-copies"]);
  assert.equal(byId.get("EFFECTIVENESS_COPIES")?.coverage, "PARTIALLY_COVERED");
  assert.ok(byId.get("EFFECTIVENESS_COPIES")?.uncoveredScopes.includes("缺少权威合同份数要求，当前仅展示原文份数事实"));
  assert.ok(!byId.get("TERM")?.items.some((item) => item.outputId === "term-copies"));
});

test("uses dedicated persisted template and attachment checks as category coverage", () => {
  const run = completeRun();
  const term = run.outputs.find((item) => item.step.stepType === "TERM")!;
  term.findings.push({
    ...finding("template-structure", "TERM", null,
      "已按冻结材料中的指定模板核对合同结构；当前合同未识别到：争议解决。",
      "TEMPLATE_STRUCTURE_COMPARISON"),
    stepId: term.step.stepId, attemptId: "attempt-TERM", statusCode: "REVIEW",
    source: "DETERMINISTIC",
  }, {
    ...finding("attachment-match", "TERM", null,
      "合同正文引用附件“技术规格书”，并在本次冻结材料中找到可定位内容。",
      "ATTACHMENT_REFERENCE_MATCH"),
    stepId: term.step.stepId, attemptId: "attempt-TERM", statusCode: "NORMAL",
    source: "DETERMINISTIC",
  }, {
    ...finding("attachment-missing", "TERM", null,
      "合同正文引用附件“报价清单”，但本次冻结材料中未能证明匹配。",
      "ATTACHMENT_REFERENCE_MATCH"),
    stepId: term.step.stepId, attemptId: "attempt-TERM", statusCode: "REVIEW",
    source: "DETERMINISTIC",
  });

  const byId = new Map(createEightCategoryReviewProjection(run, new Map())
    .map((item) => [item.id, item]));

  assert.deepEqual([byId.get("DRAFT_STRUCTURE")?.coverage, byId.get("DRAFT_STRUCTURE")?.judgement],
    ["COVERED", "ATTENTION"]);
  assert.deepEqual(byId.get("DRAFT_STRUCTURE")?.items.map((item) => item.outputId),
    ["template-structure"]);
  assert.deepEqual([byId.get("ATTACHMENT_COMPLETENESS")?.coverage,
    byId.get("ATTACHMENT_COMPLETENESS")?.judgement], ["PARTIALLY_COVERED", "ATTENTION"]);
  assert.deepEqual(byId.get("ATTACHMENT_COMPLETENESS")?.items.map((item) => item.outputId),
    ["attachment-match", "attachment-missing"]);
  assert.ok(!byId.get("TERM")?.items.some((item) =>
    ["template-structure", "attachment-match", "attachment-missing"].includes(item.outputId)));
});

test("marks attachment completeness covered only when every persisted reference is matched", () => {
  const run = completeRun();
  const term = run.outputs.find((item) => item.step.stepType === "TERM")!;
  term.findings.push({
    ...finding("attachment-match", "TERM", null,
      "合同正文引用附件“技术规格书”，冻结材料已取得唯一匹配。", "ATTACHMENT_REFERENCE_MATCH"),
    stepId: term.step.stepId, attemptId: "attempt-TERM", statusCode: "NORMAL",
    source: "DETERMINISTIC",
  });

  const attachment = createEightCategoryReviewProjection(run, new Map())
    .find((item) => item.id === "ATTACHMENT_COMPLETENESS")!;

  assert.deepEqual([attachment.coverage, attachment.judgement], ["COVERED", "NORMAL"]);
  assert.deepEqual(attachment.uncoveredScopes, []);
});

test("keeps persisted ATTENTION and unknown finding states out of NORMAL and in the overall opinion", () => {
  const run = completeRun();
  const term = run.outputs.find((item) => item.step.stepType === "TERM")!;
  term.findings.push({
    ...finding("attachment-attention", "TERM", null,
      "附件引用与冻结材料不一致", "ATTACHMENT_REFERENCE_MATCH"),
    stepId: term.step.stepId, attemptId: "attempt-TERM", statusCode: "ATTENTION",
    source: "DETERMINISTIC",
  }, {
    ...finding("attachment-unknown", "TERM", null,
      "附件检查返回了当前前端不认识的持久化状态", "ATTACHMENT_REFERENCE_MATCH"),
    stepId: term.step.stepId, attemptId: "attempt-TERM", statusCode: "FUTURE_STATE",
    source: "DETERMINISTIC",
  });

  const categories = createEightCategoryReviewProjection(run, new Map());
  const attachment = categories.find((item) => item.id === "ATTACHMENT_COMPLETENESS")!;
  const byOutput = new Map(attachment.items.map((item) => [item.outputId, item]));

  assert.equal(byOutput.get("attachment-attention")?.status, "ATTENTION");
  assert.equal(byOutput.get("attachment-unknown")?.status, "INSUFFICIENT");
  assert.equal(attachment.judgement, "ATTENTION");
  assert.equal(attachment.attentionCount, 2);
  const opinion = buildOverallOpinion(categories, run.runId, run.status);
  assert.match(opinion, /附件引用与冻结材料不一致/);
  assert.match(opinion, /附件检查返回了当前前端不认识的持久化状态/);
});

test("reports failed and unknown required steps as unfinished without fabricating normal results", () => {
  const run = completeRun();
  const semantic = run.outputs.find((item) => item.step.stepType === "SEMANTIC_AI")!;
  const breach = run.outputs.find((item) => item.step.stepType === "BREACH")!;
  semantic.step = { ...semantic.step, status: "UNKNOWN", safeReasonCode: "OUTCOME_UNCERTAIN" };
  breach.step = { ...breach.step, status: "FAILED", safeReasonCode: "PROVIDER_FAILURE" };

  const byId = new Map(createEightCategoryReviewProjection(run, new Map()).map((item) => [item.id, item]));
  assert.deepEqual([byId.get("DRAFT_STRUCTURE")?.coverage, byId.get("DRAFT_STRUCTURE")?.judgement],
    ["UNFINISHED", "UNAVAILABLE"]);
  assert.equal(byId.get("DRAFT_STRUCTURE")?.statusLabel, "结果状态待确认");
  assert.deepEqual([byId.get("BREACH")?.coverage, byId.get("BREACH")?.judgement],
    ["UNFINISHED", "UNAVAILABLE"]);
});

test("uses stable identities for referenced findings and excludes breach/term primary findings from clause-risk counts", () => {
  const byId = new Map(createEightCategoryReviewProjection(completeRun(), new Map()).map((item) => [item.id, item]));
  const breachIds = byId.get("BREACH")!.items.map((item) => item.stableId);
  const clauseRiskIds = byId.get("CLAUSE_RISK")!.items.map((item) => item.stableId);

  assert.ok(breachIds.includes("run-1:SEMANTIC_AI:semantic-breach"));
  assert.ok(breachIds.includes("run-1:BREACH:breach-1"));
  assert.ok(!clauseRiskIds.includes("run-1:SEMANTIC_AI:semantic-breach"));
  assert.ok(!clauseRiskIds.includes("run-1:SEMANTIC_AI:semantic-term"));
  assert.equal(new Set(clauseRiskIds).size, clauseRiskIds.length);
});

test("projects persisted typed bilateral sides without deriving them from summaries", () => {
  const run = completeRun();
  const breach = run.outputs.find((item) => item.step.stepType === "BREACH")!;
  breach.findings[0] = {
    ...breach.findings[0]!, findingSchemaVersion: 2,
    typedComparison: {
      comparisonId: "comparison-1", findingId: "breach-1", runId: "run-1",
      stepId: breach.step.stepId, attemptId: "attempt-BREACH", comparisonKey: "SUPPLIER_DELAY",
      kind: "BREACH", result: "NOT_EVALUATED", schemaVersion: 2,
      values: [{
        valueId: "value-contract", comparisonId: "comparison-1", runId: "run-1",
        sideKey: "CONTRACT_BODY:file-contract", sourceFileId: "file-contract", state: "KNOWN",
        scenarioKey: "SUPPLIER_DELAY", responsibleParty: "PARTY_B", calculationBasis: "CONTRACT_AMOUNT",
        rate: 0.001, rateUnit: "PERCENT", frequency: "PER_DAY", period: null,
        capRate: 0.1, capAmount: null, terminationThreshold: null, dateType: null,
        normalizedDate: null, termStart: null, duration: null, effectiveCondition: null,
        terminationCondition: null, evidenceIds: ["evidence-contract"],
      }, {
        valueId: "value-tender", comparisonId: "comparison-1", runId: "run-1",
        sideKey: "TENDER_DOCUMENT:file-tender", sourceFileId: "file-tender", state: "NOT_EXTRACTED",
        scenarioKey: "SUPPLIER_DELAY", responsibleParty: null, calculationBasis: null,
        rate: null, rateUnit: null, frequency: null, period: null, capRate: null, capAmount: null,
        terminationThreshold: null, dateType: null, normalizedDate: null, termStart: null,
        duration: null, effectiveCondition: null, terminationCondition: null, evidenceIds: [],
      }],
    },
  };

  const category = createEightCategoryReviewProjection(run, new Map())
    .find((item) => item.id === "BREACH")!;
  const item = category.items.find((value) => value.outputId === "breach-1")!;
  assert.deepEqual(item.typedFacts, [
    "合同侧：责任方 乙方；基数 合同总价；费率 0.001%；频次 每天；上限费率 0.1",
    "采购依据侧：未提取到可比较事实",
  ]);
});

test("legacy deterministic rule prose and comparison enums use Chinese without altering raw facts", () => {
  const run = completeRun();
  const f02 = run.outputs.find((part) => part.step.stepType === "F02_DETERMINISTIC")!;
  f02.auditCheckResults = [{ ...check("supplier", "F02_AWARD_SUPPLIER_MATCH", "FAIL", null),
    detail: "Contract supplier differs from award supplier by normalized supplier name",
    actualValue: "DIFFERENT", expectedValue: "MATCH" }];
  const item = createEightCategoryReviewProjection(run, new Map())[0]!.items[0]!;
  assert.equal(item.summary, "合同乙方与中标供应商的规范化名称不一致");
  assert.equal(item.actualValue, "不一致");
  assert.equal(item.expectedValue, "一致");
  assert.equal(item.status, "ATTENTION");
  assert.equal(f02.auditCheckResults[0]!.actualValue, "DIFFERENT");
  assert.equal(f02.auditCheckResults[0]!.expectedValue, "MATCH");
});

test("server versioned business projection is used unchanged by page and copied opinions", () => {
  const run = completeRun();
  run.businessProjection = {
    version: 1, runId: run.runId, runStatus: run.status,
    scopeStatement: "人工确认不表示问题已解决。",
    categories: [{ id: "QUANTITATIVE", order: 1, title: "金额一致性", coverage: "COVERED",
      judgement: "NORMAL", statusLabel: "已核对未发现问题", attentionCount: 0,
      summary: "已核对合同与中标金额123456.78元，两者一致。", items: [], uncoveredScopes: [] },
      ...createEightCategoryReviewProjection(run, new Map()).slice(1)],
  };
  const categories = createEightCategoryReviewProjection(run, new Map());
  assert.equal(categories[0]!.title, "金额一致性");
  assert.equal(categories[0]!.summary, "已核对合同与中标金额123456.78元，两者一致。");
  assert.match(buildOverallOpinion(categories, run.runId, run.status), /123456\.78/);
  run.businessProjection = { ...run.businessProjection, runId: "other-run" };
  assert.throws(() => createEightCategoryReviewProjection(run, new Map()), /无法验证/);
});

test("server opinion metadata is inherited by every category without accepting an undefined projection version", () => {
  const run = completeRun();
  const legacy = createEightCategoryReviewProjection(run, new Map());
  run.businessProjection = {
    version: 1, runId: run.runId, runStatus: run.status, scopeStatement: "冻结材料范围。",
    opinionVersion: 3, overallAdvice: "请核实主体资格与附件九。",
    categories: legacy.map((category) => ({ ...category, conciseOpinion: `${category.title}的具体简版结论。` })),
  };
  const categories = createEightCategoryReviewProjection(run, new Map());
  for (const category of categories) {
    assert.equal(category.opinionVersion, 3);
    assert.equal(category.overallAdvice, "请核实主体资格与附件九。");
    assert.equal(category.conciseOpinion, `${category.title}的具体简版结论。`);
  }
  run.businessProjection = { ...run.businessProjection, version: 2 as never };
  assert.throws(() => createEightCategoryReviewProjection(run, new Map()), /无法验证/);
});

test("canonical opinion and frozen observation metadata reach every category without changing persisted risk details", () => {
  const run = completeRun();
  const legacy = createEightCategoryReviewProjection(run, new Map());
  const observation = { protocol: "contract-money-observation-v1", mode: "OBSERVE",
    warnings: [{ fieldPath: "hints[0].summary", errorKind: "MONETARY_CALCULATION", category: "DELIVERY_TERMS", textSha256: "a".repeat(64), ruleSubtypes: ["UNSAFE_CONCLUSION"] }],
    workWarnings: [{ logicalKey: "A1", ordinal: 1, purpose: "ANALYZE", operationFingerprint: "b".repeat(64), warningCount: 1, warningsSha256: "c".repeat(64) }] };
  run.businessProjection = { version: 1, runId: run.runId, runStatus: run.status,
    scopeStatement: "冻结材料范围。", opinionVersion: 4, overallOpinion: "合同金额存在差异，需复核交付条件。",
    monetaryObservation: observation, categories: legacy };
  const projected = createEightCategoryReviewProjection(run, new Map());
  for (let index = 0; index < projected.length; index++) {
    assert.equal(projected[index]!.overallOpinion, "合同金额存在差异，需复核交付条件。");
    assert.deepEqual(projected[index]!.monetaryObservation, observation);
    assert.deepEqual(projected[index]!.items, legacy[index]!.items);
    assert.equal(projected[index]!.id, legacy[index]!.id);
  }
});

test("projection rejects malformed or unsupported canonical opinion and observation metadata", () => {
  const run = completeRun();
  const categories = createEightCategoryReviewProjection(run, new Map());
  const observation = { protocol: "contract-money-observation-v1", mode: "OBSERVE", warnings: [], workWarnings: [] };
  const base = { version: 1, runId: run.runId, runStatus: run.status, scopeStatement: "冻结材料。", categories };
  for (const metadata of [
    { overallOpinion: " " }, { overallOpinion: 42 }, { monetaryObservation: null },
    { monetaryObservation: { ...observation, protocol: "unsupported" } },
    { monetaryObservation: { ...observation, mode: "OFF" } },
    { monetaryObservation: { ...observation, mode: ["OBSERVE"] } },
    { monetaryObservation: { ...observation, warnings: {} } },
    { monetaryObservation: { ...observation, warnings: [{ fieldPath: "summary", errorKind: "TYPE_ERROR", category: "DELIVERY_TERMS", textSha256: "a".repeat(64) }] } },
    { monetaryObservation: { ...observation, warnings: [{ fieldPath: "summary", errorKind: "MONETARY_VALUE", category: "DELIVERY_TERMS", textSha256: "not-a-hash" }] } },
    { monetaryObservation: { ...observation, workWarnings: [{ logicalKey: "A1", ordinal: 1, purpose: "ANALYZE", operationFingerprint: "b".repeat(64), warningCount: -1, warningsSha256: "c".repeat(64) }] } },
    { monetaryObservation: { ...observation, workWarnings: [{ logicalKey: "A1", ordinal: 0, purpose: "ANALYZE", operationFingerprint: "b".repeat(64), warningCount: 1, warningsSha256: "c".repeat(64) }] } },
    { monetaryObservation: { ...observation, warnings: [{ fieldPath: "summary", errorKind: ["MONETARY_VALUE"], category: "DELIVERY_TERMS", textSha256: "a".repeat(64) }] } },
    { monetaryObservation: { ...observation, workWarnings: [{ logicalKey: "A1", ordinal: 1, purpose: ["ANALYZE"], operationFingerprint: "b".repeat(64), warningCount: 1, warningsSha256: "c".repeat(64) }] } },
    { monetaryObservation: { ...observation, warningCount: 0.5 } },
    { monetaryObservation: { ...observation, warningCount: -1 } },
    { monetaryObservation: { ...observation, warnings: [{ fieldPath: "summary", errorKind: "MONETARY_VALUE", category: "DELIVERY_TERMS", textSha256: "a".repeat(64), ruleSubtypes: "ABSOLUTE_VALUE" }] } },
    { monetaryObservation: { ...observation, warnings: [{ fieldPath: "summary", errorKind: "MONETARY_VALUE", category: "DELIVERY_TERMS", textSha256: "a".repeat(64), ruleSubtypes: ["INVENTED"] }] } },
    { monetaryObservation: { ...observation, warnings: [{ fieldPath: "summary", errorKind: "MONETARY_VALUE", category: "DELIVERY_TERMS", textSha256: "a".repeat(64), ruleSubtypes: [42] }] } },
    { monetaryObservation: { ...observation, warnings: [{ fieldPath: "hints[0].description", errorKind: "MONETARY_VALUE", category: null, textSha256: "a".repeat(64) }] } },
  ]) {
    run.businessProjection = { ...base, ...metadata } as never;
    assert.throws(() => createEightCategoryReviewProjection(run, new Map()), /无法验证/);
  }
});

test("summary warning may explicitly have no category and still reaches projection and copied opinion", () => {
  const run = completeRun();
  const categories = createEightCategoryReviewProjection(run, new Map());
  run.businessProjection = { version: 1, runId: run.runId, runStatus: run.status, scopeStatement: "冻结材料。", categories,
    overallOpinion: "金额相关模型表述待人工核对。",
    monetaryObservation: { protocol: "contract-money-observation-v1", mode: "OBSERVE", workWarnings: [],
      warnings: ["summary", "summaries[0]"].map(fieldPath => ({ fieldPath, errorKind: "MONETARY_VALUE", category: null,
        textSha256: "a".repeat(64), ruleSubtypes: ["ABSOLUTE_VALUE"] })) } } as never;
  const projected = createEightCategoryReviewProjection(run, new Map());
  assert.equal(projected[0]!.monetaryObservation?.warnings[0]?.category, null);
  assert.match(buildOverallOpinion(projected, run.runId, run.status), /联调参考意见/);
  assert.match(buildOverallOpinion(projected, run.runId, run.status), /金额相关模型表述待人工核对/);
});

test("STRICT diagnostics and protocol extensions remain readable without adding an OBSERVE marker", () => {
  const run = completeRun();
  const categories = createEightCategoryReviewProjection(run, new Map());
  run.businessProjection = { version: 1, runId: run.runId, runStatus: run.status, scopeStatement: "冻结材料。", categories,
    monetaryObservation: { protocol: "contract-money-observation-v1", mode: "STRICT", warningCount: 1,
      warnings: [{ fieldPath: "summary", errorKind: "MONETARY_VALUE", category: "DELIVERY_TERMS", textSha256: "a".repeat(64) }],
      workWarnings: [], futureExtension: true } } as never;
  const projected = createEightCategoryReviewProjection(run, new Map());
  assert.equal(projected[0]!.monetaryObservation?.mode, "STRICT");
  assert.equal(projected[0]!.monetaryObservation?.warnings.length, 1);
  assert.doesNotMatch(buildOverallOpinion(projected, run.runId, run.status), /联调参考意见/);
});

test("legacy F02 source-unavailable carrier preserves numeric PASS and human recommendation while showing source insufficiency", () => {
  const run = completeRun();
  const f02 = run.outputs.find((output) => output.step.stepType === "F02_DETERMINISTIC")!;
  f02.auditCheckResults = [f02.auditCheckResults[0]!];
  f02.findings = [{ ...finding("source-carrier", "F02_LINE_ITEM_ARITHMETIC", "SOURCE_UNAVAILABLE:CONFIRMED_VALUE_DIFFERS_FROM_SOURCE"),
    stepId: f02.step.stepId, statusCode: "PASS", recommendation: "人工确认值与原文值不同，请对照确认记录。" }];
  f02.findingCheckResultRelations = { "source-carrier": f02.auditCheckResults[0]!.id };
  const amount = createEightCategoryReviewProjection(run, new Map())[0]!;
  assert.equal(amount.items[0]!.status, "NORMAL", "numeric PASS is independent of source availability");
  assert.equal(amount.items[0]!.actualValue, "100.00");
  assert.equal(amount.items[0]!.expectedValue, "100.00");
  assert.equal(amount.coverage, "PARTIALLY_COVERED");
  assert.equal(amount.judgement, "INSUFFICIENT");
  assert.match(amount.uncoveredScopes.join("；"), /原文.*待核实/);
  assert.equal(amount.items[0]!.recommendation, "人工确认值与原文值不同，请对照确认记录。");
  assert.match(amount.items[0]!.typedFacts.join("；"), /数值结论：通过.*人工确认.*原文/);
});

test("version 4 opinion rejects a missing canonical paragraph or frozen money mode", () => {
  const run = completeRun();
  const categories = createEightCategoryReviewProjection(run, new Map());
  const base = { version: 1, runId: run.runId, runStatus: run.status, scopeStatement: "冻结材料。", categories, opinionVersion: 4 };
  for (const metadata of [
    {},
    { overallOpinion: "请核对交付条件。" },
    { monetaryObservation: { protocol: "contract-money-observation-v1", mode: "OBSERVE", warnings: [], workWarnings: [] } },
    { monetaryObservation: { protocol: "contract-money-observation-v1", mode: "STRICT", warnings: [], workWarnings: [] } },
  ]) {
    run.businessProjection = { ...base, ...metadata } as never;
    assert.throws(() => createEightCategoryReviewProjection(run, new Map()), /无法验证/);
  }
  for (const mode of ["STRICT", "OBSERVE"]) {
    run.businessProjection = { ...base, overallOpinion: "请核对交付条件。",
      monetaryObservation: { protocol: "contract-money-observation-v1", mode, warnings: [], workWarnings: [] } } as never;
    const projected = createEightCategoryReviewProjection(run, new Map());
    assert.equal(projected[0]!.overallOpinion, "请核对交付条件。");
    assert.equal(projected[0]!.monetaryObservation?.mode, mode);
    assert.equal(buildOverallOpinion(projected, run.runId, run.status).includes("联调参考意见"), mode === "OBSERVE");
  }
});
