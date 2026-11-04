import { apiUrl, request } from "../../../api/client";
import type { ReviewDocumentKind } from "../workspaceTypes";
import type {
  ContractReviewSession,
  SessionDocumentList,
  SessionUploadResult,
  ReviewSessionTaskPage,
} from "../sessionTypes";

export interface SessionRequestOptions {
  signal?: AbortSignal;
}

export interface ContractReviewSessionDataSource {
  createSession(
    contractId: string,
    input: { name: string; remark: string; idempotencyKey: string; signal?: AbortSignal },
  ): Promise<ContractReviewSession>;
  listSessions(contractId: string, options?: SessionRequestOptions): Promise<ContractReviewSession[]>;
  listTasks(input: {
    page: number;
    size: number;
    status?: string;
    keyword?: string;
    signal?: AbortSignal;
  }): Promise<ReviewSessionTaskPage>;
  getSession(sessionId: string, options?: SessionRequestOptions): Promise<ContractReviewSession>;
  getDocuments(sessionId: string, includeHistory: boolean, options?: SessionRequestOptions): Promise<SessionDocumentList>;
  uploadDocument(
    sessionId: string,
    input: {
      documentType: ReviewDocumentKind;
      expectedVersion: number;
      file: File;
      idempotencyKey: string;
      signal?: AbortSignal;
    },
  ): Promise<SessionUploadResult>;
  removeDocument(
    sessionId: string,
    documentId: string,
    input: { expectedVersion: number; idempotencyKey: string; signal?: AbortSignal },
  ): Promise<ContractReviewSession>;
  confirmMaterials(
    sessionId: string,
    input: { expectedVersion: number; idempotencyKey: string; signal?: AbortSignal },
  ): Promise<ContractReviewSession>;
  recoverDocument(
    sessionId: string,
    documentId: string,
    options?: SessionRequestOptions,
  ): Promise<SessionUploadResult>;
  contentUrl(sessionId: string, documentId: string, history?: boolean): string;
}

class HttpContractReviewSessionDataSource implements ContractReviewSessionDataSource {
  createSession(
    contractId: string,
    input: { name: string; remark: string; idempotencyKey: string; signal?: AbortSignal },
  ): Promise<ContractReviewSession> {
    return request(`/api/v1/contracts/${encodeURIComponent(contractId)}/review-sessions`, {
      method: "POST",
      headers: { "Idempotency-Key": input.idempotencyKey },
      body: { name: input.name, remark: input.remark || null },
      signal: input.signal,
    });
  }

  listSessions(contractId: string, options: SessionRequestOptions = {}): Promise<ContractReviewSession[]> {
    return request(`/api/v1/contracts/${encodeURIComponent(contractId)}/review-sessions`, options);
  }

  listTasks(input: {
    page: number;
    size: number;
    status?: string;
    keyword?: string;
    signal?: AbortSignal;
  }): Promise<ReviewSessionTaskPage> {
    return request("/api/v1/contract-review-sessions", {
      signal: input.signal,
      params: {
        page: String(input.page),
        size: String(input.size),
        ...(input.status ? { status: input.status } : {}),
        ...(input.keyword?.trim() ? { keyword: input.keyword.trim() } : {}),
      },
    });
  }

  getSession(sessionId: string, options: SessionRequestOptions = {}): Promise<ContractReviewSession> {
    return request(`/api/v1/contract-review-sessions/${encodeURIComponent(sessionId)}`, options);
  }

  getDocuments(
    sessionId: string,
    includeHistory: boolean,
    options: SessionRequestOptions = {},
  ): Promise<SessionDocumentList> {
    return request(`/api/v1/contract-review-sessions/${encodeURIComponent(sessionId)}/documents`, {
      ...options,
      params: { includeHistory: String(includeHistory) },
    });
  }

  uploadDocument(
    sessionId: string,
    input: {
      documentType: ReviewDocumentKind;
      expectedVersion: number;
      file: File;
      idempotencyKey: string;
      signal?: AbortSignal;
    },
  ): Promise<SessionUploadResult> {
    const form = new FormData();
    form.set("documentType", input.documentType);
    form.set("expectedVersion", String(input.expectedVersion));
    form.set("file", input.file);
    return request(`/api/v1/contract-review-sessions/${encodeURIComponent(sessionId)}/documents`, {
      method: "POST",
      headers: { "Idempotency-Key": input.idempotencyKey },
      body: form,
      signal: input.signal,
    });
  }

  removeDocument(
    sessionId: string,
    documentId: string,
    input: { expectedVersion: number; idempotencyKey: string; signal?: AbortSignal },
  ): Promise<ContractReviewSession> {
    return request(
      `/api/v1/contract-review-sessions/${encodeURIComponent(sessionId)}/documents/${encodeURIComponent(documentId)}`,
      {
        method: "DELETE",
        headers: { "Idempotency-Key": input.idempotencyKey },
        params: { expectedVersion: String(input.expectedVersion) },
        signal: input.signal,
      },
    );
  }

  confirmMaterials(
    sessionId: string,
    input: { expectedVersion: number; idempotencyKey: string; signal?: AbortSignal },
  ): Promise<ContractReviewSession> {
    return request(`/api/v1/contract-review-sessions/${encodeURIComponent(sessionId)}/confirm-materials`, {
      method: "POST",
      headers: { "Idempotency-Key": input.idempotencyKey },
      body: { expectedVersion: input.expectedVersion },
      signal: input.signal,
    });
  }

  recoverDocument(
    sessionId: string,
    documentId: string,
    options: SessionRequestOptions = {},
  ): Promise<SessionUploadResult> {
    return request(
      `/api/v1/contract-review-sessions/${encodeURIComponent(sessionId)}/documents/${encodeURIComponent(documentId)}/recover`,
      { method: "POST", signal: options.signal },
    );
  }

  contentUrl(sessionId: string, documentId: string, history = false): string {
    const path = `/api/v1/contract-review-sessions/${encodeURIComponent(sessionId)}/documents/${encodeURIComponent(documentId)}/content`;
    return apiUrl(history ? `${path}?history=true` : path);
  }
}

export const contractReviewSessionDataSource: ContractReviewSessionDataSource =
  new HttpContractReviewSessionDataSource();
