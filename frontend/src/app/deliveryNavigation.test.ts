import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import { build } from "esbuild";
import React from "react";
import { createRoot } from "react-dom/client";
import { installScopedGlobals } from "../test/scopedGlobals.ts";

// Minimal DOM, following the existing hook tests. React owns state/effects;
// only network-backed destination workspaces are replaced at the page boundary.
class Element {
  nodeType = 1;
  namespaceURI = "http://www.w3.org/1999/xhtml";
  style = { setProperty() {}, removeProperty() {} };
  children: Element[] = [];
  parentNode: Element | null = null;
  textContent = "";
  nodeValue = "";
  tagName: string;
  nodeName: string;
  ownerDocument: any;
  constructor(doc: any, tag: string) { this.ownerDocument = doc; this.tagName = this.nodeName = tag.toUpperCase(); }
  addEventListener() {}
  removeEventListener() {}
  setAttribute() {}
  setAttributeNS() {}
  removeAttribute() {}
  appendChild(child: Element) { child.parentNode = this; this.children.push(child); return child; }
  insertBefore(child: Element, before: Element) {
    child.parentNode = this;
    const index = this.children.indexOf(before);
    if (index < 0) this.children.push(child); else this.children.splice(index, 0, child);
    return child;
  }
  removeChild(child: Element) { this.children.splice(this.children.indexOf(child), 1); child.parentNode = null; return child; }
}
const doc: any = {
  nodeType: 9, activeElement: null, addEventListener() {}, removeEventListener() {},
  createElement: (tag: string) => new Element(doc, tag),
  createElementNS: (_namespace: string, tag: string) => new Element(doc, tag),
  createTextNode: (text: string) => Object.assign(new Element(doc, "#text"), { nodeType: 3, nodeValue: text }),
};
doc.documentElement = new Element(doc, "html");
doc.body = new Element(doc, "body");
const sessionValues = new Map<string, string>();
const sessionStorage: Storage = {
  get length() { return sessionValues.size; },
  clear() { sessionValues.clear(); },
  getItem(key: string) { return sessionValues.get(key) ?? null; },
  key(index: number) { return [...sessionValues.keys()][index] ?? null; },
  removeItem(key: string) { sessionValues.delete(key); },
  setItem(key: string, value: string) { sessionValues.set(key, value); },
};
const win = { document: doc, sessionStorage, HTMLElement: Element, HTMLIFrameElement: class {}, addEventListener() {}, removeEventListener() {} };
doc.defaultView = win;

const require = createRequire(import.meta.url);
async function compile(contents: string, plugins: any[] = []) {
  const result = await build({
    bundle: true, write: false, platform: "node", format: "cjs", jsx: "automatic",
    loader: { ".css": "empty", ".woff2": "dataurl" },
    external: ["react", "react-dom", "react-dom/client", "react/jsx-runtime", "lucide-react", "recharts"],
    define: { "import.meta.env": '{"DEV":false,"VITE_CONTRACT_DATA_SOURCE":"http"}' },
    stdin: { contents, loader: "tsx", resolveDir: process.cwd() }, plugins,
  });
  const module = { exports: {} as any };
  new Function("require", "module", "exports", result.outputFiles[0].text)(require, module, module.exports);
  return module.exports;
}

const task = {
  taskId: "audit-task-17",
  contract: { id: "formal-contract-23", contractNo: "HT-23", contractName: "真实合同",
    contractType: "SERVICE", partyA: "甲方", partyB: "乙方", partyBCreditCode: null,
    contractAmount: 100, amountInWords: null, status: "ACTIVE", versionNo: 1,
    createdAt: "2026-09-10T08:00:00Z", updatedAt: "2026-09-10T08:00:00Z" },
  project: { projectId: "project-41", projectName: "真实项目", projectNo: "XM-41" },
  taskStatus: "NEEDS_MANUAL_REVIEW", createdAt: "2026-09-10T08:00:00Z", startedAt: null, finishedAt: null,
  reviewProgress: { current: 2, total: 3, complete: false, pendingAcknowledgement: true },
  risk: { riskCount: 1, materialInsufficient: false, riskSummary: "低风险" },
  reviewRun: { kind: "INTEGRATED", projectId: "project-41", runId: "run-risk-exact", status: "PARTIALLY_COMPLETED" },
};

