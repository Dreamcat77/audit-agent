import { apiUrl, request } from "../../../api/client";
import type { CandidateDetail, ContractFactExtractionProjection, Project, ProjectDetail,
  ProjectFile, ProjectIntakeDraftPage, ProjectPage, ProjectPreparationDraftPage } from "../types";
import type { CandidateSupportAuthorityReplacementRequest, CandidateSupportAuthorityReplacementResponse,
  IdempotentProjectRequest, IdentityConfirmationRequest, IdentityConfirmationResponse,
  ProjectDataSource, ProjectMetadataConfirmation, RoleConfirmationRequest,
  RoleConfirmationResponse, ProgressionRecoveryRequest } from "./projectDataSource";
import type { ProjectFileAcceptance, ProjectFileJobs, ProjectFileJob } from "./projectDataSource";
import type { CandidateConfirmation, CandidatePromotionRequest, ConfirmedContractPayload } from
  "../candidateConfirmation";
import type { PromotionResponse } from "../promotionReconciliation";

const segment = encodeURIComponent;
function processingConflict():never{throw new Error("材料接收或处理结果无法核对，请重新读取原操作。");}
function validJob(job:ProjectFileJob,projectId:string,fileId?:string):ProjectFileJob {
  if(!job||job.projectId!==projectId||fileId!==undefined&&job.fileId!==fileId||typeof job.jobId!=="string"
    ||!job.jobId||typeof job.fileId!=="string"||!job.fileId||!Number.isSafeInteger(job.attemptNo)||job.attemptNo<0
    ||!["QUEUED","PROCESSING","SUCCEEDED","FAILED","UNKNOWN"].includes(job.status))processingConflict();
  return job;
}
function validAcceptance(value:ProjectFileAcceptance,projectId:string,requiredJob:boolean):ProjectFileAcceptance {
  if(!value||value.projectId!==projectId||!value.file||value.file.projectId!==projectId
    ||value.file.id!==value.fileId||requiredJob&&!value.job)processingConflict();
  if(value.job){validJob(value.job,projectId,value.fileId);if(value.job.jobId!==value.jobId)processingConflict();}
  else if(value.jobId!==null)processingConflict();
  return value;
}

export class HttpProjectDataSource implements ProjectDataSource {
  listProjects(options: { page?: number; size?: number; keyword?: string; signal?: AbortSignal } = {}): Promise<ProjectPage> {
    const params: Record<string, string> = {
      page: String(options.page ?? 1), size: String(options.size ?? 20),
    };
    if (options.keyword?.trim()) params.keyword = options.keyword.trim();
    return request<ProjectPage>("/api/v1/projects", { params, signal: options.signal });
  }

  listIntakeDrafts(options: { page?: number; size?: number; signal?: AbortSignal } = {}): Promise<ProjectIntakeDraftPage> {
    return request<ProjectIntakeDraftPage>("/api/v1/projects/intake-drafts", {
      params: { page: String(options.page ?? 1), size: String(options.size ?? 20) },
      signal: options.signal,
    });
  }

  listPreparationDrafts(options: { page?: number; size?: number; signal?: AbortSignal } = {}): Promise<ProjectPreparationDraftPage> {
    return request<ProjectPreparationDraftPage>("/api/v1/projects/preparation-drafts", {
      params: { page: String(options.page ?? 1), size: String(options.size ?? 20) },
      signal: options.signal,
    });
  }

  async createBlank(options: IdempotentProjectRequest): Promise<Project> {
    if(options.automaticAnalysisFileCount!==undefined){
      const receipt=await request<{projectId:string;caseId:string}>("/api/v1/contract-analysis-cases/intakes",{
        method:"POST",headers:{"Idempotency-Key":options.idempotencyKey},body:{fileCount:options.automaticAnalysisFileCount},signal:options.signal});
      return (await this.getProject(receipt.projectId,options.signal)).project;
    }
    return request<Project>("/api/v1/projects", {
      method: "POST", headers: { "Idempotency-Key": options.idempotencyKey }, signal: options.signal,
    });
  }

