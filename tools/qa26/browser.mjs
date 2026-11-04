// Actual production UI against the independent QA26 app, no route mocks.
import {createRequire} from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const require=createRequire(new URL('../../frontend/package.json',import.meta.url));
const {chromium}=require('playwright-core');
let raw='';for await(const chunk of process.stdin)raw+=chunk;
const config=JSON.parse(raw);raw='';
const root=fileURLToPath(new URL('../..',import.meta.url));
const out=path.join(root,'docs/qa-26/evidence');
const browser=await chromium.launch({executablePath:config.executable,headless:true,args:['--no-first-run','--disable-background-networking']});
const context=await browser.newContext({viewport:{width:1440,height:1100},acceptDownloads:true});
const page=await context.newPage(),errors=[];
page.on('pageerror',e=>errors.push(e.message));
const ensure=(ok,message)=>{if(!ok)throw new Error(message);};
try{
  await page.goto(config.base,{waitUntil:'networkidle'});
  await page.getByPlaceholder('请输入用户名').fill(config.username);
  await page.getByPlaceholder('请输入密码').fill(config.password);delete config.password;
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.getByRole('button',{name:'预算审查',exact:true}).waitFor({timeout:30000});
  const api=async url=>{const response=await context.request.get(config.base+url);ensure(response.status()===200,'read HTTP '+response.status());return(await response.json()).data;};
  const results=[];
  for(const label of ['预算审查','采购审查','合同审核']){
    await page.getByRole('button',{name:label,exact:true}).click();
    if(label==='采购审查')await page.getByRole('region',{name:'上传材料自动询比价'}).waitFor({timeout:30000});
    await page.waitForLoadState('networkidle');
    const summary=await page.evaluate(()=>({headings:[...document.querySelectorAll('h1,h2,h3')].map(e=>e.textContent),
      buttons:[...document.querySelectorAll('main button')].map(e=>e.textContent),
      inputs:[...document.querySelectorAll('input')].map(e=>({type:e.type,placeholder:e.placeholder,accept:e.accept,aria:e.getAttribute('aria-label')}))}));
    fs.writeFileSync(path.join(out,`browser109-${label}-inspection.json`),JSON.stringify(summary,null,2));
    if(config.mode==='inspect')continue;
    if(label==='预算审查'){
      const files=['项目预算申报.docx','预算批复.txt'].map(n=>path.join(root,'.test-runtime/qa26/fixtures/budget/over',n));
      await page.locator('input[type=file]').first().setInputFiles(files);
      const receipt=page.waitForResponse(r=>r.url().endsWith('/budget/material-uploads/batches')&&r.request().method()==='POST');
      await page.getByRole('button',{name:/^开始智能解析/}).click();
      const accepted=await receipt;ensure(accepted.ok(),'budget registration failed');
      const {batchId}=await accepted.json().then(r=>r.data);
      fs.writeFileSync(path.join(out,'browser109-budget-receipt.json'),JSON.stringify({batchId,synthetic:true}));
      const region=page.getByRole('region',{name:'预算文件结果',exact:true});
      await region.getByText(/2,240\.00/).first().waitFor({timeout:1800000});
      ensure((await region.locator('article').count())===1,'cross-file duplicate budget result');
      ensure((await page.getByRole('button',{name:'返回',exact:true}).count())===1,'duplicate return controls');
      await region.screenshot({path:path.join(out,'browser109-budget-over-result.png')});
      const downloadEvent=page.waitForEvent('download');await region.getByRole('button',{name:'导出',exact:true}).click();
      const download=await downloadEvent;const target=path.join(root,'.test-runtime/qa26/browser-budget.xlsx');await download.saveAs(target);
      const XLSX=require('xlsx'),book=XLSX.readFile(target);
      ensure(XLSX.utils.sheet_to_json(book.Sheets['差异清单']).length===1,'wrong difference export');
      await page.setViewportSize({width:390,height:844});ensure(await region.evaluate(e=>e.scrollWidth<=e.clientWidth),'mobile budget overflow');
      await page.setViewportSize({width:1440,height:1100});
      await page.getByRole('button',{name:'返回',exact:true}).click();
      await page.getByRole('button',{name:'历史结果',exact:true}).click();
      const history=page.getByRole('region',{name:'预算历史结果'});
      // Another user operation may add newer history while this browser is open.
      // Reopen this exact batch, rather than assuming the first row is ours.
      await history.locator('tbody tr').filter({hasText:batchId}).getByRole('button',{name:'查看批次结果',exact:true}).click();
      await region.getByText(/2,240\.00/).first().waitFor();
      results.push({module:'budget',batchId,automaticResultNavigation:true,historyReopen:true,differenceExport:true,mobileNoOverflow:true});
    }
    if(label==='采购审查'){
      const region=page.getByRole('region',{name:'上传材料自动询比价'});
      await region.locator('select').selectOption(config.procurementCaseId);
      await region.getByText(/预算上限：222600/).waitFor();
      for(const name of ['澄川星河办公设备有限公司','澄川明川数字设备有限公司','澄川启衡信息设备有限公司'])await region.locator('p.font-medium').filter({hasText:name}).first().waitFor();
      ensure((await region.getByRole('button',{name:/开始预审|提交审批|开始合规/}).count())===0,'manual procurement gates');
      ensure((await region.getByText('COMPLETED',{exact:true}).count())===0,'raw status code');
      await region.screenshot({path:path.join(out,'browser109-procurement-normal-result.png')});
      await region.getByText(/本批 \d+ 份材料及来源/).click();
      await region.getByRole('button',{name:'查看证据',exact:true}).first().click();
      await region.getByText('解析原文（含非结构化技术要求）',{exact:true}).waitFor();
      if(['r4','r5'].includes(config.revision)){
        ensure(!/docx\/body\/paragraph:|purchaserName：|projectName：|projectNo：|执行原因：MODEL_/.test(await region.textContent()),'internal fields or source locators exposed to ordinary readers');
        ensure(await region.getByText('项目编号：',{exact:false}).count()>0,'Chinese fact labels missing');
      }
      const frozen=JSON.stringify(await api('/api/v1/procurement/cases/'+config.procurementCaseId));
      await page.reload({waitUntil:'networkidle'});await page.getByRole('button',{name:'采购审查',exact:true}).click();
      await page.getByRole('region',{name:'上传材料自动询比价'}).locator('select').selectOption(config.procurementCaseId);
      ensure(JSON.stringify(await api('/api/v1/procurement/cases/'+config.procurementCaseId))===frozen,'refresh changed procurement report');
      const panel=page.getByRole('region',{name:'上传材料自动询比价'});
      const rejection=page.waitForResponse(r=>r.url().endsWith('/procurement/cases')&&r.request().method()==='POST');
      await panel.locator('input[type=file]').setInputFiles({name:'不支持的压缩包.zip',mimeType:'application/zip',buffer:Buffer.from('synthetic rejected archive')});
      ensure((await rejection).status()===400,'archive was not rejected');
      const golden=JSON.parse(fs.readFileSync(path.join(root,'.test-runtime/qa26/fixtures/golden.json'),'utf8'));
      const partial=golden.contract.find(s=>s.id==='partial-file');
      const bad=partial.paths.find(p=>p.endsWith('.docx')&&(/损坏|broken|bad/i).test(p));ensure(bad,'missing parser-failure fixture');
      const nextUpload=page.waitForResponse(r=>r.url().endsWith('/procurement/cases')&&r.request().method()==='POST');
      await panel.locator('input[type=file]').setInputFiles(path.join(root,'.test-runtime/qa26/fixtures',bad));
      ensure((await nextUpload).ok(),'validation rejection locked subsequent upload');
      await panel.getByRole('status').filter({hasText:/数据不足/}).waitFor({timeout:120000});
      ensure(!(await panel.getByRole('status').innerText()).includes('本轮分析完成'),'broken document falsely completed');
      await panel.locator('select').selectOption(config.procurementCaseId);
      results.push({module:'procurement',caseId:config.procurementCaseId,threeSuppliersVisible:true,noManualBusinessGate:true,evidenceReadable:true,refreshPreserved:true,definiteRejectionAllowsNextUpload:true,brokenSourceNotFalseSuccess:true});
    }
    if(label==='合同审核'){
      const report=await api('/api/v1/contract-analysis-cases/'+config.contractCaseId+'/report');
      for(let i=0;i<50;i++){
        await page.locator('[data-contract-history][aria-busy="false"]').waitFor();
        const row=page.locator(`[data-analysis-case-id="${config.contractCaseId}"]`);
        if(await row.count()){await row.click();break;}
        const next=page.getByRole('button',{name:'下一页',exact:true});ensure(!(await next.isDisabled()),'contract case undiscoverable');await next.click();
      }
      await page.getByRole('heading',{name:report.headline,exact:true}).waitFor();
      const main=page.locator('section.delivery-page').filter({has:page.getByRole('heading',{name:'合同审核',exact:true})});
      ensure((await main.locator('input:not([type=file])').count())===0,'contract business form returned');
      ensure((await main.getByRole('button',{name:/确认材料|确认项目|确认合同|开始审核|下一步/}).count())===0,'contract manual gate');
      await main.screenshot({path:path.join(out,'browser109-contract-normal-result.png')});
      await main.getByRole('button',{name:'查看原文',exact:true}).first().click();
      const dialog=page.getByRole('dialog',{name:'来源原文'});await dialog.waitFor();ensure((await dialog.locator('pre').innerText()).length>10,'missing contract source');
      await dialog.getByRole('button',{name:'关闭',exact:true}).click();
      const frozen=JSON.stringify(report);await page.reload({waitUntil:'networkidle'});
      ensure(JSON.stringify(await api('/api/v1/contract-analysis-cases/'+config.contractCaseId+'/report'))===frozen,'refresh changed contract report');
      results.push({module:'contract',caseId:config.contractCaseId,manualBusinessInputs:0,noManualConfirmation:true,evidenceReadable:true,refreshPreserved:true});
    }
  }
  ensure(errors.length===0,'browser runtime errors');
  const result={stage:config.mode,revision:config.revision,browser:browser.version(),production:true,realHttp:true,realModel:config.mode==='accept',realDm8:true,pageErrors:errors,results};
  fs.writeFileSync(path.join(out,'browser109-'+config.mode+'-verification.json'),JSON.stringify(result,null,2));
  console.log(JSON.stringify(result));
}catch(error){
  await page.screenshot({path:path.join(root,'.test-runtime/qa26/browser-failure.png'),fullPage:true}).catch(()=>{});
  throw error;
}finally{await browser.close();}
