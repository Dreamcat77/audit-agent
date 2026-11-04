import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";
// Node's type-stripping runner does not provide Vite's import.meta.env object.
const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(specifier === "./client" ? "./client.ts" : specifier, context);
  },
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    if (!url.endsWith("/src/api/client.ts")) return loaded;
    const source = typeof loaded.source === "string" ? loaded.source : Buffer.from(loaded.source ?? []).toString("utf8");
    return { ...loaded, source: `import.meta.env ??= {};\n${source}` };
  },
});
const { ApiError, setUnauthenticatedHandler } = await import("./client.ts");
const { uploadRequest: request } = await import("./clientUpload.ts");
hooks.deregister();

function envelope(data: unknown, code = 200, message = "success") {
  return new Response(JSON.stringify({ code, message, data }), {
    status: code === 200 ? 200 : code,
    headers: { "Content-Type": "application/json" },
  });
}

test("FormData upload reports actual byte progress and keeps the shared CSRF/session contract", async (t) => {
  const originalFetch = globalThis.fetch;
  const originalXhr = globalThis.XMLHttpRequest;
  const calls: Array<{ url: string; method: string; withCredentials: boolean; headers: Record<string, string>; body: unknown }> = [];
  const progress: Array<{ loaded: number; total: number }> = [];

  class FakeXmlHttpRequest extends EventTarget {
    upload = new EventTarget();
    status = 0;
    statusText = "";
    responseText = "";
    withCredentials = false;
    method = "";
    url = "";
    headers: Record<string, string> = {};
    body: unknown;
    open(method: string, url: string) { this.method = method; this.url = url; }
    setRequestHeader(name: string, value: string) { this.headers[name] = value; }
    send(body: unknown) {
      this.body = body;
      calls.push({ url: this.url, method: this.method, withCredentials: this.withCredentials, headers: this.headers, body });
      const event = new Event("progress");
      Object.assign(event, { lengthComputable: true, loaded: 37, total: 100 });
      this.upload.dispatchEvent(event);
      this.status = 200;
      this.responseText = JSON.stringify({ code: 200, message: "success", data: { id: "case-1" } });
      this.dispatchEvent(new Event("load"));
    }
    abort() { this.dispatchEvent(new Event("abort")); }
  }

  globalThis.fetch = async (input) => {
    assert.equal(String(input), "/api/v1/auth/csrf");
    return envelope({ headerName: "X-CSRF-TOKEN", token: "upload-csrf" });
  };
  globalThis.XMLHttpRequest = FakeXmlHttpRequest as unknown as typeof XMLHttpRequest;
  t.after(() => {
    globalThis.fetch = originalFetch;
    if (originalXhr) globalThis.XMLHttpRequest = originalXhr;
    else delete (globalThis as { XMLHttpRequest?: typeof XMLHttpRequest }).XMLHttpRequest;
  });

  const form = new FormData();
  form.append("files", new Blob(["file bytes"]), "batch.pdf");
  const result = await request<{ id: string }>("/api/v1/procurement/cases", {
    method: "POST",
    body: form,
    headers: { "Idempotency-Key": "stable-operation-key" },
    onUploadProgress: (value) => progress.push(value),
  });

  assert.deepEqual(result, { id: "case-1" });
  assert.deepEqual(progress, [{ loaded: 37, total: 100 }]);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].method, "POST");
  assert.equal(calls[0].withCredentials, true);
  assert.equal(calls[0].headers["idempotency-key"], "stable-operation-key");
  assert.equal(calls[0].headers["x-csrf-token"], "upload-csrf");
  assert.equal(Object.keys(calls[0].headers).some((name) => name.toLowerCase() === "content-type"), false);
  assert.equal(calls[0].body, form);
});

