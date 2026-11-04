import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

// Vite supplies import.meta.env in production. Node's type-stripping runner does not,
// so install only that environment object before loading the unchanged shared client.
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

const [{ HttpContractDataSource }, { MockContractDataSource }, { ShowcaseContractDataSource }, { contractMockCases }, { ApiError }, {
  acknowledgeFindingAndReloadProgress,
  createFindingAcknowledgementOperationState,
  deriveProgressiveReplayState,
}] = await Promise.all([
  import("./httpContractDataSource.ts"),
  import("./contractDataSource.ts"),
  import("./showcaseContractDataSource.ts"),
  import("../mock/contractMock.ts"),
  import("../../../api/client.ts"),
  import("../reviewProgressState.ts"),
]).finally(() => hooks.deregister());
import type {
  FindingAcknowledgement,
  ReviewProgress,
  ReviewProgressItem,
} from "../types.ts";

const CONTRACT_ID = "00000000-0000-0000-0000-000000000101";
const TASK_ID = "00000000-0000-0000-0000-000000000701";
const RESULT_ID = "00000000-0000-0000-0000-000000000801";
const ACK_KEY = "review-ack-ui-immutable-key";
const MOCK_RISK_CONTRACT_ID = "CT-2024-0094";
const MOCK_PASS_CONTRACT_ID = "CT-2024-0089";
const PROJECT_ID = "00000000-0000-0000-0000-000000000201";
const PROJECT_FILE_ID = "00000000-0000-0000-0000-000000000301";

const ACKNOWLEDGEMENT: FindingAcknowledgement = {
  id: "00000000-0000-0000-0000-000000000901",
  checkResultId: RESULT_ID,
  acknowledgedBy: "auditor-1",
  acknowledgedByDisplayName: "审核员甲",
  acknowledgedAt: "2026-08-25T09:10:00Z",
  requestId: "request-ack-1",
  traceId: "trace-ack-1",
  ruleVersion: 1,
};

function progress(acknowledgement: FindingAcknowledgement | null): ReviewProgress {
  const items: ReviewProgressItem[] = [
    {
      type: "SOURCE_PREPARATION",
      checkResultId: null,
      title: "读取合同及比对来源",
      displayOrder: 0,
      status: "DONE",
      risk: null,
      completedAt: "2026-08-25T09:00:00Z",
      acknowledgement: null,
    },
    {
      type: "FINDING",
      checkResultId: RESULT_ID,
      title: "合同金额与中标金额",
      displayOrder: 1,
      status: "FAIL",
      risk: "RED",
      completedAt: "2026-08-25T09:01:00Z",
      acknowledgement,
    },
  ];
  return { total: 2, items, riskSummary: "低风险" };
}

function envelope<T>(data: T, status = 200): Response {
  return new Response(JSON.stringify({ code: status, message: status === 200 ? "success" : "error", data }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

test("contract text analysis uses the typed Project endpoint with shared CSRF and cookies", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const calls: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(input), init });
    if (calls.length === 1) {
      return envelope({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf-ai" });
    }
    if (calls.length === 2) return envelope({
      projectId: PROJECT_ID,
      operation: "CONTRACT_TEXT_ANALYSIS",
      requestId: "request-ai-1",
      available: true,
      summaries: ["付款条款要求验收后结算。"],
      hints: [{
        category: "DELIVERY_TERMS",
        attention: "REVIEW",
        title: "关注验收条件",
        description: "建议人工核对验收材料。",
        evidenceRefs: [PROJECT_FILE_ID],
      }],
      model: "qwen-plus",
      durationMs: 31,
    });
    throw new Error(`unexpected fetch ${calls.length}`);
  }) as typeof fetch;

  const result = await new HttpContractDataSource().analyzeContractText(
    PROJECT_ID,
    [PROJECT_FILE_ID],
  );
  assert.equal(result.operation, "CONTRACT_TEXT_ANALYSIS");
  assert.equal(result.model, "qwen-plus");
  assert.deepEqual(calls.map((call) => [call.init.method, call.url]), [
    ["GET", "/api/v1/auth/csrf"],
    ["POST", `/api/v1/projects/${PROJECT_ID}/ai/contract-text-analysis`],
  ]);
  assert.equal(calls[0]?.init.credentials, "include");
  assert.equal(calls[1]?.init.credentials, "include");
  assert.equal(new Headers(calls[1]?.init.headers).get("X-CSRF-TOKEN"), "csrf-ai");
  assert.equal(calls[1]?.init.body, JSON.stringify({ fileIds: [PROJECT_FILE_ID] }));
  assert.equal(new Headers(calls[1]?.init.headers).get("Content-Type"), "application/json");
});

