import assert from "node:assert/strict";
import { createRequire, registerHooks } from "node:module";
import test from "node:test";
import { build } from "esbuild";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)
      ? `${specifier}.ts` : specifier, context);
  },
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    return url.endsWith("/src/api/client.ts")
      ? { ...loaded, source: `import.meta.env ??= {};\n${loaded.source}` } : loaded;
  },
});
const { useProjectWorkspace } = await import("./useProjectWorkspace.ts");
hooks.deregister();
const React = await import("react");
const { createRoot } = await import("react-dom/client");
const require = createRequire(import.meta.url);
const bundle = await build({ bundle: true, format: "cjs", platform: "node", jsx: "automatic",
  write: false, packages: "external", stdin: { loader: "tsx", resolveDir: process.cwd(),
    contents: `export { ProjectMetadataConfirmationPanel } from "./src/features/project/components/ProjectMetadataConfirmationPanel.tsx";
      export { CandidateSupportAuthorityPanel } from "./src/features/project/components/CandidateSupportAuthorityPanel.tsx";` } });
const compiled = { exports: {} };
new Function("require", "module", "exports", bundle.outputFiles[0].text)(require, compiled, compiled.exports);
const { ProjectMetadataConfirmationPanel, CandidateSupportAuthorityPanel } = compiled.exports;

// React is real; this host boundary exposes rendered input state and React's own event props.
class Element {
  nodeType = 1; namespaceURI = "http://www.w3.org/1999/xhtml"; style = {}; children = [];
  parentNode = null; textContent = ""; value = ""; checked = false; attributes = new Map();
  ownerDocument; tagName: string;
  constructor(ownerDocument, tagName: string) { this.ownerDocument = ownerDocument; this.tagName = tagName.toUpperCase(); }
  get nodeName() { return this.tagName; }
  addEventListener() {} removeEventListener() {}
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
  insertBefore(child, before) { child.parentNode = this; const index = this.children.indexOf(before);
    if (index < 0) this.children.push(child); else this.children.splice(index, 0, child); return child; }
  removeChild(child) { this.children.splice(this.children.indexOf(child), 1); child.parentNode = null; return child; }
}
class Document {
  nodeType = 9; activeElement = null; defaultView;
  documentElement = new Element(this, "html"); body = new Element(this, "body");
  addEventListener() {} removeEventListener() {}
  createElement(tag) { return new Element(this, tag); }
  createTextNode(text) { return { nodeType: 3, textContent: text, parentNode: null }; }
}
class MemoryStorage {
  values = new Map(); get length() { return this.values.size; }
  key(index) { return [...this.values.keys()][index] ?? null; }
  getItem(key) { return this.values.get(key) ?? null; }
  setItem(key, value) { this.values.set(key, value); }
  removeItem(key) { this.values.delete(key); }
}
function inputs(node): Element[] {
  return [...(node.tagName === "INPUT" ? [node] : []), ...(node.children ?? []).flatMap(inputs)];
}
function props(node) {
  return node[Object.keys(node).find((key) => key.startsWith("__reactProps$"))!];
}
function authoritative(version = 1) {
  const candidate = { id: "candidate-a", projectId: "project-a", sourceContractFileId: "body",
    versionNo: version, promotionState: "DRAFT", lineItems: [], awardAmount: null, awardSupplier: null };
  const files = [{ id: "award", projectId: "project-a", originalFilename: "synthetic-award.pdf",
    category: "OTHER", ingestionStatus: "AVAILABLE", parseStatus: "PARSED", effectiveRole: "AWARD_NOTICE",
    effectiveRoleSource: "HUMAN_CONFIRMATION", identityDecisionStatus: "CONFIRMED",
    effectiveIdentityStatus: "SAME_PROJECT", identityCurrent: true, excludedFromProjectUse: false }];
  return { detail: { project: { id: "project-a", versionNo: version, name: `Durable ${version}`,
    projectNo: `P-${version}`, category: "GOODS" }, files, candidates: [candidate], extractionResults: [],
    associatedContracts: [] }, candidate: { candidate, sourceFile: { id: "body" }, fieldStates: {},
      evidence: [], lineItemFieldStates: {}, supportAuthorities: [] } };
}
async function mount(t) {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const original = { window: globalThis.window, document: globalThis.document,
    IS_REACT_ACT_ENVIRONMENT: globalThis.IS_REACT_ACT_ENVIRONMENT };
  const document = new Document(), window = { document, sessionStorage: new MemoryStorage(),
    HTMLElement: Element, HTMLIFrameElement: class {}, addEventListener() {}, removeEventListener() {},
    location: { protocol: "http:" } };
  document.defaultView = window;
  Object.assign(globalThis, { document, window, IS_REACT_ACT_ENVIRONMENT: true });
  let durable = authoritative(), state, release, delayed = false, candidateDelayed = false, candidateFailure = false, projectReads = 0;
  const source = { getProject: async () => { projectReads++; if (delayed) await new Promise((done) => { release = done; });
      return structuredClone(durable.detail); },
    getCandidate: async () => { if (candidateDelayed) await new Promise((done) => { release = done; });
      if (candidateFailure) throw new Error("private read failure"); return structuredClone(durable.candidate); },
    getLatestConfirmation: async () => { throw { status: 404 }; },
    listFileJobs: async () => ({ projectId: "project-a", workerEnabled: true, total: 1,
      completed: 0, failed: 0, unknown: 0, items: [{ jobId: "job-a", projectId: "project-a",
        fileId: "scan-a", status: "QUEUED", attemptNo: 0, reasonCode: null, parseResultId: null }] }) };
  function Harness() {
    state = useProjectWorkspace("project-a", source, "actor-a");
    if (!state.detail) return null;
    return React.createElement("div", null,
      React.createElement(ProjectMetadataConfirmationPanel, { detail: state.detail,
        reloadVersion: state.draftReloadVersion, loading: state.loading, onConfirm: async () => null }),
      state.candidate && React.createElement(CandidateSupportAuthorityPanel, { candidate: state.candidate,
        files: state.detail.files, reloadVersion: state.draftReloadVersion,
        loading: state.loading || state.candidateLoadState === "ERROR",
        pending: false, onSave: async () => null, onReload: state.reload, onReconcile: async () => null }));
  }
  const container = document.createElement("div"), root = createRoot(container);
  await React.act(async () => root.render(React.createElement(Harness)));
  t.after(async () => { await React.act(async () => root.unmount()); Object.assign(globalThis, original); });
  return { get state() { return state; }, get inputs() { return inputs(container); },
    get projectReads() { return projectReads; }, setVersion: (version) => { durable = authoritative(version); },
    delayNextRead: () => { delayed = true; }, delayCandidateRead: () => { candidateDelayed = true; },
    failCandidateRead: (fail) => { candidateFailure = fail; }, release: async () => { delayed = false; candidateDelayed = false;
      await React.act(async () => release()); },
    edit: async () => React.act(async () => {
      for (const [index, value] of ["Unsaved name", "UNSAVED-NO", "Unsaved category"].entries())
        props(inputs(container)[index]).onChange({ target: { value } });
      props(inputs(container)[3]).onChange({ target: { checked: true } });
    }),
    tick: async () => React.act(async () => t.mock.timers.tick(2001)),
    reload: async () => React.act(async () => state.reload()),
  };
}

