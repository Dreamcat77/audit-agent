import type {
  ReviewRunDto,
  ReviewRunIdentityDto,
  ReviewRunStatus,
  ReviewStepAttemptDto,
  ReviewStepDto,
  ReviewStepOutputDto,
  ReviewStepStatus,
  ReviewStepType,
} from "./reviewRunTypes.ts";
import { assertReviewOpinionMetadata } from "./reviewRunOpinionMetadata.ts";

const REQUIRED_STEP_TYPES: readonly ReviewStepType[] = [
  "MATERIAL_VALIDATION", "F02_DETERMINISTIC", "SEMANTIC_AI", "BREACH", "TERM",
];
const RUN_STATUSES: readonly ReviewRunStatus[] = [
  "PENDING", "RUNNING", "COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "UNKNOWN",
];
const EXECUTION_MODES = ["CLIENT_COMPATIBILITY", "SERVER_WORKER"] as const;
const STEP_STATUSES: readonly ReviewStepStatus[] = [
  "PENDING", "RUNNING", "COMPLETED", "FAILED", "UNKNOWN",
];
const MATERIAL_ROLES = [
  "CONTRACT_BODY", "AWARD_NOTICE", "TENDER_DOCUMENT", "BID_RESPONSE", "SUPPLEMENT", "OTHER",
] as const;
const EXTERNAL_CALL_STATES = ["NOT_STARTED", "REQUESTED", "OUTCOME_UNKNOWN", "COMPLETED"] as const;
const AUDIT_TASK_STATUSES = ["PENDING", "PROCESSING", "DONE", "FAILED"] as const;
const CHECK_STATUSES = ["PASS", "FAIL", "NOT_EVALUATED"] as const;
const RISK_LEVELS = ["GREEN", "YELLOW", "RED"] as const;
const FINDING_SOURCES = ["DETERMINISTIC", "MODEL_ASSISTED"] as const;
const EVIDENCE_ROLES = ["PROBLEM", "COMPARISON", "SUPPORT"] as const;
const VALIDATION_STATUSES = ["VALIDATED", "LOCATOR_UNAVAILABLE"] as const;
const INCOMPLETE_MANIFEST_ERROR = "审核记录结构不完整，暂时无法安全展示";

export type ReviewRunProjectionKind = "CREATE" | "REOPEN";

const RUN_IDENTITY_KEYS: readonly (keyof ReviewRunIdentityDto)[] = [
  "runId", "projectId", "contractId", "contractVersion", "sourceCandidateId",
  "sourceConfirmationId", "runSchemaVersion", "contractSnapshotCanonicalJson",
  "contractSnapshotSha256", "reviewProfileVersion", "ruleVersion", "modelPolicyVersion",
  "inputSnapshotIdentity", "requestedBy", "status", "requestedAt", "startedAt",
  "completedAt", "failedAt", "unknownAt", "safeReasonCode", "versionNo",
];
const STEP_KEYS: readonly (keyof ReviewStepDto)[] = [
  "stepId", "runId", "stepType", "required", "status", "stepPolicyVersion",
  "currentAttemptNo", "outputResourceType", "outputResourceId", "safeReasonCode",
  "startedAt", "completedAt", "versionNo",
];
const ATTEMPT_KEYS: readonly (keyof ReviewStepAttemptDto)[] = [
  "attemptId", "runId", "stepId", "attemptNo", "operationIdentity", "status",
  "leaseOwner", "leaseUntil", "claimedAt", "externalCallState",
  "externalRequestFingerprint", "providerRequestId", "provider", "model", "startedAt",
  "completedAt", "safeReasonCode", "outputResourceType", "outputResourceId", "versionNo",
];

function incomplete(): never {
  throw new Error(INCOMPLETE_MANIFEST_ERROR);
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) incomplete();
  return value as Record<string, unknown>;
}

function stringField(value: Record<string, unknown>, key: string, nonEmpty = true): string {
  const field = value[key];
  if (typeof field !== "string" || nonEmpty && field.trim().length === 0) incomplete();
  return field;
}