async function waitForCallCount(calls: readonly unknown[], expected: number): Promise<void> {
  for (let attempt = 0; attempt < 100 && calls.length < expected; attempt += 1) {
    await new Promise<void>((resolve) => setImmediate(resolve));
  }
  assert.equal(calls.length, expected, `expected ${expected} controlled fetch calls`);
}

test("uncertain acknowledgement retry keeps one key and cannot advance before the forced durable GET", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });

  const heldProgress = deferred<Response>();
  const calls: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    const url = String(input);
    calls.push({ url, init });
    if (calls.length === 1) {
      return envelope({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf-first" });
    }
    if (calls.length === 2) throw new TypeError("connection closed after write");
    if (calls.length === 3) {
      return envelope({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf-retry" });
    }
    if (calls.length === 4) return envelope(ACKNOWLEDGEMENT); // same-key server replay
    if (calls.length === 5) return heldProgress.promise;
    if (calls.length === 6) return envelope(progress(ACKNOWLEDGEMENT));
    throw new Error(`unexpected fetch ${calls.length}: ${url}`);
  }) as typeof fetch;

  const dataSource = new HttpContractDataSource();
  const operationState = createFindingAcknowledgementOperationState({
    createIdempotencyKey: () => ACK_KEY,
  });
  const command = {
    dataSource,
    contractId: CONTRACT_ID,
    taskId: TASK_ID,
    checkResultId: RESULT_ID,
    operationState,
  };

  await assert.rejects(
    acknowledgeFindingAndReloadProgress(command),
    (error: Error) => error.name === "ApiError" && error.message === "connection closed after write",
  );
  assert.equal(operationState.retainedKey(RESULT_ID), ACK_KEY);

  let coordinatorResolved = false;
  const retry = acknowledgeFindingAndReloadProgress(command).then((value) => {
    coordinatorResolved = true;
    return value;
  });
  await waitForCallCount(calls, 5);
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(coordinatorResolved, false, "POST replay body cannot resolve the coordinator");
  assert.equal(deriveProgressiveReplayState(progress(null)).complete, false);
  assert.equal(operationState.retainedKey(RESULT_ID), ACK_KEY);

  heldProgress.resolve(envelope(progress(null)));
  const unprovenProgress = await retry;
  assert.equal(unprovenProgress.items[1]?.acknowledgement, null);
  assert.equal(deriveProgressiveReplayState(unprovenProgress).pendingAcknowledgementItem?.checkResultId, RESULT_ID);
  assert.equal(operationState.retainedKey(RESULT_ID), ACK_KEY);

  const provenProgress = await dataSource.getReviewProgress(CONTRACT_ID, TASK_ID);
  operationState.reconcile(provenProgress);
  assert.equal(deriveProgressiveReplayState(provenProgress).complete, true);
  assert.equal(operationState.retainedKey(RESULT_ID), null);

  const posts = calls.filter((call) => call.init.method === "POST");
  assert.equal(posts.length, 2);
  assert.deepEqual(posts.map((call) => new Headers(call.init.headers).get("Idempotency-Key")), [ACK_KEY, ACK_KEY]);
  assert.deepEqual(posts.map((call) => new Headers(call.init.headers).get("X-CSRF-TOKEN")), ["csrf-first", "csrf-retry"]);
  assert.deepEqual(posts.map((call) => call.init.credentials), ["include", "include"]);
  assert.deepEqual(posts.map((call) => call.init.body), [undefined, undefined]);
  assert.deepEqual([calls[0]?.init.credentials, calls[2]?.init.credentials], ["include", "include"]);
  assert.deepEqual(calls.slice(0, 5).map((call) => [call.init.method, call.url]), [
    ["GET", "/api/v1/auth/csrf"],
    ["POST", `/api/v1/audit-check-results/${RESULT_ID}/acknowledgement`],
    ["GET", "/api/v1/auth/csrf"],
    ["POST", `/api/v1/audit-check-results/${RESULT_ID}/acknowledgement`],
    ["GET", `/api/v1/contracts/${CONTRACT_ID}/audit-tasks/${TASK_ID}/review-progress`],
  ]);
});