  getProject(projectId: string, signal?: AbortSignal): Promise<ProjectDetail> {
    return request<ProjectDetail>(`/api/v1/projects/${segment(projectId)}`, { signal });
  }
  getCreationOperation(key: string, signal?: AbortSignal): Promise<Project> {
    return request<Project>("/api/v1/projects/creation-operations", { params: { key }, signal });
  }
  uploadAsync(projectId: string, file: File, options: IdempotentProjectRequest): Promise<ProjectFileAcceptance> {
    const body=new FormData(); body.append("file",file);
    return request<ProjectFileAcceptance>(`/api/v1/projects/${segment(projectId)}/file-jobs`,{
      method:"POST",headers:{"Idempotency-Key":options.idempotencyKey},body,signal:options.signal})
      .then(value=>validAcceptance(value,projectId,true));
  }
  getUploadOperation(projectId: string,key: string,signal?: AbortSignal): Promise<ProjectFileAcceptance> {
    return request<ProjectFileAcceptance>(`/api/v1/projects/${segment(projectId)}/file-jobs/by-operation`,{params:{key},signal})
      .then(value=>validAcceptance(value,projectId,false));
  }
  listFileJobs(projectId: string,signal?: AbortSignal): Promise<ProjectFileJobs> {
    return request<ProjectFileJobs>(`/api/v1/projects/${segment(projectId)}/file-jobs`,{signal}).then(value=>{
      if(!value||value.projectId!==projectId||!Array.isArray(value.items)||typeof value.workerEnabled!=="boolean")processingConflict();
      value.items.forEach(job=>validJob(job,projectId));
      if(value.total!==value.items.length||value.completed!==value.items.filter(job=>job.status==="SUCCEEDED").length
        ||value.failed!==value.items.filter(job=>job.status==="FAILED").length||value.unknown!==value.items.filter(job=>job.status==="UNKNOWN").length
        ||new Set(value.items.map(job=>job.fileId)).size!==value.items.length)processingConflict();
      return value;
    });
  }
  getFileProcessing(projectId: string,fileId: string,signal?: AbortSignal): Promise<ProjectFileJob> {
    return request<ProjectFileJob>(`/api/v1/projects/${segment(projectId)}/files/${segment(fileId)}/processing`,{signal})
      .then(value=>validJob(value,projectId,fileId));
  }
  retryFileProcessing(projectId: string,fileId: string,signal?: AbortSignal): Promise<ProjectFileJob> {
    return request<ProjectFileJob>(`/api/v1/projects/${segment(projectId)}/files/${segment(fileId)}/processing/retry`,{method:"POST",signal})
      .then(value=>validJob(value,projectId,fileId));
  }

  confirmMetadata(projectId: string, payload: ProjectMetadataConfirmation,
                  options: IdempotentProjectRequest): Promise<Project> {
    return request<Project>(`/api/v1/projects/${segment(projectId)}`, {
      method: "PATCH", headers: { "Idempotency-Key": options.idempotencyKey },
      body: payload, signal: options.signal,
    });
  }

  listFiles(projectId: string, signal?: AbortSignal): Promise<ProjectFile[]> {
    return request<ProjectFile[]>(`/api/v1/projects/${segment(projectId)}/files`, { signal });
  }

  upload(projectId: string, file: File, options: IdempotentProjectRequest): Promise<ProjectFile> {
    const body = new FormData();
    body.append("file", file);
    return request<ProjectFile>(`/api/v1/projects/${segment(projectId)}/files`, {
      method: "POST", headers: { "Idempotency-Key": options.idempotencyKey }, body, signal: options.signal,
    });
  }

  recover(projectId: string, fileId: string, signal?: AbortSignal): Promise<ProjectFile> {
    return request<ProjectFile>(`/api/v1/projects/${segment(projectId)}/files/${segment(fileId)}/recover`, {
      method: "POST", signal,
    });
  }

  resumeProgression(projectId: string, fileId: string, payload: ProgressionRecoveryRequest,
                    options: IdempotentProjectRequest): Promise<ProjectFile> {
    return request<ProjectFile>(
      `/api/v1/projects/${segment(projectId)}/files/${segment(fileId)}/progression/resume`, {
        method: "POST", headers: { "Idempotency-Key": options.idempotencyKey },
        body: payload, signal: options.signal,
      });
  }