function nullableStringField(value: Record<string, unknown>, key: string): string | null {
  const field = value[key];
  if (field !== null && typeof field !== "string") incomplete();
  return field as string | null;
}

function integerField(value: Record<string, unknown>, key: string): number {
  const field = value[key];
  if (typeof field !== "number" || !Number.isSafeInteger(field)) incomplete();
  return field;
}

function nullableIntegerField(value: Record<string, unknown>, key: string): number | null {
  const field = value[key];
  if (field !== null && (typeof field !== "number" || !Number.isSafeInteger(field))) incomplete();
  return field as number | null;
}

function booleanField(value: Record<string, unknown>, key: string): boolean {
  const field = value[key];
  if (typeof field !== "boolean") incomplete();
  return field;
}

function arrayField(value: Record<string, unknown>, key: string): unknown[] {
  const field = value[key];
  if (!Array.isArray(field)) incomplete();
  return field;
}

function enumField<T extends string>(
  value: Record<string, unknown>, key: string, allowed: readonly T[],
): T {
  const field = value[key];
  if (typeof field !== "string" || !allowed.includes(field as T)) incomplete();
  return field as T;
}

function nullableEnumField<T extends string>(
  value: Record<string, unknown>, key: string, allowed: readonly T[],
): T | null {
  const field = value[key];
  if (field !== null && (typeof field !== "string" || !allowed.includes(field as T))) incomplete();
  return field as T | null;
}

function sameFields<T extends object>(left: T, right: T, keys: readonly (keyof T)[]): boolean {
  return keys.every((key) => left[key] === right[key]);
}

function assertRunIdentity(value: unknown): ReviewRunIdentityDto {
  const run = record(value);
  stringField(run, "runId");
  stringField(run, "projectId");
  stringField(run, "contractId");
  integerField(run, "contractVersion");
  stringField(run, "sourceCandidateId");
  nullableStringField(run, "sourceConfirmationId");
  integerField(run, "runSchemaVersion");
  stringField(run, "contractSnapshotCanonicalJson", false);
  stringField(run, "contractSnapshotSha256");
  stringField(run, "reviewProfileVersion");
  stringField(run, "ruleVersion");
  stringField(run, "modelPolicyVersion");
  stringField(run, "inputSnapshotIdentity");
  stringField(run, "requestedBy");
  enumField(run, "status", RUN_STATUSES);
  stringField(run, "requestedAt");
  nullableStringField(run, "startedAt");
  nullableStringField(run, "completedAt");
  nullableStringField(run, "failedAt");
  nullableStringField(run, "unknownAt");
  nullableStringField(run, "safeReasonCode");
  integerField(run, "versionNo");
  return run as unknown as ReviewRunIdentityDto;
}

function assertMaterial(
  value: unknown, runId: string, projectId: string,
): { id: string; fileId: string; ordinal: number } {
  const material = record(value);
  const id = stringField(material, "runMaterialId");
  if (stringField(material, "runId") !== runId || stringField(material, "projectId") !== projectId) incomplete();
  const ordinal = integerField(material, "ordinal");
  const fileId = stringField(material, "fileId");
  stringField(material, "fileSha256");
  stringField(material, "parseResultId");
  integerField(material, "parseVersion");
  stringField(material, "canonicalTextSha256");
  enumField(material, "effectiveRole", MATERIAL_ROLES);
  nullableStringField(material, "roleDecisionId");
  nullableStringField(material, "identityDecisionId");
  nullableStringField(material, "sourceRoleDispositionId");
  stringField(material, "selectedBy");
  stringField(material, "snapshottedAt");
  return { id, fileId, ordinal };
}

