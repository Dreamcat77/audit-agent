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
    if (
      !url.endsWith("/src/api/client.ts")
      && !url.endsWith("/features/contract/data/contractDataSource.ts")
    ) return loaded;
    const source = typeof loaded.source === "string"
      ? loaded.source
      : Buffer.from(loaded.source ?? []).toString("utf8");
    return { ...loaded, source: `import.meta.env ??= {};\n${source}` };
  },
});

class MemoryStorage implements Storage {
  readonly values = new Map<string, string>();
  get length(): number { return this.values.size; }
  clear(): void { this.values.clear(); }
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  key(index: number): string | null { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string): void { this.values.delete(key); }
  setItem(key: string, value: string): void { this.values.set(key, value); }
}

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
  sessionStorage: new MemoryStorage(),
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
const [React, { createRoot }, { useContractReviewWorkspace }, { ShowcaseContractDataSource }] =
  await Promise.all([
    import("react"),
    import("react-dom/client"),
    import("./useContractReviewWorkspace.ts"),
    import("./data/showcaseContractDataSource.ts"),
  ]).finally(() => hooks.deregister());
import type { ContractDataSource } from "./data/contractDataSource.ts";
import type { F02ReviewOperationPersistence } from "./reviewOperation.ts";
import type {
  Contract,
  FindingAcknowledgement,
  ReviewProgress,
  ReviewResult,
} from "./types.ts";

const CONTRACT_ID = "contract-race";
const ACTOR_ID = "auditor-race";
const OLD_TASK_ID = "task-old";
const NEW_TASK_ID = "task-new";
const OLD_RESULT_ID = "result-old";
const NEW_RESULT_ID = "result-new";

const CONTRACT: Contract = {
  id: CONTRACT_ID,
  contractNo: "CT-RACE",
  contractName: "Race contract",
  contractType: "PROCUREMENT",
  partyA: "A",
  partyB: "B",
  partyBCreditCode: null,
    contractAmount: 100,
  amountInWords: null,
  status: "READY_FOR_REVIEW",
  versionNo: 1,
  createdAt: "2026-08-25T00:00:00Z",
  updatedAt: "2026-08-25T00:00:00Z",
  lineItems: [],
  awardNotice: null,
};

function reviewResult(taskId: string, findingId: string): ReviewResult {
  return {
    taskId,
    status: "DONE",
    reviewType: "F02_DETERMINISTIC",
    overallRisk: "RED",
    blockSign: true,
    replayed: false,
    findings: [{
      id: findingId,
      ruleCode: "F02_TEST",
      itemKey: null,
      checkStatus: "FAIL",
      riskLevel: "RED",
      blockSign: true,
      actualValue: "100.00",
      expectedValue: "90.00",
      detail: `risk ${findingId}`,
      ruleVersion: 1,
      createdAt: "2026-08-25T00:01:00Z",
    }],
  };
}

function progress(findingId: string, acknowledgement: FindingAcknowledgement | null = null): ReviewProgress {
  return {
    total: 2,
    riskSummary: "低风险",
    items: [
      {
        type: "SOURCE_PREPARATION",
        checkResultId: null,
        title: "source",
        displayOrder: 0,
        status: "DONE",
        risk: null,
        completedAt: null,
        acknowledgement: null,
      },
      {
        type: "FINDING",
        checkResultId: findingId,
        title: `risk ${findingId}`,
        displayOrder: 1,
        status: "FAIL",
        risk: "RED",
        completedAt: "2026-08-25T00:01:00Z",
        acknowledgement,
      },
    ],
  };
}

const OLD_ACKNOWLEDGEMENT: FindingAcknowledgement = {
  id: "ack-old",
  checkResultId: OLD_RESULT_ID,
  acknowledgedBy: ACTOR_ID,
  acknowledgedByDisplayName: "Auditor",
  acknowledgedAt: "2026-08-25T00:02:00Z",
  requestId: "request-old",
  traceId: "trace-old",
  ruleVersion: 1,
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise; });
  return { promise, resolve };
}

async function flushUntil(predicate: () => boolean, message: string): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    await React.act(async () => { await new Promise<void>((resolve) => setImmediate(resolve)); });
    if (predicate()) return;
  }
  assert.fail(message);
}

