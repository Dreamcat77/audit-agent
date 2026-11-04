import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import test from "node:test";
import { installScopedGlobals } from "../../test/scopedGlobals.ts";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    if (!url.endsWith("/src/api/client.ts")) return loaded;
    const source = typeof loaded.source === "string"
      ? loaded.source
      : Buffer.from(loaded.source ?? []).toString("utf8");
    return { ...loaded, source: `import.meta.env ??= {};\n${source}` };
  },
});

class FakeElement {
  readonly nodeType = 1;
  readonly namespaceURI = "http://www.w3.org/1999/xhtml";
  readonly style: Record<string, string> = {};
  readonly children: FakeElement[] = [];
  readonly ownerDocument: FakeDocument;
  readonly tagName: string;
  readonly nodeName: string;
  parentNode: FakeElement | null = null;
  textContent = "";

  constructor(ownerDocument: FakeDocument, tagName: string) {
    this.ownerDocument = ownerDocument;
    this.tagName = tagName.toUpperCase();
    this.nodeName = this.tagName;
  }

  addEventListener(): void {}
  removeEventListener(): void {}
  setAttribute(): void {}
  removeAttribute(): void {}
  appendChild(child: FakeElement): FakeElement {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }
  insertBefore(child: FakeElement, before: FakeElement | null): FakeElement {
    child.parentNode = this;
    const index = before ? this.children.indexOf(before) : -1;
    if (index < 0) this.children.push(child);
    else this.children.splice(index, 0, child);
    return child;
  }
  removeChild(child: FakeElement): FakeElement {
    const index = this.children.indexOf(child);
    if (index >= 0) this.children.splice(index, 1);
    child.parentNode = null;
    return child;
  }
}

class FakeDocument {
  readonly nodeType = 9;
  readonly documentElement: FakeElement;
  readonly body: FakeElement;
  activeElement: FakeElement | null = null;
  defaultView!: Record<string, unknown>;

  constructor() {
    this.documentElement = new FakeElement(this, "html");
    this.body = new FakeElement(this, "body");
    this.documentElement.appendChild(this.body);
  }

  addEventListener(): void {}
  removeEventListener(): void {}
  createElement(tagName: string): FakeElement { return new FakeElement(this, tagName); }
}

const fakeDocument = new FakeDocument();
const fakeWindow = {
  document: fakeDocument,
  addEventListener() {},
  removeEventListener() {},
  setTimeout: globalThis.setTimeout.bind(globalThis),
  clearTimeout: globalThis.clearTimeout.bind(globalThis),
  HTMLElement: FakeElement,
  HTMLIFrameElement: class {},
  location: { protocol: "http:" },
};
Object.assign(fakeWindow, { window: fakeWindow, self: fakeWindow, top: fakeWindow, parent: fakeWindow });
fakeDocument.defaultView = fakeWindow;
const [React, { createRoot }, { useContractSemanticAnalysis }] = await Promise.all([
  import("react"),
  import("react-dom/client"),
  import("./useContractSemanticAnalysis.ts"),
]).finally(() => hooks.deregister());
import type { ContractTextAnalysis } from "./types.ts";

interface Material {
  id: string;
  fileName: string;
}

interface Analyzer {
  analyzeContractText(
    projectId: string,
    fileIds: readonly string[],
    options?: { signal?: AbortSignal },
  ): Promise<ContractTextAnalysis>;
}

interface HookProps {
  contractId: string;
  projectId: string | null;
  materials: readonly Material[];
  analyzer: Analyzer;
}

