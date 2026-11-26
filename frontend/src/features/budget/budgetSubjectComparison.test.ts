import assert from 'node:assert/strict';
import {test} from 'node:test';
import {budgetComparisonRows,type BudgetComparisonLine} from './budgetComparisonRows.ts';

const line=(subject:string,status='WITHIN_APPROVAL'):BudgetComparisonLine=>({project:'运维项目',subject,subjectCode:'A01',year:'2027',department:'保障中心',declaredAmount:100,approvalAmount:100,status,opinion:'原始核对说明',sources:[]});
test('hundred-row budgets retain every row and evidence; attention sorts before normal without mutating source',()=>{
  const rows=Array.from({length:120},(_,i)=>line(`服务${i+1}`,i===99?'OVER_APPROVAL':'WITHIN_APPROVAL'));
  const result=budgetComparisonRows(rows,'','all');
  assert.equal(result.length,120);assert.equal(result[0].line.subject,'服务100');assert.equal(rows[0].subject,'服务1');
  assert.equal(new Set(result.map(r=>r.index)).size,120);
});
test('filters distinguish unverified comparisons from cleared checks and support code/name searches',()=>{
  const a=line('平台运行维护'),b=line('现场支持','INSUFFICIENT'),c=line('设备租赁','OVER_APPROVAL');
  assert.deepEqual(budgetComparisonRows([a,b,c],'','attention').map(r=>r.line),[b,c]);
  assert.deepEqual(budgetComparisonRows([a,b,c],'','clear').map(r=>r.line),[a]);
  assert.deepEqual(budgetComparisonRows([a,b,c],' 平台 运行 ','all').map(r=>r.line),[a]);
  assert.equal(budgetComparisonRows([a,b,c],'ａ０１','all').length,3);
  assert.equal(budgetComparisonRows([a,b,c],'不存在','all').length,0);
});
