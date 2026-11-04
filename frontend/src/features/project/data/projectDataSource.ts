import type { CandidateAuthorityMode, CandidateDetail, CandidateSupportAuthority,
  ContractFactExtractionProjection, DocumentRole, Project, ProjectDetail, ProjectFile,
  ProjectIdentityResolution, ProjectIntakeDraftPage, ProjectPage } from "../types";
import type { ProjectPreparationDraftPage } from "../types";
import type { CandidateConfirmation, CandidatePromotionRequest, ConfirmedContractPayload } from
  "../candidateConfirmation";
import type { PromotionResponse } from "../promotionReconciliation";

export interface IdempotentProjectRequest { idempotencyKey: string; signal?: AbortSignal; automaticAnalysisFileCount?:number }
export interface ProjectFileJob {
  jobId: string; projectId: string; fileId: string;
  status: "QUEUED" | "PROCESSING" | "SUCCEEDED" | "FAILED" | "UNKNOWN";
  attemptNo: number; reasonCode: string | null; parseResultId: string | null;
  createdAt: string; updatedAt: string;
}
export interface ProjectFileAcceptance {
  projectId: string; fileId: string; jobId: string | null; file: ProjectFile; job: ProjectFileJob | null;
}
export interface ProjectFileJobs {
  projectId: string; workerEnabled: boolean; items: ProjectFileJob[];
  total: number; completed: number; failed: number; unknown: number;
}

export interface ProjectMetadataConfirmation {
  expectedVersion: number;
  name: string | null;
  projectNo: string | null;
  category: string | null;
}

export interface RoleConfirmationRequest {
  assessmentId: string;
  selectedRole: DocumentRole;
  expectedVersion: number;
  reason: string;
}

export interface RoleConfirmationResponse {
  decisionId: string;
  versionNo: number;
  effectiveRole: DocumentRole;
  effectiveRoleSource: string;
  replayed: boolean;
}

export interface IdentityConfirmationRequest {
  assessmentId: string;
  resolution: ProjectIdentityResolution;
  expectedVersion: number;
  reason: string;
}

export interface IdentityConfirmationResponse {
  decisionId: string;
  versionNo: number;
  effectiveIdentityStatus: string;
  identityCurrent: boolean;
  candidateId: string | null;
  replayed: boolean;
}

export interface ProgressionRecoveryRequest {
  expectedBlocker: "ROLE_ASSESSMENT_PENDING" | "IDENTITY_ASSESSMENT_PENDING"
    | "IDENTITY_ASSESSMENT_STALE" | "CANDIDATE_MATERIALIZATION_PENDING"
    | "FACT_EXTRACTION_PENDING";
  expectedRecoveryInputFingerprint: string;
}

export interface CandidateSupportAuthorityReplacementRequest {
  expectedCandidateVersion: number;
  supportFileIds: string[];
}

export interface CandidateSupportAuthorityReplacementResponse {
  candidateId: string;
  candidateVersion: number;
  supportAuthorityVersion: number;
  authorityMode: CandidateAuthorityMode;
  supportAssociations: Array<CandidateSupportAuthority & {
    projectId: string;
    candidateId: string;
    createdBy: string;
    createdAt: string;
  }>;
  replayed: boolean;
}

export interface ProjectDataSource {
  listProjects(options?: { page?: number; size?: number; keyword?: string; signal?: AbortSignal }): Promise<ProjectPage>;
  listIntakeDrafts(options?: { page?: number; size?: number; signal?: AbortSignal }): Promise<ProjectIntakeDraftPage>;
  listPreparationDrafts(options?: { page?: number; size?: number; signal?: AbortSignal }): Promise<ProjectPreparationDraftPage>;
  createBlank(options: IdempotentProjectRequest): Promise<Project>;
  getCreationOperation?(key: string, signal?: AbortSignal): Promise<Project>;
  getProject(projectId: string, signal?: AbortSignal): Promise<ProjectDetail>;
  confirmMetadata(projectId: string, payload: ProjectMetadataConfirmation,
    options: IdempotentProjectRequest): Promise<Project>;
  listFiles(projectId: string, signal?: AbortSignal): Promise<ProjectFile[]>;
  upload(projectId: string, file: File, options: IdempotentProjectRequest): Promise<ProjectFile>;
  uploadAsync?(projectId: string, file: File, options: IdempotentProjectRequest): Promise<ProjectFileAcceptance>;
  getUploadOperation?(projectId: string, key: string, signal?: AbortSignal): Promise<ProjectFileAcceptance>;
  listFileJobs?(projectId: string, signal?: AbortSignal): Promise<ProjectFileJobs>;
  getFileProcessing?(projectId: string, fileId: string, signal?: AbortSignal): Promise<ProjectFileJob>;
  retryFileProcessing?(projectId: string, fileId: string, signal?: AbortSignal): Promise<ProjectFileJob>;
  recover(projectId: string, fileId: string, signal?: AbortSignal): Promise<ProjectFile>;
  resumeProgression(projectId: string, fileId: string, payload: ProgressionRecoveryRequest,
    options: IdempotentProjectRequest): Promise<ProjectFile>;
  getLatestFactExtraction?(projectId: string, fileId: string,
    signal?: AbortSignal): Promise<ContractFactExtractionProjection>;
  confirmRole(projectId: string, fileId: string, payload: RoleConfirmationRequest,
    options: IdempotentProjectRequest): Promise<RoleConfirmationResponse>;
  confirmProjectIdentity(projectId: string, fileId: string, payload: IdentityConfirmationRequest,
    options: IdempotentProjectRequest): Promise<IdentityConfirmationResponse>;
  getCandidate(projectId: string, candidateId: string, signal?: AbortSignal): Promise<CandidateDetail>;
  replaceCandidateSupportAuthority(projectId: string, candidateId: string,
    payload: CandidateSupportAuthorityReplacementRequest,
    options: IdempotentProjectRequest): Promise<CandidateSupportAuthorityReplacementResponse>;
  confirmCandidate(projectId: string, candidateId: string, payload: ConfirmedContractPayload,
    options: IdempotentProjectRequest): Promise<CandidateConfirmation>;
  getConfirmation(projectId: string, candidateId: string, confirmationId: string,
    signal?: AbortSignal): Promise<CandidateConfirmation>;
  getLatestConfirmation(projectId: string, candidateId: string,
    signal?: AbortSignal): Promise<CandidateConfirmation>;
  promote(projectId: string, candidateId: string, payload: CandidatePromotionRequest,
    options: IdempotentProjectRequest): Promise<PromotionResponse>;
  originalContentUrl(projectId: string, fileId: string): string;
}
