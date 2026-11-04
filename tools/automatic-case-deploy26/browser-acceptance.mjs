import {createRequire} from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
const require=createRequire(new URL('../../frontend/package.json',import.meta.url));
const {chromium}=require('playwright-core');
let input=''; for await(const chunk of process.stdin) input+=chunk;
const config=JSON.parse(input);
const accounts=JSON.parse(fs.readFileSync(config.credentials,'utf8'));
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1050}});
const page=await context.newPage();
const errors=[];page.on('pageerror',e=>{errors.push(e.message);console.log('PAGE_ERROR',e.message)});
const assert=(value,message)=>{if(!value)throw Error(message)};
const save=(name,value)=>fs.writeFileSync(path.join(config.evidence,name+'.json'),JSON.stringify(value,null,2));
try {
  const initial = await page.goto(config.base,{waitUntil:'networkidle'});
  console.log('INITIAL_HTTP',initial.status(),(await page.locator('body').innerText()).slice(0,220));
  const platform=await page.evaluate(()=>({secure:window.isSecureContext,randomUUID:typeof crypto.randomUUID,getRandomValues:typeof crypto.getRandomValues}));
  console.log('BROWSER_PLATFORM',JSON.stringify(platform));save('browser-platform',platform);
  await page.getByPlaceholder('请输入用户名').fill(accounts.primary.username);
  await page.locator('input[type=password]').fill(accounts.primary.password);
  const loginResponse=page.waitForResponse(r=>r.url().endsWith('/api/v1/auth/login')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'登录',exact:true}).click();
  const login=await loginResponse;console.log('LOGIN_HTTP',login.status(),login.status()===200?'success':await login.text());
  console.log('COOKIE_ATTRIBUTES',JSON.stringify((await context.cookies()).map(c=>({name:c.name,httpOnly:c.httpOnly,secure:c.secure,sameSite:c.sameSite}))));
  const panel=page.locator('section[aria-label="上传材料自动询比价"]');
  await panel.waitFor({timeout:30000});
  if(config.action==='inspect') {
    console.log((await page.locator('body').innerText()).slice(0,3500));
    save('browser-login',{realBrowser:true,platform,pageErrors:errors,cookies:(await context.cookies()).map(c=>({name:c.name,httpOnly:c.httpOnly,secure:c.secure,sameSite:c.sameSite}))});
    await page.screenshot({path:path.join(config.evidence,'browser-login.png'),fullPage:true});
  } else if(config.action==='acceptance' || config.action==='reopen') {
    assert(await panel.locator('input:not([type=file])').count()===0,'unexpected business input');
    let id, operationKey;
    const progress=[];
    page.on('response',async response=>{
      if(id&&response.url().endsWith('/api/v1/procurement/cases/'+id)&&response.status()===200) {
        const value=(await response.json()).data;
        const item={state:value.status,extracted:value.documents.filter(d=>d.status==='EXTRACTED').length,total:value.documents.length};
        if(JSON.stringify(item)!==JSON.stringify(progress.at(-1))) {
          progress.push(item);save('browser-progress',progress);console.log('CASE_PROGRESS',JSON.stringify(item));
        }
      }
    });
    if(config.action==='acceptance') {
      assert(!fs.existsSync(path.join(config.evidence,'browser-upload.json')),'a recorded upload exists; reopen it instead of charging another model run');
      const files=fs.readdirSync(config.fixtures).filter(n=>/\.(pdf|docx)$/i.test(n)).sort().map(n=>path.join(config.fixtures,n));
      assert(files.length===12,'expected twelve fixtures');
      const uploadResponse=page.waitForResponse(r=>r.url().endsWith('/api/v1/procurement/cases')&&r.request().method()==='POST');
      await panel.getByLabel('选择采购材料并开始分析').setInputFiles(files);
      const uploaded=await uploadResponse, body=await uploaded.json();
      save('browser-upload',{httpStatus:uploaded.status(),body});
      assert(uploaded.status()===200&&body.code===200,'upload rejected');id=body.data.id;
      operationKey=uploaded.request().headers()['idempotency-key'];
      save('browser-operation',{caseId:id,operationKey});
      console.log('BROWSER_CREATED_CASE',id);
      await panel.getByRole('status').filter({hasText:/已提取/}).waitFor();
      await page.screenshot({path:path.join(config.evidence,'browser-processing.png'),fullPage:true});
      await page.reload({waitUntil:'networkidle'});await panel.waitFor();
      assert(await panel.locator('select').inputValue()===id,'processing refresh lost case');
    } else {
      ({caseId:id,operationKey}=JSON.parse(fs.readFileSync(path.join(config.evidence,'browser-operation.json'),'utf8')));
      await panel.locator('select').selectOption(id);
    }
    await page.waitForFunction(()=>{
      const s=document.querySelector('section[aria-label="上传材料自动询比价"] [role=status]');
      return s&&/^(本轮分析完成|部分完成|执行失败|数据不足|结果待核实)/.test(s.textContent);
    },null,{timeout:1200000});
    const response=await page.request.get(config.base+'/api/v1/procurement/cases/'+id);
    const completed=await response.json();save('browser-dm8-actual-result',completed);
    assert(response.status()===200&&completed.data.status==='COMPLETED','case did not complete: '+completed.data.status);
    const c=completed.data, report=c.report;
    assert(c.documents.length===12&&c.documents.every(d=>d.status==='EXTRACTED'),'not all documents extracted');
    assert(Number(report.budget)===222600&&Number(report.awardAmount)===212000,'amount role mismatch');
    const expected=new Map([['澄川星河办公设备有限公司',212000],['澄川明川数字设备有限公司',216400],['澄川启衡信息设备有限公司',219800]]);
    assert(report.offers.length===3&&report.offers.every(o=>expected.get(o.supplier)===Number(o.amount)),'supplier quotes mismatch');
    assert(report.offers.reduce((s,o)=>s+o.lines.length,0)===9,'quote line count mismatch');
    assert(report.offers.every(o=>o.lineValidation==='VERIFIED'),'line verification failed');
    assert(report.modules.AGENT_PRECHECK==='COMPLETED'&&report.modules.TENDER_COMPLIANCE==='COMPLETED','review modules incomplete');
    assert(Number(report.calculation.averageQuote)===216066.67,'quote average mismatch');
    const allEvidence=[]; const byId=new Map();
    for(const doc of c.documents) {
      const res=await page.request.get(config.base+'/api/v1/procurement/cases/'+id+'/files/'+doc.id+'/evidence');
      assert(res.status()===200,'evidence inaccessible'); const e=await res.json();
      allEvidence.push(e);byId.set(doc.id,e.data);
      const local=fs.readFileSync(path.join(config.fixtures,doc.name));
      assert(createHash('sha256').update(local).digest('hex')===e.data.sha256,'source hash mismatch');
    }
    save('browser-dm8-actual-evidence',allEvidence);
    const normalize=s=>s.replace(/\s+/gu,'');let citations=0;
    for(const review of Object.values(report.reviews))for(const finding of [...review.findings,...review.checks])for(const source of finding.evidence) {
      const e=byId.get(source.documentId);assert(e&&e.sha256===source.sha256,'citation hash mismatch');
      assert(e.segments.some(s=>s.locator===source.locator&&normalize(s.text).includes(normalize(source.excerpt))),'citation not in located original');citations++;
    }
    const facts=allEvidence.flatMap(e=>e.data.facts);
    assert(facts.some(f=>f.value==='FZ-CG-2026-001'),'project number missing');
    const tech=c.documents.find(d=>d.name.startsWith('02_'));const techSource=byId.get(tech.id);
    assert(/16\s*GB/i.test(techSource.segments.map(s=>s.text).join(''))&&/512\s*GB/i.test(techSource.segments.map(s=>s.text).join('')),'technical source unavailable');
    assert(report.reviews.TENDER_COMPLIANCE.scope.some(s=>s.documentId===tech.id&&s.suppliedChars>0),'technical source not supplied to F10');
    assert((await panel.innerText()).includes('黑名单、围串标、拆分采购：本次未执行'),'scope disclosure absent');
    await panel.getByRole('button',{name:'查看证据',exact:true}).first().click();await panel.locator('details[open] blockquote').first().waitFor();
    await page.screenshot({path:path.join(config.evidence,'browser-completed-evidence.png'),fullPage:true});
    await panel.getByRole('button',{name:'查看证据',exact:true}).nth(c.documents.indexOf(tech)).click();
    await panel.locator('details[open]').getByText('解析原文（含非结构化技术要求）',{exact:true}).click();
    await page.screenshot({path:path.join(config.evidence,'browser-technical-evidence.png'),fullPage:true});
    const downloadEvent=page.waitForEvent('download');await panel.getByRole('link',{name:'原始文件',exact:true}).first().click();
    const download=await downloadEvent;assert(await download.failure()===null,'original download failed');
    const downloaded=fs.readFileSync(await download.path());
    assert(createHash('sha256').update(downloaded).digest('hex')===allEvidence[0].data.sha256,'download hash mismatch');
    await page.reload({waitUntil:'networkidle'});await panel.getByRole('status').filter({hasText:'本轮分析完成'}).waitFor();
    assert(await panel.locator('select').inputValue()===id,'completed refresh lost case');
    save('browser-acceptance',{caseId:id,realBrowser:true,realModel:true,files:12,facts:facts.length,quoteLines:9,verifiedCitations:citations,
      progress,processingRefreshRecovered:true,completedRefreshRecovered:true,originalDownloadShaVerified:true,technicalFactCount:tech.factCount,technicalFullTextSupplied:true,modules:report.modules,pageErrors:errors});
    assert(errors.length===0,'browser runtime errors');console.log('BROWSER_ACCEPTANCE_PASS case='+id);
  } else throw Error('unsupported action');
} catch(e) {await page.screenshot({path:path.join(config.evidence,'browser-failure.png'),fullPage:true}).catch(()=>{});throw e;}
finally {await browser.close();}
