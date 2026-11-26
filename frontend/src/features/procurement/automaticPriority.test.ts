import assert from 'node:assert/strict';
import {test} from 'node:test';
import {mergePriorityFindings,financialPriorityFindings,comparisonForOffer} from './automaticPriority.ts';

const source=(documentId:string,excerpt:string)=>({id:documentId,documentId,fileName:documentId+'.pdf',sha256:'a',locator:'p1',excerpt});
const a=source('tender','全部费用'),b=source('response','不含耗材和备件');
const finding=(title:string,code='QUOTE_SCOPE',evidence=[a,b])=>({module:'A',code,level:'CRITICAL',title,explanation:'有范围偏差',suggestion:'核对范围',evidence});

test('one ceiling excess with the same offer and optional total retains all members',()=>{
 const offer=source('offer','测试公司 | 1,230,000.00 | 耗材、备件另行收费');
 const ceiling=source('tender','最高限价1200000元，预算1260000元。');
 const total=source('offer','合计 | 1,230,000.00');
 const deterministic=finding('报价超过最高限价','QUOTE_CEILING',[offer,total,ceiling]);
 const semantic=finding('供应商报价超出最高限价','BUDGET_CONFLICT',[ceiling,offer]);
 const rows=mergePriorityFindings([deterministic,semantic]);
 assert.equal(rows.length,1);assert.equal(rows[0].members.length,2);assert.equal(rows[0].evidence.length,3);
 for(const changed of [{...semantic,title:'供应商报价超出预算'},
   {...semantic,evidence:[ceiling,{...offer,locator:'row2'}]},
   {...semantic,evidence:[ceiling,offer,source('other','另一个供应商报价')]}])
  assert.equal(mergePriorityFindings([deterministic,changed]).length,2);
});

test('same explicit excluded costs can cite two requirements in the same tender',()=>{
 const response=source('response','项目 | 公司 | 1230000 | 仅含人工与税费；耗材、备件另行收费');
 const p4={...source('tender','报价应包括人工、税费、交通、耗材、备件及完成全部服务的费用。'),locator:'p4'};
 const p5={...source('tender','报价须包含本合同期全部耗材和备件，不接受仅报人工费后另行收费。'),locator:'p5'};
 const first=finding('报价范围与采购要求不一致','QUOTE_SCOPE',[p4,response]);
 const second={...finding('供应商报价范围与采购要求不一致','QUOTE_SCOPE',[p5,response]),module:'B'};
 const rows=mergePriorityFindings([first,second]);
 assert.equal(rows.length,1);assert.equal(rows[0].members.length,2);assert.equal(rows[0].evidence.length,3);
 for(const changed of [{...p5,excerpt:'报价须包含税费。'}, {...p5,documentId:'other-tender'},
   {...p5,excerpt:'合同履行期限须包含12个月。'}])
  assert.equal(mergePriorityFindings([first,{...second,evidence:[changed,response]}]).length,2);
 assert.equal(mergePriorityFindings([first,{...second,evidence:[p5,{...response,locator:'row2'}]}]).length,2);
});

test('same regional restriction may carry an extra unchanged-contact notice without becoming two issues',()=>{
 const normal=source('scan','资格要求：无地域注册限制。');
 const restriction={...source('scan','资格条件补充：供应商必须在本市注册满三年，否则不得参与。'),locator:'page105'};
 const notice=source('notice','本公告仅修正联系人，不调整预算、限价、评分或资格。');
 const first=finding('资格条件存在地域注册限制','RESTRICTIVE_CONDITION',[normal,restriction]);
 const second={...finding(first.title,first.code,[normal,restriction,notice]),module:'B',explanation:'更正公告未修改该限制'};
 const rows=mergePriorityFindings([first,second]);
 assert.equal(rows.length,1);assert.equal(rows[0].members.length,2);assert.equal(rows[0].evidence.length,3);
 assert(rows[0].evidence.some(e=>e.documentId==='notice'));
 assert.equal(first.evidence.length,2);assert.equal(second.evidence.length,3);
 for(const extra of [source('notice','本公告删除原资格要求，允许各地供应商参加。'),
   source('notice','供应商还必须在本省设立办事机构。')])
  assert.equal(mergePriorityFindings([first,{...second,evidence:[normal,restriction,extra]}]).length,2);
 assert.equal(mergePriorityFindings([first,{...second,evidence:[normal,{...restriction,locator:'page106'},notice]}]).length,2);
 assert.equal(mergePriorityFindings([first,{...second,code:'QUOTE_SCOPE'}]).length,2);
});

