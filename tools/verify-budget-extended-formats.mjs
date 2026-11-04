import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
const [source,output]=process.argv.slice(2),cases=JSON.parse(readFileSync(source,'utf8'));
const records=[];
for(const c of cases){
 const file=c.files[0],lines=file.result?.lines??[],checks=[];
 const check=(name,fn)=>{try{fn();checks.push({name,passed:true});}catch{checks.push({name,passed:false});}};
 if(file.fileName==='expected-corrupt.docx'){
  check('损坏文件明确失败',()=>assert.ok(file.failureCode));
  check('未发布有效预算明细',()=>assert.equal(lines.length,0));
 }else{
  check('读取完成且保留原文哈希',()=>{assert.ok(!file.failureCode);assert.match(file.sourceSha256,/^[0-9a-f]{64}$/);assert.ok(file.sourceText?.length);});
  const duplicatedSheets=['sample.xls','sample.xlsx'].includes(file.fileName);
  if(duplicatedSheets){
   check('两张重复工作表均保留31200元原始申报',()=>assert.equal(
    file.extraction?.facts?.filter(r=>r.facts.some(f=>f.field==='declaredAmount'&&/^31200(?:元)?$/.test(f.value))).length,2));
   check('重复表不擅自累加或宣称已核对通过',()=>assert.ok(lines.every(l=>l.declaredAmount!==62400&&l.status!=='WITHIN_APPROVAL')));
  }else check('31200元科目申报已确认',()=>assert.ok(lines.some(l=>l.declaredAmount===31200)));
  check('项目总预算未冒充科目金额',()=>assert.ok(lines.every(l=>l.declaredAmount!==200000&&l.approvalAmount!==200000)));
  if(file.fileName==='sample.png'){
   check('O与0有歧义时不猜改编号且保留金额',()=>{
    const amountLines=lines.filter(l=>l.declaredAmount!=null||l.approvalAmount!=null);
    assert.ok(amountLines.length>0);assert.ok(amountLines.every(l=>l.projectCode==null));
    assert.ok(file.extraction?.issues?.some(i=>i.reasonCode==='OCR_IDENTITY_UNCERTAIN'));
    assert.ok(file.result?.summary?.includes('字母 O 与数字 0'));
   });
  }else check('有金额的结果保留正确项目编号',()=>assert.ok(lines.filter(l=>l.declaredAmount!=null||l.approvalAmount!=null)
   .every(l=>l.projectCode==='SYN-OFFICE-2026')));
  if(['sample.xls','sample.xlsx','sample-table.docx'].includes(file.fileName)){
   check('保留32760元参考批复',()=>assert.ok(lines.some(l=>l.approvalAmount===32760)||
    file.extraction?.facts?.some(r=>r.facts.some(f=>f.field==='approvalAmount'&&/32760/.test(f.value)))));
  }else{
   check('没有编造科目批复',()=>assert.ok(lines.every(l=>l.approvalAmount==null)));
  }
  if(file.fileName==='sample-table.docx')check('Word表格8条明细完整比对',()=>assert.equal(
   new Set(lines.filter(l=>l.declaredAmount===31200&&l.approvalAmount===32760&&l.status==='WITHIN_APPROVAL').map(l=>l.subject)).size,8));
 }
 records.push({label:c.label,file:file.fileName,passed:checks.every(c=>c.passed),checks,
  readMethod:file.readMethod,failureCode:file.failureCode??null,elapsedMillis:file.elapsedMillis,
  issues:file.extraction?.issues??[],lines});
}
const result={cases:records.length,passed:records.filter(r=>r.passed).length,failed:records.filter(r=>!r.passed).length,records};
writeFileSync(output,JSON.stringify(result,null,2));
console.log(JSON.stringify({cases:result.cases,passed:result.passed,failed:result.failed,failedLabels:records.filter(r=>!r.passed).map(r=>r.label)}));
if(result.failed)process.exitCode=1;
