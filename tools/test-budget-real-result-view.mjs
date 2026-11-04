import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync,mkdirSync,writeFileSync,existsSync} from 'node:fs';
import {createServer} from 'node:http';
import path from 'node:path';
const require=createRequire(new URL('../frontend/package.json',import.meta.url)),{chromium}=require('playwright-core');
const [executable,site,label='focus',mode='check']=process.argv.slice(2);
const reviews=JSON.parse(readFileSync('.runtime/budget-focus-synthetic-reviews.json','utf8'));
const folder=path.resolve('.runtime/budget-focus-browser');mkdirSync(folder,{recursive:true});
let server,base=site;
if(!/^https?:/.test(site)){
 const dist=path.resolve(site);server=createServer((req,res)=>{const file=path.join(dist,new URL(req.url,'http://local').pathname==='/'?'index.html':new URL(req.url,'http://local').pathname.slice(1));if(!existsSync(file)){res.writeHead(404);res.end();return;}res.writeHead(200,{'Content-Type':({'.html':'text/html','.js':'application/javascript','.css':'text/css','.woff2':'font/woff2'})[path.extname(file)]??'application/octet-stream'});res.end(readFileSync(file));});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));base=`http://127.0.0.1:${server.address().port}`;
}
const browser=await chromium.launch({executablePath:executable,headless:true,args:['--no-first-run']});
try{
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url());let data=null;
  if(url.pathname==='/api/v1/auth/me')data={userId:'synthetic-view',username:'fixture',realName:'测试用户',roles:['SYSTEM_ADMIN']};
  else if(url.pathname==='/api/v1/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'synthetic',parameterName:'_csrf'};
  else if(url.pathname==='/api/v1/budget/validation-records')data={records:reviews.map(r=>({batchId:r.task.taskId,fileName:r.task.fileName,status:r.task.status,budgetYear:r.task.budgetYear,departmentCode:r.task.departmentCode,createdTime:r.task.createdTime,totalRows:r.task.parsedLineCount})),page:1,size:20,total:2};
  else if(url.pathname.startsWith('/api/v1/budget/reviews/'))data=reviews.find(r=>r.task.taskId===url.pathname.split('/').pop());
  else if(url.pathname.includes('material-risk/history'))data={items:[],page:1,size:6,total:0};
  else if(url.pathname.includes('material-risk/'))data={items:[],nextCursor:null};
  else if(url.pathname.includes('/operations/'))data=null;
  else if(url.pathname.includes('unread-count'))data={count:0};
  else if(url.pathname.includes('dashboard'))data={recentTasks:[],statusDistribution:[],riskDistribution:[]};
  else data={items:[],records:[],total:0,page:1,size:20,totalPages:0};
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:200,message:'success',data})});
 });
 await page.goto(base);await page.locator('nav button').filter({hasText:'预算审查'}).click();
 await page.getByRole('button',{name:'历史结果',exact:true}).click();
 for(const [index,review] of reviews.entries()){
  await page.getByRole('row').filter({hasText:review.task.fileName}).getByRole('button',{name:'查看结果',exact:true}).click();
  const region=page.getByRole('region',{name:'预算文件结果',exact:true});await region.getByRole('heading',{name:review.task.fileName,exact:true}).waitFor();
  const metrics=await region.locator('article').evaluateAll(nodes=>nodes.map(n=>({text:n.innerText,background:getComputedStyle(n).backgroundColor,color:getComputedStyle(n).color,classes:n.className})));
  console.log(JSON.stringify({label,file:index,differences:await region.getByLabel('预算差异清单').count(),metrics}));
  if(mode==='check'){
   await region.getByLabel('预算差异清单').waitFor();
   assert.equal(await region.locator('article[data-budget-difference="true"]').count(),2);
   const actual=await region.locator('article[data-budget-difference="true"]').first().evaluate(n=>({bg:getComputedStyle(n).backgroundColor,color:getComputedStyle(n.querySelector('[data-budget-status]')).color}));
   assert.equal(actual.bg,'rgb(254, 242, 242)');assert.equal(actual.color,'rgb(185, 28, 28)');
   await region.getByText('超出批复 20,000.00 元',{exact:true}).first().waitFor();
   assert.equal(await region.getByText(index===0?'疑似错支':'疑似漏项',{exact:true}).count()>0,true);
  }
  await region.screenshot({path:path.join(folder,`${label}-${index}.png`)});
  await page.getByRole('button',{name:'返回',exact:true}).click();
 }
 assert.deepEqual(errors,[]);writeFileSync(path.join(folder,`${label}.json`),JSON.stringify({browser:browser.version(),errors,mode}));
}finally{await browser.close();if(server)await new Promise(r=>server.close(r));}
