import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { groupBudgetMaterialLines, mergeBudgetMaterialReferences } from "./budgetBatchResult.ts";
import { budgetBatchResultState } from "./budgetResultState.ts";

test('batch semantic projection keeps project name, deduplicates totals and never upgrades partial analysis',()=>{
  const total={role:'projectBudgetAmount',scope:'TOTAL',year:'2026',amount:1000000,sources:[]};
  const assessment={code:'SEMANTIC_PURPOSE_SCOPE',level:'WARNING',title:'用途不一致',explanation:'批复购置，申请宴请',basis:'按批复调整',sources:[]};
  const reference={status:'COMPLETED' as const,summary:'已整理',lines:[],projectName:'终端更新项目',totals:[total],assessments:[assessment],analysisCoverage:'COMPLETE' as const};
  const merged=mergeBudgetMaterialReferences([reference,{...reference,analysisCoverage:'PARTIAL'}]);
  assert.equal(merged?.projectName,'终端更新项目');assert.equal(merged?.totals.length,1);
  assert.equal(merged?.assessments.length,1);assert.equal(merged?.analysisCoverage,'PARTIAL');
  assert.equal(mergeBudgetMaterialReferences([reference,{status:'PENDING',summary:'整理中',lines:[]}])?.analysisCoverage,null);
});

test('one batch name merges duplicate cross-file lines while retaining every original source',()=>{
 const line={project:'项目别称',projectCode:null,subject:'设备',subjectCode:null,year:'2026',department:null,declaredAmount:12000,approvalAmount:10000,status:'OVER_APPROVAL',opinion:'超出2000元',sources:[]};
 const reference={status:'COMPLETED' as const,summary:'',projectName:'终端改造项目',lines:[line]};
 const result=mergeBudgetMaterialReferences([reference,{...reference,lines:[{...line,project:'另一名称'}]}]);
 assert.equal(result?.lines.length,1);assert.equal(result?.lines[0].project,'终端改造项目');
});

test("identical cross-file views of one batch finding appear only once", () => {
  const line = { project: "办公设备", subject: "电脑", subjectCode: "A01", year: "2026", department: "采购",
    declaredAmount: 31200, approvalAmount: 32760, status: "WITHIN_APPROVAL", opinion: "未超批复",
    sources: [{ taskId: "declaration", fileName: "申报.xlsx", field: "declaredAmount", value: "31200",
      locator: "sheet:1/row:2", excerpt: "申报31200" },
    { taskId: "approval", fileName: "批复.xlsx", field: "approvalAmount", value: "32760",
      locator: "sheet:1/row:2", excerpt: "批复32760" }] };
  const result = mergeBudgetMaterialReferences([
    { status: "COMPLETED", coverage: "COMPLETE", summary: "已整理", lines: [line] },
    { status: "COMPLETED", coverage: "COMPLETE", summary: "已整理", lines: [{ ...line, sources: [...line.sources].reverse() }] },
  ]);
  assert.equal(result?.lines.length, 1);
  assert.deepEqual(result?.lines[0].sources, line.sources);
});

test("batch views keep unique issues and mark partial coverage when a source did not finish", () => {
  const result = mergeBudgetMaterialReferences([
    { status: "COMPLETED", coverage: "COMPLETE", summary: "已整理", lines: [] },
    { status: "COMPLETED", coverage: "PARTIAL", summary: "另一份材料未完成", lines: [] },
  ]);
  assert.equal(result?.coverage, "PARTIAL");
  assert.equal(result?.summary, "已整理；另一份材料未完成");
});

test('missing coverage remains unverified and pending members prevent a completed summary', () => {
  const result = mergeBudgetMaterialReferences([
    { status: 'COMPLETED', coverage: 'COMPLETE', summary: '已整理', lines: [] },
    { status: 'PENDING', summary: '正在处理', lines: [] },
  ]);
  assert.equal(result?.status, 'PENDING');
  assert.equal(result?.coverage, null);
});

test('an available batch file result can be shown while sibling files are still processing', async () => {
  const state = budgetBatchResultState([
    { phase: 'PARSED', result: { task: { taskId: 'task-1' }, fileName: '预算表.xlsx' } },
    { phase: 'PROCESSING', result: null },
  ]);

  assert.equal(state.processing, true);
  assert.deepEqual(state.results, [{ id: 'task-1', name: '预算表.xlsx' }]);
  const page = await readFile(new URL('../../app/BudgetCheckPage.tsx', import.meta.url), 'utf8');
  assert.match(page, /budgetBatchResultState\(rows\)/);
  assert.match(page, /if\(!currentResultId\)setCurrentResultId\(results\[0\]\.id\)/);
});

test('projects, years and departments remain separate while differences come first in each group', () => {
  const line = { project: '设备项目', year: '2026', department: '采购', subject: '设备', subjectCode: null,
    declaredAmount: 50, approvalAmount: 100, status: 'WITHIN_APPROVAL', opinion: '参考意见', sources: [] };
  const groups = groupBudgetMaterialLines([line, { ...line, subject: '超出项', status: 'OVER_APPROVAL' },
    { ...line, year: '2025' }, { ...line, department: '财务' }, { ...line, project: '另一项目' }]);
  assert.equal(groups.length, 4);
  assert.deepEqual(groups[0].lines.map(row => row.status), ['OVER_APPROVAL', 'WITHIN_APPROVAL']);
  assert.equal(line.status, 'WITHIN_APPROVAL');
});

test('same subject with different amounts or conflicting opinions is never silently discarded', () => {
  const line = { project: '设备项目', year: '2026', department: null, subject: '设备', subjectCode: null,
    declaredAmount: 50, approvalAmount: 100, status: 'CONFLICT', opinion: '原始意见', sources: [] };
  const result = mergeBudgetMaterialReferences([{ status: 'COMPLETED', summary: '待核对',
    lines: [line, { ...line, declaredAmount: 60 }, { ...line, opinion: '另一条核对意见' }] }]);
  assert.equal(result?.lines.length, 3);
});
