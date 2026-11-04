const DEFAULT_API_PROXY_TARGET = "http://127.0.0.1:8080";

export function resolveApiProxyTarget(candidate: string | undefined): string {
  const raw = candidate?.trim() || DEFAULT_API_PROXY_TARGET;
  const parsed = new URL(raw);
  if ((parsed.protocol !== "http:" && parsed.protocol !== "https:")
    || parsed.username || parsed.password || parsed.search || parsed.hash
    || (parsed.pathname !== "" && parsed.pathname !== "/")) {
    throw new Error("AUDIT_VITE_API_PROXY_TARGET must be an HTTP(S) origin without credentials or a path");
  }
  return parsed.origin;
}