test("new acknowledgement success returns only its mandatory refreshed progress projection", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });

  const heldProgress = deferred<Response>();
  const calls: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(input), init });
    if (calls.length === 1) {
      return envelope({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf-new" });
    }
    if (calls.length === 2) return envelope({ ...ACKNOWLEDGEMENT, id: "new-ack-response-only" });
    if (calls.length === 3) return heldProgress.promise;
    throw new Error(`unexpected fetch ${calls.length}`);
  }) as typeof fetch;

  const operationState = createFindingAcknowledgementOperationState({
    createIdempotencyKey: () => ACK_KEY,
  });
  let resolved = false;
  const coordinated = acknowledgeFindingAndReloadProgress({
    dataSource: new HttpContractDataSource(),
    contractId: CONTRACT_ID,
    taskId: TASK_ID,
    checkResultId: RESULT_ID,
    operationState,
  }).then((value) => {
    resolved = true;
    return value;
  });

  await waitForCallCount(calls, 3);
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(resolved, false);
  assert.equal(operationState.retainedKey(RESULT_ID), ACK_KEY);

  const durable = progress(ACKNOWLEDGEMENT);
  heldProgress.resolve(envelope(durable));
  assert.deepEqual(await coordinated, durable);
  assert.equal(operationState.retainedKey(RESULT_ID), null);
  assert.equal(calls[1]?.init.credentials, "include");
  assert.equal(new Headers(calls[1]?.init.headers).get("X-CSRF-TOKEN"), "csrf-new");
});

test("contract HTTP data preserves the existing numeric money contract", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  globalThis.fetch = (async () => envelope({
    id: CONTRACT_ID,
    contractNo: "CT-EXACT",
    contractName: "Exact contract",
    contractType: "PROCUREMENT",
    partyA: "Buyer",
    partyB: "Supplier",
    partyBCreditCode: null,
    contractAmount: 128000.5,
    amountInWords: "  人民币玖仟玖佰玖拾玖万亿元整  ",
    status: "READY_FOR_REVIEW",
    versionNo: 1,
    createdAt: "2026-08-25T00:00:00Z",
    updatedAt: "2026-08-25T00:00:00Z",
    lineItems: [{
      id: "line-exact",
      itemKey: "ITEM-1",
      itemName: "Exact item",
      quantity: 1,
      unitPrice: 1.235,
      declaredAmount: 123.5,
      sortNo: 1,
    }],
    awardNotice: {
      id: "award-exact",
      noticeNo: "AWARD-EXACT",
      projectName: "Exact project",
      winningBidder: "Supplier",
      winningBidderCreditCode: null,
      winningAmount: 128000.5,
      winningDate: "2026-08-25",
      sourceRef: null,
    },
  })) as typeof fetch;

  const contract = await new HttpContractDataSource().getContract(CONTRACT_ID);
  assert.equal(contract.contractAmount, 128000.5);
  assert.equal(contract.lineItems[0]?.unitPrice, 1.235);
  assert.equal(contract.lineItems[0]?.declaredAmount, 123.5);
  assert.equal(contract.awardNotice?.winningAmount, 128000.5);
  assert.equal(contract.amountInWords, "  人民币玖仟玖佰玖拾玖万亿元整  ");
});

