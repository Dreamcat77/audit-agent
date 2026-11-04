export type FieldState = "CONFIRMED" | "MISSING" | "CONFLICT";
export type CandidatePromotionState = "DRAFT" | "PROMOTED";
export type CandidateAuthorityMode = "PRIMARY_ONLY_COMPAT" | "EXPLICIT_SUPPORT_AUTHORITY";
export type ProjectFileParseMethod = "PDF_TEXT" | "DOC_TEXT" | "DOCX_TEXT" | "OCR";
export type DocumentRole = "CONTRACT_BODY" | "AWARD_NOTICE" | "TENDER_DOCUMENT"
  | "BID_RESPONSE" | "SUPPLEMENT" | "OTHER" | "UNKNOWN";
export type EffectiveDocumentRoleSource = "HUMAN_CONFIRMATION" | "LEGACY_EFFECTIVE"
  | "SYSTEM_AUTO_DECISION" | "NO_AUTHORITATIVE_ROLE";
export type ProjectIdentityResolution = "CONFIRM_SAME_PROJECT" | "CONFIRM_NOT_SAME_PROJECT";
export type ContractFactScope = "CORE" | "LINE_ITEMS" | "AWARD";
export type ContractFactBatchStatus = "EMPTY" | "PARTIAL" | "COMPLETE" | "FAILED";
export type ProjectProgressionAction = "CONFIRM_ROLE" | "CONFIRM_PROJECT_IDENTITY"
  | "CONFIRM_CONTRACT_CANDIDATE" | "PROMOTE_CONTRACT" | "START_REVIEW"
  | "RESUME_ROLE_ASSESSMENT" | "RESUME_IDENTITY_ASSESSMENT"
  | "RESUME_CANDIDATE_MATERIALIZATION" | "RESUME_FACT_EXTRACTION";

export interface ProjectFileProgression {
  stage: string;
  blocker: string | null;
  availableActions: ProjectProgressionAction[];
  candidateId: string | null;
  contractId: string | null;
  recoveryInputFingerprint?: string | null;
}

export interface ContractFactExtractionProjection {
  currentParseResultId: string;
  currentParseVersion: number;
  attempt: null | {
    attemptId: string;
    status: "IN_PROGRESS" | "SUCCEEDED" | "FAILED" | "OUTCOME_UNKNOWN";
    callCount: number;
    acceptedCount: number;
    rejectedCount: number;
    failureReason: string | null;
  };
  candidates: Array<{
    candidateId?: string;
    factType?: string;
    validationStatus?: string;
    validationReason?: string;
    publishedExtractionId?: string | null;
  }>;
  rejections: Array<{
    scope: ContractFactScope;
    reasonCode: string;
  }>;
  modelCalls: Array<{
    attemptNo: number;
    scope: ContractFactScope | null;
    callOrdinal: number;
    outcome: "SUCCEEDED" | "FAILED";
    safeReason: string | null;
    batchStatus: ContractFactBatchStatus | null;
  }>;
}

