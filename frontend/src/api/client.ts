/**
 * Shared HTTP client for the audit frontend.
 * Uses the backend's Session Cookie + CSRF contract for state-changing requests.
 */

const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");

/** Builds a credential-compatible API URL for browser-native streaming elements. */
export function apiUrl(path: string): string {
  return `${BASE_URL}${path}`;
}

export type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export interface RequestOptions {
  method?: HttpMethod;
  headers?: Record<string, string>;
  body?: unknown;
  params?: Record<string, string>;
  signal?: AbortSignal;
}

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export type ApiErrorCode =
  | "UNAUTHENTICATED"
  | "FORBIDDEN"
  | "BUSINESS_ERROR"
  | "SERVER_ERROR"
  | "NETWORK_ERROR";

export class ApiError extends Error {
  readonly status: number;
  readonly code: ApiErrorCode;
  readonly data?: unknown;

  constructor(status: number, code: ApiErrorCode, message: string, data?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.data = data;
  }
}

interface CsrfResponse {
  headerName: string;
  parameterName: string;
  token: string;
}

const STATE_CHANGING_METHODS = new Set<HttpMethod>(["POST", "PUT", "PATCH", "DELETE"]);

let unauthenticatedHandler: (() => void) | null = null;

export function setUnauthenticatedHandler(handler: (() => void) | null): void {
  unauthenticatedHandler = handler;
}

function classifyError(status: number): ApiErrorCode {
  if (status === 401) return "UNAUTHENTICATED";
  if (status === 403) return "FORBIDDEN";
  if ([400, 404, 409, 422].includes(status)) return "BUSINESS_ERROR";
  if (status >= 500) return "SERVER_ERROR";
  return "BUSINESS_ERROR";
}

function isApiResponse<T>(value: unknown): value is ApiResponse<T> {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<ApiResponse<T>>;
  return typeof candidate.code === "number" && typeof candidate.message === "string" && "data" in candidate;
}

async function readApiResponse<T>(response: Response): Promise<ApiResponse<T> | null> {
  try {
    const payload: unknown = await response.json();
    return isApiResponse<T>(payload) ? payload : null;
  } catch {
    return null;
  }
}

async function executeRequest<T>(url: string, init: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, init);
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") throw error;
    const message = error instanceof Error ? error.message : "网络请求失败";
    throw new ApiError(0, "NETWORK_ERROR", message);
  }

  if (response.status === 401) unauthenticatedHandler?.();

  const result = await readApiResponse<T>(response);
  if (!response.ok || !result || result.code !== 200) {
    const status = result && result.code !== 200 ? result.code : response.status;
    const message = result?.message || response.statusText || "请求失败";
    const code = !result && response.ok ? "SERVER_ERROR" : classifyError(status);
    throw new ApiError(status, code, message, result?.data);
  }

  return result.data;
}

async function fetchCsrfToken(signal?: AbortSignal): Promise<CsrfResponse> {
  const csrf = await executeRequest<CsrfResponse>(`${BASE_URL}/api/v1/auth/csrf`, {
    method: "GET",
    credentials: "include",
    signal,
  });
  if (!csrf.headerName || !csrf.token) {
    throw new ApiError(500, "SERVER_ERROR", "服务端返回的 CSRF 信息不完整");
  }
  return csrf;
}

function removeHeader(headers: Record<string, string>, name: string): void {
  const key = Object.keys(headers).find((candidate) => candidate.toLowerCase() === name.toLowerCase());
  if (key) delete headers[key];
}

function hasHeader(headers: Record<string, string>, name: string): boolean {
  return Object.keys(headers).some((candidate) => candidate.toLowerCase() === name.toLowerCase());
}

/** Sends an API request and unwraps the backend's { code, message, data } envelope. */
export async function request<T>(url: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", headers = {}, body, params, signal } = options;
  const reqHeaders = { ...headers };
  let fullUrl = `${BASE_URL}${url}`;

  if (params) {
    const query = new URLSearchParams(params).toString();
    if (query) fullUrl += `${fullUrl.includes("?") ? "&" : "?"}${query}`;
  }

  if (STATE_CHANGING_METHODS.has(method)) {
    const csrf = await fetchCsrfToken(signal);
    reqHeaders[csrf.headerName] = csrf.token;
  }

  const config: RequestInit = {
    method,
    headers: reqHeaders,
    credentials: "include",
    signal,
  };

  if (body !== undefined) {
    const formData = typeof FormData !== "undefined" && body instanceof FormData;
    if (formData) {
      removeHeader(reqHeaders, "Content-Type");
      config.body = body;
    } else {
      if (!hasHeader(reqHeaders, "Content-Type")) reqHeaders["Content-Type"] = "application/json";
      config.body = JSON.stringify(body);
    }
  }

  return executeRequest<T>(fullUrl, config);
}
