import {createRequire} from 'node:module';import fs from 'node:fs';import assert from 'node:assert/strict';
const require=createRequire(new URL('../../frontend/package.json',import.meta.url)),{chromium}=require('playwright-core'),XLSX=require('xlsx');
let input='';for await(const c of process.stdin)input+=c;const cfg=JSON.parse(input);input='';
const out='.runtime/budget-batch-20261106',origin='http://192.168.0.26';
const browser=await chromium.launch({executablePath:'E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe',headless:true});
try {
 const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.name));await page.goto(origin,{waitUntil:'networkidle'});
 await page.getByPlaceholder('请输入用户名').fill('test_admin');await page.getByPlaceholder('请输入密码').fill(cfg.password);
 await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('heading',{name:'工作台',exact:true}).waitFor();
 await page.locator('.portal-primary-nav').getByRole('button',{name:'预算审查',exact:true}).click();
 await page.getByRole('button',{name:'历史结果',exact:true}).click();
 const open=async name=>{await page.getByPlaceholder('输入材料名称关键字').fill(name);await page.getByRole('button',{name:'查询',exact:true}).click();await page.locator('tr').filter({hasText:name}).first().getByRole('button',{name:'查看批次结果',exact:true}).click();const expected={'审批执行记录.txt':'紫荆','综合说明.txt':'松林','改造计划.txt':'旧设备','实施请示.txt':'白桦'}[name];await page.locator('[data-result-project-name]').filter({hasText:expected}).waitFor();};
 await open('审批执行记录.txt');const report=page.locator('section[aria-label="预算文件结果"]');
 assert.equal(await report.locator('section[aria-label="优先关注"] .result-item').count(),0);
 assert.equal(await report.getByRole('button',{name:'优先关注',exact:false}).getAttribute('aria-expanded'),'false');
 assert.equal(await report.getByRole('button',{name:'审批与申报核对',exact:false}).getAttribute('aria-expanded'),'false');
 await report.getByRole('button',{name:'审批与申报核对',exact:false}).click();
 assert((await report.innerText()).includes('已找到'));
 assert(!(await report.innerText()).includes('未找到对应已完成审批记录'));
 await page.screenshot({path:out+'/budget-normal-approval-r7.png',fullPage:true});
 await page.getByRole('button',{name:'返回',exact:true}).click();await open('综合说明.txt');
 await report.getByRole('button',{name:'总体预算与金额核对',exact:false}).click();
 assert((await report.innerText()).includes('800,000.00'));
 assert(!(await report.innerText()).includes('3,000,000.00'));
 await page.getByRole('button',{name:'返回',exact:true}).click();await open('改造计划.txt');
 await report.getByRole('button',{name:'总体预算与金额核对',exact:false}).click();
 await report.getByText('￥850,000.00',{exact:true}).waitFor();await report.getByText('￥900,000.00',{exact:true}).waitFor();
 await page.getByRole('button',{name:'返回',exact:true}).click();await open('实施请示.txt');
 const priority=report.locator('section[aria-label="优先关注"]');await priority.locator('.result-item').first().waitFor();
 assert((await priority.innerText()).includes('资金安排意见.txt'));
 assert((await priority.innerText()).includes('实施请示.txt'));
 await priority.getByRole('button',{name:'查看原文',exact:true}).first().click();const dialog=page.getByRole('dialog',{name:'来源原文'});
 await dialog.locator('mark').first().waitFor();assert((await dialog.locator('mark').count())>=1);
 await page.keyboard.press('Escape');await page.screenshot({path:out+'/budget-cross-purpose-r7.png',fullPage:true});
 for(const width of [1440,900,390]){await page.setViewportSize({width,height:900});assert(await report.evaluate(n=>n.scrollWidth<=n.clientWidth+1));}
 assert.deepEqual(errors,[]);const proof={status:'PASS',browser:browser.version(),normalApprovalNoWarning:true,historicalAmountExcluded:true,crossFileIssueNames:true,sourceHighlight:true,responsiveWidths:[1440,900,390],pageErrors:errors};
 fs.writeFileSync(out+'/extra-browser-r7-verification.json',JSON.stringify(proof,null,2));console.log(JSON.stringify(proof));
}finally{await browser.close();}
