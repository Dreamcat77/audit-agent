import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire, registerHooks } from "node:module";
import test from "node:test";
import { build } from "esbuild";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) return nextResolve(`${specifier}.ts`, context);
    return nextResolve(specifier, context);
  },
});

const { intakeDraftRow, loadProjectCollections, preparationDraftRow, projectHeader, projectIntakeError,
  projectLandingIntake, projectListRow, projectManagementPresentation, projectManagementSections } =
  await import("./projectListViewModel.ts").finally(() => hooks.deregister());

const blank = {
  id: "project-1", name: null, projectNo: null, category: null, metadataConfirmed: false, versionNo: 0,
  createdAt: "2026-08-26T00:00:00Z", updatedAt: "2026-08-26T00:00:00Z",
};

test("blank Project copy stays factual and offers only the approved primary action", () => {
  assert.deepEqual(projectHeader(blank), {
    projectId: "project-1",
    name: "项目名称：待识别",
    projectNo: "项目编号：待识别",
    category: "项目类目：待提供",
    primaryAction: "上传项目资料",
  });
  const serialized = JSON.stringify(projectHeader(blank));
  for (const fake of ["手机号", "黑名单", "围串标", "供应商风险", "重点材料", "必传", "删除"]) {
    assert.equal(serialized.includes(fake), false);
  }
});

test("project list rows expose the durable Project identity and actual nullable metadata", () => {
  assert.deepEqual(projectListRow(blank), {
    id: "project-1", name: "待识别", projectNo: "待识别", category: "待提供",
    updatedAt: "2026-08-26T00:00:00Z",
  });
});

test("project collections keep package-backed drafts separate from confirmed formal projects", async () => {
  assert.equal(typeof loadProjectCollections, "function");
  const confirmed = { ...blank, id: "formal-1", name: "正式项目", projectNo: "P-1",
    category: "办公设备", metadataConfirmed: true, versionNo: 1 };
  const draft = {
    projectId: "project-1", versionNo: 0, packageFilename: "intake.zip", intakeStatus: "PARSED",
    parsedFileCount: 3, ocrFileCount: 1, failedFileCount: 0,
    candidateProjectName: "候选项目", candidateProjectNo: "D-1", createdAt: blank.createdAt,
  };
  let projectLoads = 0;
  let draftLoads = 0;
  let preparationLoads = 0;
  const preparationDraft = {
    projectId: "ordinary-project-1", uploadedFileCount: 5, createdAt: "2026-09-28T12:00:00Z",
  };
  const collections = await loadProjectCollections({
    async listProjects() {
      projectLoads += 1;
      return { items: [blank, confirmed], page: 1, size: 20, total: 2 };
    },
    async listIntakeDrafts() {
      draftLoads += 1;
      return { items: [draft], page: 1, size: 20, total: 1 };
    },
    async listPreparationDrafts() {
      preparationLoads += 1;
      return { items: [preparationDraft], page: 1, size: 20, total: 1 };
    },
  });

  assert.deepEqual(collections.projects, [confirmed], "unconfirmed Project cannot enter formal presentation");
  assert.deepEqual(collections.intakeDrafts, [draft]);
  assert.deepEqual(collections.preparationDrafts, [preparationDraft]);
  assert.equal(projectLoads, 1);
  assert.equal(draftLoads, 1);
  assert.equal(preparationLoads, 1);
  assert.deepEqual(preparationDraftRow(preparationDraft), {
    projectId: "ordinary-project-1",
    title: "材料已上传，系统正在整理",
    uploadedSummary: "已上传 5 份材料",
    createdAt: "2026-09-28T12:00:00Z",
    action: "查看处理进度",
  });
});

test("preparation draft collection failure is explicit instead of degrading to an empty success", async () => {
  await assert.rejects(loadProjectCollections({
    async listProjects() { return { items: [], page: 1, size: 20, total: 0 }; },
    async listIntakeDrafts() { return { items: [], page: 1, size: 20, total: 0 }; },
    async listPreparationDrafts() { throw new Error("preparation offline"); },
  }), /preparation offline/);
});