async function mountApp(t: test.TestContext) {
  const leaves = new Map<string, any>();
  const restoreGlobals = installScopedGlobals({
    window: win, document: doc, IS_REACT_ACT_ENVIRONMENT: true,
    __deliveryNavigationLeaves: leaves,
  });
  let root: ReturnType<typeof createRoot> | undefined;
  t.after(async () => {
    try { await React.act(async () => { root?.unmount(); }); }
    finally { restoreGlobals(); }
  });
  const names = ["PortalShell", "DeliveryWorkbenchPage", "ContractDashboardPage", "ContractAuditLandingPage", "ContractPortalHome", "CreateReviewSessionDialog",
    "ContractOverviewList", "ContractReviewSessionWorkspace", "DirectF02AuditTaskWorkspace", "ContractReviewWorkspace",
    "ProjectWorkspace", "BudgetAuditWorkspace", "ApiTestPage", "BlacklistAlertPage", "RiskAssessmentPage"];
  const { App } = await compile('export { default as App } from "./src/app/App.tsx";', [{
    name: "capture-destination-boundaries",
    setup(builder: any) {
      builder.onResolve({ filter: /./ }, (args: any) => {
        const name = args.path.split("/").at(-1).replace(/\.tsx?$/, "");
        if (names.includes(name) || name === "AuthProvider") return { path: name, namespace: "destination" };
      });
      builder.onLoad({ filter: /./, namespace: "destination" }, (args: any) => ({
        contents: args.path === "AuthProvider"
          ? 'export const useAuth = () => ({ user: { userId: "user-1", username: "test", roles: [] }, logout: async () => {} });'
          : args.path === "PortalShell"
            ? `import { useLayoutEffect } from "react";
              export function PortalShell(props) {
                useLayoutEffect(() => { globalThis.__deliveryNavigationLeaves.set("PortalShell", props);
                  return () => globalThis.__deliveryNavigationLeaves.delete("PortalShell"); });
                return props.children;
              }`
          : `import { useLayoutEffect } from "react";
            export function ${args.path}(props) {
              useLayoutEffect(() => { globalThis.__deliveryNavigationLeaves.set("${args.path}", props);
                return () => globalThis.__deliveryNavigationLeaves.delete("${args.path}"); });
              return null;
            }
            export default ${args.path};`,
        loader: "js", resolveDir: process.cwd(),
      }));
    },
  }]);
  const container = new Element(doc, "div");
  root = createRoot(container as any);
  await React.act(async () => { root.render(React.createElement(App)); });
  const props = (name: string) => { assert.ok(leaves.has(name), `Expected active destination ${name}; got ${[...leaves.keys()]}`); return leaves.get(name); };
  const invoke = async (name: string, callback: string, ...args: any[]) => {
    const handler = props(name)[callback];
    assert.equal(typeof handler, "function", `${name}.${callback} must be callable`);
    await React.act(async () => { await handler(...args); });
  };
  const sidebar = async (label: string) => {
    if (leaves.has("PortalShell")) {
      const shell = leaves.get("PortalShell");
      const item = shell.items.find((candidate: any) => candidate.label === label);
      assert.ok(item, `Portal navigation button ${label}`);
      await React.act(async () => { shell.onNavigate(item.id); });
      return;
    }
    function find(node: Element): Element | undefined {
      if (node.tagName === "BUTTON" && textOf(node).includes(label)) return node;
      return node.children.map(find).find(Boolean);
    }
    function textOf(node: Element): string { return node.textContent + node.nodeValue + node.children.map(textOf).join(""); }
    const button = find(container);
    assert.ok(button, `Sidebar button ${label}`);
    const key = Object.keys(button).find((key) => key.startsWith("__reactProps$"));
    assert.ok(key);
    await React.act(async () => { (button as any)[key].onClick(); });
  };
  return { props, invoke, sidebar, leaves };
}

test("workbench exposes the three business tools without a Project-management entry", async (t) => {
  const app = await mountApp(t);
  assert.equal(app.props("PortalShell").activePage, "dashboard");
  app.props("DeliveryWorkbenchPage");
  assert.equal(typeof app.props("DeliveryWorkbenchPage").onOpenProcurement, "function");
  assert.equal(typeof app.props("DeliveryWorkbenchPage").onOpenBlacklist, "function");
  assert.equal(typeof app.props("DeliveryWorkbenchPage").onOpenRisk, "function");
  assert.equal(app.props("DeliveryWorkbenchPage").onOpenProjects, undefined);
  await app.invoke("DeliveryWorkbenchPage", "onOpenContract");
  app.props("ContractPortalHome");
  await app.sidebar("工作台");
  await app.invoke("DeliveryWorkbenchPage", "onOpenBudget");
  app.props("BudgetAuditWorkspace");
  await app.sidebar("工作台");
  await app.invoke("DeliveryWorkbenchPage", "onOpenBlacklist");
  app.props("BlacklistAlertPage");
});

test("internal Project preparation keeps the global Contract tool active", async (t) => {
  const app = await mountApp(t);
  await app.sidebar("合同审核");
  await app.invoke("ContractPortalHome", "onOpenProject", "project-41");
  app.props("ProjectWorkspace");
  assert.equal(app.props("PortalShell").activePage, "contract");
});

test("persisted risk entry opens the exact existing contract task", async (t) => {
  const app = await mountApp(t);
  await app.invoke("DeliveryWorkbenchPage", "onOpenRisk");
  app.props("RiskAssessmentPage");
  await app.invoke("RiskAssessmentPage", "onOpenTask", task);
  assert.equal(app.props("DirectF02AuditTaskWorkspace").row.taskId, task.taskId);
  assert.equal(app.props("DirectF02AuditTaskWorkspace").row.reviewRun.runId, task.reviewRun?.runId);
  assert.equal(app.props("PortalShell").activePage, "contract");
});