test("already-acknowledged conflict reconciles only from refreshed durable progress", async () => {
  const conflict = new ApiError(409, "BUSINESS_ERROR", "review finding is already acknowledged");
  const provenState = createFindingAcknowledgementOperationState({
    createIdempotencyKey: () => ACK_KEY,
  });
  let provenGets = 0;
  const durable = progress(ACKNOWLEDGEMENT);
  const reconciled = await acknowledgeFindingAndReloadProgress({
    dataSource: {
      acknowledgeFinding: async () => { throw conflict; },
      getReviewProgress: async () => {
        provenGets += 1;
        return durable;
      },
    },
    contractId: CONTRACT_ID,
    taskId: TASK_ID,
    checkResultId: RESULT_ID,
    operationState: provenState,
  });
  assert.deepEqual(reconciled, durable);
  assert.equal(provenGets, 1);
  assert.equal(provenState.retainedKey(RESULT_ID), null);

  const unprovenState = createFindingAcknowledgementOperationState({
    createIdempotencyKey: () => ACK_KEY,
  });
  let unprovenGets = 0;
  await assert.rejects(acknowledgeFindingAndReloadProgress({
    dataSource: {
      acknowledgeFinding: async () => { throw conflict; },
      getReviewProgress: async () => {
        unprovenGets += 1;
        return progress(null);
      },
    },
    contractId: CONTRACT_ID,
    taskId: TASK_ID,
    checkResultId: RESULT_ID,
    operationState: unprovenState,
  }), (error: Error) => error === conflict);
  assert.equal(unprovenGets, 1);
  assert.equal(unprovenState.retainedKey(RESULT_ID), ACK_KEY);
});

test("only explicit terminal fingerprint or failed conflicts clear an unusable operation key", async () => {
  for (const message of [
    "idempotency key fingerprint conflict",
    "idempotent acknowledgement previously failed",
  ]) {
    const operationState = createFindingAcknowledgementOperationState({
      createIdempotencyKey: () => ACK_KEY,
    });
    const terminalDataSource = {
      acknowledgeFinding: async () => { throw new ApiError(409, "BUSINESS_ERROR", message); },
      getReviewProgress: async () => { throw new Error("terminal POST must not GET"); },
    };

    await assert.rejects(acknowledgeFindingAndReloadProgress({
      dataSource: terminalDataSource,
      contractId: CONTRACT_ID,
      taskId: TASK_ID,
      checkResultId: RESULT_ID,
      operationState,
    }), (error: Error) => error.message === message);
    assert.equal(operationState.retainedKey(RESULT_ID), null, message);
  }
});

test("in-progress, other conflicts, and 5xx outcomes retain the immutable operation key", async () => {
  for (const error of [
    new ApiError(409, "BUSINESS_ERROR", "idempotent acknowledgement is in progress"),
    new ApiError(409, "BUSINESS_ERROR", "review task state conflict"),
    new ApiError(503, "SERVER_ERROR", "service unavailable"),
  ]) {
    const operationState = createFindingAcknowledgementOperationState({
      createIdempotencyKey: () => ACK_KEY,
    });
    const uncertainDataSource = {
      acknowledgeFinding: async () => { throw error; },
      getReviewProgress: async () => { throw new Error("uncertain POST must not GET"); },
    };

    await assert.rejects(acknowledgeFindingAndReloadProgress({
      dataSource: uncertainDataSource,
      contractId: CONTRACT_ID,
      taskId: TASK_ID,
      checkResultId: RESULT_ID,
      operationState,
    }), (actual: Error) => actual === error);
    assert.equal(operationState.retainedKey(RESULT_ID), ACK_KEY, error.message);
  }
});

test("mock contract source implements the required durable progress and acknowledgement contract", async () => {
  const dataSource = new MockContractDataSource();
  assert.equal(typeof dataSource.getReviewProgress, "function");
  assert.equal(typeof dataSource.acknowledgeFinding, "function");

  const mockCase = contractMockCases[MOCK_RISK_CONTRACT_ID];
  assert.ok(mockCase);
  const before = await dataSource.getReviewProgress(
    MOCK_RISK_CONTRACT_ID,
    mockCase.reviewResult.taskId,
  );
  assert.equal(before.items[0]?.completedAt, null);
  const pending = deriveProgressiveReplayState(before).pendingAcknowledgementItem;
  assert.ok(pending?.checkResultId);

  const acknowledged = await dataSource.acknowledgeFinding(pending.checkResultId, {
    idempotencyKey: ACK_KEY,
  });
  assert.equal(acknowledged.checkResultId, pending.checkResultId);

  const after = await dataSource.getReviewProgress(
    MOCK_RISK_CONTRACT_ID,
    mockCase.reviewResult.taskId,
  );
  assert.equal(
    after.items.find((item) => item.checkResultId === pending.checkResultId)?.acknowledgement?.id,
    acknowledged.id,
  );
  assert.deepEqual(
    await dataSource.acknowledgeFinding(pending.checkResultId, { idempotencyKey: ACK_KEY }),
    acknowledged,
  );
});