test("project management presentation keeps server totals and labels draft facts as loaded scope", async () => {
  const confirmed = { ...blank, id: "formal-1", name: "正式项目", projectNo: "P-1",
    category: "办公设备", metadataConfirmed: true, versionNo: 1, createdAt: "2026-09-10T09:30:00Z" };
  const failedDraft = {
    projectId: "draft-failed", versionNo: 0, packageFilename: "failed.zip", intakeStatus: "PARSED",
    parsedFileCount: 3, ocrFileCount: 1, failedFileCount: 1,
    candidateProjectName: null, candidateProjectNo: null, createdAt: "2026-09-10T08:00:00Z",
  };
  const awaitingDraft = {
    projectId: "draft-awaiting", versionNo: 0, packageFilename: "awaiting.zip", intakeStatus: "AWAITING_UPLOAD",
    parsedFileCount: 0, ocrFileCount: 0, failedFileCount: 0,
    candidateProjectName: null, candidateProjectNo: null, createdAt: "2026-09-09T08:00:00Z",
  };
  const collections = await loadProjectCollections({
    async listProjects() {
      return { items: [blank, confirmed], page: 1, size: 20, total: 42 };
    },
    async listIntakeDrafts() {
      return { items: [failedDraft, awaitingDraft], page: 1, size: 20, total: 99 };
    },
    async listPreparationDrafts() {
      return { items: [], page: 1, size: 20, total: 0 };
    },
  });

  assert.equal(collections.projectTotal, 42);
  assert.equal(collections.intakeDraftTotal, 99);
  const view = projectManagementPresentation(collections, new Date("2026-09-10T12:00:00Z"));
  assert.equal(view.stats.formalTotal, 42);
  assert.equal(view.stats.loadedDrafts, 2);
  assert.equal(view.stats.loadedAnomalies, 1);
  assert.equal(view.stats.loadedMaterials, 3);
  assert.deepEqual(view.today, { formalProjects: 1, loadedDrafts: 1 });
  assert.equal(view.scopeLabel, "当前已加载预处理资料");
  assert.equal(view.reminder?.projectId, "draft-failed");
  assert.equal(view.reminder?.reason, "存在待检查材料");
  const awaitingView = projectManagementPresentation({ ...collections, intakeDrafts: [awaitingDraft] },
    new Date("2026-09-10T12:00:00Z"));
  assert.equal(awaitingView.reminder?.projectId, "draft-awaiting");
  assert.equal(awaitingView.reminder?.reason, "等待资料上传");
  assert.doesNotMatch(JSON.stringify(view), /528000|设计稿项目/);
});

test("intake draft presentation uses real facts and omits unavailable candidate placeholders", () => {
  assert.deepEqual(projectManagementSections(), {
    preprocessing: "项目资料预处理", intakeDrafts: "已预处理资料", formalProjects: "正式项目",
  });
  const base = {
    projectId: "project-1", versionNo: 0, packageFilename: "intake.zip", intakeStatus: "PARSED",
    parsedFileCount: 3, ocrFileCount: 1, failedFileCount: 0,
    candidateProjectName: "候选项目", candidateProjectNo: "D-1", createdAt: blank.createdAt,
  };
  assert.deepEqual(intakeDraftRow(base), {
    projectId: "project-1", packageFilename: "intake.zip", statusLabel: "预处理完成",
    parsedSummary: "已整理 3 份资料", warning: null,
    candidateProjectName: "候选项目", candidateProjectNo: "D-1",
    createdAt: blank.createdAt, viewAction: "查看资料", formalizeAction: "用于新建项目",
  });
  assert.deepEqual(intakeDraftRow({ ...base, candidateProjectName: null, candidateProjectNo: null,
    failedFileCount: 2 }).candidateProjectName, null);
  assert.equal(JSON.stringify(intakeDraftRow({ ...base, candidateProjectName: null,
    candidateProjectNo: null })).includes("待识别"), false);
});

