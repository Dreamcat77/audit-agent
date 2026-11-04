import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync,writeFileSync,mkdirSync,existsSync} from 'node:fs';
import {createServer} from 'node:http';
import path from 'node:path';
const require=createRequire(new URL('../frontend/package.json',import.meta.url));
const {chromium}=require('playwright-core');
const [executable,distArg,resultsArg,outputArg]=process.argv.slice(2);
const dist=path.resolve(distArg),output=path.resolve(outputArg);mkdirSync(output,{recursive:true});
const scenarios=JSON.parse(readFileSync(resultsArg,'utf8'));
const reviews=scenarios.flatMap(c=>c.files.map(f=>({
  scenario:c.label,task:{taskId:f.taskId,fileName:f.fileName,status:f.failureCode?'FAILED':'REVIEWED',contentType:'application/octet-stream',
  fileSize:100,budgetYear:null,departmentCode:null,parsedLineCount:0,errorDetail:f.failureCode?'本份材料未能完成读取，未生成分析结果。':null,
  createdTime:'2026-10-20T04:00:00Z',updatedTime:'2026-10-20T04:00:00Z'},
  allPassed:false,totalItems:0,passedItems:0,exceptionItems:0,items:[],materialReference:f.result
})));
const server=createServer((req,res)=>{
  const relative=new URL(req.url,'http://localhost').pathname;
  const file=path.resolve(dist,relative==='/'?'index.html':'.'+relative);
  if(!file.startsWith(dist+path.sep)||!existsSync(file)){res.writeHead(404);res.end();return;}
  res.writeHead(200,{'Content-Type':({'.html':'text/html','.js':'application/javascript','.css':'text/css','.woff2':'font/woff2'})[path.extname(file)]??'application/octet-stream'});res.end(readFileSync(file));
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({executablePath:executable,headless:true});
try{
  const width=Number(process.env.BUDGET_REVIEW_VIEWPORT_WIDTH??1440);
  const page=await browser.newPage({viewport:{width,height:1080}}),errors=[],checks=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/api/**',async route=>{
    const url=new URL(route.request().url());let data;
    if(url.pathname==='/api/v1/auth/me')data={userId:'synthetic-model-review',username:'fixture',realName:'仿真审核',roles:['SYSTEM_ADMIN']};
    else if(url.pathname==='/api/v1/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'synthetic',parameterName:'_csrf'};
    else if(url.pathname==='/api/v1/budget/validation-records')data={records:reviews.map(r=>({batchId:r.task.taskId,...r.task})),page:1,size:100,total:reviews.length};
    else if(url.pathname.startsWith('/api/v1/budget/reviews/'))data=reviews.find(r=>r.task.taskId===url.pathname.split('/').pop());
    else if(url.pathname.includes('/operations/'))data=null;
    else if(url.pathname.includes('unread-count'))data={count:0};
    else data={items:[],records:[],total:0,page:1,size:20,nextCursor:null,totalPages:0};
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:200,message:'success',data})});
  });
  await page.goto(process.env.BUDGET_REVIEW_BASE_URL ?? `http://127.0.0.1:${server.address().port}`);
  await page.locator('nav button').filter({hasText:'预算审查'}).click();
  await page.getByRole('button',{name:'历史结果',exact:true}).click();
  for(const [i,review] of reviews.entries()){
    if(!review.materialReference)continue;
    // The same synthetic file may be used in more than one batch.
    const matching=reviews.slice(0,i).filter(r=>r.task.fileName===review.task.fileName).length;
    await page.getByRole('row').filter({hasText:review.task.fileName}).nth(matching).getByRole('button',{name:review.task.status==='FAILED'?'查看说明':'查看结果',exact:true}).click();
    const region=page.getByRole('region',{name:'预算文件结果',exact:true});
    await region.getByRole('heading',{name:review.task.fileName,exact:true}).waitFor();
    assert.equal(await page.getByRole('button',{name:'返回',exact:true}).count(),1);
    assert.equal(await page.getByRole('button',{name:'返回历史列表',exact:true}).count(),0);
    assert.equal(await region.locator('article').count(),review.materialReference.lines.length);
    const text=await region.innerText();
    if(review.task.status==='FAILED')assert.ok(text.includes(review.task.errorDetail));
    else assert.ok(text.includes(review.materialReference.summary));
    for(const line of review.materialReference.lines){
      if(line.project)assert.ok(text.includes(line.project));
      if(line.subject)assert.ok(text.includes(line.subject));
      if(line.status==='INSUFFICIENT'||line.status==='CONFLICT'||line.status==='INVALID_AMOUNT')assert.ok(text.includes(line.opinion));
    }
    assert.ok(await region.evaluate(el=>el.scrollWidth<=el.clientWidth),'Result must not overflow horizontally');
    if(i===0||review.scenario==='扫描PDF申报表'||review.scenario==='Excel金额比对'||review.scenario==='完整科目比对错支漏项'||review.task.status==='FAILED')await region.screenshot({path:path.join(output,`product-${i}.png`)});
    checks.push({scenario:review.scenario,file:review.task.fileName,lines:review.materialReference.lines.length});
    await page.getByRole('button',{name:'返回',exact:true}).click();
  }
  assert.deepEqual(errors,[]);
  writeFileSync(path.join(output,'browser-check.json'),JSON.stringify({browser:browser.version(),width,http:'mock API carrying actual model/analyzer results',checks,errors},null,2));
  console.log(JSON.stringify({browser:browser.version(),width,files:checks.length,errors}));
}finally{await browser.close();await new Promise(r=>server.close(r));}