test("mounted metadata and support drafts survive several real queue polls without temporary loading locks", async (t) => {
  const w = await mount(t);
  await w.edit();
  for (let index = 0; index < 3; index++) {
    await w.tick();
    assert.deepEqual(w.inputs.slice(0, 3).map((input) => input.value),
      ["Unsaved name", "UNSAVED-NO", "Unsaved category"]);
    assert.equal(w.inputs[3].checked, true);
    assert.equal(props(w.inputs[3]).disabled, false);
  }
  assert.equal(w.projectReads, 4, "real GET facts must continue refreshing");
  w.delayNextRead(); await w.tick();
  assert.equal(w.state.loading, false, "a slow background GET must not lock manual editing");
  assert.equal(w.state.candidateLoadState, "READY");
  assert.equal(props(w.inputs[3]).disabled, false);
  await w.release();
  assert.equal(w.inputs[0].value, "Unsaved name");
  assert.equal(w.inputs[3].checked, true);
});

test("explicit reload discards unsaved drafts even when authoritative versions are unchanged", async (t) => {
  const w = await mount(t); await w.edit(); await w.reload();
  assert.deepEqual(w.inputs.slice(0, 3).map((input) => input.value), ["Durable 1", "P-1", "GOODS"]);
  assert.equal(w.inputs[3].checked, false);
});

test("mounted unsaved award checkbox selection survives background queue reads", async (t) => {
  const w = await mount(t); await w.edit();
  for (let index = 0; index < 3; index++) { await w.tick(); assert.equal(w.inputs[3].checked, true); }
});

test("a pending background read does not set workspace or candidate loading", async (t) => {
  const w = await mount(t); w.delayNextRead(); await w.tick();
  try { assert.equal(w.state.loading, false); assert.equal(w.state.candidateLoadState, "READY"); }
  finally { await w.release(); }
});

test("background authoritative version changes reset drafts to the new durable facts", async (t) => {
  const w = await mount(t); await w.edit(); w.setVersion(2); await w.tick();
  assert.deepEqual(w.inputs.slice(0, 3).map((input) => input.value), ["Durable 2", "P-2", "GOODS"]);
  assert.equal(w.inputs[3].checked, false);
});

test("slow background candidate reads are serial rather than repeatedly invalidated by another timer", async (t) => {
  const w = await mount(t); w.delayCandidateRead(); await w.tick();
  try { await w.tick(); await w.tick(); assert.equal(w.projectReads, 2); }
  finally { await w.release(); }
  await w.tick(); assert.equal(w.projectReads, 3);
});

test("a failed background candidate GET retains edits but truthfully locks support save until a successful read", async (t) => {
  const w = await mount(t); await w.edit(); w.failCandidateRead(true); await w.tick();
  assert.equal(w.state.candidateLoadState, "ERROR");
  assert.equal(w.inputs[0].value, "Unsaved name"); assert.equal(w.inputs[3].checked, true);
  assert.equal(props(w.inputs[3]).disabled, true);
  assert.doesNotMatch(w.state.candidateError, /private/);
  w.failCandidateRead(false); await w.tick();
  assert.equal(w.state.candidateLoadState, "READY"); assert.equal(w.state.candidateError, null);
  assert.equal(w.inputs[3].checked, true); assert.equal(props(w.inputs[3]).disabled, false);
});