function assertStep(value: unknown, runId: string): ReviewStepDto {
  const step = record(value);
  stringField(step, "stepId");
  if (stringField(step, "runId") !== runId) incomplete();
  enumField(step, "stepType", REQUIRED_STEP_TYPES);
  booleanField(step, "required");
  enumField(step, "status", STEP_STATUSES);
  stringField(step, "stepPolicyVersion");
  integerField(step, "currentAttemptNo");
  nullableStringField(step, "outputResourceType");
  nullableStringField(step, "outputResourceId");
  nullableStringField(step, "safeReasonCode");
  nullableStringField(step, "startedAt");
  nullableStringField(step, "completedAt");
  integerField(step, "versionNo");
  return step as unknown as ReviewStepDto;
}

function assertAttempt(value: unknown, runId: string, stepIds: ReadonlySet<string>): ReviewStepAttemptDto {
  const attempt = record(value);
  stringField(attempt, "attemptId");
  if (stringField(attempt, "runId") !== runId) incomplete();
  const stepId = stringField(attempt, "stepId");
  if (!stepIds.has(stepId)) incomplete();
  integerField(attempt, "attemptNo");
  stringField(attempt, "operationIdentity");
  enumField(attempt, "status", STEP_STATUSES);
  nullableStringField(attempt, "leaseOwner");
  nullableStringField(attempt, "leaseUntil");
  nullableStringField(attempt, "claimedAt");
  enumField(attempt, "externalCallState", EXTERNAL_CALL_STATES);
  nullableStringField(attempt, "externalRequestFingerprint");
  nullableStringField(attempt, "providerRequestId");
  nullableStringField(attempt, "provider");
  nullableStringField(attempt, "model");
  nullableStringField(attempt, "startedAt");
  nullableStringField(attempt, "completedAt");
  nullableStringField(attempt, "safeReasonCode");
  nullableStringField(attempt, "outputResourceType");
  nullableStringField(attempt, "outputResourceId");
  integerField(attempt, "versionNo");
  return attempt as unknown as ReviewStepAttemptDto;
}

function assertAuditTask(value: unknown): string {
  const task = record(value);
  const id = stringField(task, "id");
  stringField(task, "sourceType");
  stringField(task, "sourceId");
  integerField(task, "sourceVersion");
  stringField(task, "reviewType");
  enumField(task, "status", AUDIT_TASK_STATUSES);
  stringField(task, "createdBy");
  stringField(task, "requestId");
  stringField(task, "traceId");
  stringField(task, "createdAt");
  nullableStringField(task, "startedAt");
  nullableStringField(task, "finishedAt");
  integerField(task, "versionNo");
  return id;
}

function assertAuditCheckResult(value: unknown, taskId: string): string {
  const result = record(value);
  const id = stringField(result, "id");
  if (stringField(result, "taskId") !== taskId) incomplete();
  stringField(result, "ruleCode");
  nullableStringField(result, "itemKey");
  enumField(result, "checkStatus", CHECK_STATUSES);
  enumField(result, "riskLevel", RISK_LEVELS);
  booleanField(result, "blockSign");
  nullableStringField(result, "actualValue");
  nullableStringField(result, "expectedValue");
  stringField(result, "detail", false);
  integerField(result, "ruleVersion");
  stringField(result, "createdAt");
  return id;
}

