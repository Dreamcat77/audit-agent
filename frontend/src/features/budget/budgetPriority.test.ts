import assert from 'node:assert/strict';
import {test} from 'node:test';
import {budgetPriority} from './budgetPriority.ts';

const source=(taskId:string,locator:string,excerpt:string)=>({taskId,fileName:'材料.pdf',field:'semantic',value:'',locator,excerpt});
const line=(subject:string,status:string,sources:any[])=>({project:'项目',projectCode:null,subject,subjectCode:null,year:'2027',department:null,
  declaredAmount:6000,approvalAmount:0,status,opinion:'超出批复',sources});
const risk=(code:string,title:string,sources:any[])=>({code,level:'WARNING',title,explanation:'批复用途与申报内容不一致',basis:'按批复用途核实',sources});
const reference=(lines:any[],assessments:any[])=>({status:'COMPLETED',summary:'已完成',lines,projectName:'测试项目',totals:[],assessments,analysisCoverage:'COMPLETE'} as any);

test('same original source merges an expense line and its batch purpose opinion, keeping all evidence',()=>{
  const row=source('task-a','excel/sheet:1/row:53','宣传接待餐费6000元');
  const scanned=source('task-b','pdf/page:2/ocr/part:1/line:4','宣传接待餐费6000元，拟从本项目支出');
  const semanticQuote={...scanned,locator:'pdf/page:2/ocr/part:1/record:0:35'};
  const policy=source('task-c','pdf/page:1/line:1','不得列支宣传接待费');
  const rows=budgetPriority(reference([line('宣传接待餐费','OVER_APPROVAL',[row,scanned])],
    [risk('SEMANTIC_PURPOSE_SCOPE','宣传接待费超出批复用途',[semanticQuote,policy])]));
  assert.equal(rows.length,1);
  assert.equal(rows[0].code,'SEMANTIC_PURPOSE_SCOPE');
  assert.deepEqual(new Set(rows[0].sources.map((e:any)=>e.taskId)),new Set(['task-a','task-b','task-c']));
  assert.equal(rows[0].sources.filter((e:any)=>e.taskId==='task-b').length,1);
});

test('different expenses, approval steps and unrelated amount lines stay separate',()=>{
  const meal=source('task-a','excel/row:1','宣传接待餐费6000元');
  const repair=source('task-a','excel/row:2','设备维修费6000元');
  const rule=source('task-b','pdf/page:1','不得列支宣传接待费');
  const weak=source('task-c','docx/node:2','未完成安全方案审查，已进场实施');
  const rows=budgetPriority(reference([line('宣传接待餐费','OVER_APPROVAL',[meal]),line('设备维修费','OVER_APPROVAL',[repair])],
    [risk('SEMANTIC_PURPOSE_SCOPE','宣传接待餐费问题',[meal,rule]),risk('SEMANTIC_APPROVAL_CONDITION','安全审批未完成',[weak])]));
  assert.equal(rows.length,3);
  assert.deepEqual(new Set(rows.map(r=>r.code)),new Set(['LINE_OVER_APPROVAL','SEMANTIC_PURPOSE_SCOPE','SEMANTIC_APPROVAL_CONDITION']));
});
