/** Multipart progress transport. Shared request/response semantics remain in client.ts. */
import { ApiError, apiUrl, request, type ApiResponse, type RequestOptions } from './client';
export interface UploadOptions extends Omit<RequestOptions, 'body' | 'params'> {
  body: FormData;
  onUploadProgress: (progress: { loaded: number; total: number }) => void;
}

export async function uploadRequest<T>(path: string, options: UploadOptions): Promise<T> {
  const csrf = await request<{ headerName: string; token: string }>('/api/v1/auth/csrf', { signal: options.signal });
  if (!csrf.headerName || !csrf.token) throw new ApiError(500, 'SERVER_ERROR', '服务端返回的 CSRF 信息不完整');
  const headers = new Headers(options.headers);
  headers.delete('Content-Type');
  headers.set(csrf.headerName, csrf.token);
  let response: Response;
  try {
    response = await uploadWithProgress(apiUrl(path), { method: options.method || 'POST', headers,
      credentials: 'include', body: options.body, signal: options.signal }, options.onUploadProgress);
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw error;
    throw new ApiError(0, 'NETWORK_ERROR', error instanceof Error ? error.message : '网络请求失败');
  }
  if (response.status === 401) {
    // Read-only session verification invokes the shared unauthenticated handler.
    // It never replays the upload or changes its operation identity.
    try { await request('/api/v1/auth/me', { signal: options.signal }); } catch { /* Keep the original upload error. */ }
  }
  let result: ApiResponse<T> | null = null;
  try {
    const value = await response.json();
    if (value && typeof value.code === 'number' && typeof value.message === 'string' && 'data' in value) result = value;
  } catch { /* Invalid response is an error, including HTTP 200. */ }
  if (!response.ok || !result || result.code !== 200) {
    const status = result && result.code !== 200 ? result.code : response.status;
    const code = !result && response.ok ? 'SERVER_ERROR' : status === 401 ? 'UNAUTHENTICATED'
      : status === 403 ? 'FORBIDDEN' : status >= 500 ? 'SERVER_ERROR' : 'BUSINESS_ERROR';
    throw new ApiError(status, code, result?.message || response.statusText || '请求失败', result?.data);
  }
  return result.data;
}

/** One POST only: an uncertain upload response must retain its existing operation identity. */
function uploadWithProgress(url: string, init: RequestInit, progress: NonNullable<UploadOptions["onUploadProgress"]>): Promise<Response> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const signal = init.signal;
    const abort = () => xhr.abort();
    const cleanup = () => signal?.removeEventListener("abort", abort);
    if (signal?.aborted) { reject(new DOMException("请求已取消", "AbortError")); return; }
    xhr.open(init.method || "POST", url);
    xhr.withCredentials = init.credentials === "include";
    new Headers(init.headers).forEach((value, name) => xhr.setRequestHeader(name, value));
    xhr.upload.addEventListener("progress", event => {
      const value = event as ProgressEvent;
      if (value.lengthComputable) progress({ loaded: value.loaded, total: value.total });
    });
    xhr.addEventListener("load", () => {
      cleanup();
      if (xhr.status === 0) { reject(new Error("上传结果待核实")); return; }
      resolve(new Response(xhr.status === 204 || xhr.status === 304 ? null : xhr.responseText,
        { status: xhr.status, statusText: xhr.statusText }));
    });
    xhr.addEventListener("error", () => { cleanup(); reject(new Error("上传响应未知，请恢复原上传操作")); });
    xhr.addEventListener("abort", () => { cleanup(); reject(new DOMException("请求已取消", "AbortError")); });
    xhr.addEventListener("timeout", () => { cleanup(); reject(new Error("上传响应超时，请恢复原上传操作")); });
    signal?.addEventListener("abort", abort, { once: true });
    try { xhr.send(init.body as XMLHttpRequestBodyInit); }
    catch (error) { cleanup(); reject(error); }
  });
}