test("contract center routes preparation to Project and history to the exact ReviewRun", async (t) => {
  const app = await mountApp(t);
  await app.sidebar("合同审核");
  await app.invoke("ContractPortalHome", "onOpenProject", "project-41");
  app.props("ProjectWorkspace");
  assert.equal(app.props("ProjectWorkspace").initialProjectId, "project-41");
  assert.equal(app.leaves.has("ContractReviewSessionWorkspace"), false);
  await app.sidebar("合同审核");
  const directRow = { source: "DIRECT_F02", taskId: "old-task", contractApiId: "old-contract", projectId: "project-41",
    reviewRun: { kind: "INTEGRATED", projectId: "project-41", runId: "exact-run-20260923", status: "COMPLETED" } };
  await app.invoke("ContractPortalHome", "onOpenDirectF02", directRow);
  assert.equal(app.props("DirectF02AuditTaskWorkspace").row, directRow);
  assert.equal(app.props("DirectF02AuditTaskWorkspace").row.reviewRun.runId, "exact-run-20260923");
  await app.invoke("DirectF02AuditTaskWorkspace", "onBack");
  app.props("ContractPortalHome");
  const showcase = { rowId: "showcase-row", contractDisplayId: "DEMO-HT", contract: task.contract,
    projectName: "展示项目", projectNo: "DEMO-XM", initiatedBy: "展示用户", createdAt: task.createdAt };
  await app.invoke("ContractPortalHome", "onOpenShowcase", showcase);
  assert.equal(app.props("ContractReviewWorkspace").mode, "showcase");
  assert.equal(app.props("ContractReviewWorkspace").contractId, "DEMO-HT");
  await app.invoke("ContractReviewWorkspace", "onBack");
  app.props("ContractPortalHome");
});

test("landing and workbench buttons invoke analysis, list, creation, retry and original task callbacks", async () => {
  const content = await compile(`
    export { ContractAuditLandingContent } from "./src/features/contract/components/ContractAuditLandingPage.tsx";
    export { DeliveryWorkbenchContent } from "./src/features/dashboard/DeliveryWorkbenchPage.tsx";
    export { contractAuditLandingPresentation } from "./src/features/contract/contractAuditLandingPresentation.ts";
    export { workbenchPresentation } from "./src/features/dashboard/workbenchPresentation.ts";
  `);
  function buttons(node: any): any[] {
    if (!node || typeof node !== "object") return [];
    if (Array.isArray(node)) return node.flatMap(buttons);
    if (typeof node.type === "function") return buttons(node.type(node.props));
    return [...(node.type === "button" ? [node] : []), ...buttons(node.props?.children)];
  }
  function label(node: any): string {
    if (node == null || typeof node === "boolean") return "";
    if (Array.isArray(node)) return node.map(label).join("");
    return typeof node === "object" ? label(node.props?.children) : String(node);
  }
  const summary = { total: 2, pendingOrProcessing: 1, completed: 1, needsReview: 1 };
  const page = { items: [task], total: 1, page: 1, size: 6, totalPages: 1 };
  const calls: any[] = [];
  const callbacks = Object.fromEntries(["onAnalyze", "onCreateContract", "onOpenList", "onRetry", "onOpenTask",
    "onOpenContract", "onOpenProcurement", "onOpenBudget"].map((name) => [name, (...args: any[]) => calls.push([name, ...args])]));
  const landingProps = { ...callbacks, loading: false, error: null,
    view: content.contractAuditLandingPresentation(summary, page, { ...page, items: [] }, 1) };
  const landing = buttons(content.ContractAuditLandingContent(landingProps));
  const click = (items: any[], text: string) => {
    const button = items.find((item) => label(item).includes(text));
    assert.ok(button, `Button ${text}`); button.props.onClick();
  };
  click(landing, "智能分析"); click(landing, "新建合同"); click(landing, "审核记录"); click(landing, "真实合同");
  click(buttons(content.ContractAuditLandingContent({ ...landingProps, error: "加载失败" })), "重新加载");
  const workbenchProps = { ...callbacks, loading: false, error: null, view: content.workbenchPresentation(summary, [task], 1) };
  const workbench = buttons(content.DeliveryWorkbenchContent(workbenchProps));
  click(workbench, "合同审核"); click(workbench, "采购审查"); click(workbench, "预算审查");
  click(buttons(content.DeliveryWorkbenchContent({ ...workbenchProps, error: "加载失败" })), "重新读取");
  assert.deepEqual(calls, [["onAnalyze"], ["onCreateContract"], ["onOpenList"], ["onOpenTask", task], ["onRetry"],
    ["onOpenContract"], ["onOpenProcurement"], ["onOpenBudget"], ["onRetry"]]);
});

test("contract entry uses the portal without a legacy session dialog", async (t) => {
  const app = await mountApp(t);
  await app.invoke("DeliveryWorkbenchPage", "onOpenContract");
  app.props("ContractPortalHome");
  assert.equal(app.leaves.has("ContractAuditLandingPage"), false);
  assert.equal(app.leaves.has("CreateReviewSessionDialog"), false);
  assert.equal(app.leaves.has("ContractReviewSessionWorkspace"), false);
});
