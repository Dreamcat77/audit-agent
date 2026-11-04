import assert from "node:assert/strict";
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

const [React, { createRoot }, { useContractClauseReviews }] = await Promise.all([
  import("react"),
  import("react-dom/client"),
  import("./useContractClauseReviews.ts"),
]).finally(() => hooks.deregister());
import type { ContractDataSource } from "./data/contractDataSource.ts";
import type { BreachClauseReview, ContractTermClauseReview } from "./types.ts";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function breach(sourceFileId: string): BreachClauseReview {
  return { materialInsufficient: false, items: [{
    scenario: "逾期交付", rate: 1, rateUnit: "PERCENT", frequency: "WEEK",
    calculationBasis: "CONTRACT_AMOUNT", correspondingAmount: 670, capRate: 20, capRateUnit: "PERCENT",
    capAmount: 13400, actualPenaltyAmount: null, terminationCondition: null,
    status: "NORMAL", warning: null, sourceFileId, excerpt: "每延期一周",
  }] };
}

function term(sourceFileId: string): ContractTermClauseReview {
  return { materialInsufficient: false, dates: [{
    type: "DELIVERY_DEADLINE", source: "2026年6月30日", normalizedDate: "2026-06-30",
    status: "NORMAL", sourceFileId, excerpt: "2026年6月30日前完成", locatorAvailable: true,
  }], conditions: [] };
}

interface HookProps {
  projectId: string | null;
  contractId: string;
  fileIds: readonly string[];
  dataSource: ContractDataSource;
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
  let state: ReturnType<typeof useContractClauseReviews> | null = null;
  function Harness(props: HookProps) {
    state = useContractClauseReviews(props.projectId, props.contractId, props.fileIds, props.dataSource);
    return null;
  }
  root = createRoot(new FakeElement(fakeDocument, "div") as unknown as Element);
  const render = async (props: HookProps) => {
    currentProps = props;
    await React.act(async () => { root.render(React.createElement(Harness, currentProps)); });
  };
  return {
    render,
    rerender: (overrides: Partial<HookProps>) => render({ ...currentProps, ...overrides }),
    state: () => { assert.ok(state); return state; },
  };
}

test("contract switch aborts and stale clause results cannot populate the new workspace", async (t) => {
  const requests: Array<{
    projectId: string;
    kind: "breach" | "term";
    signal: AbortSignal | undefined;
    completion: ReturnType<typeof deferred<BreachClauseReview | ContractTermClauseReview>>;
  }> = [];
  const dataSource = {
    reviewBreachClauses(projectId: string, _contractId: string, _fileIds: readonly string[], options?: { signal?: AbortSignal }) {
      const completion = deferred<BreachClauseReview>();
      requests.push({ projectId, kind: "breach", signal: options?.signal, completion });
      return completion.promise;
    },
    reviewTermClauses(projectId: string, _contractId: string, _fileIds: readonly string[], options?: { signal?: AbortSignal }) {
      const completion = deferred<ContractTermClauseReview>();
      requests.push({ projectId, kind: "term", signal: options?.signal, completion });
      return completion.promise;
    },
  } as unknown as ContractDataSource;
  const hook = mountHook(t, { projectId: "project-a", contractId: "contract-a", fileIds: ["file-a"], dataSource });
  await hook.render({ projectId: "project-a", contractId: "contract-a", fileIds: ["file-a"], dataSource });
  await React.act(async () => {
    void hook.state().analyze();
    await new Promise<void>((resolve) => setImmediate(resolve));
  });
  assert.equal(requests.length, 2);

  await hook.rerender({ projectId: "project-b", contractId: "contract-b" });
  assert.ok(requests.every((request) => request.signal?.aborted));
  await React.act(async () => {
    void hook.state().analyze();
    await new Promise<void>((resolve) => setImmediate(resolve));
  });
  assert.equal(requests.length, 4);

  await React.act(async () => {
    requests[2]?.completion.resolve(breach("file-b"));
    requests[3]?.completion.resolve(term("file-b"));
    await Promise.all([requests[2]?.completion.promise, requests[3]?.completion.promise]);
  });
  await React.act(async () => {
    requests[0]?.completion.resolve(breach("file-a"));
    requests[1]?.completion.resolve(term("file-a"));
    await Promise.all([requests[0]?.completion.promise, requests[1]?.completion.promise]);
  });
  assert.equal(hook.state().breachResult?.items[0]?.sourceFileId, "file-b");
  assert.equal(hook.state().termResult?.dates[0]?.sourceFileId, "file-b");
  assert.equal(hook.state().breachLoading, false);
  assert.equal(hook.state().termLoading, false);
});

test("a failed rerun clears the previous clause result and reports the current failure", async (t) => {
  let run = 0;
  const dataSource = {
    reviewBreachClauses: async () => {
      if (run === 0) return breach("file-a");
      throw new Error("breach unavailable");
    },
    reviewTermClauses: async () => {
      if (run === 0) return term("file-a");
      throw new Error("term unavailable");
    },
  } as unknown as ContractDataSource;
  const hook = mountHook(t, { projectId: "project-a", contractId: "contract-a", fileIds: ["file-a"], dataSource });
  await hook.render({ projectId: "project-a", contractId: "contract-a", fileIds: ["file-a"], dataSource });
  await React.act(async () => { await hook.state().analyze(); });
  assert.ok(hook.state().breachResult);
  assert.ok(hook.state().termResult);

  run = 1;
  await React.act(async () => { await hook.state().analyze(); });
  assert.equal(hook.state().breachResult, null);
  assert.equal(hook.state().termResult, null);
  assert.equal(hook.state().breachError, "违约责任条款审查暂未完成。");
  assert.equal(hook.state().termError, "合同期限与终止条件审查暂未完成。");
});

test("material selection switch aborts stale clause requests and sends the current fileIds", async (t) => {
  const calls: Array<{ fileIds: readonly string[]; signal: AbortSignal | undefined;
    completion: ReturnType<typeof deferred<BreachClauseReview | ContractTermClauseReview>> }> = [];
  const dataSource = {
    reviewBreachClauses(_projectId: string, _contractId: string, fileIds: readonly string[], options?: { signal?: AbortSignal }) {
      const completion = deferred<BreachClauseReview>();
      calls.push({ fileIds: [...fileIds], signal: options?.signal, completion });
      return completion.promise;
    },
    reviewTermClauses(_projectId: string, _contractId: string, fileIds: readonly string[], options?: { signal?: AbortSignal }) {
      const completion = deferred<ContractTermClauseReview>();
      calls.push({ fileIds: [...fileIds], signal: options?.signal, completion });
      return completion.promise;
    },
  } as unknown as ContractDataSource;
  const initial = { projectId: "project-a", contractId: "contract-a", fileIds: ["contract", "tender"], dataSource };
  const hook = mountHook(t, initial);
  await hook.render(initial);
  await React.act(async () => { void hook.state().analyze(); });
  assert.deepEqual(calls.map((call) => call.fileIds), [["contract", "tender"], ["contract", "tender"]]);

  await hook.rerender({ fileIds: ["contract", "award"] });
  assert.ok(calls.every((call) => call.signal?.aborted));
  assert.equal(hook.state().breachResult, null);
  assert.equal(hook.state().termResult, null);
});
