import assert from 'node:assert/strict';
import {after,before,test} from 'node:test';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {readdirSync} from 'node:fs';
const require=createRequire(new URL('../../package.json',import.meta.url)),{chromium}=require('playwright-core');
const {createServer}=await import(pathToFileURL(path.join(path.dirname(require.resolve('vite/package.json')),'dist/node/index.js')));
const root=path.dirname(fileURLToPath(import.meta.url));let server,browser,base;
before(async()=>{
 server=await createServer({configFile:false,root,logLevel:'error',cacheDir:path.resolve(root,'../../../.runtime/history-vite-cache'),esbuild:{jsx:'automatic'},
  resolve:{alias:[{find:/^react$/,replacement:require.resolve('react')},{find:'react/jsx-runtime',replacement:require.resolve('react/jsx-runtime')},
   {find:'react/jsx-dev-runtime',replacement:require.resolve('react/jsx-dev-runtime')},{find:'react-dom/client',replacement:require.resolve('react-dom/client')}]},
  server:{host:'127.0.0.1',port:0,fs:{allow:[path.resolve(root,'../..'),path.dirname(require.resolve('react'))]}}});
 await server.listen();base='http://127.0.0.1:'+server.httpServer.address().port;
 browser=await chromium.launch({headless:true,executablePath:process.env.AUDIT_BROWSER_EXECUTABLE,args:['--no-sandbox']});
});
after(async()=>{await browser?.close();await server?.close();});
const statuses=['COMPLETED','RUNNING','FAILED','PARTIAL'];
const rows=Array.from({length:21},(_,i)=>({id:'case-'+i,projectId:'project-'+i,projectName:(i===0?'很长的项目名称'.repeat(30):'项目'+i)+'设备更新',
 headline:'设备更新项目',status:statuses[i%4],statusLabel:['已完成','正在分析','处理失败','部分完成'][i%4],createdAt:'2026-11-09T05:06:00Z',
 files:i%3+1,risks:i%2,fileNames:['预算材料'+i+'.pdf','采购申请'+i+'.docx'].slice(0,i%2+1)}));
function batch(r){return {batchId:r.id,taskId:null,projectName:r.projectName,status:r.status==='COMPLETED'?'REVIEWED':r.status==='RUNNING'?'PROCESSING':r.status,
 createdAt:r.createdAt,fileCount:r.fileNames.length,previewFileNames:r.fileNames,budgetYear:2026,departmentCode:'FINANCE'};}