function assertFinding(
  value: unknown, runId: string, stepId: string, attemptIds: ReadonlySet<string>,
): { id: string; ordinal: number } {
  const finding = record(value);
  const id = stringField(finding, "findingId");
  if (stringField(finding, "runId") !== runId || stringField(finding, "stepId") !== stepId) incomplete();
  const attemptId = stringField(finding, "attemptId");
  if (!attemptIds.has(attemptId)) incomplete();
  const ordinal = integerField(finding, "ordinal");
  stringField(finding, "findingType");
  stringField(finding, "category");
  nullableStringField(finding, "statusCode");
  nullableStringField(finding, "attentionCode");
  nullableEnumField(finding, "riskLevel", RISK_LEVELS);
  stringField(finding, "summary", false);
  nullableStringField(finding, "recommendation");
  const findingSchemaVersion = integerField(finding, "findingSchemaVersion");
  stringField(finding, "policyVersion");
  enumField(finding, "source", FINDING_SOURCES);
  nullableStringField(finding, "modelProvider");
  nullableStringField(finding, "modelName");
  nullableStringField(finding, "modelRevision");
  nullableStringField(finding, "promptVersion");
  nullableStringField(finding, "modelSchemaVersion");
  stringField(finding, "createdAt");
  integerField(finding, "versionNo");
  if ("typedComparison" in finding) {
    if (finding.typedComparison === null) {
      if (findingSchemaVersion === 2 && stringField(finding, "findingType").endsWith("_COMPARISON")) incomplete();
    } else {
      const comparison = record(finding.typedComparison);
      if (stringField(comparison, "findingId") !== id
          || stringField(comparison, "runId") !== runId
          || stringField(comparison, "stepId") !== stepId
          || stringField(comparison, "attemptId") !== attemptId
          || integerField(comparison, "schemaVersion") !== 2) incomplete();
      stringField(comparison, "comparisonId");
      stringField(comparison, "comparisonKey");
      enumField(comparison, "kind", ["BREACH", "TERM"] as const);
      enumField(comparison, "result", ["MATCH", "DIFFERENT", "NOT_EVALUATED", "CONFLICT"] as const);
      const values = arrayField(comparison, "values");
      if (values.length < 2) incomplete();
      for (const raw of values) {
        const side = record(raw);
        stringField(side, "valueId");
        if (stringField(side, "comparisonId") !== stringField(comparison, "comparisonId")
            || stringField(side, "runId") !== runId) incomplete();
        stringField(side, "sideKey"); stringField(side, "sourceFileId");
        enumField(side, "state", ["KNOWN", "NOT_EXTRACTED", "NOT_APPLICABLE", "CONFLICT"] as const);
        stringField(side, "scenarioKey"); nullableStringField(side, "responsibleParty");
        nullableStringField(side, "calculationBasis"); nullableStringField(side, "rateUnit");
        nullableStringField(side, "frequency"); nullableStringField(side, "period");
        nullableStringField(side, "terminationThreshold"); nullableStringField(side, "dateType");
        nullableStringField(side, "normalizedDate"); nullableStringField(side, "termStart");
        nullableStringField(side, "duration"); nullableStringField(side, "effectiveCondition");
        nullableStringField(side, "terminationCondition");
        const evidenceIds = arrayField(side, "evidenceIds");
        if (evidenceIds.some((evidenceId) => typeof evidenceId !== "string" || evidenceId.length === 0)
            || new Set(evidenceIds).size !== evidenceIds.length) incomplete();
        const state = enumField(side, "state", ["KNOWN", "NOT_EXTRACTED", "NOT_APPLICABLE", "CONFLICT"] as const);
        if ((state === "KNOWN" && evidenceIds.length < 1)
            || (state === "CONFLICT" && evidenceIds.length < 2)
            || ((state === "NOT_EXTRACTED" || state === "NOT_APPLICABLE") && evidenceIds.length !== 0)) incomplete();
        for (const key of ["rate", "capRate", "capAmount"] as const) {
          const decimal = side[key];
          if (decimal !== null && (typeof decimal !== "number" || !Number.isFinite(decimal))) incomplete();
        }
      }
    }
  } else if (findingSchemaVersion === 2 && stringField(finding, "findingType").endsWith("_COMPARISON")) incomplete();
  return { id, ordinal };
}