export interface Project {
  id: string;
  name: string | null;
  projectNo: string | null;
  category: string | null;
  metadataConfirmed: boolean;
  versionNo: number;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectPage {
  items: Project[];
  page: number;
  size: number;
  total: number;
}

export interface ProjectIntakeDraft {
  projectId: string;
  versionNo: number;
  packageFilename: string;
  intakeStatus: string;
  parsedFileCount: number;
  ocrFileCount: number;
  failedFileCount: number;
  candidateProjectName: string | null;
  candidateProjectNo: string | null;
  createdAt: string;
}

export interface ProjectIntakeDraftPage {
  items: ProjectIntakeDraft[];
  page: number;
  size: number;
  total: number;
}

export interface ProjectPreparationDraft {
  projectId: string;
  createdAt: string;
  uploadedFileCount: number;
}

export interface ProjectPreparationDraftPage {
  items: ProjectPreparationDraft[];
  page: number;
  size: number;
  total: number;
}

export interface ProjectFile {
  id: string;
  projectId: string;
  parentArchiveFileId: string | null;
  normalizedRelativePath: string | null;
  originalFilename: string;
  mediaType: string;
  category: string;
  ingestionStatus: "UPLOAD_PENDING" | "AVAILABLE" | "UPLOAD_FAILED";
  parseStatus: "PENDING" | "PARSED" | "UNSUPPORTED" | "FAILED" | "NOT_APPLICABLE";
  sizeBytes: number;
  parsedText: string | null;
  parseMethod: ProjectFileParseMethod | null;
  parseEngine: string | null;
  parseConfidence: number | null;
  pageCount: number | null;
  parseDurationMs: number | null;
  safeReasonCode: string | null;
  createdAt: string;
  updatedAt: string;
  fileSha256?: string | null;
  canonicalTextSha256?: string | null;
  parseResultId?: string | null;
  parseVersion?: number | null;
  parseOrigin?: string | null;
  roleAssessmentId?: string | null;
  suggestedRole?: DocumentRole | null;
  roleConfidence?: number | null;
  roleExecutionStatus?: string | null;
  roleAssessmentSource?: string | null;
  roleSafeReasonCode?: string | null;
  roleModelProvider?: string | null;
  roleModelName?: string | null;
  roleModelRevision?: string | null;
  rolePromptVersion?: string | null;
  roleSchemaVersion?: string | null;
  aiRoleAssessmentId?: string | null;
  aiRoleExecutionStatus?: string | null;
  aiRoleSafeReasonCode?: string | null;
  roleDecisionStatus?: string | null;
  effectiveRole?: DocumentRole;
  effectiveRoleSource?: EffectiveDocumentRoleSource;
  roleDecisionId?: string | null;
  roleDecisionVersion?: number | null;
  identityAssessmentId?: string | null;
  identityAssessmentStatus?: string | null;
  identityDecisionStatus?: string | null;
  effectiveIdentityStatus?: string;
  identityDecisionId?: string | null;
  identityDecisionVersion?: number | null;
  identityCurrent?: boolean;
  excludedFromProjectUse?: boolean;
  progression: ProjectFileProgression;
}

export interface CandidateLineItem {
  id: string;
  projectId: string;
  candidateId: string;
  itemKey: string | null;
  itemName: string | null;
  basis?: "QUANTITY_PRICE" | "AMOUNT_ONLY";
  quantity: number | null;
  unitPrice: number | null;
  declaredAmount: number | null;
  sortNo: number;
}

/** Exact server-owned authority tuple shared by command results and detail projections. */
export interface CandidateSupportAuthority {
  supportAuthorityId: string;
  sourceFileId: string;
  fileSha256: string;
  parseResultId: string;
  parseVersion: number;
  projectVersionNo: number;
  effectiveRole: "AWARD_NOTICE";
  roleDecisionId: string;
  identityResolution: "CONFIRM_SAME_PROJECT";
  identityDecisionId: string;
  sourceFactFingerprint: string;
  authorityVersion: number;
}

/** Currentness is supplied by Candidate detail, never inferred from a saved command result. */
export interface CandidateSupportAssociation extends CandidateSupportAuthority {
  originalFilename: string | null;
  current: boolean;
  safeReasonCode: string | null;
  eligibleAwardFacts: ExtractionEvidence[];
}

export interface ContractCandidate {
  id: string;
  projectId: string;
  sourceContractFileId: string;
  sourceParseResultId?: string | null;
  sourceRoleDecisionId?: string | null;
  sourceIdentityDecisionId?: string | null;
  supportAuthorityVersionNo?: number | null;
  supportAuthorityFingerprint?: string | null;
  contractNo: string | null;
  contractName: string | null;
  contractType: string | null;
  partyA: string | null;
  partyB: string | null;
  partyBCreditCode: string | null;
  contractAmount: number | null;
  amountInWords: string | null;
  awardAmount: number | null;
  awardSupplier: string | null;
  awardSupplierCreditCode: string | null;
  lineItems: CandidateLineItem[];
  promotionState: CandidatePromotionState;
  versionNo: number;
  promotedContractId: string | null;
  promotedConfirmationId: string | null;
  promotedBy: string | null;
  promotedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ExtractionEvidence {
  id?: string;
  projectId?: string;
  sourceFileId: string;
  parseResultId?: string | null;
  candidateId?: string | null;
  factType: string;
  groupKey: string | null;
  valueText?: string | null;
  normalizedValue: string | null;
  valueAmount: number | null;
  sourceText: string;
  pageNumber: number | null;
  paragraphIndex: number | null;
  sourceLocator: string;
}

export interface ProjectContract {
  projectId: string;
  candidateId: string;
  contractId: string;
  contractNo: string;
  contractName: string;
  contractType: string;
  partyA: string;
  partyB: string;
  partyBCreditCode: string | null;
  contractAmount: number;
  amountInWords: string | null;
  status: string;
  versionNo: number;
  createdBy: string;
  createdAt: string;
}

export interface SelectedProjectSource {
  projectId: string;
  fileId: string;
  pageNumber: number | null;
  paragraphIndex: number | null;
  locator: string;
  excerpt: string;
}

export interface ProjectDetail {
  project: Project;
  intakeStatus: string;
  files: ProjectFile[];
  candidates: ContractCandidate[];
  extractionResults: ExtractionEvidence[];
  associatedContracts: ProjectContract[];
  failedFileCount: number;
  unsupportedFileCount: number;
}

export interface CandidateDetail {
  candidate: ContractCandidate;
  sourceFile: ProjectFile;
  evidence: ExtractionEvidence[];
  fieldStates: Record<string, FieldState>;
  lineItemFieldStates: Record<string, Record<string, FieldState>>;
  authorityMode?: CandidateAuthorityMode;
  supportAuthorityVersionNo?: number | null;
  supportAuthorityFingerprint?: string | null;
  supportAuthorities?: CandidateSupportAssociation[];
}