test("project landing makes the package flow primary and presents only factual stages", () => {
  assert.deepEqual(projectLandingIntake("IDLE", null), {
    title: "项目资料预处理",
    description: "项目资料包将在此完成安全检查、材料整理、内容读取和业务信息识别。完成后可在合同、预算等业务环节直接选择已整理资料，无需重复上传。",
    primaryAction: "导入 ZIP 资料包",
    secondaryAction: "新建空白项目",
    stageLabel: "等待选择项目资料包",
    filename: null,
    busy: false,
    retryable: false,
  });
  assert.deepEqual(projectLandingIntake("UPLOADING_PACKAGE", "package.zip"), {
    title: "项目资料预处理",
    description: "项目资料包将在此完成安全检查、材料整理、内容读取和业务信息识别。完成后可在合同、预算等业务环节直接选择已整理资料，无需重复上传。",
    primaryAction: "导入 ZIP 资料包",
    secondaryAction: "新建空白项目",
    stageLabel: "正在接收项目资料包",
    filename: "package.zip",
    busy: true,
    retryable: false,
  });
  assert.equal(projectLandingIntake("UNCERTAIN", "package.zip").stageLabel,
    "资料处理结果尚未确认，请使用同一资料包继续重试");
  assert.equal(projectLandingIntake("UNCERTAIN", "package.zip").retryable, true);
  assert.deepEqual(projectLandingIntake("FAILED", "failed.zip"), {
    title: "项目资料预处理",
    description: "项目资料包将在此完成安全检查、材料整理、内容读取和业务信息识别。完成后可在合同、预算等业务环节直接选择已整理资料，无需重复上传。",
    primaryAction: "导入 ZIP 资料包",
    secondaryAction: "新建空白项目",
    stageLabel: "项目资料接收失败，请重新选择资料包",
    filename: "failed.zip",
    busy: false,
    retryable: false,
  });
});

