import test from 'node:test';
import assert from 'node:assert/strict';
import {procurementReferenceView} from './procurementReferenceView.ts';

const item=(type:string,risk:string,detail:string)=>({checkType:type,checkName:type==='PROJECT_STATUS'?'采购项目状态':'预算金额',riskLevel:risk,status:risk==='INFO'?'PASSED':'FAILED',detail,suggestion:detail});
const report=(items:any[],extra:any={})=>({riskLevel:'WARNING',agentReview:{riskLevel:'WARNING',suggestion:'请确认采购项目当前审批状态',aiSummary:'采购项目尚未提交审批',items},quoteCheck:null,blacklist:{hits:[]},tender:{findings:[]},collusion:null,splitPurchase:null,...extra}) as any;
test('legacy status-only warning is removed without changing the saved report',()=>{
  const saved=report([item('PROJECT_STATUS','WARNING','采购项目尚未进入提交或审批通过状态'),item('BUDGET_AMOUNT','INFO','金额有效')]);
  const before=JSON.stringify(saved),view=procurementReferenceView(saved);
  assert.equal(view.riskLevel,'INFO');assert.equal(view.agentReview.items.length,1);assert.deepEqual(view.lines,[]);
  assert.doesNotMatch(view.agentReview.suggestion,/审批|提交/);assert.equal(view.agentReview.aiSummary,null);assert.equal(JSON.stringify(saved),before);
});
test('a real budget risk survives legacy workflow removal and names its location',()=>{
  const view=procurementReferenceView(report([item('PROJECT_STATUS','WARNING','尚未审批'),item('BUDGET_AMOUNT','CRITICAL','预算金额缺失')],{riskLevel:'CRITICAL'}));
  assert.equal(view.riskLevel,'CRITICAL');assert.match(view.lines.join('；'),/智能体预审 · 预算金额：预算金额缺失/);assert.doesNotMatch(view.lines.join('；'),/审批/);
});
test('tender, blacklist and split findings identify the affected section and content',()=>{
  const view=procurementReferenceView(report([],{agentReview:{items:[],riskLevel:'INFO'},riskLevel:'CRITICAL',blacklist:{hits:[{alertLevel:'CRITICAL',supplierName:'合成供应商',blacklistReason:'资格受限'}]},tender:{findings:[{riskLevel:'WARNING',riskCategory:'资格限制',matchedText:'指定品牌',sourceLocator:'第3段'}]},splitPurchase:{riskLevel:'WARNING',findings:[{riskLevel:'WARNING',supplierName:'合成供应商',totalAmount:1200,thresholdAmount:1000,thresholdName:'限额'}]}}));
  assert.equal(view.riskLevel,'CRITICAL');assert.match(view.lines.join('\n'),/黑名单比对：合成供应商/);assert.match(view.lines.join('\n'),/招标文件合规审查 · 资格限制：指定品牌/);assert.doesNotMatch(view.lines.join('\n'),/第3段/);assert.match(view.lines.join('\n'),/拆分采购识别：合成供应商/);
});
test('unknown aggregate severity is retained with a specific historical-record explanation',()=>{
  const view=procurementReferenceView(report([],{riskLevel:'CRITICAL',agentReview:{riskLevel:'INFO',items:[]}}));
  assert.equal(view.riskLevel,'CRITICAL');assert.match(view.lines[0],/历史风险记录.*未能定位/);
});

test('price and collusion summaries explain the affected supplier and observed indicators',()=>{
  const view=procurementReferenceView(report([],{agentReview:{riskLevel:'INFO',items:[]},quoteCheck:{riskLevel:'WARNING',coefficientOfVariation:0.08,quotes:[{supplierName:'合成报价方',overBudget:true}]},collusion:{riskLevel:'WARNING',riskScore:55,maxDocumentSimilarity:0.82,duplicateIpGroups:1,duplicateHandlerGroups:0}}));
  assert.equal(view.riskLevel,'WARNING');assert.match(view.lines.join('\n'),/询比价分析：合成报价方的报价超过预算/);assert.match(view.lines.join('\n'),/围串标评分：风险评分 55 分，最高文件相似度 82.0%/);
});
