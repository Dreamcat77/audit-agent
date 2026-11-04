import type { CheckStatus, RiskLevel } from "./types.ts";

export type ReviewRunStatus =
  | "PENDING"
  | "RUNNING"
  | "COMPLETED"
  | "PARTIALLY_COMPLETED"
  | "FAILED"
  | "UNKNOWN";

export type ReviewRunExecutionMode = "CLIENT_COMPATIBILITY" | "SERVER_WORKER";

export type ReviewStepStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED" | "UNKNOWN";

export type ReviewMaterialRole =
  | "CONTRACT_BODY"
  | "AWARD_NOTICE"
  | "TENDER_DOCUMENT"
  | "BID_RESPONSE"
  | "SUPPLEMENT"
  | "OTHER";

export type ReviewAuditTaskStatus = "PENDING" | "PROCESSING" | "DONE" | "FAILED";

export type ReviewStepType =
  | "MATERIAL_VALIDATION"
  | "F02_DETERMINISTIC"
  | "SEMANTIC_AI"
  | "BREACH"
  | "TERM";

export interface ReviewRunMaterialDto {
  runMaterialId: string;
  runId: string;
  ordinal: number;
  projectId: string;
  fileId: string;
  fileSha256: string;
  parseResultId: string;
  parseVersion: number;
  canonicalTextSha256: string;
  effectiveRole: ReviewMaterialRole;
  roleDecisionId: string | null;
  identityDecisionId: string | null;
  sourceRoleDispositionId: string | null;
  selectedBy: string;
  snapshottedAt: string;
}

export interface ReviewStepDto {
  stepId: string;
  runId: string;
  stepType: ReviewStepType;
  required: boolean;
  status: ReviewStepStatus;
  stepPolicyVersion: string;
  currentAttemptNo: number;
  outputResourceType: string | null;
  outputResourceId: string | null;
  safeReasonCode: string | null;
  startedAt: string | null;
  completedAt: string | null;
  versionNo: number;
}

export interface ReviewStepAttemptDto {
  attemptId: string;
  runId: string;
  stepId: string;
  attemptNo: number;
  operationIdentity: string;
  status: ReviewStepStatus;
  leaseOwner: string | null;
  leaseUntil: string | null;
  claimedAt: string | null;
  externalCallState: "NOT_STARTED" | "REQUESTED" | "OUTCOME_UNKNOWN" | "COMPLETED";
  externalRequestFingerprint: string | null;
  providerRequestId: string | null;
  provider: string | null;
  model: string | null;
  startedAt: string | null;
  completedAt: string | null;
  safeReasonCode: string | null;
  outputResourceType: string | null;
  outputResourceId: string | null;
  versionNo: number;
}

export interface ReviewAuditTaskDto {
  id: string;
  sourceType: string;
  sourceId: string;
  sourceVersion: number;
  reviewType: string;
  status: ReviewAuditTaskStatus;
  createdBy: string;
  requestId: string;
  traceId: string;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  versionNo: number;
}

export interface ReviewAuditCheckResultDto {
  id: string;
  taskId: string;
  ruleCode: string;
  itemKey: string | null;
  checkStatus: CheckStatus;
  riskLevel: RiskLevel;
  blockSign: boolean;
  actualValue: string | null;
  expectedValue: string | null;
  detail: string;
  ruleVersion: number;
  createdAt: string;
}

export interface ReviewFindingDto {
  findingId: string;
  runId: string;
  stepId: string;
  attemptId: string;
  ordinal: number;
  findingType: string;
  category: string;
  statusCode: string | null;
  attentionCode: string | null;
  riskLevel: RiskLevel | null;
  summary: string;
  recommendation: string | null;
  findingSchemaVersion: number;
  policyVersion: string;
  source: "DETERMINISTIC" | "MODEL_ASSISTED";
  modelProvider: string | null;
  modelName: string | null;
  modelRevision: string | null;
  promptVersion: string | null;
  modelSchemaVersion: string | null;
  createdAt: string;
  versionNo: number;
  typedComparison?: ReviewClauseComparisonDto | null;
}