test('same supported cost-scope finding merges across module wording and preserves acknowledgment identities',()=>{
 const rows=mergePriorityFindings([
   finding('供应商报价未覆盖采购文件要求的耗材和备件'),
   {...finding('供应商报价未覆盖磋商文件要求的耗材和备件'),module:'B',explanation:'需要补充报价'}]);
 assert.equal(rows.length,1);assert.equal(rows[0].members.length,2);assert.equal(rows[0].evidence.length,2);
 assert.equal(rows[0].members[1].explanation,'需要补充报价');
 assert.equal(mergePriorityFindings([finding('供应商报价未覆盖采购文件要求的耗材和备件'),
   {...finding('供应商报价未包含采购文件要求的耗材和备件'),module:'B'}]).length,1);
 assert.equal(mergePriorityFindings([finding('资格条款存在地域限制','RESTRICTIVE_CONDITION',[a]),
   {...finding('资格条件存在地域限制','RESTRICTIVE_CONDITION',[a]),module:'B'}]).length,1);
});
test('different suppliers, scope aspects and evidence locations remain independent',()=>{
 assert.equal(mergePriorityFindings([finding('耗材未包含'),finding('税费未包含')]).length,2);
 assert.equal(mergePriorityFindings([finding('耗材未包含'),finding('耗材未包含','QUOTE_SCOPE',[a,source('other','不含耗材和备件')])]).length,2);
 assert.equal(mergePriorityFindings([finding('服务期不足'),finding('服务期不足','QUOTE_SCOPE',[a,{...b,locator:'p2'}])]).length,2);
});
test('quote-scope wording variants merge only when they assert the same omission and cite the same material',()=>{
 const scope=finding('供应商报价范围与采购需求不符');
 const alternative={...finding('报价范围与需求范围不一致'),module:'B'};
 assert.equal(mergePriorityFindings([scope,alternative]).length,1);
 const omitted=finding('响应报价未包含采购文件要求的备件费用');
 const restatement={...finding('报价未含备件与采购要求不一致'),module:'B'};
 assert.equal(mergePriorityFindings([omitted,restatement]).length,1);
 assert.equal(mergePriorityFindings([omitted,{...restatement,title:'报价未含税费与采购要求不一致'}]).length,2);
 assert.equal(mergePriorityFindings([omitted,{...restatement,evidence:[a,{...b,documentId:'different-supplier'}]}]).length,2);
});
test('pure scope mislabeled as quantity merges with same scope finding, actual quantity stays separate',()=>{
 assert.equal(mergePriorityFindings([finding('供应商报价范围与采购需求不符'),{...finding('报价范围与采购需求不一致','QUANTITY_CONFLICT'),module:'B'}]).length,1);
 assert.equal(mergePriorityFindings([finding('报价数量与采购需求不一致','QUANTITY_CONFLICT'),finding('报价范围与采购需求不一致')]).length,2);
});
test('existing exact server comparisons enter priority even with semantic findings; never infer excess from a missing comparison',()=>{
 const rows=financialPriorityFindings({offers:[{documentId:'response',supplier:'供应商甲',amount:'670000'}],
   quoteComparisons:[{documentId:'response',status:'WITHIN_BUDGET',difference:'-10000'}],
   ceilingComparisons:[{documentId:'response',status:'OVER_CEILING',difference:'20000'}]},
   [{label:'报价',evidence:[b]},{label:'最高限价',evidence:[a]}]);
 assert.equal(rows.length,1);assert.equal(rows[0].code,'QUOTE_CEILING');
 assert.match(rows[0].explanation,/20000/);assert.equal(rows[0].evidence.length,2);
 assert.equal(financialPriorityFindings({offers:[{documentId:'response',supplier:'供应商甲',amount:'670000'}]},[]).length,0);
});
test('suppliers sharing one file retain their own comparison and evidence row',()=>{
 const offers=[{documentId:'sheet',supplier:'供应商甲',amount:'550000',sourceRow:'row2'},
   {documentId:'sheet',supplier:'供应商乙',amount:'590000',sourceRow:'row3'}];
 const comparisons=[{documentId:'sheet',sourceRow:'row2',supplier:'供应商甲',status:'WITHIN_CEILING',difference:'-30000'},
   {documentId:'sheet',sourceRow:'row3',supplier:'供应商乙',status:'OVER_CEILING',difference:'10000'}];
 assert.equal(comparisonForOffer(comparisons,offers[0],offers)?.status,'WITHIN_CEILING');
 assert.equal(comparisonForOffer(comparisons,offers[1],offers)?.status,'OVER_CEILING');
 assert.equal(comparisonForOffer([{documentId:'sheet',status:'WITHIN_CEILING',difference:'0'}],{...offers[0],sourceRow:undefined},offers),undefined);
 const evidence=[source('sheet','供应商甲 报价550000元'),source('sheet','供应商乙 报价590000元')];
 const rows=financialPriorityFindings({offers,ceilingComparisons:comparisons},evidence.map(e=>({label:'报价',evidence:[e]})));
 assert.equal(rows.length,1);assert.match(rows[0].title,/供应商乙/);assert.deepEqual(rows[0].evidence,[evidence[1]]);
});
