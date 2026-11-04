// Production Chromium acceptance: upload the caller's twelve fixtures without business form fields.
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
const require=createRequire(new URL('../../frontend/package.json',import.meta.url));
const {chromium}=require('playwright-core');
let raw='';for await(const chunk of process.stdin)raw+=chunk;
const config=JSON.parse(raw);raw='';
const root=fileURLToPath(new URL('../..',import.meta.url));
const out=path.join(root,'docs/unified-material-refactor/evidence');fs.mkdirSync(out,{recursive:true});
const identityPath=path.join(root,'.runtime/unified-browser-operation.json');
const ensure=(ok,reason)=>{if(!ok)throw new Error(reason);};
const browser=await chromium.launch({executablePath:config.executable,headless:true,args:['--no-first-run','--disable-background-networking']});
const context=await browser.newContext({viewport:{width:1440,height:1100}}),page=await context.newPage(),errors=[];
page.on('pageerror',error=>errors.push(error.name));
const api=async url=>{const r=await context.request.get(config.base+url);ensure(r.status()===200,'READ_HTTP_'+r.status());return(await r.json()).data;};
const fingerprint=value=>crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex');
try {
  await page.goto(config.base,{waitUntil:'networkidle'});
  await page.getByPlaceholder('请输入用户名').fill(config.username);
  await page.getByPlaceholder('请输入密码').fill(config.password);delete config.password;
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.getByRole('button',{name:'采购审查',exact:true}).waitFor({timeout:30000});
  await page.getByRole('button',{name:'采购审查',exact:true}).click();
  let region=page.getByRole('region',{name:'采购材料批次自动审查',exact:true});await region.waitFor();
  ensure(await region.locator('input:not([type=file])').count()===0,'PROCUREMENT_BUSINESS_FORM_RETURNED');
  ensure(await page.getByRole('region',{name:'正式采购需求与审批管理',exact:true}).count()===0,'FORMAL_MANAGEMENT_NOT_INITIALLY_COLLAPSED');
  const paths=fs.readdirSync(config.fixtures).filter(n=>/\.(pdf|docx)$/i.test(n)).sort().map(n=>path.join(config.fixtures,n));
  ensure(paths.length===12,'EXPECTED_TWELVE_FIXTURES');
  const manifest=paths.map(p=>({name:path.basename(p),sha256:crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')}));
  let id;
  if(fs.existsSync(identityPath)) {
    const saved=JSON.parse(fs.readFileSync(identityPath,'utf8'));
    ensure(saved.base===config.base&&fingerprint(saved.manifest)===fingerprint(manifest),'BROWSER_IDENTITY_MANIFEST_DRIFT');
    if(saved.caseId)id=saved.caseId;
    else if(saved.key){const recovered=await api('/api/v1/procurement/cases/operations/'+saved.key);ensure(recovered,'UNCERTAIN_UPLOAD_REQUIRES_SAME_OPERATION_RECOVERY');id=recovered.id;}
  }
  if(!id) {
    const receipt=page.waitForResponse(r=>r.url().endsWith('/api/v1/procurement/cases')&&r.request().method()==='POST',{timeout:180000});
    page.on('request',request=>{
      if(request.url().endsWith('/api/v1/procurement/cases')&&request.method()==='POST')
        fs.writeFileSync(identityPath,JSON.stringify({base:config.base,key:request.headers()['idempotency-key'],manifest}));
    });
    await region.locator('input[type=file]').setInputFiles(paths);
    const response=await receipt;ensure(response.ok(),'UPLOAD_HTTP_'+response.status());
    const caseValue=(await response.json()).data;id=caseValue.id;
    const saved=JSON.parse(fs.readFileSync(identityPath,'utf8'));fs.writeFileSync(identityPath,JSON.stringify({...saved,caseId:id}));
  }
  console.log(JSON.stringify({phase:'UPLOAD_ACCEPTED',caseId:id,fileCount:12,noBusinessFields:true}));
  const deadline=Date.now()+1800000;let value,previous;
  do {
    value=await api('/api/v1/procurement/cases/'+id);
    const marker=JSON.stringify({state:value.status,extracted:value.documents.filter(d=>d.status==='EXTRACTED').length,
      modules:value.report?.modules});
    if(marker!==previous){console.log(marker);previous=marker;}
    if(!['UPLOADING','QUEUED','RUNNING'].includes(value.status))break;
    ensure(Date.now()<deadline,'CASE_TERMINAL_DEADLINE');await new Promise(r=>setTimeout(r,2500));
  }while(true);
  ensure(value.documents.every(d=>d.status==='EXTRACTED'),'SOME_ORIGINAL_FIXTURES_NOT_EXTRACTED');
  const report=value.report;ensure(String(report.budget)==='222600','BUDGET_WRONG');
  ensure(String(report.awardAmount)==='212000','AWARD_WRONG');
  ensure(report.offers.length===3,'THREE_QUOTES_MISSING');
  ensure(JSON.stringify(report.offers.map(o=>Number(o.amount)).sort((a,b)=>a-b))==='[212000,216400,219800]','QUOTE_AMOUNTS_WRONG');
  ensure(report.offers.every(o=>o.lineValidation==='VERIFIED'),'QUOTE_LINE_ARITHMETIC_UNPROVEN');
  ensure(report.modules.QUOTE==='COMPLETED','QUOTE_MODULE_INCOMPLETE');
  for(const module of ['AGENT_PRECHECK','TENDER_COMPLIANCE'])ensure(['COMPLETED','PARTIAL'].includes(report.modules[module]),module+'_NOT_EXECUTED');
  for(const module of ['BLACKLIST','COLLUSION','SPLIT_PURCHASE'])ensure(report.modules[module]==='NOT_EXECUTED','UNEXECUTED_MODULE_FALSE_CLEAR');
  let references=0;
  for(const doc of value.documents) {
    const evidence=await api(`/api/v1/procurement/cases/${id}/files/${doc.id}/evidence`);
    const local=manifest.find(m=>m.name===doc.name);ensure(local&&local.sha256===evidence.sha256,'SOURCE_HASH_MISMATCH');
    ensure(evidence.segments.length>0,'SOURCE_TEXT_MISSING');
    const original=await context.request.get(`${config.base}/api/v1/procurement/cases/${id}/files/${doc.id}/original`);
    ensure(original.status()===200&&crypto.createHash('sha256').update(await original.body()).digest('hex')===local.sha256,'ORIGINAL_DOWNLOAD_HASH_MISMATCH');
    for(const fact of evidence.facts.filter(f=>f.verification==='SOURCE_VERIFIED')) {
      ensure(evidence.segments.some(s=>s.locator===fact.locator&&s.text.includes(fact.excerpt)),'UNGROUNDED_SAVED_FACT');references++;
    }
  }
  await page.reload({waitUntil:'networkidle'});
  await page.getByRole('button',{name:'采购审查',exact:true}).click();
  region=page.getByRole('region',{name:'采购材料批次自动审查',exact:true});
  await region.locator('select').selectOption(id);
  await region.getByRole('status').filter({hasText:value.caseNumber}).waitFor();
  await region.getByRole('region',{name:'本批采购审查结果',exact:true}).waitFor();
  ensure(fingerprint((await api('/api/v1/procurement/cases/'+id)).report)===fingerprint(report),'REFRESH_CHANGED_REPORT');
  await region.screenshot({path:path.join(out,'procurement-twelve-result.png')});
  await region.getByText('本批 12 份材料及来源',{exact:true}).click();
  await region.getByRole('button',{name:'查看证据',exact:true}).first().click();
  await region.getByText('解析原文（含非结构化技术要求）',{exact:true}).waitFor();
  ensure(errors.length===0,'BROWSER_RUNTIME_ERROR');
  const result={passed:true,browser:browser.version(),production:true,routeMocks:false,realHttp:true,realModel:true,
    realDm8:true,caseId:id,fileCount:12,sourceVerifiedFacts:references,noBusinessFields:true,automaticUpload:true,
    refreshPreserved:true,evidenceReadable:true,modules:report.modules,pageErrors:errors};
  fs.writeFileSync(path.join(out,'browser-twelve-verification.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
} catch(error) {
  await page.screenshot({path:path.join(out,'browser-failure.png'),fullPage:true}).catch(()=>{});
  console.error('BROWSER_ACCEPTANCE_FAILED '+error.message);process.exitCode=1;
} finally {await browser.close();}
