import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
const args=process.argv.slice(2),allowReplay=args.includes('--allow-replay');
if(allowReplay)args.splice(args.indexOf('--allow-replay'),1);
const [resultsPath,expectationsPath,outputPath]=args;
const cases=JSON.parse(readFileSync(resultsPath,'utf8'));
const expected=JSON.parse(readFileSync(expectationsPath,'utf8'));
const records=[];
for(const item of expected){
 const scenario=cases.find(c=>c.label===item.label),checks=[];
 const files=scenario?.files??[],lines=files.flatMap(f=>f.result?.lines??[]);
 function check(name,fn){try{fn();checks.push({name,passed:true});}catch{checks.push({name,passed:false});}}
 check('全部文件实际读取且没有技术失败',()=>{
  assert.ok(scenario);assert.equal(files.length,item.fileCount);
  for(const f of files){assert.ok(!f.failureCode);assert.match(f.sourceSha256,/^[a-f0-9]{64}$/);assert.ok(f.result);
   if(!allowReplay){assert.equal(f.modelResultReused,false);assert.equal(f.sourceTextReused,false);}}
 });
 for(const c of item.checks)check(JSON.stringify(c),()=>{
  if(c.kind==='exact')assert.ok(lines.some(l=>(c.project==null||l.project===c.project)&&l.subject===c.subject&&
   l.declaredAmount===c.declaredAmount&&l.approvalAmount===c.approvalAmount&&l.status===c.status));
  else if(c.kind==='forbidAmount')assert.ok(lines.every(l=>!c.values.includes(l.declaredAmount)&&!c.values.includes(l.approvalAmount)));
  else if(c.kind==='forbidDeclared')assert.ok(lines.every(l=>!c.values.includes(l.declaredAmount)));
  else if(c.kind==='noWithin')assert.ok(lines.every(l=>l.status!=='WITHIN_APPROVAL'));
  else if(c.kind==='noPairedAmounts')assert.ok(lines.every(l=>l.declaredAmount==null||l.approvalAmount==null));
  else if(c.kind==='noAmounts')assert.ok(lines.every(l=>l.declaredAmount==null&&l.approvalAmount==null));
  else if(c.kind==='hasStatus')assert.ok(lines.some(l=>l.status===c.status));
  else if(c.kind==='hasUncertainty')assert.ok(lines.some(l=>['CONFLICT','INVALID_AMOUNT','INSUFFICIENT'].includes(l.status))||files.some(f=>f.extraction?.issues?.length));
  else throw Error('Unknown expectation');
 });
 records.push({label:item.label,passed:checks.every(c=>c.passed),checks,
  files:files.length,modelOperations:files.reduce((n,f)=>n+(f.extraction?.acknowledgedModelResults?.length??0),0),
  elapsedMillis:files.reduce((n,f)=>n+(f.elapsedMillis??0),0),
  lines:lines.map(({project,subject,declaredAmount,approvalAmount,status,opinion})=>({project,subject,declaredAmount,approvalAmount,status,opinion})),
  issues:files.flatMap(f=>(f.extraction?.issues??[]).map(i=>({file:f.fileName,...i})))});
}
const result={cases:records.length,passed:records.filter(r=>r.passed).length,
 failed:records.filter(r=>!r.passed).length,records};
writeFileSync(outputPath,JSON.stringify(result,null,2));
console.log(JSON.stringify({cases:result.cases,passed:result.passed,failed:result.failed,
 failedLabels:records.filter(r=>!r.passed).map(r=>r.label)}));
// A failing expectation must remain visible to automation; never rewrite expected values from results.
if(result.failed)process.exitCode=1;
