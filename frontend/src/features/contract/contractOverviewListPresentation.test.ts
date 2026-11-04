import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import { build } from "esbuild";

async function loadRowHarness(): Promise<{
  renderRow: (row: object) => string;
  invokeOpenActions: (row: object) => { opens: number; stopped: number };
}> {
  const bundled = await build({
    bundle: true,
    define: {
      "import.meta.env.DEV": "false",
      "import.meta.env.VITE_API_BASE_URL": '""',
      "import.meta.env.VITE_CONTRACT_DATA_SOURCE": '"http"',
    },
    format: "cjs",
    jsx: "automatic",
    platform: "node",
    write: false,
    stdin: {
      contents: `
        import React from "react";
        import { renderToStaticMarkup } from "react-dom/server";
        import { ContractOverviewTableRow } from "./src/features/contract/components/ContractOverviewList.tsx";

        function textOf(node) {
          if (node === null || node === undefined || typeof node === "boolean") return "";
          if (typeof node === "string" || typeof node === "number") return String(node);
          if (Array.isArray(node)) return node.map(textOf).join("");
          return textOf(node.props?.children);
        }

        function descendants(node) {
          if (node === null || node === undefined || typeof node !== "object") return [];
          const children = React.Children.toArray(node.props?.children);
          return [node, ...children.flatMap(descendants)];
        }

        export function renderRow(row) {
          return renderToStaticMarkup(React.createElement("table", null,
            React.createElement("tbody", null,
              React.createElement(ContractOverviewTableRow, { row, onOpen: () => {}, onOpenHistory: () => {} }))));
        }

        export function invokeOpenActions(row) {
          let opens = 0;
          let stopped = 0;
          const tree = ContractOverviewTableRow({
            row,
            onOpen: () => { opens += 1; },
            onOpenHistory: () => {},
          });
          tree.props.onClick();
          const viewButton = descendants(tree).find((node) => node.type === "button" && textOf(node).includes("查看"));
          viewButton.props.onClick({ stopPropagation: () => { stopped += 1; } });
          return { opens, stopped };
        }
      `,
      loader: "tsx",
      resolveDir: process.cwd(),
      sourcefile: "contract-overview-row-harness.tsx",
    },
  });
  const compiled = { exports: {} as {
    renderRow: (row: object) => string;
    invokeOpenActions: (row: object) => { opens: number; stopped: number };
  } };
  const require = createRequire(import.meta.url);
  new Function("require", "module", "exports", bundled.outputFiles[0].text)(
    require, compiled, compiled.exports,
  );
  return compiled.exports;
}

const row = {
  rowId: "direct-f02:task-1",
  source: "DIRECT_F02",
  taskId: "task-1",
  initiatedBy: "合同内审人员",
  contractDisplayId: "HT-2026-001",
  contractApiId: "contract-1",
  contract: {
    id: "contract-1", contractNo: "HT-2026-001", contractName: "智慧维修系统采购合同",
    contractType: "SERVICE", partyA: "甲方", partyB: "乙方", partyBCreditCode: null,
    contractAmount: 100000, amountInWords: "人民币壹拾万元整", status: "ACTIVE", versionNo: 1,
    createdAt: "2026-09-03T09:00:00+08:00", updatedAt: "2026-09-03T09:05:00+08:00",
  },
  reviewName: "合同量化一致性审核",
  projectId: "project-1",
  projectName: "智慧维修系统采购项目",
  projectNo: "XM-2026-001",
  taskStatus: "DONE",
  reviewProgress: { current: 6, total: 6, complete: true, pendingAcknowledgement: false },
  risk: { riskCount: 3, materialInsufficient: false, riskSummary: "高风险" },
  reviewResult: null,
  createdAt: "2026-09-03T09:00:00+08:00",
  updatedAt: "2026-09-03T09:05:00+08:00",
  isShowcase: false,
};

test("contract overview row hides reviewName while preserving contract identity and review signals", async () => {
  const { renderRow } = await loadRowHarness();
  const markup = renderRow(row);

  assert.doesNotMatch(markup, /合同量化一致性审核/);
  assert.match(markup, /智慧维修系统采购合同/);
  assert.match(markup, /合同编号：HT-2026-001/);
  assert.match(markup, /F02 量化核对进度：6\/6/);
  assert.match(markup, /量化核对完成/);
  assert.match(markup, /建议重点复核/);
  assert.match(markup, /bg-red-/);
  assert.doesNotMatch(markup, /h-\[72px\]/);
});

test("contract overview row and its 查看 button keep their separate open behavior", async () => {
  const { invokeOpenActions } = await loadRowHarness();
  assert.deepEqual(invokeOpenActions(row), { opens: 2, stopped: 1 });
});

test("an integrated history row retains its exact ReviewRun identity", async () => {
  const { directF02AuditTaskRow } = await import("./contractAuditTaskPresentation.ts");
  const history = directF02AuditTaskRow({
    ...row,
    contract: row.contract,
    project: { projectId: "project-1", projectName: row.projectName, projectNo: row.projectNo },
    reviewRun: {
      kind: "INTEGRATED",
      projectId: "project-1",
      runId: "run-exact-7",
      status: "COMPLETED",
    },
  } as never);

  assert.equal(history.projectId, "project-1");
  assert.equal(history.reviewRun?.runId, "run-exact-7");
});
