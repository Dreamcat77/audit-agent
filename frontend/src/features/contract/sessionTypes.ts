import type { ReviewDocumentKind } from "./workspaceTypes";

export type ReviewSessionStatus = "PREPARING_MATERIALS" | "READY";
export type ReviewDocumentStatus = "UPLOAD_PENDING" | "AVAILABLE" | "UPLOAD_FAILED";
export type ReviewSessionSourceKind = "LEGACY_REVIEW_DOCUMENT" | "PROJECT_FILE_REFERENCE";

export interface ContractReviewSession {
  sessionId: string;
  contractId: string;
  sourceKind: ReviewSessionSourceKind;
  projectId: string | null;
  contractVersion: number;
  name: string;
  reviewType: string;
  status: ReviewSessionStatus;
  remark: string | null;
  createdBy: string;
  createdByName: string | null;
  createdAt: string;
  updatedAt: string;
  materialsConfirmedAt: string | null;
  versionNo: number;
  deleted: boolean;
  deletedAt: string | null;
  deletedBy: string | null;
  replayed: boolean;
}

export interface ReviewSessionTaskContract {
  id: string;
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
  createdAt: string;
  updatedAt: string;
}

export interface ReviewSessionTaskSummary {
  sessionId: string;
  name: string;
  reviewType: string;
  status: ReviewSessionStatus;
  remark: string | null;
  contractVersion: number;
  createdBy: string;
  createdByName: string | null;
  createdAt: string;
  updatedAt: string;
  materialsConfirmedAt: string | null;
  versionNo: number;
  deletedAt: string | null;
  deletedBy: string | null;
  contract: ReviewSessionTaskContract;
}

export interface ReviewSessionTaskPage {
  items: ReviewSessionTaskSummary[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface SessionReviewDocument {
  documentId: string;
  sessionId: string;
  documentKind: ReviewDocumentKind;
  slotKey: string;
  originalFileName: string;
  fileExtension: string;
  contentType: string;
  fileSize: number;
  sha256: string;
  status: ReviewDocumentStatus;
  replacesDocumentId: string | null;
  createdAt: string;
  updatedAt: string;
  storedAt: string | null;
  failedAt: string | null;
  versionNo: number;
  current: boolean;
}

export interface SessionMaterialBinding {
  bindingId: string;
  slotKey: string;
  documentKind: ReviewDocumentKind;
  boundDocumentId: string;
  active: boolean;
  versionNo: number;
}

export interface SessionDocumentList {
  currentMaterials: SessionReviewDocument[];
  history: SessionReviewDocument[];
}

export interface SessionUploadResult {
  document: SessionReviewDocument;
  currentBinding: SessionMaterialBinding | null;
  session: ContractReviewSession;
  replayed: boolean;
}