function analysis(projectId: string, summary: string, evidenceRef = "file-a"): ContractTextAnalysis {
  return {
    projectId,
    operation: "CONTRACT_TEXT_ANALYSIS",
    requestId: `request-${summary}`,
    available: true,
    summaries: [summary],
    hints: [{
      category: "DELIVERY_TERMS",
      attention: "INFO",
      title: "交付期限",
      description: "交付期限已提取",
      evidenceRefs: [evidenceRef],
    }],
    model: "qwen-test",
    durationMs: 10,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function flush(): Promise<void> {
  await React.act(async () => { await new Promise<void>((resolve) => setImmediate(resolve)); });
}

function mountHook(t: test.TestContext, initialProps: HookProps) {
  const restoreGlobals = installScopedGlobals({
    window: fakeWindow, document: fakeDocument, IS_REACT_ACT_ENVIRONMENT: true,
  });
  let root: ReturnType<typeof createRoot> | undefined;
  t.after(async () => {
    try { await React.act(async () => { root?.unmount(); }); }
    finally { restoreGlobals(); }
  });
  let currentProps = initialProps;
  let state: ReturnType<typeof useContractSemanticAnalysis> | null = null;
  function Harness(props: HookProps) {
    state = useContractSemanticAnalysis({
      contractId: props.contractId,
      projectId: props.projectId,
      selectedMaterials: props.materials,
      dataSource: props.analyzer,
    });
    return null;
  }
  const container = new FakeElement(fakeDocument, "div");
  root = createRoot(container as unknown as Element);
  const render = async (props: HookProps) => {
    currentProps = props;
    await React.act(async () => { root.render(React.createElement(Harness, currentProps)); });
  };
  return {
    render,
    rerender: (overrides: Partial<HookProps>) => render({ ...currentProps, ...overrides }),
    state: () => {
      assert.ok(state);
      return state;
    },
  };
}

test("double click starts one request and stale A cannot overwrite newer selection B", async (t) => {
  const requests: Array<{
    fileIds: readonly string[];
    signal: AbortSignal | undefined;
    completion: ReturnType<typeof deferred<ContractTextAnalysis>>;
  }> = [];
  const analyzer: Analyzer = {
    analyzeContractText: async (_projectId, fileIds, options) => {
      const completion = deferred<ContractTextAnalysis>();
      requests.push({ fileIds: [...fileIds], signal: options?.signal, completion });
      return completion.promise;
    },
  };
  const hook = mountHook(t, {
    contractId: "contract-a",
    projectId: "project-a",
    materials: [
      { id: "file-a", fileName: "A.pdf" },
      { id: "file-b", fileName: "B.pdf" },
      { id: "file-c", fileName: "C.pdf" },
      { id: "file-d", fileName: "D.pdf" },
    ],
    analyzer,
  });
  await hook.render({
    contractId: "contract-a", projectId: "project-a",
    materials: [
      { id: "file-a", fileName: "A.pdf" }, { id: "file-b", fileName: "B.pdf" },
      { id: "file-c", fileName: "C.pdf" }, { id: "file-d", fileName: "D.pdf" },
    ], analyzer,
  });

  await React.act(async () => {
    void hook.state().analyze();
    void hook.state().analyze();
  });
  assert.equal(requests.length, 1);

  await hook.rerender({
    materials: [
      { id: "file-a", fileName: "A.pdf" },
      { id: "file-b", fileName: "B.pdf" },
    ],
  });
  assert.equal(requests[0].signal?.aborted, true);
  assert.equal(hook.state().result, null);
  assert.equal(hook.state().notice, "材料已变更，等待重新分析");

  await React.act(async () => { void hook.state().analyze(); });
  assert.deepEqual(requests[1].fileIds, ["file-a", "file-b"]);
  requests[1].completion.resolve(analysis("project-a", "selection-b"));
  await flush();
  assert.deepEqual(hook.state().result?.summaries, ["selection-b"]);

  requests[0].completion.resolve(analysis("project-a", "stale-selection-a"));
  await flush();
  assert.deepEqual(hook.state().result?.summaries, ["selection-b"]);
});

test("long request does not locally self-timeout and eventual 200 retains safe AI hints", async (t) => {
  const hookSource = readFileSync(new URL("./useContractSemanticAnalysis.ts", import.meta.url), "utf8");
  assert.doesNotMatch(hookSource, /setTimeout|AbortSignal\.timeout/);

  const pending = deferred<ContractTextAnalysis>();
  const analyzer: Analyzer = { analyzeContractText: async () => pending.promise };
  const initial: HookProps = {
    contractId: "contract-long",
    projectId: "project-long",
    materials: [{ id: "file-a", fileName: "合同正文.docx" }],
    analyzer,
  };
  const hook = mountHook(t, initial);
  await hook.render(initial);

  await React.act(async () => { void hook.state().analyze(); });
  await flush();
  await flush();
  assert.equal(hook.state().loading, true);
  assert.equal(hook.state().error, null);
  assert.equal(hook.state().result, null);

  pending.resolve(analysis("project-long", "真实服务最终返回", "file-a"));
  await flush();

  assert.equal(hook.state().loading, false);
  assert.equal(hook.state().error, null);
  assert.deepEqual(hook.state().result?.summaries, ["真实服务最终返回"]);
  assert.deepEqual(hook.state().result?.hints.map((hint) => hint.title), ["交付期限"]);
});

for (const identity of ["contractId", "projectId"] as const) {
  test(`${identity} switch aborts and discards the old response`, async (t) => {
    const pending = deferred<ContractTextAnalysis>();
    let signal: AbortSignal | undefined;
    const analyzer: Analyzer = {
      analyzeContractText: async (_projectId, _fileIds, options) => {
        signal = options?.signal;
        return pending.promise;
      },
    };
    const initial: HookProps = {
      contractId: "contract-a",
      projectId: "project-a",
      materials: [{ id: "file-a", fileName: "A.pdf" }],
      analyzer,
    };
    const hook = mountHook(t, initial);
    await hook.render(initial);
    await React.act(async () => { void hook.state().analyze(); });

    await hook.rerender({ [identity]: identity === "contractId" ? "contract-b" : "project-b" });
    assert.equal(signal?.aborted, true);
    pending.resolve(analysis("project-a", "stale-identity"));
    await flush();
    assert.equal(hook.state().result, null);
    assert.equal(hook.state().error, null);
  });
}

test("a successful result invalidates immediately when selected files change", async (t) => {
  const analyzer: Analyzer = {
    analyzeContractText: async (projectId) => analysis(projectId, "current"),
  };
  const initial: HookProps = {
    contractId: "contract-a",
    projectId: "project-a",
    materials: [{ id: "file-a", fileName: "A.pdf" }],
    analyzer,
  };
  const hook = mountHook(t, initial);
  await hook.render(initial);
  await React.act(async () => { await hook.state().analyze(); });
  assert.deepEqual(hook.state().result?.summaries, ["current"]);

  await hook.rerender({ materials: [{ id: "file-b", fileName: "B.pdf" }] });
  assert.equal(hook.state().result, null);
  assert.equal(hook.state().notice, "材料已变更，等待重新分析");
});

test("AbortError is normal cancellation and never becomes a visible failure", async (t) => {
  const analyzer: Analyzer = {
    analyzeContractText: (_projectId, _fileIds, options) => new Promise((_, reject) => {
      options?.signal?.addEventListener("abort", () => {
        const error = new Error("cancelled");
        error.name = "AbortError";
        reject(error);
      }, { once: true });
    }),
  };
  const initial: HookProps = {
    contractId: "contract-a",
    projectId: "project-a",
    materials: [{ id: "file-a", fileName: "A.pdf" }],
    analyzer,
  };
  const hook = mountHook(t, initial);
  await hook.render(initial);
  await React.act(async () => { void hook.state().analyze(); });
  await hook.rerender({ materials: [{ id: "file-b", fileName: "B.pdf" }] });
  await flush();

  assert.equal(hook.state().error, null);
  assert.equal(hook.state().notice, "材料已变更，等待重新分析");
});

test("evidence names use the request snapshot even if current names refresh", async (t) => {
  const pending = deferred<ContractTextAnalysis>();
  const analyzer: Analyzer = { analyzeContractText: async () => pending.promise };
  const initial: HookProps = {
    contractId: "contract-a",
    projectId: "project-a",
    materials: [{ id: "file-a", fileName: "请求时名称.pdf" }],
    analyzer,
  };
  const hook = mountHook(t, initial);
  await hook.render(initial);
  await React.act(async () => { void hook.state().analyze(); });
  await hook.rerender({ materials: [{ id: "file-a", fileName: "刷新后名称.pdf" }] });
  pending.resolve(analysis("project-a", "snapshot", "file-a"));
  await flush();

  assert.deepEqual(hook.state().resultMaterials, [
    { id: "file-a", fileName: "请求时名称.pdf" },
  ]);
});

test("AI retry after invalidation uses the current selected files", async (t) => {
  const calls: readonly string[][] = [];
  const mutableCalls = calls as string[][];
  const analyzer: Analyzer = {
    analyzeContractText: async (projectId, fileIds) => {
      mutableCalls.push([...fileIds]);
      return analysis(projectId, `run-${mutableCalls.length}`, fileIds[0]);
    },
  };
  const initial: HookProps = {
    contractId: "contract-a",
    projectId: "project-a",
    materials: [{ id: "file-a", fileName: "A.pdf" }],
    analyzer,
  };
  const hook = mountHook(t, initial);
  await hook.render(initial);
  await React.act(async () => { await hook.state().analyze(); });
  await hook.rerender({ materials: [{ id: "file-b", fileName: "B.pdf" }] });
  await React.act(async () => { await hook.state().analyze(); });

  assert.deepEqual(calls, [["file-a"], ["file-b"]]);
  assert.deepEqual(hook.state().result?.summaries, ["run-2"]);
});
