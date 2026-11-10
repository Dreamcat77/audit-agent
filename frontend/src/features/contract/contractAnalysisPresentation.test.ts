import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {partitionAnalysisFindings,analysisBusy,aggregateMaterialHighlights,findingCopyText,findingSourceNames} from "./contractAnalysisPresentation.ts";

test("optional follow-ups remain visible but do not inflate observed risk counts",()=>{
  const optional={level:"INFO",title:"补充核对附件"};
  const observed={level:"WARNING",title:"付款安排互不抵扣"};
  const result=partitionAnalysisFindings([optional,observed]);
  assert.deepEqual(result.risks,[observed]);
  assert.deepEqual(result.followUps,[optional]);
  assert.equal(result.risks.length+result.followUps.length,2);
});

test("uncertain and partial terminal results are never displayed as still running",()=>{
  for(const status of ["UNKNOWN","PARTIAL","DATA_INSUFFICIENT","FAILED","COMPLETED"] as const)assert.equal(analysisBusy(status),false);
  assert.equal(analysisBusy("QUEUED"),true);assert.equal(analysisBusy("RUNNING"),true);
});

test("contract findings name only the source documents attached to their evidence", () => {
  const finding = {
    title: "付款条件不明确",
    explanation: "未明确付款时间",
    suggestion: "补充付款期限",
    evidence: [{ fileId: "f1" }, { fileId: "f1" }, { fileId: "f2" }],
  };
  const documents = [
    { fileId: "f1", name: "合同正文.docx", facts: [], clauses: [] },
    { fileId: "f2", name: "付款补充协议.pdf", facts: [], clauses: [] },
    { fileId: "f3", name: "无关附件.pdf", facts: [], clauses: [] },
  ];

  const sources = findingSourceNames(finding, documents);
  assert.deepEqual(sources, ["合同正文.docx", "付款补充协议.pdf"]);
  assert.equal(findingCopyText(finding, sources), "问题项：付款条件不明确\n涉及材料：合同正文.docx、付款补充协议.pdf\n问题说明：未明确付款时间\n建议：补充付款期限");
  assert.match(findingCopyText(finding, []), /涉及材料：未能定位到具体文件/);
});

test("contract material highlights aggregate facts and clauses without losing source names", () => {
  const result = aggregateMaterialHighlights([
    { fileId: "f1", name: "合同.docx", facts: [{ type: "TOTAL_AMOUNT", value: "壹万元" }], clauses: [{ topic: "PAYMENT", summary: "验收后付款" }] },
    { fileId: "f2", name: "补充协议.pdf", facts: [{ type: "SIGN_DATE", value: "2026年1月1日" }], clauses: [] },
  ]);

  assert.deepEqual(result.map(item => [item.title, item.text, item.sourceName]), [
    ["TOTAL_AMOUNT", "壹万元", "合同.docx"],
    ["PAYMENT", "验收后付款", "合同.docx"],
    ["SIGN_DATE", "2026年1月1日", "补充协议.pdf"],
  ]);
});

test("contract analysis summary is consolidated while formal eight-category review remains mounted", async () => {
  const analysis = await readFile(new URL("./components/ContractAnalysisWorkspace.tsx", import.meta.url), "utf8");
  const formalReview = await readFile(new URL("./components/ContractReviewWorkspace.tsx", import.meta.url), "utf8");

  assert.doesNotMatch(analysis, /本次处理说明/);
  assert.match(analysis, /材料提取内容/);
  assert.match(analysis, /复制问题项/);
  assert.match(formalReview, /<EightCategoryReviewPanel/);
});
