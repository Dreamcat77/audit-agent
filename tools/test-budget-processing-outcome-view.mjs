import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync,existsSync,writeFileSync} from 'node:fs';
import {createServer} from 'node:http';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const require=createRequire(new URL('../frontend/package.json',import.meta.url));
const {chromium}=require('playwright-core');
const [executable,distArg]=process.argv.slice(2),dist=path.resolve(distArg);
const evidenceRoot=fileURLToPath(new URL('../.runtime/',import.meta.url));
const stamp='2026-10-19T12:00:00Z';
function review(id,status,name,explanation=null,summary='本批材料尚未找到可对应的批复金额，暂不能判断是否超过批复。') {
 return {task:{taskId:id,fileName:name,contentType:'text/plain',fileSize:100,status,parsedLineCount:0,errorDetail:explanation,
  budgetYear:'2026',departmentCode:'PROCUREMENT',reviewCursor:0,pendingRowNumber:null,pendingStep:null,pendingReason:null,createdTime:stamp,updatedTime:stamp},
  allPassed:false,totalItems:0,passedItems:0,exceptionItems:0,items:[],materialReference:{status:status==='PROCESSING'?'PENDING':'COMPLETED',summary,lines:[]}};
}
const rows=[review('insufficient','REVIEWED','合成缺少批复.txt'),
 review('evidence','FAILED','合成原文核对.txt','已读取材料，但部分信息无法准确对应到原文中的具体条目，本次未形成可靠的分析结果。'),
 review('unconfirmed','UNKNOWN','合成结果未确认.txt','本次处理已结束，尚未取得可核实的分析结果，具体原因需要管理员查看关联日志。'),
 review('processing','PROCESSING','合成正在分析.txt',null,'正在整理同批材料的预算参考结果，完成后会自动显示。')];
