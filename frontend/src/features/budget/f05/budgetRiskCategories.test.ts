import test from 'node:test';
import assert from 'node:assert/strict';
import {budgetRiskCategory,groupRiskCandidates,candidateKey,repeatedMaterialGroups} from './budgetRiskCategories.ts';
import {riskOperation} from './budgetRiskOperation.ts';
test('category is based on material subject, preserving unknown subjects and distinct file references',()=>{
  assert.equal(budgetRiskCategory('房屋装饰工程'),'装潢');assert.equal(budgetRiskCategory('网络设备'),'电子');assert.equal(budgetRiskCategory('培训费'),'其他');assert.equal(budgetRiskCategory(null),'科目待核对');
  const rows=['file1','file2'].map(taskId=>({source:{taskId,lineIndex:0},fileName:taskId,line:{project:'同名项目',subject:'网络设备'}})) as any;
  const groups=groupRiskCandidates(rows);assert.equal(groups.find(g=>g.name==='电子')?.rows.length,2);assert.notEqual(candidateKey(rows[0]),candidateKey(rows[1]));assert.equal(groups.find(g=>g.name==='工程')?.rows.length,0);
});

test('repeated material warning only groups matching records from different files and never changes selection',()=>{
  const row=(taskId:string,lineIndex=0,overrides:any={})=>({source:{taskId,lineIndex},fileName:taskId,line:{project:'设备更新',subject:'电子设备',year:'2026',department:'信息中心',declaredAmount:12000,...overrides}}) as any;
  const selected=[row('a'),row('b'),row('c',0,{declaredAmount:15000}),row('d',0,{department:'财务处'}),row('a',1)];
  const groups=repeatedMaterialGroups(selected);
  assert.equal(groups.length,1);
  assert.deepEqual(groups[0].map(candidateKey),['a:0','b:0','a:1']);
  assert.equal(selected.length,5);
  assert.equal(repeatedMaterialGroups([row('a'),row('a',1)]).length,0);
  assert.equal(repeatedMaterialGroups([row('a',0,{declaredAmount:null}),row('b',0,{declaredAmount:null})]).length,0);
  assert.equal(repeatedMaterialGroups([row('a'),row('b',0,{year:'2025'})]).length,0);
  assert.equal(repeatedMaterialGroups([row('a',0,{project:null}),row('b',0,{project:null})]).length,0);
});

test('arbitrary uploaded subjects never create more categories or overwrite original subjects',()=>{
  const rows=Array.from({length:500},(_,i)=>({source:{taskId:`file${i}`,lineIndex:0},fileName:'fixture',line:{subject:`培训场地${i}`}})) as any;
  const groups=groupRiskCandidates(rows);
  assert.deepEqual(groups.map(g=>g.name),['电子','工程','装潢','其他','科目待核对']);
  assert.equal(groups.find(g=>g.name==='其他')?.rows.length,500);
  assert.equal(rows[499].line.subject,'培训场地499');
});
test('uncertain risk analysis retains identity across reload and selection order',()=>{
  const map=new Map<string,string>();const storage={getItem:(k:string)=>map.get(k)??null,setItem:(k:string,v:string)=>map.set(k,v)} as Storage;
  assert.equal(riskOperation(['a:1','b:2'],storage).id,riskOperation(['b:2','a:1'],storage).id);
  assert.notEqual(riskOperation(['a:2'],storage).id,riskOperation(['a:1','b:2'],storage).id);
});