function assertEvidence(
  value: unknown, runId: string, stepId: string, findingIds: ReadonlySet<string>,
  attemptIds: ReadonlySet<string>,
): { id: string; ordinalIdentity: string } {
  const evidence = record(value);
  const id = stringField(evidence, "evidenceId");
  const findingId = stringField(evidence, "findingId");
  if (!findingIds.has(findingId)
      || stringField(evidence, "runId") !== runId
      || stringField(evidence, "stepId") !== stepId
      || !attemptIds.has(stringField(evidence, "attemptId"))) incomplete();
  const ordinal = integerField(evidence, "ordinal");
  stringField(evidence, "fileId");
  stringField(evidence, "fileSha256");
  stringField(evidence, "parseResultId");
  integerField(evidence, "parseVersion");
  stringField(evidence, "canonicalTextSha256");
  enumField(evidence, "evidenceRole", EVIDENCE_ROLES);
  enumField(evidence, "validationStatus", VALIDATION_STATUSES);
  stringField(evidence, "validationPolicyVersion");
  nullableIntegerField(evidence, "pageNumber");
  nullableIntegerField(evidence, "paragraphIndex");
  nullableIntegerField(evidence, "tableIndex");
  nullableIntegerField(evidence, "rowIndex");
  nullableIntegerField(evidence, "cellIndex");
  nullableIntegerField(evidence, "segmentIndex");
  booleanField(evidence, "locatorAvailable");
  nullableIntegerField(evidence, "startOffset");
  nullableIntegerField(evidence, "endOffset");
  nullableStringField(evidence, "excerptSha256");
  stringField(evidence, "boundedExcerpt", false);
  integerField(evidence, "locatorSchemaVersion");
  stringField(evidence, "createdAt");
  integerField(evidence, "versionNo");
  return { id, ordinalIdentity: `${findingId}:${ordinal}` };
}

export function assertReviewStepOutputProjection(value: unknown): ReviewStepOutputDto {
  const output = record(value);
  const run = assertRunIdentity(output.run);
  const step = assertStep(output.step, run.runId);
  const stepIds = new Set([step.stepId]);
  const attempts = arrayField(output, "attempts").map((attempt) => assertAttempt(attempt, run.runId, stepIds));
  const attemptIds = new Set(attempts.map((attempt) => attempt.attemptId));
  if (attemptIds.size !== attempts.length) incomplete();

  const auditTaskId = output.auditTask === null ? null : assertAuditTask(output.auditTask);
  const checks = arrayField(output, "auditCheckResults");
  if (checks.length > 0 && auditTaskId === null) incomplete();
  const checkIds = checks.map((check) => assertAuditCheckResult(check, auditTaskId ?? ""));
  if (new Set(checkIds).size !== checkIds.length) incomplete();

  const findings = arrayField(output, "findings");
  const findingIdentities = findings.map((finding) =>
    assertFinding(finding, run.runId, step.stepId, attemptIds));
  const findingIds = new Set(findingIdentities.map((finding) => finding.id));
  if (findingIds.size !== findings.length) incomplete();
  if (new Set(findingIdentities.map((finding) => finding.ordinal)).size !== findings.length) incomplete();
  const evidenceIdentities = arrayField(output, "evidence").map((evidence) =>
    assertEvidence(evidence, run.runId, step.stepId, findingIds, attemptIds));
  if (new Set(evidenceIdentities.map((evidence) => evidence.id)).size !== evidenceIdentities.length
      || new Set(evidenceIdentities.map((evidence) => evidence.ordinalIdentity)).size
        !== evidenceIdentities.length) incomplete();
  const typedOutput = output as unknown as ReviewStepOutputDto;
  const evidenceById = new Map(typedOutput.evidence.map((item) => [item.evidenceId, item]));
  for (const finding of typedOutput.findings) {
    if (finding.typedComparison
        && ((typedOutput.step.stepType === "BREACH" ? "BREACH"
          : typedOutput.step.stepType === "TERM" ? "TERM" : null) !== finding.typedComparison.kind)) incomplete();
    for (const side of finding.typedComparison?.values ?? []) {
      for (const evidenceId of side.evidenceIds) {
        const item = evidenceById.get(evidenceId);
        if (!item || item.findingId !== finding.findingId || item.fileId !== side.sourceFileId
            || item.evidenceRole !== "COMPARISON") incomplete();
      }
    }
  }
  if ("findingCheckResultRelations" in output) {
    const relations = record(output.findingCheckResultRelations);
    for (const [findingId, checkId] of Object.entries(relations)) {
      if (!findingIds.has(findingId) || typeof checkId !== "string" || !checkIds.includes(checkId)) incomplete();
    }
  } else if (step.outputResourceType === "AUDIT_TASK_F02_NAVIGATION_V2" && step.stepType === "F02_DETERMINISTIC"
      && step.status === "COMPLETED") incomplete();
  if ("replayed" in output && typeof output.replayed !== "boolean") incomplete();
  return output as unknown as ReviewStepOutputDto;
}