async function open(module,{fail=false}={}){
 const page=await browser.newPage({viewport:{width:1280,height:900}});page.setDefaultTimeout(8000);await page.clock.install();
 const reads=[],writes=[],errors=[];let failure=fail,releaseMore=null,holdMore=false;
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/v1/**',async route=>{
  const req=route.request(),url=new URL(req.url()),p=url.pathname,q=url.searchParams;let data;
  if(req.method()!=='GET')writes.push(req.method()+' '+p);
  if(p==='/api/v1/auth/me')data={userId:'history-test',username:'test',realName:'测试',roles:['AUDITOR']};
  else if(p==='/api/v1/budget/material-uploads/batches'){
   reads.push(url);if(failure)return route.fulfill({status:500,json:{code:500,message:'synthetic failure'}});
   const selected=q.get('fileName')?rows.filter(r=>r.fileNames.some(s=>s.includes(q.get('fileName')))):rows;
   const items=q.get('createdFrom')==='2026-11-08'?[]:selected;
   const pg=Number(q.get('page')??1);data={records:items.slice((pg-1)*20,pg*20).map(batch),page:pg,size:20,total:items.length};
  }else if(p==='/api/v1/procurement/cases/history'){
   reads.push(url);if(failure)return route.fulfill({status:500,json:{code:500,message:'synthetic failure'}});
   if(q.has('before')&&holdMore)await new Promise(resolve=>releaseMore=resolve);
   const selected=q.get('keyword')?rows.filter(r=>r.projectName.includes(q.get('keyword'))||r.fileNames.some(s=>s.includes(q.get('keyword')))):rows;
   const items=q.get('from')&&new Date(q.get('from')).toLocaleDateString('en-CA',{timeZone:'Asia/Shanghai'})==='2026-11-08'?[]:selected;
   data={items:q.has('before')?items.slice(15):items.slice(0,15),nextCursor:!q.has('before')&&items.length>15?'older':null};
  }else if(p==='/api/v1/contract-analysis-cases'){
   reads.push(url);if(failure)return route.fulfill({status:500,json:{code:500,message:'synthetic failure'}});
   const selected=q.get('projectName')?rows.filter(r=>r.projectName.includes(q.get('projectName'))):rows;
   const items=q.get('createdDate')==='2026-11-08'?[]:selected;const pg=Number(q.get('page')??1);data=items.slice((pg-1)*15,pg*15);
  }else if(/^\/api\/v1\/procurement\/cases\/case-\d+$/.test(p)){
   const row=rows.find(r=>p.endsWith('/'+r.id));data={...row,status:'COMPLETED',caseNumber:row.id,documents:[],report:{offers:[],notices:[],modules:{},reviews:{},quoteStatus:'DATA_INSUFFICIENT'}};
  }else if(p.endsWith('/acknowledgments'))data=[];
  else if(/^\/api\/v1\/contract-analysis-cases\/case-\d+\/report$/.test(p))data={...rows.find(r=>p.includes('/'+r.id+'/')),status:'COMPLETED',documents:[],findings:[],checks:[],categories:[],limitations:[],comparisons:[]};
  else if(/^\/api\/v1\/contract-analysis-cases\/case-\d+$/.test(p))data={projectId:null};
  else if(p.includes('/projects/'))data={project:{id:p.split('/').at(-1),name:'项目',versionNo:1},files:[],associatedContracts:[],candidates:[]};
  else return route.fulfill({status:404,json:{code:404,message:'not found',data:null}});
  return route.fulfill({json:{code:200,message:'success',data}});
 });
 await page.goto(base+'/?module='+module);
 for(const name of readdirSync(path.resolve(root,'../../dist/assets')).filter(s=>s.endsWith('.css')))await page.addStyleTag({path:path.resolve(root,'../../dist/assets',name)});
 if(module==='procurement'){await page.getByRole('button',{name:'历史结果',exact:true}).click();await page.clock.runFor(350);}
 if(!fail)await page.locator('.history-row').first().waitFor();
 return {page,reads,writes,errors,recover:()=>failure=false,hold:()=>holdMore=true,release:()=>releaseMore?.()};
}
async function submit(page){await page.getByRole('button',{name:'查询',exact:true}).click();await page.clock.runFor(350);}
async function reset(page){await page.getByRole('button',{name:'重置',exact:true}).click();await page.clock.runFor(350);}
for(const module of ['budget','procurement','contract']){
 test(module+' query, date, reset, pagination and correct result selection',async()=>{
  const v=await open(module),{page}=v;try{
   const initial=v.reads.length;const keyword=page.locator('.history-filter-keyword input');
   await keyword.fill(module==='budget'?'预算材料1':'项目1');assert.equal(v.reads.length,initial,'DRAFT_MUST_NOT_QUERY');
   await submit(page);await page.waitForFunction(()=>document.querySelectorAll('.history-row').length===11);
   const params=v.reads.at(-1).searchParams;assert.equal(params.get(module==='budget'?'fileName':module==='contract'?'projectName':'keyword'),module==='budget'?'预算材料1':'项目1');
   const filtered=v.reads.length;await submit(page);await page.locator('.history-row').first().waitFor();assert(v.reads.length>filtered,'REPEAT_QUERY_RETRIES');
   await reset(page);await page.locator('.history-row').first().waitFor();assert.equal(await keyword.inputValue(),'');
   await page.getByRole('button',{name:'下一页',exact:true}).click();
   await page.clock.runFor(350);await page.waitForFunction(m=>m==='procurement'?document.querySelector('.history-row')?.getAttribute('data-procurement-case-id')==='case-15':document.querySelector('.history-row')?.getAttribute(m==='budget'?'data-budget-history-id':'data-analysis-case-id')===(m==='budget'?'case-20':'case-15'),module);
   assert.equal(await page.getByRole('button',{name:'第 2 页',exact:true}).isDisabled(),true);
   await page.getByRole('button',{name:'上一页',exact:true}).click();await page.clock.runFor(350);
   await page.waitForFunction(()=>document.querySelector('.history-row')?.textContent?.includes('很长的项目名称'));
   assert.equal(await page.getByRole('button',{name:'上一页',exact:true}).isDisabled(),true);
   await reset(page);await page.locator('.history-row').first().waitFor();
   const date=page.getByLabel('上传日期',{exact:true});await date.focus();await date.fill('2026-11-08');await submit(page);
   await page.getByText('暂无符合条件的记录',{exact:true}).waitFor();
   const q=v.reads.at(-1).searchParams;const value=q.get(module==='budget'?'createdFrom':module==='contract'?'createdDate':'from');assert.equal(module==='procurement'?new Date(value).toLocaleDateString('en-CA',{timeZone:'Asia/Shanghai'}):value.slice(0,10),'2026-11-08');
   await reset(page);await page.locator('.history-row').first().waitFor();
   await page.locator('.history-row').nth(2).getByRole('button',{name:module==='budget'?'查看说明':'查看结果',exact:true}).click();
   if(module==='budget')assert.equal(await page.locator('[data-opened]').textContent(),'case-2');else await page.getByText(rows[2].projectName,{exact:true}).first().waitFor();
   assert.deepEqual(v.writes,[]);assert.deepEqual(v.errors,[]);
  }finally{await page.close();}
 });
 test(module+' compact unified sizes and long names fit desktop and mobile without decorative row icons',async()=>{
  const v=await open(module),{page}=v;try{
   for(const width of [1440,1024,768,375]){
    await page.setViewportSize({width,height:900});
    const bounds=await page.evaluate(()=>({screen:innerWidth,scroll:document.documentElement.scrollWidth,
      control:[...document.querySelectorAll('.history-filter input')].map(e=>e.getBoundingClientRect().height),
      title:getComputedStyle(document.querySelector('.history-row-name')).fontSize,
      pageTitle:getComputedStyle(document.querySelector('.history-page-title')).fontSize,
      rows:[...document.querySelectorAll('.history-row')].slice(0,3).map(e=>{const r=e.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,height:r.height};})}));
    assert(bounds.scroll<=bounds.screen+1,JSON.stringify(bounds));assert(bounds.control.every(v=>v===40));
    assert.equal(bounds.title,'15px');assert.equal(bounds.pageTitle,'20px');assert(bounds.rows.every(r=>r.height>=78&&r.right<=width));
    assert(bounds.rows[1].top>bounds.rows[0].top);assert.equal(await page.locator('.history-row svg').count(),0);
   }
   await page.setViewportSize({width:1280,height:900});await page.screenshot({path:path.resolve(root,'../../../.runtime/history-ui-20261109/component-'+module+'.png'),fullPage:true});
   assert.equal(await page.getByRole('button',{name:'修改名称',exact:true}).count(),0);
   assert.deepEqual([...new Set(await page.locator('.history-status').allTextContents())].sort(),['已完成','已结束','正在分析'].sort());
   assert.equal(await page.locator('.history-status-complete').count()>0,true);assert.equal(await page.locator('.history-status-processing').count()>0,true);
   assert.equal(await page.locator('.history-status-neutral').count()>0,true);assert.deepEqual(v.errors,[]);
  }finally{await page.close();}
 });
 test(module+' same query can recover a failed history request',async()=>{
  const v=await open(module,{fail:true});try{await v.page.getByRole('alert').waitFor();v.recover();await submit(v.page);await v.page.locator('.history-row').first().waitFor();assert.deepEqual(v.errors,[]);}finally{await v.page.close();}
 });
}
test('procurement ignores a late older-page response after changing the filter',async()=>{
 const v=await open('procurement'),{page}=v;try{
  v.hold();await page.getByRole('button',{name:'下一页',exact:true}).click();await page.clock.runFor(350);
  await page.locator('.history-filter-keyword input').fill('项目1');await submit(page);await page.waitForFunction(()=>document.querySelectorAll('.history-row').length===11);
  v.release();await page.clock.runFor(100);assert.equal(await page.locator('.history-row').count(),11);
  const names=await page.locator('.history-row-name').allTextContents();assert(names.every(n=>n.includes('项目1')));assert.deepEqual(v.errors,[]);
 }finally{v.release();await page.close();}
});
