import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import test from "node:test";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) return nextResolve(`${specifier}.ts`, context);
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    if (!url.endsWith("/src/api/client.ts")) return loaded;
    return { ...loaded, source: `import.meta.env ??= {};\n${loaded.source}` };
  },
});
const { useProjectWorkspace } = await import("./useProjectWorkspace.ts");
const { ApiError } = await import("../../api/client.ts");
const { createSessionStorageCandidateSupportOperationPersistence, loadCandidateSupportOperation,
  loadOrCreateCandidateSupportOperation } = await import("./candidateSupportAuthorityOperation.ts");
hooks.deregister();

class MemoryStorage {
  values = new Map<string, string>();
  get length() { return this.values.size; }
  clear() { this.values.clear(); }
  getItem(key: string) { return this.values.get(key) ?? null; }
  key(index: number) { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string) { this.values.delete(key); }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

// Real React hooks; the minimal DOM only supplies the browser host boundary.
class Element {
  nodeType = 1; namespaceURI = "http://www.w3.org/1999/xhtml"; style = {}; children = [];
  parentNode = null; textContent = "";
  ownerDocument; tagName: string;
  constructor(ownerDocument, tagName: string) { this.ownerDocument = ownerDocument; this.tagName = tagName.toUpperCase(); }
  get nodeName() { return this.tagName; }
  addEventListener() {} removeEventListener() {} setAttribute() {} removeAttribute() {}
  appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
  insertBefore(child, before) { child.parentNode = this; const index = this.children.indexOf(before);
    if (index < 0) this.children.push(child); else this.children.splice(index, 0, child); return child; }
  removeChild(child) { this.children.splice(this.children.indexOf(child), 1); child.parentNode = null; return child; }
}
class Document {
  nodeType = 9; activeElement = null; defaultView;
  documentElement = new Element(this, "html"); body = new Element(this, "body");
  addEventListener() {} removeEventListener() {}
  createElement(tag: string) { return new Element(this, tag); }
}
const React = await import("react");
const { createRoot } = await import("react-dom/client");
const scope = ["auditor-1", "project-1", "candidate-1"] as const;
const file = { id: "award-a", projectId: scope[1], originalFilename: "award.pdf", category: "OTHER",
  ingestionStatus: "AVAILABLE", parseStatus: "PARSED", effectiveRole: "AWARD_NOTICE",
  effectiveRoleSource: "HUMAN_CONFIRMATION", identityDecisionStatus: "CONFIRMED",
  effectiveIdentityStatus: "SAME_PROJECT", identityCurrent: true, excludedFromProjectUse: false };
function detail(version = 4, ids: string[] = []) {
  return { candidate: { id: scope[2], projectId: scope[1], sourceContractFileId: "contract", versionNo: version,
      promotionState: "DRAFT", supportAuthorityVersionNo: ids.length ? 2 : null,
      awardAmount: 98, awardSupplier: "Supplier", lineItems: [] },
    sourceFile: { id: "contract" }, fieldStates: { AWARD_AMOUNT: "CONFIRMED" }, evidence: [], lineItemFieldStates: {},
    authorityMode: ids.length ? "EXPLICIT_SUPPORT_AUTHORITY" : "PRIMARY_ONLY_COMPAT",
    supportAuthorityVersionNo: ids.length ? 2 : null,
    supportAuthorities: ids.map((sourceFileId) => ({ sourceFileId, authorityVersion: 2,
      originalFilename: `${sourceFileId}.pdf`, effectiveRole: "AWARD_NOTICE", current: true, eligibleAwardFacts: [] })) };
}
const classified = (classification: string) => new ApiError(409, "BUSINESS_ERROR", "private backend details", { classification });

async function workspace(t, initial = detail()) {
  const original = { window: globalThis.window, document: globalThis.document,
    IS_REACT_ACT_ENVIRONMENT: globalThis.IS_REACT_ACT_ENVIRONMENT };
  const storage = new MemoryStorage(), document = new Document();
  const window = { document, sessionStorage: storage, HTMLElement: Element, HTMLIFrameElement: class {},
    addEventListener() {}, removeEventListener() {}, location: { protocol: "http:" } };
  document.defaultView = window;
  Object.assign(globalThis, { window, document, IS_REACT_ACT_ENVIRONMENT: true });
  const persistence = createSessionStorageCandidateSupportOperationPersistence(storage);
  let durable = initial, state, posts = [], projectGets = 0, projectError = null;
  let post = async (payload) => {
    durable = detail(payload.expectedCandidateVersion + 1, payload.supportFileIds);
    durable.supportAuthorityVersionNo = 2;
    durable.candidate.supportAuthorityVersionNo = 2;
    return { candidateId: scope[2], candidateVersion: durable.candidate.versionNo,
      supportAuthorityVersion: 2, authorityMode: durable.authorityMode, replayed: false,
      supportAssociations: durable.supportAuthorities.map((row) => ({ ...row, projectId: scope[1], candidateId: scope[2] })) };
  };
  const source = { getProject: async () => { projectGets++; if (projectError) throw projectError;
      return { project: { id: scope[1] },
      files: [file, { ...file, id: "award-b" }], candidates: [durable.candidate], associatedContracts: [] }; },
    getCandidate: async () => durable, getLatestConfirmation: async () => { throw new ApiError(404, "BUSINESS_ERROR", "absent"); },
    replaceCandidateSupportAuthority: async (p, c, payload, options) => {
      posts.push({ p, c, payload, key: options.idempotencyKey }); return post(payload); } };
  function Harness() { state = useProjectWorkspace(scope[1], source, scope[0]); return null; }
  const root = createRoot(document.createElement("div"));
  await React.act(async () => root.render(React.createElement(Harness)));
  t.after(async () => { await React.act(async () => root.unmount()); Object.assign(globalThis, original); });
  return { get state() { return state; }, posts, storage, persistence, get projectGets() { return projectGets; },
    setDurable: (next) => { durable = next; }, setPost: (next) => { post = next; },
    setProjectError: (error) => { projectError = error; },
    pending: () => loadCandidateSupportOperation(persistence, ...scope),
    save: async (ids = ["award-a"]) => { await React.act(async () => state.saveCandidateSupport(ids)); },
    reload: async () => { await React.act(async () => state.reload(scope[2])); },
    reconcile: async () => { await React.act(async () => state.reconcileCandidateSupport()); } };
}

test("panel exposes explicit full-set actions, durable reset and retained-operation reconciliation", () => {
  const url = new URL("./components/CandidateSupportAuthorityPanel.tsx", import.meta.url);
  assert.ok(existsSync(url), "Task 17 CandidateSupportAuthorityPanel is missing");
  const source = readFileSync(url, "utf8");
  for (const label of ["关联中标材料", "中标金额", "中标供应商", "保存关联", "清空选择", "重新加载", "核对上次关联结果"])
    assert.ok(source.includes(label), label);
  assert.match(source, /useEffect/);
  assert.match(source, /assignedIds/);
  assert.match(source, /type="checkbox"/);
  assert.match(source, /onSave\(view.selectedIds\)/);
  assert.match(source, /onReconcile/);
  assert.doesNotMatch(source, /\.category|parseResultId|roleDecisionId|identityDecisionId|Fingerprint/);
});

test("save posts canonical full set/current version once and reloads durable candidate", async (t) => {
  const w = await workspace(t);
  await w.save(["award-b", "award-a", "award-b"]);
  assert.equal(w.posts.length, 1);
  assert.deepEqual(w.posts[0].payload, { expectedCandidateVersion: 4, supportFileIds: ["award-a", "award-b"] });
  assert.equal(w.posts[0].p, scope[1]); assert.equal(w.posts[0].c, scope[2]);
  assert.equal(w.pending(), null);
  assert.equal(w.state.candidate.candidate.versionNo, 5);
  assert.deepEqual(w.state.candidate.supportAuthorities.map((a) => a.sourceFileId), ["award-a", "award-b"]);
  assert.equal(w.projectGets, 2);
});

test("single-flight guards a double save and unchanged/promoted/foreign selections send no POST", async (t) => {
  const w = await workspace(t);
  await w.save([]); await w.save(["foreign"]);
  assert.equal(w.posts.length, 0);
  let release;
  w.setPost(() => new Promise((_, reject) => { release = () => reject(classified("OUTCOME_UNCERTAIN")); }));
  await React.act(async () => {
    const first = w.state.saveCandidateSupport(["award-a"]);
    await w.state.saveCandidateSupport(["award-b"]); release(); await first;
  });
  assert.equal(w.posts.length, 1);
  w.setDurable({ ...detail(), candidate: { ...detail().candidate, promotionState: "PROMOTED" } });
  await w.reload(); await w.save(["award-b"]);
  assert.equal(w.posts.length, 1);
});

for (const [label, error] of [
  ["uncertain", classified("OUTCOME_UNCERTAIN")], ["integrity", classified("INTEGRITY_CONFLICT")],
  ["old backend 409", new ApiError(409, "BUSINESS_ERROR", "RESULT_SUPERSEDED private")],
  ["network", new ApiError(0, "NETWORK_ERROR", "private network")], ["timeout", new Error("private timeout")],
  ["5xx", new ApiError(503, "SERVER_ERROR", "private server")], ["unproven supersession", classified("RESULT_SUPERSEDED")],
]) test(`${label} retains exact operation without automatic POST or unsafe error text`, async (t) => {
  const w = await workspace(t); w.setPost(async () => { throw error; });
  await w.save();
  const retained = w.pending(); assert.ok(retained);
  assert.equal(w.posts.length, 1); assert.equal(w.state.supportPending, true);
  assert.ok(w.state.supportError); assert.doesNotMatch(w.state.supportError, /private|RESULT_SUPERSEDED/);
  await w.reload(); assert.deepEqual(w.pending(), retained); assert.equal(w.posts.length, 1);
  await w.save(["award-b"]); assert.equal(w.posts.length, 1);
  await w.reconcile(); assert.equal(w.posts.length, 2);
  assert.equal(w.posts[1].key, w.posts[0].key);
  assert.deepEqual(w.posts[1].payload, w.posts[0].payload);
});

test("deterministic rejection allows only a later explicit save with a new key after reload", async (t) => {
  const w = await workspace(t);
  w.setPost(async () => { w.setDurable(detail(7)); throw classified("REQUEST_REJECTED"); });
  await w.save();
  assert.equal(w.posts.length, 1); assert.equal(w.pending(), null);
  assert.equal(w.state.candidate.candidate.versionNo, 7);
  assert.match(w.state.supportError, /未保存|拒绝/);
  await w.save();
  assert.equal(w.posts.length, 2); assert.notEqual(w.posts[1].key, w.posts[0].key);
  assert.equal(w.posts[1].payload.expectedCandidateVersion, 7);
});

test("proven supersession reloads new full set and never reapplies old selection", async (t) => {
  const w = await workspace(t);
  w.setPost(async () => { w.setDurable(detail(8, ["award-b"])); throw classified("RESULT_SUPERSEDED"); });
  await w.save();
  assert.equal(w.posts.length, 1); assert.equal(w.pending(), null);
  assert.deepEqual(w.state.candidate.supportAuthorities.map((a) => a.sourceFileId), ["award-b"]);
  assert.match(w.state.supportError, /变化.*核对/);
});

for (const classification of ["REQUEST_REJECTED", "RESULT_SUPERSEDED"]) {
  test(`${classification} never clears a concurrently stored newer operation`, async (t) => {
    const w = await workspace(t);
    w.setPost(async () => {
      w.storage.clear();
      loadOrCreateCandidateSupportOperation(w.persistence, ...scope, 8, ["award-b"]);
      w.setDurable(detail(8, ["award-b"]));
      throw classified(classification);
    });
    await w.save();
    assert.equal(w.posts.length, 1);
    assert.deepEqual(w.pending().supportFileIds, ["award-b"]);
    assert.equal(w.pending().expectedCandidateVersion, 8);
    assert.equal(w.state.supportPending, true);
    assert.ok(w.state.supportError);
  });
}

test("refresh reconstructs server assignment while retaining unresolved key for explicit reconciliation", async (t) => {
  const w = await workspace(t);
  const old = loadOrCreateCandidateSupportOperation(w.persistence, ...scope, 4, ["award-a"]);
  w.setDurable(detail(5, ["award-a"]));
  await w.reload();
  assert.equal(w.state.supportPending, true);
  assert.deepEqual(w.state.candidate.supportAuthorities.map((a) => a.sourceFileId), ["award-a"]);
  w.setPost(async () => { throw classified("OUTCOME_UNCERTAIN"); });
  await w.reconcile();
  assert.equal(w.posts.length, 1);
  assert.equal(w.posts[0].key, old.idempotencyKey);
  assert.equal(w.posts[0].payload.expectedCandidateVersion, 4);
});

test("clear-all saves an explicit empty full set once and reloads primary-only server state", async (t) => {
  const w = await workspace(t, detail(5, ["award-a", "award-b"]));
  await w.save([]);
  assert.deepEqual(w.posts.map((p) => p.payload), [{ expectedCandidateVersion: 5, supportFileIds: [] }]);
  assert.equal(w.state.candidate.authorityMode, "PRIMARY_ONLY_COMPAT");
  assert.deepEqual(w.state.candidate.supportAuthorities, []);
  assert.equal(w.state.candidate.candidate.versionNo, 6);
  assert.equal(w.pending(), null);
  await w.save([]);
  assert.equal(w.posts.length, 1);
});

test("a rejected request whose refresh fails cannot allocate another key until explicit successful reload", async (t) => {
  const w = await workspace(t);
  w.setPost(async () => { throw classified("REQUEST_REJECTED"); });
  w.setProjectError(new Error("private read failure"));
  await w.save();
  assert.equal(w.pending(), null);
  await w.save();
  assert.equal(w.posts.length, 1);
  assert.equal(w.storage.length, 0);
  assert.match(w.state.supportError, /重新加载/);
  w.setProjectError(null); w.setDurable(detail(7)); await w.reload(); await w.save();
  assert.equal(w.posts.length, 2);
  assert.equal(w.posts[1].payload.expectedCandidateVersion, 7);
  assert.notEqual(w.posts[1].key, w.posts[0].key);
});

test("corrupt persisted operation stops saving and remains untouched after reload", async (t) => {
  const w = await workspace(t);
  loadOrCreateCandidateSupportOperation(w.persistence, ...scope, 4, ["award-a"]);
  w.storage.setItem(w.storage.key(0), "invalid stored operation");
  await w.reload(); await w.save(); await w.reconcile();
  assert.equal(w.posts.length, 0);
  assert.equal(w.storage.getItem(w.storage.key(0)), "invalid stored operation");
  assert.equal(w.state.supportPending, true);
  assert.ok(w.state.supportError);
  assert.doesNotMatch(w.state.supportError, /invalid stored operation/);
});

test("supersession never claims project refresh succeeded when the project GET failed", async (t) => {
  const w = await workspace(t);
  w.setPost(async () => { w.setDurable(detail(8, ["award-b"])); throw classified("RESULT_SUPERSEDED"); });
  w.setProjectError(new Error("project unavailable"));
  await w.save();
  assert.equal(w.posts.length, 1);
  assert.equal(w.pending(), null);
  assert.match(w.state.supportError, /变化.*核对/);
  assert.doesNotMatch(w.state.supportError, /已重新加载/);
  await w.save(["award-b"]);
  assert.equal(w.posts.length, 1);
});
