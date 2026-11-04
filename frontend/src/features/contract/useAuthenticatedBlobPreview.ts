import { useEffect, useRef, useState } from "react";

interface BlobPreviewState {
  objectUrl: string | null;
  contentType: string;
  loading: boolean;
  error: string | null;
}

class BlobResponseError extends Error {}

function responseError(status: number): string {
  if (status === 401) return "登录状态已失效，请重新登录。";
  if (status === 403) return "当前账号无权限查看该材料。";
  if (status === 404) return "该材料暂时无法查看，请刷新后重试。";
  if (status >= 500) return "材料暂时无法读取，请稍后重试。";
  return "材料暂时无法读取，请稍后重试。";
}

export function useAuthenticatedBlobPreview(
  sourceUrl: string | null,
  declaredContentType: string,
): BlobPreviewState {
  const [state, setState] = useState<BlobPreviewState>({
    objectUrl: null,
    contentType: declaredContentType,
    loading: false,
    error: null,
  });
  const runIdRef = useRef(0);
  const objectUrlRef = useRef<string | null>(null);

  useEffect(() => {
    const runId = ++runIdRef.current;
    const controller = new AbortController();
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = null;
    }
    setState({
      objectUrl: null,
      contentType: declaredContentType,
      loading: Boolean(sourceUrl),
      error: null,
    });

    if (!sourceUrl) return () => controller.abort();

    void fetch(sourceUrl, {
      method: "GET",
      credentials: "include",
      signal: controller.signal,
    }).then(async (response) => {
      if (!response.ok) throw new BlobResponseError(responseError(response.status));
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      if (controller.signal.aborted || runId !== runIdRef.current) {
        URL.revokeObjectURL(objectUrl);
        return;
      }
      objectUrlRef.current = objectUrl;
      setState({
        objectUrl,
        contentType: blob.type || declaredContentType,
        loading: false,
        error: null,
      });
    }).catch((cause: unknown) => {
      if (cause instanceof Error && cause.name === "AbortError") return;
      if (runId !== runIdRef.current) return;
      setState({
        objectUrl: null,
        contentType: declaredContentType,
        loading: false,
        error: cause instanceof BlobResponseError
          ? cause.message
          : "材料暂时无法读取，请稍后重试。",
      });
    });

    return () => controller.abort();
  }, [declaredContentType, sourceUrl]);

  useEffect(() => () => {
    if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    objectUrlRef.current = null;
  }, []);

  return state;
}