test("upload progress cannot mask an error envelope from the shared API contract", async (t) => {
  const originalFetch = globalThis.fetch;
  const originalXhr = globalThis.XMLHttpRequest;
  class FakeXmlHttpRequest extends EventTarget {
    upload = new EventTarget();
    status = 409;
    statusText = "Conflict";
    responseText = JSON.stringify({ code: 409, message: "operation conflict", data: null });
    withCredentials = false;
    open() {}
    setRequestHeader() {}
    send() { this.dispatchEvent(new Event("load")); }
    abort() {}
  }
  globalThis.fetch = async () => envelope({ headerName: "X-CSRF-TOKEN", token: "csrf" });
  globalThis.XMLHttpRequest = FakeXmlHttpRequest as unknown as typeof XMLHttpRequest;
  t.after(() => {
    globalThis.fetch = originalFetch;
    if (originalXhr) globalThis.XMLHttpRequest = originalXhr;
    else delete (globalThis as { XMLHttpRequest?: typeof XMLHttpRequest }).XMLHttpRequest;
  });

  const form = new FormData();
  form.append("files", new Blob(["file bytes"]), "batch.pdf");
  await assert.rejects(
    request("/api/v1/procurement/cases", { method: "POST", body: form, onUploadProgress: () => {} }),
    (error: unknown) => error instanceof ApiError && error.status === 409 && error.message === "operation conflict",
  );
});

test("unknown upload responses and aborts do not issue a replacement POST", async (t) => {
  const originalFetch = globalThis.fetch;
  const originalXhr = globalThis.XMLHttpRequest;
  t.after(() => {
    globalThis.fetch = originalFetch;
    if (originalXhr) globalThis.XMLHttpRequest = originalXhr;
    else delete (globalThis as { XMLHttpRequest?: typeof XMLHttpRequest }).XMLHttpRequest;
  });
  globalThis.fetch = async () => envelope({ headerName: "X-CSRF-TOKEN", token: "csrf" });
  for (const outcome of ["error", "timeout", "abort"]) {
    let posts = 0;
    class FailedUpload extends EventTarget {
      upload = new EventTarget();
      withCredentials = false;
      open() {}
      setRequestHeader() {}
      send() { posts++; this.dispatchEvent(new Event(outcome)); }
      abort() { this.dispatchEvent(new Event("abort")); }
    }
    globalThis.XMLHttpRequest = FailedUpload as unknown as typeof XMLHttpRequest;
    const body = new FormData();
    body.append("files", new Blob(["test bytes"]), "source.pdf");
    await assert.rejects(request("/api/v1/procurement/cases", {
      method: "POST", body, headers: { "Idempotency-Key": "immutable-operation" }, onUploadProgress: () => {},
    }), (error: unknown) => outcome === "abort" ? error instanceof Error && error.name === "AbortError"
      : error instanceof ApiError && error.code === "NETWORK_ERROR");
    assert.equal(posts, 1, `uncertain ${outcome} outcome must not replay the upload`);
  }
});


test("upload errors preserve shared classifications, machine data, and session expiry handling", async (t) => {
  const originalFetch = globalThis.fetch;
  const originalXhr = globalThis.XMLHttpRequest;
  let expired = 0;
  setUnauthenticatedHandler(() => expired++);
  t.after(() => {
    globalThis.fetch = originalFetch;
    if (originalXhr) globalThis.XMLHttpRequest = originalXhr;
    else delete (globalThis as { XMLHttpRequest?: typeof XMLHttpRequest }).XMLHttpRequest;
    setUnauthenticatedHandler(null);
  });
  for (const [status, code] of [[401, 'UNAUTHENTICATED'], [403, 'FORBIDDEN'], [409, 'BUSINESS_ERROR'], [503, 'SERVER_ERROR']] as const) {
    let posts = 0;
    class RejectedUpload extends EventTarget {
      upload = new EventTarget();
      withCredentials = false;
      status = status;
      statusText = 'rejected';
      responseText = JSON.stringify({ code: status, message: 'rejected', data: { classification: 'OUTCOME_UNCERTAIN' } });
      open() {} setRequestHeader() {} abort() {}
      send() { posts++; this.dispatchEvent(new Event('load')); }
    }
    globalThis.XMLHttpRequest = RejectedUpload as unknown as typeof XMLHttpRequest;
    globalThis.fetch = async input => String(input).endsWith('/auth/me')
      ? envelope(null, 401, 'expired') : envelope({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
    await assert.rejects(request('/api/v1/procurement/cases', { body: new FormData(), onUploadProgress: () => {} }),
      (error: unknown) => error instanceof ApiError && error.status === status && error.code === code
        && (error.data as { classification: string }).classification === 'OUTCOME_UNCERTAIN');
    assert.equal(posts, 1);
  }
  assert.equal(expired, 1);
});
