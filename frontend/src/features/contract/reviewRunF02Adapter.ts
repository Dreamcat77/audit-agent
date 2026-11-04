import type { ReviewRunMaterialDto, ReviewStepOutputDto } from "./reviewRunTypes.ts";
import type { FindingSourceAnchor, FindingSourceProjection, ReviewProgress, ReviewResult, RiskLevel } from "./types.ts";
import type { ReviewAuditCheckResultDto, ReviewEvidenceDto, ReviewFindingDto } from "./reviewRunTypes.ts";

export interface DurableF02Projection {
  result: ReviewResult;
  progress: ReviewProgress;
}

const RISK_ORDER: Record<RiskLevel, number> = { GREEN: 0, YELLOW: 1, RED: 2 };

function riskSummary(riskCount: number): ReviewProgress["riskSummary"] {
  if (riskCount === 0) return "无风险";
  if (riskCount <= 2) return "低风险";
  return "高风险";
}

function conflict(): never {
  throw new Error("无法验证精确校验结果，请稍后重新读取审核记录");
}

const CROSS_FILE_RULES = new Set(["F02_AWARD_AMOUNT_MATCH", "F02_AWARD_SUPPLIER_MATCH"]);

function evidenceRole(material: ReviewRunMaterialDto): string {
  if (material.effectiveRole === "CONTRACT_BODY") return "CONTRACT";
  return material.effectiveRole;
}

function locator(item: ReviewEvidenceDto): string {
  if (!item.locatorAvailable) return "frozen:locator-unavailable";
  if (item.pageNumber !== null) return `pdf/page:${item.pageNumber}`;
  if (item.startOffset !== null && item.endOffset !== null) {
    return `frozen/text:${item.startOffset}-${item.endOffset}`;
  }
  if (item.paragraphIndex !== null) return `frozen/paragraph:${item.paragraphIndex}`;
  return "frozen:validated-source";
}

function sourceProjection(output: ReviewStepOutputDto, check: ReviewAuditCheckResultDto,
  finding: ReviewFindingDto, frozenMaterials: ReadonlyMap<string, ReviewRunMaterialDto>): FindingSourceProjection {
  const evidence = output.evidence.filter((item) => item.findingId === finding.findingId)
    .sort((left, right) => left.ordinal - right.ordinal);
  if (evidence.length === 0) return {
    available: false,
    reason: finding.attentionCode?.startsWith("SOURCE_UNAVAILABLE:")
      ? finding.attentionCode.slice("SOURCE_UNAVAILABLE:".length) : "SOURCE_UNAVAILABLE",
    comparisonMode: null,
    sources: [],
  };
  const anchors: FindingSourceAnchor[] = evidence.map((item) => {
    const material = frozenMaterials.get(item.fileId);
    if (!material
        || material.runId !== output.run.runId
        || material.projectId !== output.run.projectId
        || material.fileSha256 !== item.fileSha256
        || material.parseResultId !== item.parseResultId
        || material.parseVersion !== item.parseVersion
        || material.canonicalTextSha256 !== item.canonicalTextSha256) conflict();
    return {
      role: evidenceRole(material),
      projectId: output.run.projectId,
      fileId: item.fileId,
      pageNumber: item.locatorAvailable ? item.pageNumber : null,
      paragraphIndex: item.locatorAvailable ? item.paragraphIndex : null,
      locator: locator(item),
      excerpt: item.boundedExcerpt,
      locatorAvailable: item.locatorAvailable,
    };
  });
  return {
    available: true,
    reason: null,
    comparisonMode: CROSS_FILE_RULES.has(check.ruleCode) ? "CROSS_FILE" : "INTERNAL",
    sources: anchors,
  };
}

export function adaptDurableF02Output(output: ReviewStepOutputDto | null,
  materials: readonly ReviewRunMaterialDto[] = []): DurableF02Projection | null {
  if (!output || output.step.stepType !== "F02_DETERMINISTIC") return null;
  if (output.step.status !== "COMPLETED") return null;
  const task = output.auditTask;
  if (!task
      || !["AUDIT_TASK", "AUDIT_TASK_F02_NAVIGATION_V2"].includes(output.step.outputResourceType ?? "")
      || output.step.outputResourceId !== task.id
      || task.sourceType !== "CONTRACT"
      || task.sourceId !== output.run.contractId
      || task.sourceVersion !== output.run.contractVersion
      || task.status !== "DONE"
      || output.auditCheckResults.length === 0
      || output.auditCheckResults.some((check) => check.taskId !== task.id)) conflict();

  const findings = output.auditCheckResults.map((check) => ({
    id: check.id,
    ruleCode: check.ruleCode,
    itemKey: check.itemKey,
    checkStatus: check.checkStatus,
    riskLevel: check.riskLevel,
    blockSign: check.blockSign,
    actualValue: check.actualValue,
    expectedValue: check.expectedValue,
    detail: check.detail,
    ruleVersion: check.ruleVersion,
    createdAt: check.createdAt,
  }));
  const projectedSources = new Map<string, FindingSourceProjection>();
  if (output.step.outputResourceType === "AUDIT_TASK") {
    if (output.findings.length > 0 || output.evidence.length > 0) conflict();
  } else if (output.step.outputResourceType === "AUDIT_TASK_F02_NAVIGATION_V2") {
    const frozenMaterials = new Map(materials.map((material) => [material.fileId, material]));
    const relations = output.findingCheckResultRelations;
    if (!relations || Object.keys(relations).length !== output.auditCheckResults.length
        || output.findings.length !== output.auditCheckResults.length) conflict();
    const findingById = new Map(output.findings.map((finding) => [finding.findingId, finding]));
    for (const check of output.auditCheckResults) {
      const related = Object.entries(relations).filter(([, checkId]) => checkId === check.id);
      if (related.length !== 1) conflict();
      const carrier = findingById.get(related[0]![0]);
      if (!carrier) conflict();
      projectedSources.set(check.id, sourceProjection(output, check, carrier, frozenMaterials));
    }
  } else conflict();
  const overallRisk = findings.reduce<RiskLevel>((current, finding) =>
    RISK_ORDER[finding.riskLevel] > RISK_ORDER[current] ? finding.riskLevel : current, "GREEN");
  const riskCount = findings.filter((finding) => finding.riskLevel !== "GREEN").length;

  return {
    result: {
      taskId: task.id,
      status: task.status,
      reviewType: task.reviewType,
      overallRisk,
      blockSign: findings.some((finding) => finding.blockSign),
      replayed: output.replayed === true,
      findings,
    },
    progress: {
      total: findings.length + 1,
      riskSummary: riskSummary(riskCount),
      items: [
        {
          type: "SOURCE_PREPARATION",
          checkResultId: null,
          title: "读取本次审核材料",
          displayOrder: 0,
          status: "DONE",
          risk: null,
          completedAt: task.startedAt,
          acknowledgement: null,
        },
        ...findings.map((finding, index) => ({
          type: "FINDING" as const,
          checkResultId: finding.id,
          title: finding.ruleCode,
          displayOrder: index + 1,
          status: finding.checkStatus,
          risk: finding.riskLevel,
          completedAt: finding.createdAt,
          acknowledgement: null,
          source: projectedSources.get(finding.id) ?? null,
        })),
      ],
    },
  };
}