export function assertReviewRunProjection(
  value: unknown,
  projectionKind: ReviewRunProjectionKind = "REOPEN",
): ReviewRunDto {
  const runIdentity = assertRunIdentity(value);
  const runRecord = record(value);
  if (runRecord.businessProjection !== undefined && runRecord.businessProjection !== null)
    assertReviewOpinionMetadata(runRecord.businessProjection);
  booleanField(runRecord, "replayed");
  enumField(runRecord, "executionMode", EXECUTION_MODES);

  const materialIdentities = arrayField(runRecord, "materials")
    .map((material) => assertMaterial(material, runIdentity.runId, runIdentity.projectId));
  if (new Set(materialIdentities.map((material) => material.id)).size !== materialIdentities.length
      || new Set(materialIdentities.map((material) => material.fileId)).size !== materialIdentities.length
      || new Set(materialIdentities.map((material) => material.ordinal)).size
        !== materialIdentities.length) incomplete();

  const steps = arrayField(runRecord, "steps").map((step) => assertStep(step, runIdentity.runId));
  const stepIds = new Set(steps.map((step) => step.stepId));
  if (stepIds.size !== steps.length
      || !REQUIRED_STEP_TYPES.every((type) => steps.filter((step) => step.stepType === type).length === 1)) {
    incomplete();
  }

  const attempts = arrayField(runRecord, "attempts")
    .map((attempt) => assertAttempt(attempt, runIdentity.runId, stepIds));
  const attemptIds = new Set(attempts.map((attempt) => attempt.attemptId));
  const attemptNumbers = new Set(attempts.map((attempt) => `${attempt.stepId}:${attempt.attemptNo}`));
  if (attemptIds.size !== attempts.length || attemptNumbers.size !== attempts.length) incomplete();

  const outputs = arrayField(runRecord, "outputs").map(assertReviewStepOutputProjection);
  if (projectionKind === "CREATE" && outputs.length !== 0) incomplete();
  if (projectionKind === "REOPEN" && outputs.length !== steps.length) incomplete();
  const outputStepIds = new Set<string>();
  const outputFindingIds = new Set<string>();
  const outputEvidenceIds = new Set<string>();
  const typedRun = runRecord as unknown as ReviewRunDto;
  const materialFileIds = new Set(typedRun.materials.map((material) => material.fileId));
  for (const output of outputs) {
    if (outputStepIds.has(output.step.stepId)) incomplete();
    outputStepIds.add(output.step.stepId);
    const manifestStep = steps.find((step) => step.stepId === output.step.stepId);
    if (!manifestStep
        || !sameFields(typedRun, output.run, RUN_IDENTITY_KEYS)
        || !sameFields(manifestStep, output.step, STEP_KEYS)) incomplete();
    for (const outputAttempt of output.attempts) {
      const manifestAttempt = attempts.find((attempt) => attempt.attemptId === outputAttempt.attemptId);
      if (!manifestAttempt || !sameFields(manifestAttempt, outputAttempt, ATTEMPT_KEYS)) incomplete();
    }
    const manifestStepAttempts = attempts.filter((attempt) => attempt.stepId === output.step.stepId);
    if (output.attempts.length !== manifestStepAttempts.length) incomplete();
    for (const finding of output.findings) {
      if (outputFindingIds.has(finding.findingId)) incomplete();
      outputFindingIds.add(finding.findingId);
      if (finding.typedComparison?.values.some((side) => !materialFileIds.has(side.sourceFileId))) incomplete();
    }
    for (const evidence of output.evidence) {
      if (outputEvidenceIds.has(evidence.evidenceId)) incomplete();
      outputEvidenceIds.add(evidence.evidenceId);
    }
  }
  return typedRun;
}