  getLatestFactExtraction(projectId: string, fileId: string,
                          signal?: AbortSignal): Promise<ContractFactExtractionProjection> {
    return request<ContractFactExtractionProjection>(
      `/api/v1/projects/${segment(projectId)}/files/${segment(fileId)}/contract-fact-extractions/latest`,
      { signal });
  }

  confirmRole(projectId: string, fileId: string, payload: RoleConfirmationRequest,
              options: IdempotentProjectRequest): Promise<RoleConfirmationResponse> {
    return request<RoleConfirmationResponse>(
      `/api/v1/projects/${segment(projectId)}/files/${segment(fileId)}/role-confirmations`, {
        method: "POST", headers: { "Idempotency-Key": options.idempotencyKey },
        body: payload, signal: options.signal,
      });
  }

  confirmProjectIdentity(projectId: string, fileId: string, payload: IdentityConfirmationRequest,
                         options: IdempotentProjectRequest): Promise<IdentityConfirmationResponse> {
    return request<IdentityConfirmationResponse>(
      `/api/v1/projects/${segment(projectId)}/files/${segment(fileId)}/identity-confirmations`, {
        method: "POST", headers: { "Idempotency-Key": options.idempotencyKey },
        body: payload, signal: options.signal,
      });
  }

  getCandidate(projectId: string, candidateId: string, signal?: AbortSignal): Promise<CandidateDetail> {
    return request<CandidateDetail>(
      `/api/v1/projects/${segment(projectId)}/contract-candidates/${segment(candidateId)}`, { signal });
  }

  replaceCandidateSupportAuthority(projectId: string, candidateId: string,
      payload: CandidateSupportAuthorityReplacementRequest,
      options: IdempotentProjectRequest): Promise<CandidateSupportAuthorityReplacementResponse> {
    return request<CandidateSupportAuthorityReplacementResponse>(
      `/api/v1/projects/${segment(projectId)}/contract-candidates/${segment(candidateId)}/support-authority-replacements`, {
        method: "POST", headers: { "Idempotency-Key": options.idempotencyKey }, signal: options.signal,
        body: { expectedCandidateVersion: payload.expectedCandidateVersion, supportFileIds: payload.supportFileIds },
      });
  }

  confirmCandidate(projectId: string, candidateId: string, payload: ConfirmedContractPayload,
          options: IdempotentProjectRequest): Promise<CandidateConfirmation> {
    return request<CandidateConfirmation>(
      `/api/v1/projects/${segment(projectId)}/contract-candidates/${segment(candidateId)}/confirmations`, {
        method: "POST", headers: { "Idempotency-Key": options.idempotencyKey },
        body: payload, signal: options.signal,
      });
  }

  getConfirmation(projectId: string, candidateId: string, confirmationId: string,
                  signal?: AbortSignal): Promise<CandidateConfirmation> {
    return request<CandidateConfirmation>(
      `/api/v1/projects/${segment(projectId)}/contract-candidates/${segment(candidateId)}`
      + `/confirmations/${segment(confirmationId)}`, { signal });
  }

  getLatestConfirmation(projectId: string, candidateId: string,
                        signal?: AbortSignal): Promise<CandidateConfirmation> {
    return request<CandidateConfirmation>(
      `/api/v1/projects/${segment(projectId)}/contract-candidates/${segment(candidateId)}`
      + "/confirmations/latest", { signal });
  }

  promote(projectId: string, candidateId: string, payload: CandidatePromotionRequest,
          options: IdempotentProjectRequest): Promise<PromotionResponse> {
    return request<PromotionResponse>(
      `/api/v1/projects/${segment(projectId)}/contract-candidates/${segment(candidateId)}/promote`, {
        method: "POST", headers: { "Idempotency-Key": options.idempotencyKey },
        body: payload, signal: options.signal,
      });
  }

  originalContentUrl(projectId: string, fileId: string): string {
    return apiUrl(`/api/v1/projects/${segment(projectId)}/files/${segment(fileId)}/content`);
  }
}

export const httpProjectDataSource = new HttpProjectDataSource();
