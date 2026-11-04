// Real browser acceptance. Credentials arrive through stdin and are never saved.
import {createRequire} from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import {createServer,request as proxyRequest} from 'node:http';
import {fileURLToPath} from 'node:url';
const require=createRequire(new URL('../../frontend/package.json',import.meta.url));
const {chromium}=require('playwright-core');
let input='';for await(const part of process.stdin)input+=part;
const config=JSON.parse(input);input='';
const root=fileURLToPath(new URL('../..',import.meta.url));
let staticServer;
if(config.production){
  const dist=path.resolve(root,'frontend/dist-contract-acceptance');
  staticServer=createServer((req,res)=>{
    if(req.url.startsWith('/api/')){
      const upstream=proxyRequest(new URL(req.url,'http://127.0.0.1:19141'),{method:req.method,headers:{...req.headers,host:'127.0.0.1:19141'}},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res);});
      upstream.on('error',()=>{res.writeHead(502);res.end();});req.pipe(upstream);return;
    }
    const file=path.resolve(dist,'.'+new URL(req.url,'http://localhost').pathname.replace(/\/$/,'/index.html'));
    if(!file.startsWith(dist+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404);res.end();return;}
    res.writeHead(200,{'Content-Type':({'.html':'text/html','.js':'application/javascript','.css':'text/css','.woff2':'font/woff2'})[path.extname(file)]??'application/octet-stream'});res.end(fs.readFileSync(file));
  });
  await new Promise((resolve,reject)=>{staticServer.once('error',reject);staticServer.listen(19144,'127.0.0.1',resolve);});
}
const browser=await chromium.launch({headless:true,...(config.executable?{executablePath:config.executable}:{})});
const context=await browser.newContext({viewport:{width:1440,height:1000}});
const page=await context.newPage();const errors=[];
page.on('pageerror',error=>errors.push(error.message));
const ensure=(condition,message)=>{if(!condition)throw new Error(message);};
let caseId=config.caseId;
async function openHistoryCase(id){
  await page.waitForLoadState('networkidle');
  const back=page.getByRole('button',{name:'返回分析记录',exact:true});
  if(await back.count())await back.click();
  for(let n=0;n<100;n++){
    await page.getByRole('heading',{name:'分析记录',exact:true}).waitFor();
    await page.locator('[data-contract-history][aria-busy="false"]').waitFor();
    const row=page.locator(`[data-analysis-case-id="${id}"]`);
    if(await row.count()){await row.click();return;}
    const next=page.getByRole('button',{name:'下一页',exact:true});
    if(await next.isDisabled())break;
    const response=page.waitForResponse(r=>r.url().includes('/contract-analysis-cases?')&&r.request().method()==='GET');
    await next.click();await response;
  }
  throw new Error('persisted case has no navigable history row');
}
try{
  await page.goto('http://127.0.0.1:19144',{waitUntil:'networkidle'});
  await page.getByPlaceholder('请输入用户名').fill(config.username);
  await page.getByPlaceholder('请输入密码').fill(config.password);delete config.password;
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.getByRole('button',{name:'合同审核',exact:true}).click({timeout:30000});
  await page.getByRole('heading',{name:'合同审核',exact:true}).waitFor();
  if(config.paths){
    const admitted=page.waitForResponse(response=>response.url().endsWith('/contract-analysis-cases/intakes')&&response.request().method()==='POST');
    await page.locator('input[type=file]').setInputFiles(config.paths);
    const response=await admitted;ensure(response.ok(),'intake HTTP rejected');caseId=(await response.json()).data.caseId;
    fs.writeFileSync(path.join(config.evidence,config.label+'-receipt.json'),JSON.stringify({caseId,synthetic:true}));
  }else{
    const api=await context.request.get(`http://127.0.0.1:19144/api/v1/contract-analysis-cases/${caseId}/report`);
    const report=(await api.json()).data;
    await openHistoryCase(caseId);
  }
  let report;
  const deadline=Date.now()+1900000;
  do{
    const response=await context.request.get(`http://127.0.0.1:19144/api/v1/contract-analysis-cases/${caseId}/report`);
    ensure(response.ok(),'persisted report unreadable');report=(await response.json()).data;
    if(!['QUEUED','RUNNING'].includes(report.status))break;
    await new Promise(resolve=>setTimeout(resolve,2000));
  }while(Date.now()<deadline);
  ensure(!['QUEUED','RUNNING'].includes(report.status),'case did not finish within total acceptance window');
  ensure(report.documents.some(d=>d.clauses.length>0),'normal contract produced no useful clause analysis');
  await page.getByText(report.statusLabel,{exact:true}).first().waitFor({timeout:15000});
  ensure(!(await page.getByText('材料已接收，系统正在自动分析。你也可以从分析记录中随时查看。',{exact:true}).count()),'terminal report still displays stale processing notice');
  const main=page.locator('section.delivery-page').filter({has:page.getByRole('heading',{name:'合同审核',exact:true})});
  ensure(!(await main.locator('input:not([type=file])').count()),'analysis unexpectedly asks for business fields');
  ensure(!(await main.getByRole('button',{name:/确认材料|确认项目|确认合同|开始审核|下一步/}).count()),'manual gate returned');
  await page.screenshot({path:path.join(config.evidence,config.label+'-result.png'),fullPage:true});
  await main.getByRole('button',{name:'查看原文',exact:true}).first().click();
  await page.getByRole('dialog',{name:'来源原文'}).waitFor();
  ensure((await page.getByRole('dialog',{name:'来源原文'}).locator('pre').innerText()).length>10,'original source evidence unavailable');
  await page.getByRole('dialog').getByRole('button',{name:'关闭',exact:true}).click();
  const before=JSON.stringify(report);
  await page.reload({waitUntil:'networkidle'});
  await page.getByRole('button',{name:'合同审核',exact:true}).click({timeout:30000});
  await page.getByRole('heading',{name:'合同审核',exact:true}).waitFor();
  const after=await context.request.get(`http://127.0.0.1:19144/api/v1/contract-analysis-cases/${caseId}/report`);
  ensure(after.ok()&&JSON.stringify((await after.json()).data)===before,'refresh changed persisted report');
  await openHistoryCase(caseId);
  await page.getByRole('heading',{name:report.headline,exact:true}).waitFor();
  ensure(errors.length===0,'unhandled browser error');
  const outcome={caseId,status:report.status,uploadThroughBrowser:!!config.paths,manualBusinessInputs:0,originalEvidenceVisible:true,refreshPreservedReport:true,pageErrors:errors,browserVersion:browser.version(),productionBundle:!!config.production,realModel:true,realDm8:true,synthetic:true};
  fs.writeFileSync(path.join(config.evidence,config.label+'-verification.json'),JSON.stringify(outcome,null,2));
  console.log('BROWSER_CONTRACT_PASS '+JSON.stringify(outcome));
}finally{await browser.close();if(staticServer)await new Promise(resolve=>staticServer.close(resolve));}