test("successful package intake stays on the landing page while the list remains a compatibility delegate", () => {
  const page = readFileSync(new URL("./components/ProjectManagementLandingPage.tsx", import.meta.url), "utf8");
  const legacyPage = readFileSync(new URL("./components/ProjectListPage.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(page, /startPackageIntake\(file\)\.then\([^\n]*onOpenProject/);
  assert.doesNotMatch(page, /retryPackageIntake![^\n]*onOpenProject/);
  assert.doesNotMatch(page, />\{row\.id\}<\/div>/);
  assert.match(page, /预处理完成/);
  assert.match(page, /查看项目资料/);
  assert.match(page, /继续处理/);
  assert.match(page, /用于新建项目/);
  assert.match(page, /今日新增正式项目/);
  assert.match(page, /contractReviewEntry \? "待核验资料" : "预处理资料"/);
  assert.match(page, /contractReviewEntry \? "合同材料核验" : "项目管理"/);
  assert.match(legacyPage, /ProjectManagementLandingPage/);
  assert.match(page, /preparationDrafts\.map/);
  const preparationCards = page.slice(page.indexOf("state.preparationDrafts.map"),
    page.indexOf("!state.preparationDrafts.length"));
  assert.match(preparationCards, /onOpenProject\(row\.projectId\)/);
  assert.doesNotMatch(preparationCards, /createBlank/);
  assert.doesNotMatch(page, /localStorage|sessionStorage/);
});

test("terminal package failure copy never asks for uncertain same-package retry", () => {
  assert.equal(projectIntakeError("FAILED"), "项目资料包接入失败，请重新选择资料包后再试。");
  assert.equal(projectIntakeError("UNCERTAIN"),
    "项目资料包处理结果尚未确认；请保留当前页面并使用同一资料包继续重试。");
});

test("unavailable project collections cannot manufacture zero counts or reminders", () => {
  const view = projectManagementPresentation(null, new Date("2026-09-10T12:00:00Z"));
  assert.deepEqual(view.stats, { formalTotal: null, loadedDrafts: null, loadedAnomalies: null, loadedMaterials: null });
  assert.deepEqual(view.today, { formalProjects: null, loadedDrafts: null });
  assert.equal(view.reminder, null);
});

// React runs real effects/state; this minimal DOM only hosts a hook returning null.
class HookElement {
  nodeType = 1;
  namespaceURI = "http://www.w3.org/1999/xhtml";
  style = {};
  children: HookElement[] = [];
  parentNode: HookElement | null = null;
  textContent = "";
  tagName = "DIV";
  nodeName = "DIV";
  readonly ownerDocument: object;
  constructor(ownerDocument: object) { this.ownerDocument = ownerDocument; }
  addEventListener() {}
  removeEventListener() {}
  setAttribute() {}
  removeAttribute() {}
  appendChild(child: HookElement) { child.parentNode = this; this.children.push(child); return child; }
  removeChild(child: HookElement) { this.children = this.children.filter((item) => item !== child); child.parentNode = null; return child; }
}

const hookDocument = {
  nodeType: 9, activeElement: null, addEventListener() {}, removeEventListener() {},
  createElement() { return new HookElement(this); },
};
const hookWindow = { document: hookDocument, HTMLElement: HookElement, HTMLIFrameElement: class {},
  addEventListener() {}, removeEventListener() {}, location: { protocol: "http:" } };
Object.assign(hookDocument, { documentElement: new HookElement(hookDocument), defaultView: hookWindow });
Object.assign(globalThis, { document: hookDocument, window: hookWindow, IS_REACT_ACT_ENVIRONMENT: true });

type ProjectListState = ReturnType<typeof import("./useProjectList.ts").useProjectList>;
type ProjectSource = import("./data/projectDataSource.ts").ProjectDataSource;
const React = await import("react");
const { createRoot } = await import("react-dom/client");

async function loadProjectHarness() {
  const bundled = await build({
    bundle: true, format: "cjs", jsx: "automatic", platform: "node", write: false,
    external: ["react", "react-dom/client", "react-dom/server"],
    define: { "import.meta.env.VITE_API_BASE_URL": '""', "import.meta.env.DEV": "false" },
    plugins: [{ name: "render-observed-hook-snapshot", setup(builder) {
      builder.onResolve({ filter: /project-snapshot|useProjectList$/ }, (args) => {
        if (args.path === "project-snapshot" || args.importer.endsWith("ProjectManagementLandingPage.tsx")) {
          return { path: "project-snapshot", namespace: "snapshot" };
        }
      });
      // SSR does not run effects. Render the state observed from the actual mounted hook.
      builder.onLoad({ filter: /.*/, namespace: "snapshot" }, () => ({ contents:
        "let state; export function setSnapshot(value) { state = value; } export function useProjectList() { return state; }" }));
    } }],
    stdin: { loader: "tsx", resolveDir: process.cwd(), sourcefile: "project-list-harness.tsx", contents: `
      import React from "react";
      import { renderToStaticMarkup } from "react-dom/server";
      import { setSnapshot } from "project-snapshot";
      import { ProjectManagementLandingPage } from "./src/features/project/components/ProjectManagementLandingPage.tsx";
      export { useProjectList } from "./src/features/project/useProjectList.ts";
      export function render(state, options = {}) {
        setSnapshot(state);
        return renderToStaticMarkup(<ProjectManagementLandingPage dataSource={{}} onOpenProject={() => {}}
          contractReviewEntry={options.contractReviewEntry} embeddedContractQueue={options.embeddedContractQueue}/>);
      }
    ` },
  });
  const compiled = { exports: {} as {
    useProjectList: (source: ProjectSource) => ProjectListState;
    render: (state: ProjectListState,
      options?: { contractReviewEntry?: boolean; embeddedContractQueue?: boolean }) => string;
  } };
  new Function("require", "module", "exports", bundled.outputFiles[0].text)(createRequire(import.meta.url), compiled, compiled.exports);
  return compiled.exports;
}

async function mountProjectHook(t: test.TestContext, source: Partial<ProjectSource>) {
  const { useProjectList, render } = await loadProjectHarness();
  let state: ProjectListState;
  const root = createRoot(new HookElement(hookDocument) as unknown as Element);
  function Harness() { state = useProjectList(source as ProjectSource); return null; }
  await React.act(async () => { root.render(React.createElement(Harness)); });
  const unmount = async () => { await React.act(async () => root.unmount()); };
  t.after(unmount);
  return { state: () => state!,
    markup: (options?: { contractReviewEntry?: boolean; embeddedContractQueue?: boolean }) => render(state!, options),
    unmount };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const formalProject = { ...blank, id: "formal-loaded", name: "已读取正式项目", metadataConfirmed: true };
const loadedDraft = { projectId: "draft-loaded", versionNo: 0, packageFilename: "已读取资料.zip", intakeStatus: "PARSED",
  parsedFileCount: 3, ocrFileCount: 0, failedFileCount: 1, candidateProjectName: null, candidateProjectNo: null,
  createdAt: "2026-09-10T08:00:00Z" };
const projectPage = (items = [formalProject], total = 42) => ({ items, total, page: 1, size: 20 });
const draftPage = () => ({ items: [loadedDraft], total: 99, page: 1, size: 20 });
const preparationPage = () => ({ items: [{ projectId: "preparation-loaded", uploadedFileCount: 5,
  createdAt: "2026-09-10T08:30:00Z" }], total: 1, page: 1, size: 20 });

function assertCollectionUnavailable(hook: Awaited<ReturnType<typeof mountProjectHook>>) {
  assert.deepEqual(hook.state().projects, []);
  assert.deepEqual(hook.state().intakeDrafts, []);
  assert.deepEqual(hook.state().preparationDrafts, []);
  assert.equal(hook.state().projectTotal, null);
  assert.equal(hook.state().intakeDraftTotal, null);
  assert.equal(hook.state().preparationDraftTotal, null);
  const markup = hook.markup();
  assert.equal((markup.match(/<strong[^>]*>--<\/strong>/g) ?? []).length, 6);
  assert.doesNotMatch(markup, /暂无正式项目|暂无已预处理资料|已读取正式项目|已读取资料\.zip|存在待检查材料/);
  assert.equal(hook.state().collectionsAvailable, false);
}

test("contract pending queue refetches and renders ordinary preparation drafts from server state", async (t) => {
  let loads = 0;
  const hook = await mountProjectHook(t, {
    listProjects: async () => projectPage(),
    listIntakeDrafts: async () => draftPage(),
    listPreparationDrafts: async () => { loads += 1; return preparationPage(); },
  });
  assert.equal(loads, 1);
  assert.equal(hook.state().preparationDrafts[0]?.projectId, "preparation-loaded");
  let markup = hook.markup({ contractReviewEntry: true, embeddedContractQueue: true });
  assert.match(markup, /材料已上传，系统正在整理/);
  assert.match(markup, /已上传 5 份材料/);
  assert.match(markup, /资料包处理中的材料/);
  await React.act(async () => { await hook.state().reload(); });
  assert.equal(loads, 2);
  markup = hook.markup({ contractReviewEntry: true, embeddedContractQueue: true });
  assert.match(markup, /材料已上传，系统正在整理/);
});

test("contract pending queue can reopen durable ZIP and ordinary drafts beyond the first page", async (t) => {
  const intakePages: number[] = [];
  const preparationPages: number[] = [];
  const hook = await mountProjectHook(t, {
    listProjects: async () => projectPage(),
    listIntakeDrafts: async (options) => {
      const page = options?.page ?? 1;
      intakePages.push(page);
      return { items: [{ ...loadedDraft, projectId: `zip-page-${page}` }], total: 21, page, size: 20 };
    },
    listPreparationDrafts: async (options) => {
      const page = options?.page ?? 1;
      preparationPages.push(page);
      return { items: [{ projectId: `ordinary-page-${page}`, uploadedFileCount: 5,
        createdAt: "2026-09-10T08:30:00Z" }], total: 21, page, size: 20 };
    },
  });

  await React.act(async () => { await hook.state().loadPreparationDraftPage(2); });
  assert.equal(hook.state().preparationDraftPage, 2);
  assert.equal(hook.state().preparationDrafts[0]?.projectId, "ordinary-page-2");
  assert.deepEqual(preparationPages, [1, 2]);

  await React.act(async () => { await hook.state().loadIntakeDraftPage(2); });
  assert.equal(hook.state().intakeDraftPage, 2);
  assert.equal(hook.state().intakeDrafts[0]?.projectId, "zip-page-2");
  assert.deepEqual(intakePages, [1, 1, 2]);
  assert.match(hook.markup({ contractReviewEntry: true, embeddedContractQueue: true }), /第 2 \/ 2 页/);
});

test("refresh clamps a preparation draft page after the durable collection shrinks", async (t) => {
  let shrunk = false;
  const requestedPages: number[] = [];
  const hook = await mountProjectHook(t, {
    listProjects: async () => projectPage(),
    listIntakeDrafts: async () => ({ items: [], total: 0, page: 1, size: 20 }),
    listPreparationDrafts: async (options) => {
      const page = options?.page ?? 1;
      requestedPages.push(page);
      if (shrunk && page === 2) return { items: [], total: 20, page: 2, size: 20 };
      return { items: [{ projectId: `ordinary-page-${page}`, uploadedFileCount: 5,
        createdAt: "2026-09-10T08:30:00Z" }], total: shrunk ? 20 : 21, page, size: 20 };
    },
  });

  await React.act(async () => { await hook.state().loadPreparationDraftPage(2); });
  shrunk = true;
  await React.act(async () => { await hook.state().reload(); });

  assert.equal(hook.state().preparationDraftPage, 1);
  assert.equal(hook.state().preparationDrafts[0]?.projectId, "ordinary-page-1");
  assert.deepEqual(requestedPages, [1, 2, 2, 1]);
});

test("initial project load failure renders unavailable facts and keeps load errors separate from operations", async (t) => {
  const hook = await mountProjectHook(t, {
    listProjects: async () => { throw new Error("offline"); }, listIntakeDrafts: async () => draftPage(),
    listPreparationDrafts: async () => preparationPage(),
    createBlank: async () => formalProject,
  });
  assertCollectionUnavailable(hook);
  assert.ok(hook.state().collectionError);
  await React.act(async () => { await hook.state().createBlank(); });
  assert.ok(hook.state().collectionError, "starting an operation cannot erase an unavailable collection error");
  assertCollectionUnavailable(hook);
});

test("failed project refresh clears rows, totals and reminders, then a successful empty retry shows zero", async (t) => {
  let mode = "loaded";
  const hook = await mountProjectHook(t, {
    listProjects: async () => { if (mode === "failed") throw new Error("offline"); return mode === "empty" ? projectPage([], 0) : projectPage(); },
    listIntakeDrafts: async () => mode === "empty" ? { items: [], total: 0, page: 1, size: 20 } : draftPage(),
    listPreparationDrafts: async () => mode === "empty" ? { items: [], total: 0, page: 1, size: 20 } : preparationPage(),
  });
  const populated = hook.markup();
  assert.match(populated, /已读取正式项目/);
  assert.match(populated, /待处理提醒/);
  assert.match(populated, /今日项目概要/);
  assert.match(populated, /导入项目资料包/);
  assert.match(populated, /继续处理/);
  assert.match(populated, /用于新建项目/);
  mode = "failed";
  await React.act(async () => { await hook.state().reload(); });
  assertCollectionUnavailable(hook);
  mode = "empty";
  await React.act(async () => { await hook.state().reload(); });
  assert.equal(hook.state().collectionsAvailable, true);
  assert.equal(hook.state().collectionError, null);
  const empty = hook.markup();
  assert.equal((empty.match(/<strong[^>]*>0<\/strong>/g) ?? []).length, 6);
  assert.match(empty, /待处理提醒/);
  assert.match(empty, /今日项目概要/);
  assert.match(empty, /导入项目资料包/);
  assert.match(empty, /预处理资料/);
  assert.match(empty, /异常资料/);
  assert.match(empty, /已整理资料/);
  assert.match(empty, /当前数据基于已加载记录统计/);
  assert.doesNotMatch(empty, /当前已加载|当前加载|服务器返回/);
  assert.match(empty, /正式项目/);
  assert.match(empty, /暂无正式项目/);
  assert.match(empty, /暂无已预处理资料/);
});

for (const oldOutcome of ["success", "failure"] as const) {
  test(`superseded project load ${oldOutcome} cannot overwrite a newer successful load`, async (t) => {
    const requests: Array<{ signal?: AbortSignal; completion: ReturnType<typeof deferred<ReturnType<typeof projectPage>>> }> = [];
    const hook = await mountProjectHook(t, {
      listProjects: (options) => { const completion = deferred<ReturnType<typeof projectPage>>(); requests.push({ signal: options?.signal, completion }); return completion.promise; },
      listIntakeDrafts: async () => draftPage(),
      listPreparationDrafts: async () => preparationPage(),
    });
    let latest!: Promise<void>;
    await React.act(async () => { latest = hook.state().reload(); });
    await React.act(async () => { requests[1].completion.resolve(projectPage([{ ...formalProject, id: "latest-project" }], 7)); await latest; });
    await React.act(async () => {
      if (oldOutcome === "success") requests[0].completion.resolve(projectPage());
      else requests[0].completion.reject(new Error("stale failure"));
    });
    assert.equal(hook.state().projects[0]?.id, "latest-project");
    assert.equal(hook.state().projectTotal, 7);
    assert.equal(hook.state().error, null);
    assert.equal(requests[0].signal?.aborted, true);
    assert.equal(hook.state().collectionsAvailable, true);
    assert.equal(hook.state().collectionError, null);
    assert.equal(hook.state().loading, false);
  });
}

test("refresh does not change an uncertain intake operation identity or enable a new operation", async (t) => {
  const keys: string[] = [];
  const hook = await mountProjectHook(t, {
    listProjects: async () => projectPage(), listIntakeDrafts: async () => draftPage(),
    listPreparationDrafts: async () => preparationPage(),
    createBlank: async (options) => { keys.push(options.idempotencyKey); throw new TypeError("Failed to fetch"); },
  });
  await React.act(async () => { await hook.state().startPackageIntake(new File(["zip"], "same.zip")); });
  assert.equal(hook.state().intakeSnapshot.stage, "UNCERTAIN");
  await React.act(async () => { await hook.state().reload(); });
  assert.equal(hook.state().intakeSnapshot.stage, "UNCERTAIN");
  assert.match(hook.markup(), /使用同一操作重试/);
  await React.act(async () => { await hook.state().retryPackageIntake!(); });
  assert.equal(keys.length, 2);
  assert.equal(keys[0], keys[1]);
});

test("unmount aborts a pending project collection load", async (t) => {
  let signal: AbortSignal | undefined;
  const completion = deferred<ReturnType<typeof projectPage>>();
  const hook = await mountProjectHook(t, {
    listProjects: (options) => { signal = options?.signal; return completion.promise; },
    listIntakeDrafts: async () => draftPage(),
    listPreparationDrafts: async () => preparationPage(),
  });
  await hook.unmount();
  assert.equal(signal?.aborted, true);
  completion.resolve(projectPage());
});
