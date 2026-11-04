import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
const require=createRequire(new URL('../../frontend/package.json',import.meta.url));
const {chromium}=require('playwright-core');
let input='';for await(const chunk of process.stdin)input+=chunk;
const config=JSON.parse(input);
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000}});
const page=await context.newPage();
const errors=[];page.on('pageerror',error=>errors.push(error.message));
const assert=(condition,message)=>{if(!condition)throw new Error(message)};
try {
  await page.goto('http://127.0.0.1:19132',{waitUntil:'networkidle'});
  if(config.action==='inspect') {
    console.log((await page.locator('body').innerText()).slice(0,7000));
    console.log('INPUTS',JSON.stringify(await page.locator('input').evaluateAll(elements=>elements.map(e=>({type:e.type,placeholder:e.placeholder,name:e.name})))));
    await page.screenshot({path:path.join(config.evidence,'browser-initial.png'),fullPage:true});
  } else {
    await page.getByPlaceholder('请输入用户名').fill(config.username);
    await page.locator('input[type="password"]').fill(config.password);
    await page.getByRole('button',{name:'登录',exact:true}).click();
    await page.getByRole('button',{name:'采购审查',exact:true}).waitFor({timeout:30000});
    if(config.action==='login-inspect') {
      console.log((await page.locator('body').innerText()).slice(0,8000));
      await page.screenshot({path:path.join(config.evidence,'browser-after-login.png'),fullPage:true});
    } else if(config.action==='evidence-review') {
      const c=JSON.parse(fs.readFileSync(path.join(config.evidence,'browser-dm8-actual-result.json'),'utf8')).data;
      await page.getByRole('button',{name:'采购审查',exact:true}).click();
      const panel=page.locator('section[aria-label="上传材料自动询比价"]');await panel.waitFor();
      await panel.locator('select').selectOption(c.id);
      await panel.getByRole('status').filter({hasText:'本轮分析完成'}).waitFor();
      await panel.getByRole('button',{name:'查看证据',exact:true}).first().click();
      const excerpt=panel.locator('details[open] blockquote').first();await excerpt.waitFor();await excerpt.scrollIntoViewIfNeeded();
      assert((await excerpt.innerText()).length>0,'empty source excerpt');
      await page.screenshot({path:path.join(config.evidence,'browser-original-evidence.png'),fullPage:true});
      console.log('BROWSER_PERSISTED_EVIDENCE_REVIEW_PASS');
    } else if(config.action==='semantic-review') {
      const c=JSON.parse(fs.readFileSync(path.join(config.evidence,'browser-dm8-actual-result.json'),'utf8')).data;
      await page.getByRole('button',{name:'采购审查',exact:true}).click();
      const panel=page.locator('section[aria-label="上传材料自动询比价"]');await panel.waitFor();await panel.locator('select').selectOption(c.id);
      await panel.getByRole('status').filter({hasText:/^本轮分析完成/}).waitFor();
      const f10=panel.locator('section[aria-label="F10招标合规结果"]');
      await f10.locator('details > summary').filter({hasText:'已核对事实与分析范围'}).click();
      assert((await f10.innerText()).includes('技术'),'technical semantic observations missing');
      await f10.locator('details details > summary').first().click();
      assert((await f10.innerText()).includes('16GB') && (await f10.innerText()).includes('512GB'),'raw technical evidence not visible');
      await f10.scrollIntoViewIfNeeded();await page.screenshot({path:path.join(config.evidence,'browser-f10-semantic-analysis.png'),fullPage:true});
      fs.writeFileSync(path.join(config.evidence,'browser-semantic-review.json'),JSON.stringify({caseId:c.id,model:c.report.reviews.TENDER_COMPLIANCE.model,technicalEvidence16GB512GBVisible:true,realBrowser:true,pageErrors:errors},null,2));
      console.log('BROWSER_SEMANTIC_TECHNICAL_EVIDENCE_PASS');
    } else if(config.action==='cross-file-finding') {
      const c=JSON.parse(fs.readFileSync(path.join(config.evidence,'actual-http-conflict-result.json'),'utf8')).data;
      await page.getByRole('button',{name:'采购审查',exact:true}).click();
      const panel=page.locator('section[aria-label="上传材料自动询比价"]');await panel.waitFor();await panel.locator('select').selectOption(c.id);
      const f10=panel.locator('section[aria-label="F10招标合规结果"]');await f10.waitFor();
      const f=c.report.reviews.TENDER_COMPLIANCE.findings.find(f=>['PAYMENT_CONFLICT','CLARIFICATION_CONFLICT','BUSINESS_CONFLICT'].includes(f.code));
      assert(f,'missing real conflict finding');await f10.getByText(f.title,{exact:true}).first().waitFor();
      await f10.locator('article details summary').first().click();
      assert((await f10.locator('article details[open] blockquote').count())>=2,'cross-file finding lost its source quotes');
      await f10.scrollIntoViewIfNeeded();await page.screenshot({path:path.join(config.evidence,'browser-cross-file-finding.png'),fullPage:true});
      fs.writeFileSync(path.join(config.evidence,'browser-cross-file-finding.json'),JSON.stringify({caseId:c.id,model:c.report.reviews.TENDER_COMPLIANCE.model,visibleRisk:true,atLeastTwoOriginalSources:true,aggregate:c.status,pageErrors:errors},null,2));
      console.log('BROWSER_ACTUAL_CROSS_FILE_FINDING_PASS');
    } else if(config.action==='module-partial') {
      const c=JSON.parse(fs.readFileSync(path.join(config.evidence,'restart-interrupted-after.json'),'utf8'));
      await page.getByRole('button',{name:'采购审查',exact:true}).click();
      const panel=page.locator('section[aria-label="上传材料自动询比价"]');await panel.waitFor();
      await panel.locator('select').selectOption(c.id);
      await panel.getByRole('status').filter({hasText:/^部分完成/}).waitFor();
      assert((await panel.locator('section[aria-label="智能体预审结果"]').innerText()).includes('结果待核实'),'interrupted review did not expose unknown');
      assert((await panel.locator('section[aria-label="F10招标合规结果"]').innerText()).includes('本轮分析完成'),'other review lost its completed result');
      assert((await panel.innerText()).includes('212000'),'quote result lost after interrupted module');
      await page.screenshot({path:path.join(config.evidence,'browser-partial-module.png'),fullPage:true});
      fs.writeFileSync(path.join(config.evidence,'browser-partial-module.json'),JSON.stringify({caseId:c.id,aggregate:'PARTIAL',precheck:'UNKNOWN',f10:'COMPLETED',quotesRetained:true,realBrowser:true,pageErrors:errors},null,2));
      console.log('BROWSER_PARTIAL_MODULE_PASS');
    } else if(config.action==='acceptance') {
      await page.getByRole('button',{name:'采购审查',exact:true}).click();
      const panel=page.locator('section[aria-label="上传材料自动询比价"]');
      await panel.waitFor({timeout:30000});
      assert(await panel.locator('input:not([type="file"])').count()===0,'automatic panel must not request business fields');
      const files=fs.readdirSync(config.fixtures).sort().map(name=>path.join(config.fixtures,name));assert(files.length===12,'expected 12 files');
      const progress=[];
      let id;
      page.on('response',async response=>{
        if(id && response.url().endsWith('/api/v1/procurement/cases/'+id) && response.status()===200) {
          const c=(await response.json()).data;
          progress.push({state:c.status,extracted:c.documents.filter(d=>d.status==='EXTRACTED').length,total:c.documents.length});
        }
      });
      const uploadResponse=page.waitForResponse(r=>r.url().endsWith('/api/v1/procurement/cases')&&r.request().method()==='POST');
      await panel.getByLabel('选择采购材料并开始分析').setInputFiles(files);
      const response=await uploadResponse;const uploaded=await response.json();
      assert(response.status()===200 && uploaded.code===200,'browser upload failed');id=uploaded.data.id;
      await panel.getByRole('status').filter({hasText:/已提取/}).waitFor();
      await page.screenshot({path:path.join(config.evidence,'browser-processing.png'),fullPage:true});
      // Reload while processing: keep the original operation/case and resume polling automatically.
      await page.reload({waitUntil:'networkidle'});
      if(!await panel.isVisible())await page.getByRole('button',{name:'采购审查',exact:true}).click();
      await panel.waitFor();
      assert(await panel.locator('select').inputValue()===id,'refresh lost the active case');
      await panel.getByRole('status').filter({hasText:'本轮分析完成'}).waitFor({timeout:1200000});
      const text=await panel.innerText();
      assert(text.includes('智能体预审') && text.includes('F10 招标文件合规'),'new modules missing');
      assert(text.includes('黑名单、围串标、拆分采购：本次未执行'),'unexecuted modules not disclosed');
      for(const expected of ['222600','212000','216400','219800','216066.67','澄川星河办公设备有限公司','澄川明川数字设备有限公司','澄川启衡信息设备有限公司'])assert(text.includes(expected),'missing browser result '+expected);
      assert((text.match(/明细核算：一致/g)||[]).length===3,'expected 3 verified line groups');
      const result=await page.request.get('http://127.0.0.1:19132/api/v1/procurement/cases/'+id);
      const completed=await result.json();assert(completed.data.status==='COMPLETED','durable result is not completed');
      assert(completed.data.report.modules.AGENT_PRECHECK==='COMPLETED' && completed.data.report.modules.TENDER_COMPLIANCE==='COMPLETED','new modules did not complete');
      const allEvidence=[];
      for(const doc of completed.data.documents) {
        const r=await page.request.get('http://127.0.0.1:19132/api/v1/procurement/cases/'+id+'/files/'+doc.id+'/evidence');
        assert(r.status()===200,'evidence HTTP failure');allEvidence.push(await r.json());
      }
      fs.writeFileSync(path.join(config.evidence,'browser-dm8-actual-result.json'),JSON.stringify(completed,null,2));
      fs.writeFileSync(path.join(config.evidence,'browser-dm8-actual-evidence.json'),JSON.stringify(allEvidence,null,2));
      const f10=panel.locator('section[aria-label="F10招标合规结果"]');
      await f10.locator('details > summary').filter({hasText:'已核对事实与分析范围'}).click();
      assert((await f10.innerText()).includes('技术'),'technical semantic observation not visible');
      await f10.scrollIntoViewIfNeeded();await page.screenshot({path:path.join(config.evidence,'browser-f10-semantic-analysis.png'),fullPage:true});
      await f10.locator('details > summary').filter({hasText:'已核对事实与分析范围'}).click();
      await panel.getByRole('button',{name:'查看证据',exact:true}).first().click();
      await panel.locator('details[open] blockquote').first().waitFor();
      await page.screenshot({path:path.join(config.evidence,'browser-completed-evidence.png'),fullPage:true});
      const downloadEvent=page.waitForEvent('download');
      await panel.getByRole('link',{name:'原始文件',exact:true}).first().click();
      const download=await downloadEvent;assert(await download.failure()===null,'original file download failed');
      await page.reload({waitUntil:'networkidle'});
      if(!await panel.isVisible())await page.getByRole('button',{name:'采购审查',exact:true}).click();
      await panel.getByRole('status').filter({hasText:'本轮分析完成'}).waitFor();
      assert(await panel.locator('select').inputValue()===id,'completed case lost after refresh');
      // Real HTTP rejects a disguised PDF. The UI must retain the original key without creating a success case.
      const failedResponse=page.waitForResponse(r=>r.url().endsWith('/api/v1/procurement/cases')&&r.request().method()==='POST');
      await panel.getByLabel('选择采购材料并开始分析').setInputFiles({name:'invalid-signature.pdf',mimeType:'application/pdf',buffer:Buffer.from('plain text, invalid PDF signature')});
      const rejected=await failedResponse;assert(rejected.status()===400,'invalid signature was not rejected');
      await panel.getByRole('alert').waitFor();
      assert(!(await panel.innerText()).includes('本轮分析完成'),'failed upload displayed an old success as its result');
      const saved=await page.evaluate(()=>Object.entries(localStorage).filter(([k])=>k.startsWith('procurement-auto-case-v1:')).map(([k,v])=>[k,JSON.parse(v)]));
      const pending=saved.find(([,v])=>v.key&&!v.caseId);assert(pending,'uncertain upload identity was lost');
      const key=pending[1].key;
      // Simulate an operation-query network failure during refresh; never silently abandon its identity.
      await page.route('**/api/v1/procurement/cases/operations/'+key,route=>route.abort('connectionfailed'));
      await page.reload({waitUntil:'networkidle'});
      if(!await panel.isVisible())await page.getByRole('button',{name:'采购审查',exact:true}).click();
      await panel.getByRole('alert').waitFor();
      assert(!(await panel.innerText()).includes('本轮分析完成'),'uncertain refresh displayed a success result');
      const preserved=await page.evaluate(k=>JSON.parse(localStorage.getItem(k)),pending[0]);
      assert(preserved.key===key && !preserved.caseId,'refresh changed an uncertain operation');
      await page.screenshot({path:path.join(config.evidence,'browser-failed-upload.png'),fullPage:true});
      assert(progress.some(p=>p.state==='RUNNING'),'no real running progress observed');
      assert(progress.some(p=>p.extracted>0 && p.extracted<12),'no partial extraction progress observed');
      assert(errors.length===0,'uncaught browser errors');
      fs.writeFileSync(path.join(config.evidence,'browser-acceptance.json'),JSON.stringify({browser:'Playwright Chromium, real DOM and HTTP',caseId:id,files:12,progress,uploaded,completedVisible:true,processingRefreshRecovered:true,completedRefreshRecovered:true,evidenceVisible:true,originalDownload:true,invalidSignatureHttpStatus:rejected.status(),failedUploadPreservedOperation:true,networkFailurePreservedOperation:true,pageErrors:errors},null,2));
      console.log('BROWSER_ACCEPTANCE_PASS case='+id);
    } else {
      throw new Error('Acceptance interactions must be selected from the observed page, run login-inspect first');
    }
  }
  console.log('PAGE_ERRORS',JSON.stringify(errors));
}finally{await browser.close();}