export type ReviewClauseSideState = "KNOWN" | "NOT_EXTRACTED" | "NOT_APPLICABLE" | "CONFLICT";
export type ReviewClauseComparisonResult = "MATCH" | "DIFFERENT" | "NOT_EVALUATED" | "CONFLICT";
export interface ReviewClauseValueDto {
  valueId: string; comparisonId: string; runId: string; sideKey: string; sourceFileId: string;
  state: ReviewClauseSideState; scenarioKey: string; responsibleParty: string | null;
  calculationBasis: string | null; rate: number | null; rateUnit: string | null;
  frequency: string | null; period: string | null; capRate: number | null;
  capAmount: number | null; terminationThreshold: string | null;
  dateType: string | null; normalizedDate: string | null; termStart: string | null;
  duration: string | null; effectiveCondition: string | null;
  terminationCondition: string | null; evidenceIds: readonly string[];
}
export interface ReviewClauseComparisonDto {
  comparisonId: string; findingId: string; runId: string; stepId: string;
  attemptId: string; comparisonKey: string; kind: "BREACH" | "TERM";
  result: ReviewClauseComparisonResult; schemaVersion: 2;
  values: readonly ReviewClauseValueDto[];
}

export interface ReviewEvidenceDto {
  evidenceId: string;
  findingId: string;
  runId: string;
  stepId: string;
  attemptId: string;
  ordinal: number;
  fileId: string;
  fileSha256: string;
  parseResultId: string;
  parseVersion: number;
  canonicalTextSha256: string;
  evidenceRole: "PROBLEM" | "COMPARISON" | "SUPPORT";
  validationStatus: "VALIDATED" | "LOCATOR_UNAVAILABLE";
  validationPolicyVersion: string;
  pageNumber: number | null;
  paragraphIndex: number | null;
  tableIndex: number | null;
  rowIndex: number | null;
  cellIndex: number | null;
  segmentIndex: number | null;
  locatorAvailable: boolean;
  startOffset: number | null;
  endOffset: number | null;
  excerptSha256: string | null;
  boundedExcerpt: string;
  locatorSchemaVersion: number;
  createdAt: string;
  versionNo: number;
}

export interface ReviewRunIdentityDto {
  runId: string;
  projectId: string;
  contractId: string;
  contractVersion: number;
  sourceCandidateId: string;
  sourceConfirmationId: string | null;
  runSchemaVersion: number;
  contractSnapshotCanonicalJson: string;
  contractSnapshotSha256: string;
  reviewProfileVersion: string;
  ruleVersion: string;
  modelPolicyVersion: string;
  inputSnapshotIdentity: string;
  requestedBy: string;
  status: ReviewRunStatus;
  requestedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  failedAt: string | null;
  unknownAt: string | null;
  safeReasonCode: string | null;
  versionNo: number;
}

export interface ReviewStepOutputDto {
  run: ReviewRunIdentityDto;
  step: ReviewStepDto;
  attempts: readonly ReviewStepAttemptDto[];
  auditTask: ReviewAuditTaskDto | null;
  auditCheckResults: readonly ReviewAuditCheckResultDto[];
  findings: readonly ReviewFindingDto[];
  evidence: readonly ReviewEvidenceDto[];
  findingCheckResultRelations?: Readonly<Record<string, string>>;
  /** Present on step command responses; absent from GET ReviewRun embedded outputs. */
  replayed?: boolean;
}

export interface ReviewMonetaryWarningDto {
  fieldPath: string;
  errorKind: "MONETARY_VALUE" | "MONETARY_CALCULATION";
  category: string | null;
  textSha256: string;
  ruleSubtypes?: readonly ("ABSOLUTE_VALUE" | "LABELLED_NUMBER" | "ARITHMETIC" | "RATE_COMPARISON" | "UNSAFE_CONCLUSION")[];
}

export interface ReviewMonetaryWorkWarningDto {
  logicalKey: string;
  ordinal: number;
  purpose: "ANALYZE" | "REPAIR" | "SYNTHESIZE";
  operationFingerprint: string;
  warningCount: number;
  warningsSha256: string;
}

export interface ReviewMonetaryObservationDto {
  protocol: "contract-money-observation-v1";
  mode: "STRICT" | "OBSERVE";
  warnings: readonly ReviewMonetaryWarningDto[];
  workWarnings: readonly ReviewMonetaryWorkWarningDto[];
  warningCount?: number;
}

export interface ReviewOpinionMetadataDto {
  overallOpinion?: string;
  monetaryObservation?: ReviewMonetaryObservationDto;
}

export interface ReviewRunDto extends ReviewRunIdentityDto {
  businessProjection?: ReviewOpinionMetadataDto & {
    version: 1;
    runId: string;
    runStatus: ReviewRunStatus;
    scopeStatement: string;
    opinionVersion?: number;
    overallAdvice?: string;
    categories: readonly import("./eightCategoryReviewProjection.ts").ContractReviewCategoryView[];
  } | null;
  executionMode: ReviewRunExecutionMode;
  materials: readonly ReviewRunMaterialDto[];
  steps: readonly ReviewStepDto[];
  attempts: readonly ReviewStepAttemptDto[];
  outputs: readonly ReviewStepOutputDto[];
  replayed: boolean;
}