test("mock contract source rejects PASS GREEN findings as acknowledgement-ineligible", async () => {
  const dataSource = new MockContractDataSource();
  const mockCase = contractMockCases[MOCK_PASS_CONTRACT_ID];
  assert.ok(mockCase);
  const progressProjection = await dataSource.getReviewProgress(
    MOCK_PASS_CONTRACT_ID,
    mockCase.reviewResult.taskId,
  );
  const passed = progressProjection.items.find((item) => (
    item.type === "FINDING" && item.status === "PASS" && item.risk === "GREEN"
  ));
  assert.ok(passed?.checkResultId);

  await assert.rejects(
    dataSource.acknowledgeFinding(passed.checkResultId, { idempotencyKey: ACK_KEY }),
    (error: Error & { status?: number }) => (
      error.status === 422
      && error.message === "review finding status and risk are not acknowledgement-eligible"
    ),
  );
});

test("mock contract source rejects a new key after acknowledgement while preserving original-key replay", async () => {
  const dataSource = new MockContractDataSource();
  const mockCase = contractMockCases[MOCK_RISK_CONTRACT_ID];
  assert.ok(mockCase);
  const before = await dataSource.getReviewProgress(
    MOCK_RISK_CONTRACT_ID,
    mockCase.reviewResult.taskId,
  );
  const pending = deriveProgressiveReplayState(before).pendingAcknowledgementItem;
  assert.ok(pending?.checkResultId);
  const original = await dataSource.acknowledgeFinding(pending.checkResultId, {
    idempotencyKey: ACK_KEY,
  });

  await assert.rejects(
    dataSource.acknowledgeFinding(pending.checkResultId, {
      idempotencyKey: "review-ack-ui-different-key",
    }),
    (error: Error & { status?: number }) => (
      error.status === 409 && error.message === "review finding is already acknowledged"
    ),
  );
  assert.deepEqual(
    await dataSource.acknowledgeFinding(pending.checkResultId, { idempotencyKey: ACK_KEY }),
    original,
  );
  const durable = await dataSource.getReviewProgress(
    MOCK_RISK_CONTRACT_ID,
    mockCase.reviewResult.taskId,
  );
  assert.equal(
    durable.items.find((item) => item.checkResultId === pending.checkResultId)
      ?.acknowledgement?.id,
    original.id,
  );
});

test("mock and shipped showcase projections retain finding facts and use only the operation clock for acknowledgements", async () => {
  const operationTime = new Date("2026-08-25T12:34:56.789Z");
  for (const dataSource of [
    new MockContractDataSource(() => operationTime),
    new ShowcaseContractDataSource(() => operationTime),
  ]) {
    const mockCase = contractMockCases[MOCK_RISK_CONTRACT_ID];
    assert.ok(mockCase);
    assert.equal(
      mockCase.reviewResult.findings.every((finding) => (
        typeof finding.createdAt === "string" && finding.createdAt.length > 0
      )),
      true,
      "every showcase result must own an explicit completion fact",
    );
    const projected = await dataSource.getReviewProgress(
      MOCK_RISK_CONTRACT_ID,
      mockCase.reviewResult.taskId,
    );
    for (const finding of mockCase.reviewResult.findings) {
      assert.equal(
        projected.items.find((item) => item.checkResultId === finding.id)?.completedAt,
        finding.createdAt,
      );
      assert.notEqual(finding.createdAt, mockCase.contract.updatedAt);
    }

    const pending = deriveProgressiveReplayState(projected).pendingAcknowledgementItem;
    assert.ok(pending?.checkResultId);
    const operationKey = `${ACK_KEY}-${dataSource.constructor.name}`;
    const acknowledgement = await dataSource.acknowledgeFinding(pending.checkResultId, {
      idempotencyKey: operationKey,
    });
    assert.equal(acknowledgement.acknowledgedAt, operationTime.toISOString());
    assert.deepEqual(
      await dataSource.acknowledgeFinding(pending.checkResultId, {
        idempotencyKey: operationKey,
      }),
      acknowledgement,
    );
    await assert.rejects(
      dataSource.acknowledgeFinding(pending.checkResultId, {
        idempotencyKey: `${operationKey}-different`,
      }),
      (error: Error & { status?: number }) => (
        error.status === 409 && error.message === "review finding is already acknowledged"
      ),
    );
  }
});
