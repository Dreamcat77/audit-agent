import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const scenarios=JSON.parse(readFileSync(process.argv[2],'utf8'));
const get=name=>{const c=scenarios.find(c=>c.label===name);assert.ok(c,name);return c.files;};
const lines=name=>get(name).flatMap(f=>f.result.lines);
for(const c of scenarios)for(const f of c.files){assert.ok(!f.failureCode,c.label);assert.match(f.sourceSha256,/^[a-f0-9]{64}$/);}
for(const l of lines('自然语言跨文件比对')){
  assert.equal(l.declaredAmount,120000);assert.equal(l.approvalAmount,100000);assert.equal(l.status,'OVER_APPROVAL');
  assert.ok(l.opinion.includes('20000.00'));assert.equal(new Set(l.sources.map(s=>s.fileName)).size,2);
}
const uppercase=lines('财务大写及角分')[0];assert.equal(uppercase.declaredAmount,123456.78);assert.equal(uppercase.approvalAmount,100000);assert.ok(uppercase.opinion.includes('23456.78'));
const separate=lines('重复科目、不同项目');assert.equal(separate.length,2);
assert.ok(separate.some(l=>l.project==='甲项目'&&l.declaredAmount===9000&&l.status==='WITHIN_APPROVAL'));
assert.ok(separate.some(l=>l.project==='乙项目'&&l.declaredAmount===12000&&l.status==='OVER_APPROVAL'));
const missing=lines('只有申请，没有批复')[0];assert.equal(missing.declaredAmount,48000);assert.equal(missing.approvalAmount,null);assert.equal(missing.status,'INSUFFICIENT');
assert.equal(lines('无预算信息的通知').length,0);
for(const l of lines('批复版本冲突')){assert.equal(l.status,'CONFLICT');assert.equal(l.approvalAmount,null);}
const conflictSources=lines('批复版本冲突').flatMap(l=>l.sources.filter(s=>s.field==='approvalAmount'));
assert.ok(conflictSources.some(s=>s.value.includes('150000')));assert.ok(conflictSources.some(s=>s.value.includes('拾万元')));
assert.deepEqual(lines('非固定格式Word申请报告').map(l=>l.declaredAmount).sort((a,b)=>a-b),[16800,27200,168000]);
const excel=lines('Excel金额比对');assert.equal(excel.length,4);assert.equal(get('Excel金额比对')[0].extraction.issues.length,0);
for(const [i,amount] of [120000,62400,48000,35600].entries())assert.equal(excel[i].declaredAmount,amount);
assert.equal(excel[0].approvalAmount,107100);assert.equal(excel[0].status,'OVER_APPROVAL');
assert.ok(excel[0].opinion.includes('12900.00'));assert.ok(excel.slice(1).every(l=>l.status==='WITHIN_APPROVAL'));
const scan=lines('扫描PDF申报表');assert.ok(scan.some(l=>l.declaredAmount===168000));assert.ok(scan.some(l=>l.declaredAmount===16800));
assert.ok(get('扫描PDF申报表')[0].extraction.issues.length>0);assert.ok(scan.every(l=>l.status!=='WITHIN_APPROVAL'));
assert.equal(lines('旧版Word')[0].declaredAmount,31200);assert.equal(lines('旧版Word')[0].approvalAmount,null);
assert.ok(lines('旧版Excel').every(l=>l.declaredAmount!==62400));
console.log(JSON.stringify({scenarios:scenarios.length,files:scenarios.flatMap(c=>c.files).length,
  resultAssertions:'PASS',knownIncomplete:{scannedTable:{expectedDetails:3,verifiedDetails:scan.length},
  other:'PDF name breaks, duplicate sheets, and OCR O/0 identities require further verification'},
  meaning:'Assertions confirm the listed amounts and safe uncertainty, not complete recognition of every fixture.'}));
