import assert from 'node:assert/strict';
import {test} from 'node:test';
import {budgetAttention,budgetDifference,budgetDifferenceAmount} from './budgetDifferences.ts';
test('only actual difference statuses are highlighted; unknown is not an omission',()=>{
  assert.equal(budgetDifference('OVER_APPROVAL'),'超支');assert.equal(budgetDifference('UNAPPROVED_SUBJECT'),'疑似错支');assert.equal(budgetDifference('MISSING_DECLARATION'),'疑似漏项');
  for(const state of ['INSUFFICIENT','CONFLICT','INVALID_AMOUNT','PENDING','WITHIN_APPROVAL'])assert.equal(budgetDifference(state),null);
  assert.equal(budgetDifferenceAmount(100.01,100),'0.01');assert.equal(budgetDifferenceAmount(0.1,0.3),'-0.20');assert.equal(budgetDifferenceAmount(null,100),null);
});
test('attention wording names the actual difference and never turns missing information into a risk conclusion',()=>{
 const line=(status:string,d:number|null=120000,a:number|null=100000)=>({status,declaredAmount:d,approvalAmount:a});
 assert.equal(budgetAttention(line('OVER_APPROVAL')).headline,'超出批复 20,000.00 元');
 assert.equal(budgetAttention(line('OVER_APPROVAL',100.01,100)).headline,'超出批复 0.01 元');
 assert.equal(budgetAttention(line('OVER_APPROVAL',1,0)).headline,'超出批复 1.00 元');
 assert.equal(budgetAttention(line('UNAPPROVED_SUBJECT',5000,null)).label,'疑似错支');
 assert.equal(budgetAttention(line('MISSING_DECLARATION',null,8000)).label,'疑似漏项');
 for(const state of ['INSUFFICIENT','CONFLICT','INVALID_AMOUNT','UNKNOWN']){
  assert.equal(budgetAttention(line(state,null,null)).tone,'uncertain');
  assert.doesNotMatch(budgetAttention(line(state,null,null)).label,/超支|错支|漏项|通过/);
 }
 assert.equal(budgetAttention(line('WITHIN_APPROVAL',100,100)).tone,'normal');
});