test("an old acknowledgement forced GET cannot overwrite progress from a newer review run", async (t) => {
  const restoreGlobals = installScopedGlobals({
    window: fakeWindow, document: fakeDocument, IS_REACT_ACT_ENVIRONMENT: true,
  });
  fakeWindow.sessionStorage.clear();
  let root: ReturnType<typeof createRoot> | undefined;
  t.after(async () => {
    try { await React.act(async () => { root?.unmount(); }); }
    finally { restoreGlobals(); }
  });
  const oldAcknowledgementProgress = deferred<ReviewProgress>();
  let oldProgressReads = 0;
  let oldAcknowledgementSignal: AbortSignal | undefined;
  const dataSource: ContractDataSource = {
    supportsReview: () => true,
    getContract: async () => CONTRACT,
    getLatestReviewResult: async () => reviewResult(OLD_TASK_ID, OLD_RESULT_ID),
    reviewContract: async () => reviewResult(NEW_TASK_ID, NEW_RESULT_ID),
    acknowledgeFinding: async () => OLD_ACKNOWLEDGEMENT,
    getReviewProgress: async (_contractId, taskId, options) => {
      if (taskId === NEW_TASK_ID) return progress(NEW_RESULT_ID);
      assert.equal(taskId, OLD_TASK_ID);
      oldProgressReads += 1;
      if (oldProgressReads === 2) oldAcknowledgementSignal = options?.signal;
      return oldProgressReads === 1
        ? progress(OLD_RESULT_ID)
        : oldAcknowledgementProgress.promise;
    },
  };
  const operationPersistence: F02ReviewOperationPersistence = {
    load: () => null,
    save: () => undefined,
    clear: () => undefined,
  };
  let workspace: ReturnType<typeof useContractReviewWorkspace> | null = null;
  function Harness() {
    workspace = useContractReviewWorkspace(
      CONTRACT_ID,
      ACTOR_ID,
      dataSource,
      operationPersistence,
    );
    return null;
  }

  const container = new FakeElement(fakeDocument, "div");
  root = createRoot(container as unknown as Element);
  await React.act(async () => { root.render(React.createElement(Harness)); });
  await flushUntil(
    () => workspace?.progress?.items[1]?.checkResultId === OLD_RESULT_ID,
    "initial old-task progress did not load",
  );

  await React.act(async () => { workspace?.acknowledgeFinding(OLD_RESULT_ID); });
  await flushUntil(() => oldProgressReads === 2, "acknowledgement forced GET did not start");

  await React.act(async () => { workspace?.startNewReview(); });
  await flushUntil(
    () => workspace?.result?.taskId === NEW_TASK_ID
      && workspace.progress?.items[1]?.checkResultId === NEW_RESULT_ID,
    "new review progress did not become current",
  );
  assert.equal(oldAcknowledgementSignal?.aborted, true);

  oldAcknowledgementProgress.resolve(progress(OLD_RESULT_ID, OLD_ACKNOWLEDGEMENT));
  await React.act(async () => { await new Promise<void>((resolve) => setImmediate(resolve)); });
  assert.equal(workspace?.result?.taskId, NEW_TASK_ID);
  assert.equal(workspace?.progress?.items[1]?.checkResultId, NEW_RESULT_ID);

  await t.test("the shipped showcase source drives hook progress and durable acknowledgement refresh", async (t) => {
    const dataSource = new ShowcaseContractDataSource(
      () => new Date("2026-08-25T10:30:00.000Z"),
    );
    let showcaseWorkspace: ReturnType<typeof useContractReviewWorkspace> | null = null;
    function ShowcaseHarness() {
      showcaseWorkspace = useContractReviewWorkspace(
        "CT-2024-0094",
        "showcase-auditor",
        dataSource,
        operationPersistence,
      );
      return null;
    }

    const showcaseContainer = new FakeElement(fakeDocument, "div");
    const showcaseRoot = createRoot(showcaseContainer as unknown as Element);
    t.after(async () => {
      await React.act(async () => { showcaseRoot.unmount(); });
    });
    await React.act(async () => { showcaseRoot.render(React.createElement(ShowcaseHarness)); });
    await React.act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 180));
    });
    await flushUntil(
      () => showcaseWorkspace?.progressiveReplayState?.pendingAcknowledgementItem !== null
        && showcaseWorkspace?.progressiveReplayState?.pendingAcknowledgementItem !== undefined,
      "shipped showcase progress did not expose its first durable pending risk",
    );

    const pendingId = showcaseWorkspace?.progressiveReplayState
      ?.pendingAcknowledgementItem?.checkResultId;
    assert.ok(pendingId);
    await React.act(async () => { showcaseWorkspace?.acknowledgeFinding(pendingId); });
    await flushUntil(
      () => showcaseWorkspace?.progress?.items.some((item) => (
        item.checkResultId === pendingId
        && item.acknowledgement?.checkResultId === pendingId
      )) === true,
      "hook did not replace showcase progress with the forced durable GET projection",
    );
    assert.equal(
      showcaseWorkspace?.progress?.items.find((item) => item.checkResultId === pendingId)
        ?.acknowledgement?.acknowledgedAt,
      "2026-08-25T10:30:00.000Z",
    );
  });
});