const server=createServer((req,res)=>{
 const relative=new URL(req.url,'http://localhost').pathname;
 const file=path.resolve(dist,relative==='/'?'index.html':'.'+relative);
 if(!file.startsWith(dist+path.sep)||!existsSync(file)){res.writeHead(404);res.end();return;}
 res.writeHead(200,{'Content-Type':({'.html':'text/html','.js':'application/javascript','.css':'text/css','.woff2':'font/woff2'})[path.extname(file)]??'application/octet-stream'});res.end(readFileSync(file));
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({headless:true,executablePath:executable});
const errors=[],checks=[];
let recoveryReads=0, deniedReads=0, pendingRead;
try {
 const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();
 page.on('pageerror',error=>errors.push(error.message));
 await context.route('**/api/v1/**',async route=>{
  const url=new URL(route.request().url());let data;
  if(url.pathname==='/api/v1/budget/reviews/recovery') {
   recoveryReads++;
   if(recoveryReads===1||recoveryReads===3){await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:503,message:'temporary',data:null})});return;}
   data=review('recovery',recoveryReads<4?'PROCESSING':'REVIEWED','合成网络恢复.txt',null,recoveryReads<4?'正在整理本次预算材料。':'网络恢复后，已读取最终预算结果。');
   await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:200,message:'success',data})});return;
  }
  if(url.pathname==='/api/v1/budget/reviews/denied') {
   deniedReads++;
   await route.fulfill({status:403,contentType:'application/json',body:JSON.stringify({code:403,message:'forbidden',data:null})});return;
  }
  if(url.pathname==='/api/v1/budget/reviews/leaving') {
   pendingRead=()=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:503,message:'temporary',data:null})});return;
  }
  if(url.pathname==='/api/v1/auth/me')data={userId:'synthetic-outcome',username:'synthetic',realName:'合成用户',roles:['SYSTEM_ADMIN']};
  else if(url.pathname==='/api/v1/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'synthetic',parameterName:'_csrf'};
  else if(url.pathname==='/api/v1/budget/validation-records')data={records:rows.map(r=>({batchId:r.task.taskId,...r.task})),page:1,size:20,total:rows.length};
  else if(url.pathname.startsWith('/api/v1/budget/reviews/'))data=rows.find(r=>r.task.taskId===url.pathname.split('/').pop());
  else if(url.pathname==='/api/v1/budget/material-uploads'&&route.request().method()==='POST')data={id:'evidence',fileName:rows[1].task.fileName,status:'FAILED',updatedAt:stamp,errorCode:'BUDGET_PARSE_FAILED',task:rows[1].task};
  else if(url.pathname.includes('/operations/'))data=null;
  else if(url.pathname.includes('unread-count'))data={count:0};
  else data={items:[],records:[],total:0,page:1,size:20,nextCursor:null,totalPages:0};
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:200,message:'success',data})});
 });
 const base=process.env.BUDGET_REVIEW_BASE_URL??`http://127.0.0.1:${server.address().port}`;
 await page.goto(base);await page.locator('nav button').filter({hasText:'预算审查'}).click();
 await page.getByRole('button',{name:'历史结果',exact:true}).click();
 const history=page.getByRole('region',{name:'预算历史结果',exact:true});
 await history.getByText('合成原文核对.txt',{exact:true}).waitFor();
 assert.equal(await history.getByText('已结束',{exact:true}).count(),2);
 assert.equal(await history.getByText('解析中',{exact:true}).count(),1);
 assert.doesNotMatch(await history.innerText(),/状态待确认|处理失败/);checks.push('history-distinguishes-processing-ended-and-available-results');
 for(const [index,item] of rows.entries()){
  const action=index===1||index===2?'查看说明':'查看结果';
  await history.getByRole('row').filter({hasText:item.task.fileName}).getByRole('button',{name:action,exact:true}).click();
  const region=page.getByRole('region',{name:'预算文件结果',exact:true});
  await region.getByRole('heading',{name:item.task.fileName,exact:true}).waitFor();
  await region.getByText(item.task.errorDetail??item.materialReference.summary,{exact:true}).waitFor();
  if(index===1||index===2)assert.doesNotMatch(await region.innerText(),/材料不足|处理状态暂时无法确认|等待确认/);
  await region.screenshot({path:path.join(evidenceRoot,`budget-result-style-${index}.png`)});
  if(index===1){
   await page.setViewportSize({width:390,height:844});
   const dimensions=await region.evaluate(n=>({scroll:n.scrollWidth,client:n.clientWidth}));
   assert.ok(dimensions.scroll<=dimensions.client,'Result explanation must fit the narrow panel');
   await region.screenshot({path:path.join(evidenceRoot,'budget-result-style-mobile.png')});
   await page.setViewportSize({width:1440,height:1000});
  }
  await page.getByRole('button',{name:'返回',exact:true}).click();
 }
 checks.push('details-show-real-technical-cause-or-missing-approval-without-fabricated-material-deficiency');
 rows.push(review('recovery','PROCESSING','合成网络恢复.txt'),review('denied','PROCESSING','合成无权读取.txt'),review('leaving','PROCESSING','合成离开页面.txt'));
 await page.getByRole('button',{name:'返回',exact:true}).click();
 await page.getByRole('button',{name:'历史结果',exact:true}).click();
 await history.getByRole('row').filter({hasText:'合成网络恢复.txt'}).getByRole('button',{name:'查看结果',exact:true}).click();
 const recovering=page.getByRole('region',{name:'预算文件结果',exact:true});
 await recovering.getByText('暂时无法读取完整结果，请稍后重新打开。',{exact:true}).waitFor();
 await recovering.getByText('正在整理本次预算材料。',{exact:true}).waitFor({timeout:10000});
 await recovering.getByRole('alert').waitFor({timeout:10000});
 assert.equal(await recovering.getByText('正在整理本次预算材料。',{exact:true}).count(),1,'A transient read error must preserve the last snapshot');
 await recovering.getByText('网络恢复后，已读取最终预算结果。',{exact:true}).waitFor({timeout:15000});
 assert.equal(await recovering.getByRole('alert').count(),0);
 const readsAtCompletion=recoveryReads;await page.waitForTimeout(3400);assert.equal(recoveryReads,readsAtCompletion,'Completed results must stop polling');
 await page.getByRole('button',{name:'返回',exact:true}).click();
 await history.getByRole('row').filter({hasText:'合成无权读取.txt'}).getByRole('button',{name:'查看结果',exact:true}).click();
 await recovering.getByText('暂时无法读取完整结果，请稍后重新打开。',{exact:true}).waitFor();
 await page.waitForTimeout(3400);assert.equal(deniedReads,1,'Forbidden reads must not retry');
 await page.getByRole('button',{name:'返回',exact:true}).click();
 await history.getByRole('row').filter({hasText:'合成离开页面.txt'}).getByRole('button',{name:'查看结果',exact:true}).click();
 await page.waitForFunction(()=>document.querySelector('[aria-label="预算文件结果"]'));
 while(!pendingRead)await page.waitForTimeout(20);
 await page.getByRole('button',{name:'返回',exact:true}).click();await pendingRead();await page.waitForTimeout(3400);
 assert.equal(await recovering.count(),0,'Late failed reads must not reopen results');
 checks.push('transient-read-recovery-preserves-snapshot-stops-after-completion-and-does-not-retry-forbidden-or-unmounted-reads');
 await page.reload();await page.locator('nav button').filter({hasText:'预算审查'}).click();
 assert.equal(await page.getByRole('button',{name:'查看历史结果',exact:true}).count(),0);
 await page.locator('input[type=file]').last().setInputFiles({name:'合成原文核对.txt',mimeType:'text/plain',buffer:Buffer.from('合成测试')});
 await page.getByRole('button',{name:/开始智能解析/}).click();
 const result=page.getByRole('region',{name:'预算文件结果',exact:true});
 await result.getByText(rows[1].task.errorDetail,{exact:true}).waitFor();
 assert.doesNotMatch(await page.locator('main').first().innerText(),/解析与分析已完成，结果已保存/);
 checks.push('all-ended-without-results-navigates-to-explanation-without-success-notice');
 await page.getByRole('button',{name:'返回',exact:true}).click();
 const historyShortcut=page.getByRole('button',{name:'查看历史结果',exact:true});
 await historyShortcut.waitFor();
 await historyShortcut.click();
 await history.getByText('合成原文核对.txt',{exact:true}).waitFor();
 await page.getByRole('button',{name:'返回',exact:true}).click();
 await historyShortcut.waitFor();
 await page.screenshot({path:path.join(evidenceRoot,'budget-upload-history-shortcut109.png'),fullPage:true});
 await page.locator('input[type=file]').last().setInputFiles({name:'下一批.txt',mimeType:'text/plain',buffer:Buffer.from('下一批')});
 assert.equal(await historyShortcut.count(),0,'Selecting a new batch must hide the previous batch shortcut');
 checks.push('upload-history-shortcut-appears-after-ended-upload-opens-history-and-hides-for-new-selection');
 assert.deepEqual(errors,[]);
 await page.screenshot({path:path.join(evidenceRoot,'budget-outcome-browser109.png'),fullPage:true});
 const report={browser:browser.version(),checks,errors};writeFileSync(path.join(evidenceRoot,'budget-outcome-browser109.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
} finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
