import assert from "node:assert/strict";
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

class Element {
  nodeType = 1; namespaceURI = "http://www.w3.org/1999/xhtml"; style = {}; children = [];
  parentNode = null; textContent = ""; ownerDocument; tagName: string;
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

function candidateSummary(projectId: string) {
  return { id: `candidate-${projectId}`, projectId, sourceContractFileId: `file-${projectId}`,
    versionNo: 1, promotionState: "DRAFT", lineItems: [] };
}

function projectDetail(projectId: string, withCandidate: boolean) {
  const summary = candidateSummary(projectId);
  return { project: { id: projectId }, files: [], candidates: withCandidate ? [summary] : [],
    associatedContracts: [] };
}

function recoveryFile(projectId: string) {
  return { id: `file-${projectId}`, projectId, progression: {
    stage: "PARSED", blocker: "ROLE_ASSESSMENT_PENDING",
    availableActions: ["RESUME_ROLE_ASSESSMENT"], candidateId: null, contractId: null,
    recoveryInputFingerprint: "a".repeat(64),
  } };
}

function recoveryProjectDetail(projectId: string) {
  return { ...projectDetail(projectId, false), files: [recoveryFile(projectId)] };
}

function candidateDetail(projectId: string) {
  return { candidate: candidateSummary(projectId), sourceFile: { id: `file-${projectId}` },
    evidence: [], fieldStates: {}, lineItemFieldStates: {}, supportAuthorities: [] };
}

async function renderWorkspace(t, initialProjectId: string, source) {
  const original = { window: globalThis.window, document: globalThis.document,
    IS_REACT_ACT_ENVIRONMENT: globalThis.IS_REACT_ACT_ENVIRONMENT };
  const document = new Document();
  const window = { document, sessionStorage: new MemoryStorage(), HTMLElement: Element,
    HTMLIFrameElement: class {}, addEventListener() {}, removeEventListener() {},
    location: { protocol: "http:" } };
  document.defaultView = window;
  Object.assign(globalThis, { window, document, IS_REACT_ACT_ENVIRONMENT: true });
  let projectId = initialProjectId, state;
  function Harness() { state = useProjectWorkspace(projectId, source, "actor-1"); return null; }
  const root = createRoot(document.createElement("div"));
  await React.act(async () => root.render(React.createElement(Harness)));
  t.after(async () => { await React.act(async () => root.unmount()); Object.assign(globalThis, original); });
  return {
    get state() { return state; },
    renderProject: async (nextProjectId: string) => {
      projectId = nextProjectId;
      await React.act(async () => root.render(React.createElement(Harness)));
    },
  };
}

function source(overrides = {}) {
  return {
    getProject: async (projectId: string) => projectDetail(projectId, true),
    getCandidate: async (projectId: string) => candidateDetail(projectId),
    getLatestConfirmation: async () => { throw { status: 404 }; },
    ...overrides,
  };
}

test("a project with no candidate reports an explicit absent state", async (t) => {
  const workspace = await renderWorkspace(t, "project-empty", source({
    getProject: async (projectId: string) => projectDetail(projectId, false),
  }));
  assert.equal(workspace.state.detail.project.id, "project-empty");
  assert.equal(workspace.state.candidate, null);
  assert.equal(workspace.state.candidateLoadState, "ABSENT");
  assert.equal(workspace.state.candidateError, null);
  assert.equal(workspace.state.error, null);
});

test("candidate read failure preserves project detail and reports the failed stage", async (t) => {
  const workspace = await renderWorkspace(t, "project-error", source({
    getCandidate: async () => { throw new Error("private candidate endpoint detail"); },
  }));
  assert.equal(workspace.state.detail.project.id, "project-error");
  assert.equal(workspace.state.candidate, null);
  assert.equal(workspace.state.candidateLoadState, "ERROR");
  assert.match(workspace.state.candidateError, /合同信息暂时无法读取/);
  assert.equal(workspace.state.error, null);
});

test("a late candidate response cannot overwrite a newly selected project", async (t) => {
  const pending = new Map<string, { promise: Promise<unknown>; resolve: (value: unknown) => void }>();
  for (const projectId of ["project-old", "project-new"]) {
    let resolve!: (value: unknown) => void;
    const promise = new Promise((done) => { resolve = done; });
    pending.set(projectId, { promise, resolve });
  }
  const workspace = await renderWorkspace(t, "project-old", source({
    getCandidate: async (projectId: string) => pending.get(projectId)!.promise,
  }));
  await workspace.renderProject("project-new");
  await React.act(async () => pending.get("project-new")!.resolve(candidateDetail("project-new")));
  assert.equal(workspace.state.detail.project.id, "project-new");
  assert.equal(workspace.state.candidate.candidate.projectId, "project-new");
  await React.act(async () => pending.get("project-old")!.resolve(candidateDetail("project-old")));
  assert.equal(workspace.state.detail.project.id, "project-new");
  assert.equal(workspace.state.candidate.candidate.projectId, "project-new");
});

test("a failed new-project read never exposes the previous project's detail", async (t) => {
  const workspace = await renderWorkspace(t, "project-old", source({
    getProject: async (projectId: string) => {
      if (projectId === "project-new") throw new Error("new project unavailable");
      return projectDetail(projectId, true);
    },
  }));
  assert.equal(workspace.state.detail.project.id, "project-old");
  await workspace.renderProject("project-new");
  assert.equal(workspace.state.detail, null);
  assert.equal(workspace.state.candidate, null);
  assert.match(workspace.state.error, /项目详情暂时无法读取/);
});

test("GET does not resume automatically and a double click has one in-flight command", async (t) => {
  let resumeCalls = 0, release!: (value: unknown) => void;
  const resumed = new Promise((resolve) => { release = resolve; });
  const workspace = await renderWorkspace(t, "project-recovery", source({
    getProject: async (projectId: string) => recoveryProjectDetail(projectId),
    resumeProgression: async () => { resumeCalls += 1; return resumed; },
  }));
  assert.equal(resumeCalls, 0, "mount and GET must not create recovery facts");
  const currentFile = workspace.state.detail.files[0];
  await React.act(async () => {
    const first = workspace.state.resumeProgression(currentFile);
    const second = workspace.state.resumeProgression(currentFile);
    release(currentFile);
    await Promise.all([first, second]);
  });
  assert.equal(resumeCalls, 1);
});

test("successful recovery releases the busy state after its durable reload", async (t) => {
  const workspace = await renderWorkspace(t, "project-recovery", source({
    getProject: async (projectId: string) => recoveryProjectDetail(projectId),
    resumeProgression: async (_projectId: string, _fileId: string) => recoveryFile("project-recovery"),
  }));
  const currentFile = workspace.state.detail.files[0];
  await React.act(async () => { await workspace.state.resumeProgression(currentFile); });
  assert.equal(workspace.state.busy, false);
});

test("late recovery response from an old project cannot reload or overwrite the new project", async (t) => {
  let release!: (value: unknown) => void;
  const resumed = new Promise((resolve) => { release = resolve; });
  const gets: string[] = [];
  const workspace = await renderWorkspace(t, "project-old", source({
    getProject: async (projectId: string) => { gets.push(projectId); return recoveryProjectDetail(projectId); },
    resumeProgression: async () => resumed,
  }));
  const oldFile = workspace.state.detail.files[0];
  let oldRequest!: Promise<unknown>;
  await React.act(async () => { oldRequest = workspace.state.resumeProgression(oldFile); });
  await workspace.renderProject("project-new");
  await React.act(async () => { release(oldFile); await oldRequest; });
  assert.equal(workspace.state.detail.project.id, "project-new");
  assert.deepEqual(gets, ["project-old", "project-new"]);
  assert.equal(workspace.state.busy, false);
});

test("refresh reopens each project's real processing queue and UNKNOWN has no ordinary retry",async(t)=>{
  const reads:string[]=[];let retries=0;
  const workspace=await renderWorkspace(t,"project-a",source({
    getProject:async(p:string)=>projectDetail(p,false),
    listFileJobs:async(p:string)=>{reads.push(p);return {projectId:p,workerEnabled:true,items:[{
      jobId:`job-${p}`,projectId:p,fileId:`file-${p}`,status:"UNKNOWN",attemptNo:1,
      reasonCode:"STORAGE_UNKNOWN",parseResultId:null,createdAt:"now",updatedAt:"now"}],total:1,completed:0,failed:0,unknown:1};},
    retryFileProcessing:async()=>{retries++;},
  }));
  assert.equal(workspace.state.processingJobs.projectId,"project-a");
  assert.equal(workspace.state.processingJobs.unknown,1);
  await React.act(async()=>workspace.state.retryProcessing("file-project-a"));
  assert.equal(retries,0);
  await workspace.renderProject("project-b");
  assert.equal(workspace.state.processingJobs.projectId,"project-b");
  assert.deepEqual(reads,["project-a","project-b"]);
});

test("queue polling stops at proven completion and never invents percentage progress",async(t)=>{
  t.mock.timers.enable({apis:["setTimeout"]});
  let calls=0;
  const workspace=await renderWorkspace(t,"project-q",source({getProject:async(p:string)=>projectDetail(p,false),
    listFileJobs:async(p:string)=>{calls++;const done=calls>1;return {projectId:p,workerEnabled:true,
      items:[{jobId:"j",projectId:p,fileId:"f",status:done?"SUCCEEDED":"QUEUED",attemptNo:0,
        reasonCode:null,parseResultId:done?"parse":null,createdAt:"now",updatedAt:"now"}],total:1,completed:done?1:0,failed:0,unknown:0};}}));
  assert.equal(workspace.state.processingJobs.completed,0);
  await React.act(async()=>t.mock.timers.tick(2500));
  assert.equal(workspace.state.processingJobs.completed,1);
  await React.act(async()=>t.mock.timers.tick(10000));
  assert.equal(calls,2);
});
