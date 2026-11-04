import type { DocumentRole, EffectiveDocumentRoleSource } from "../project/types";

export type ReviewDocumentKind =
  | "CONTRACT_BODY"
  | "BIDDING_DOCUMENT"
  | "TENDER_DOCUMENT"
  | "AWARD_NOTICE"
  | "OTHER";

export type ReviewDocumentStatus = "NOT_ATTACHED" | "LOCAL_SELECTED";

export interface ReviewDocumentUiState {
  clientId: string;
  kind: ReviewDocumentKind;
  label: string;
  required: boolean;
  status: ReviewDocumentStatus;
  fileName: string | null;
  fileSize: number | null;
  mimeType: string | null;
  lastModified: number | null;
}

export type DocumentViewerMode = "ORIGINAL" | "TEXT";

export type DocumentProcessingStage =
  | "WAITING"
  | "RECEIVED"
  | "READING"
  | "EXTRACTING"
  | "COMPLETED"
  | "FAILED";

export interface EvidenceAnchor {
  documentClientId: string;
  label?: string;
  pageNumber?: number;
  excerpt?: string;
  startOffset?: number;
  endOffset?: number;
  boundingBox?: {
    x: number;
    y: number;
    width: number;
    height: number;
  };
}

export interface ProjectBackedReviewMaterial {
  id: string;
  projectId: string;
  fileName: string;
  mediaType: string;
  classifierCategory: string;
  effectiveRole: DocumentRole;
  effectiveRoleSource: EffectiveDocumentRoleSource;
  reviewRole: DocumentRole;
  roleDecisionId: string | null;
  ingestionStatus: "UPLOAD_PENDING" | "AVAILABLE" | "UPLOAD_FAILED";
  parseStatus: "PENDING" | "PARSED" | "UNSUPPORTED" | "FAILED" | "NOT_APPLICABLE";
  effectiveIdentityStatus: string | null;
  identityCurrent: boolean;
  excludedFromProjectUse: boolean;
  semanticEligible: boolean;
  statusLabel: string;
  parsedText: string | null;
  fileSha256?: string | null;
  parseResultId?: string | null;
  parseVersion?: number | null;
  canonicalTextSha256?: string | null;
  provenance: {
    methodLabel: string;
    confidenceLabel: string | null;
    pageCountLabel: string | null;
  };
  originalUrl: string;
}

export interface ProjectBackedReviewMaterialTab {
  key: "CONTRACT" | "TENDER" | "BID" | "AWARD" | "OTHER";
  label: string;
  files: ProjectBackedReviewMaterial[];
}

export type StructuredFactKey =
  | "contractNo"
  | "contractName"
  | "partyA"
  | "partyB"
  | "partyBCreditCode"
  | "contractAmount"
  | "amountInWords"
  | "lineItems"
  | "awardSupplier"
  | "awardAmount";

export const CORE_REVIEW_DOCUMENT_IDS = {
  contract: "contract-body",
  bidding: "bidding-document",
  tender: "tender-document",
  award: "award-notice",
  other: "other-material",
} as const;

export type ReviewPipelineStatus =
  | "pending"
  | "running"
  | "success"
  | "warning"
  | "undetermined"
  | "failure";

export interface ReviewPipelineStep {
  id: string;
  label: string;
  detail: string;
  status: ReviewPipelineStatus;
}

/** Frontend-only seam until a real document processing task API exists. */
export type ReviewWorkspacePhase =
  | "PREPARE_MATERIALS"
  | "READING_MATERIALS"
  | "FACTS_READY"
  | "REVIEWING"
  | "COMPLETED"
  | "FAILED";

/** Controls presentation only; it never changes the server Contract DTO. */
export interface StructuredFactVisibility {
  contractIdentity: boolean;
  parties: boolean;
  amount: boolean;
  lineItems: boolean;
  awardNotice: boolean;
}
