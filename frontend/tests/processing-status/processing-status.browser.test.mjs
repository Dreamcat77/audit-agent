import assert from 'node:assert/strict';
import {before,after,test} from 'node:test';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';

const root=path.dirname(fileURLToPath(import.meta.url));
const require=createRequire(process.env.AUDIT_BROWSER_TEST_NODE_MODULES
  ?path.resolve(process.env.AUDIT_BROWSER_TEST_NODE_MODULES,'../package.json'):new URL('../../package.json',import.meta.url));
const {createServer}=await import(pathToFileURL(path.join(path.dirname(require.resolve('vite/package.json')),'dist/node/index.js')));
const {chromium}=require('playwright-core');
let server,browser,base;
before(async()=>{
  server=await createServer({configFile:false,root,logLevel:'error',cacheDir:path.resolve(root,'../../../.runtime/processing-status-vite-cache'),esbuild:{jsx:'automatic'},
    resolve:{alias:[{find:/^react$/,replacement:require.resolve('react')},{find:'react/jsx-runtime',replacement:require.resolve('react/jsx-runtime')},
      {find:'react/jsx-dev-runtime',replacement:require.resolve('react/jsx-dev-runtime')},{find:'react-dom/client',replacement:require.resolve('react-dom/client')},
      {find:'xlsx',replacement:path.join(root,'xlsx-fixture.ts')}]},
    server:{host:'127.0.0.1',port:0,fs:{allow:[path.resolve(root,'../..'),path.dirname(require.resolve('react'))]}}});
  await server.listen();base='http://127.0.0.1:'+server.httpServer.address().port;
  browser=await chromium.launch({headless:true,executablePath:process.env.AUDIT_BROWSER_EXECUTABLE||'/usr/bin/chromium',args:['--no-sandbox']});
});
after(async()=>{await browser?.close();await server?.close();});

const date='2026-11-06T02:00:00Z';
function procurement(status){return {id:'case-a',caseNumber:'CASE-TEST',projectName:'设备采购服务项目',status,createdAt:date,
  documents:[{id:'doc-a',name:'采购材料.pdf',status:'EXTRACTED',factCount:1}],
  report:['FAILED','UNKNOWN'].includes(status)?undefined:{quoteStatus:'DATA_INSUFFICIENT',offers:[],notices:[],modules:{},reviews:{}}};}
function contract(status){return {id:'case-a',projectId:'project-a',projectName:'设备采购服务项目',status,statusLabel:'正在分析',headline:'设备采购服务项目',createdAt:date,
  documents:[{fileId:'doc-a',name:'采购合同.docx',status:'READY',facts:[],clauses:[],checks:[],findings:[],sourceChars:100,reviewedChars:0,fullyReviewed:false}],
  findings:[],checks:[],limitations:[],comparisons:[],categories:[]};}
function budget(status){return {task:{taskId:'task-a',fileName:'预算申报.xlsx',status:status==='COMPLETED'?'REVIEWED':'ANALYZING',parsedLineCount:0},items:[],
  materialReference:{status:status==='COMPLETED'?'COMPLETED':'PENDING',coverage:'COMPLETE',summary:'预算核对',assessments:[],lines:status==='COMPLETED'?[{
    project:'设备采购服务项目',subject:'设备',subjectCode:'A01',year:'2026',department:'采购',declaredAmount:100,approvalAmount:120,status:'WITHIN_APPROVAL',opinion:'未超过批复',
    sources:[{taskId:'task-a',fileName:'预算申报.xlsx',field:'declaredAmount',value:'100',locator:'sheet:1/row:1',excerpt:'申报100元'}]}]:[]}};}

async function open(module,status='RUNNING',viewport={width:1280,height:900}){
  const page=await browser.newPage({viewport});page.setDefaultTimeout(7000);await page.clock.install();
  let phase=status;const errors=[],writes=[],unexpected=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/api/v1/**',async route=>{
    const req=route.request(),url=new URL(req.url()),pathname=url.pathname;
    if(req.method()!=='GET')writes.push(req.method()+' '+pathname);
    let data;
    if(pathname==='/api/v1/auth/me')data={userId:'status-test',username:'test',realName:'测试用户',roles:['AUDITOR']};
    else if(pathname==='/api/v1/procurement/cases/case-a')data=procurement(phase);
    else if(pathname.endsWith('/acknowledgments'))data=[];
    else if(pathname==='/api/v1/procurement/cases/case-a/files/doc-a/evidence')data={fileName:'采购材料.pdf',segments:[],facts:[{type:'projectName',value:'设备采购服务项目',locator:'pdf/page:1',excerpt:'采购原文',verification:'SOURCE_VERIFIED'}]};
    else if(pathname==='/api/v1/procurement/cases/case-a/files/doc-a/pages/1/image')return route.fulfill({status:200,contentType:'image/png',headers:{'X-Page-Count':'1'},body:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=','base64')});
    else if(pathname==='/api/v1/budget/material-uploads/batches/batch-a')data={batchId:'batch-a',status:phase==='COMPLETED'?'REVIEWED':'PROCESSING',createdAt:date,files:[{taskId:'task-a',fileName:'预算申报.xlsx',status:phase==='COMPLETED'?'REVIEWED':'ANALYZING'}]};
    else if(pathname==='/api/v1/budget/reviews/task-a')data=budget(phase);
    else if(pathname==='/api/v1/contract-analysis-cases')data=[{...contract(phase),files:1,risks:0}];
    else if(pathname==='/api/v1/contract-analysis-cases/case-a/report')data=contract(phase);
    else if(pathname==='/api/v1/contract-analysis-cases/case-a')data={id:'case-a',projectId:'project-a'};
    else if(pathname==='/api/v1/projects/project-a')data={project:{id:'project-a',name:'设备采购服务项目'},files:[],associatedContracts:[],candidates:[]};
    else{unexpected.push(pathname);return route.fulfill({status:404,json:{code:404,message:'fixture route unavailable',data:null}});}
    return route.fulfill({json:{code:200,message:'success',data}});
  });
  await page.goto(base+'/?module='+module);
  if(module==='contract')await page.locator('[data-analysis-case-id="case-a"] button').first().click();
  await page.locator('.processing-status-card').first().waitFor({state:'visible'});
  return {page,errors,writes,unexpected,setPhase:value=>{phase=value;}};
}

test('shared full and compact cards expose consistent status text without actions or fabricated progress',async()=>{
  const page=await browser.newPage({viewport:{width:1000,height:320}});
  try{await page.goto(base+'/?module=gallery');
    await page.getByRole('status',{name:'正在分析材料'}).waitFor();
    assert.equal(await page.locator('.processing-status-card').count(),2);
    assert.equal(await page.locator('.processing-status-card button,.processing-status-card a,[role="progressbar"]').count(),0);
    for(const card of await page.locator('.processing-status-card').all()){
      assert.equal(await card.getAttribute('aria-live'),'polite');assert.equal(await card.getAttribute('aria-atomic'),'true');
    }
    await page.screenshot({path:path.resolve(root,'../../../.runtime/processing-status-gallery-borderless.png'),fullPage:true});
  }finally{await page.close();}
});

test('procurement waiting card replaces partial results and all document operations, then restores them on completion',async()=>{
  const view=await open('procurement');const {page}=view;
  try{
    assert.equal(await page.locator('.processing-status-card').count(),1);
    assert.equal(await page.getByRole('button',{name:'查看提取内容'}).count(),0);
    assert.equal(await page.locator('a[href$="/original"]').count(),0);
    assert.equal(await page.getByLabel('本批采购审查结果').count(),0);
    await page.screenshot({path:path.resolve(root,'../../../.runtime/processing-status-procurement-borderless.png'),fullPage:true});
    await page.locator('.result-project-header').screenshot({path:path.resolve(root,'../../../.runtime/processing-status-header-borderless.png')});
    view.setPhase('COMPLETED');await page.clock.fastForward(2600);
    await page.locator('.processing-status-card').waitFor({state:'hidden'});
    await page.getByText('本批材料（1 份）',{exact:true}).click();
    assert.equal(await page.getByRole('button',{name:'查看提取内容'}).isVisible(),true);
    assert.equal(await page.getByRole('link',{name:'原文件',exact:true}).isVisible(),true);
    assert.equal(await page.getByRole('link',{name:'原文件',exact:true}).getAttribute('href'),'/api/v1/procurement/cases/case-a/files/doc-a/original');
    assert.equal(await page.getByLabel('本批采购审查结果').count(),1);
    await page.getByRole('button',{name:'查看提取内容'}).click();
    // The shared evidence viewer opens on original pixels; switch explicitly
    // when this test needs to assert the parsed-text payload.
    await page.locator('.original-evidence-picture img').waitFor();
    assert.equal(await page.getByRole('button',{name:/关键片段|解析文字|完整引用/}).count(),0);
    assert.deepEqual(view.writes,[]);assert.deepEqual(view.errors,[]);assert.deepEqual(view.unexpected,[]);
  }finally{await page.close();}
});

test('uploading and queued procurement use the shared card and unknown results stop showing a spinner',async()=>{
  const view=await open('procurement','UPLOADING');const {page}=view;
  try{assert.equal(await page.getByRole('status',{name:'正在上传材料'}).isVisible(),true);
    view.setPhase('QUEUED');await page.clock.fastForward(2600);
    await page.getByRole('status',{name:'正在准备审查'}).waitFor();
    view.setPhase('UNKNOWN');await page.clock.fastForward(2600);
    await page.locator('.processing-status-card').waitFor({state:'hidden'});
    assert.equal(await page.getByRole('button',{name:'查看提取内容',includeHidden:true}).count(),1);
    assert.deepEqual(view.errors,[]);assert.deepEqual(view.writes,[]);
  }finally{await page.close();}
});

test('budget keeps one shared waiting card instead of empty result modules and restores export when complete',async()=>{
  const view=await open('budget');const {page}=view;
  try{assert.equal(await page.locator('.processing-status-card').count(),1);
    assert.equal(await page.getByRole('button',{name:'导出',exact:true}).count(),0);
    assert.equal(await page.locator('.result-section').count(),0);
    view.setPhase('COMPLETED');await page.clock.fastForward(3100);
    await page.getByRole('button',{name:'导出',exact:true}).waitFor();
    assert.equal(await page.locator('.processing-status-card').count(),0);
    assert.deepEqual(view.errors,[]);assert.deepEqual(view.writes,[]);assert.deepEqual(view.unexpected,[]);
  }finally{await page.close();}
});

test('contract uses the same waiting card and waits for completion before displaying review modules',async()=>{
  const view=await open('contract');const {page}=view;
  try{assert.equal(await page.locator('.processing-status-card').count(),1);
    assert.equal(await page.getByLabel('合同八大项分析').count(),0);
    view.setPhase('COMPLETED');await page.clock.fastForward(3100);
    await page.getByLabel('合同八大项分析').waitFor();
    assert.equal(await page.locator('.processing-status-card').count(),0);
    assert.deepEqual(view.errors,[]);assert.deepEqual(view.writes,[]);assert.deepEqual(view.unexpected,[]);
  }finally{await page.close();}
});

test('status cards remain compact at phone width and respect reduced motion',async()=>{
  const view=await open('procurement','RUNNING',{width:390,height:844});const {page}=view;
  try{
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    const card=await page.locator('.processing-status-card').boundingBox();assert.ok(card.height<180);
    assert.equal(await page.locator('.processing-status-card button,.processing-status-card a').count(),0);
    await page.emulateMedia({reducedMotion:'reduce'});
    assert.equal(await page.locator('.processing-status-card__spinner').evaluate(el=>getComputedStyle(el).animationName),'none');
    assert.equal(await page.getByRole('status',{name:'正在分析材料'}).getAttribute('aria-live'),'polite');
    assert.deepEqual(view.errors,[]);
  }finally{await page.close();}
});

test('budget shows the selected project immediately, bounds reads at six, and waits for the whole batch',async()=>{
 const page=await browser.newPage();const pending=[];let started=0,active=0,maxActive=0;const writes=[];
 try{
  await page.route('**/api/v1/budget/**',async route=>{
   if(route.request().method()!=='GET')writes.push(route.request().method());
   const p=new URL(route.request().url()).pathname;
   if(p.includes('/material-uploads/'))return route.fulfill({json:{code:200,message:'success',data:{batchId:'batch-slow',status:'REVIEWED',files:Array.from({length:12},(_,i)=>({taskId:'task-'+i,fileName:'预算'+i+'.pdf',status:'REVIEWED'}))}}});
   started++;active++;maxActive=Math.max(maxActive,active);await new Promise(resolve=>pending.push(resolve));active--;
   const data=budget('COMPLETED');data.task.taskId=p.split('/').at(-1);
   return route.fulfill({json:{code:200,message:'success',data}});
  });
  await page.goto(base+'/?module=budget-slow');await page.getByText('读取速度测试项目',{exact:true}).waitFor();
  await page.waitForFunction(()=>document.body.textContent.includes('（0/12）'));assert.equal(started,6);assert.equal(await page.locator('.result-section').count(),0);
  pending.splice(0).forEach(resolve=>resolve());await page.waitForFunction(()=>document.body.textContent.includes('（6/12）'));assert.equal(started,12);assert.equal(maxActive,6);
  assert.equal(await page.locator('.result-section').count(),0,'NO_PARTIAL_CONCLUSIONS');
  pending.splice(0).forEach(resolve=>resolve());await page.locator('.result-section').first().waitFor();assert.equal(await page.locator('.processing-status-card').count(),0);assert.deepEqual(writes,[]);
 }finally{pending.splice(0).forEach(resolve=>resolve());await page.close();}
});

test('leaving a slow budget result cancels active reads and does not launch queued file requests',async()=>{
 const page=await browser.newPage();const pending=[];let started=0,aborted=0;
 page.on('requestfailed',request=>{if(request.url().includes('/budget/reviews/'))aborted++;});
 try{
  await page.route('**/api/v1/budget/**',async route=>{
   if(route.request().url().includes('/material-uploads/'))return route.fulfill({json:{code:200,message:'success',data:{batchId:'batch-slow',status:'REVIEWED',files:Array.from({length:12},(_,i)=>({taskId:'task-'+i,fileName:'预算.pdf',status:'REVIEWED'}))}}});
   started++;await new Promise(resolve=>pending.push(resolve));await route.fulfill({json:{code:200,message:'success',data:budget('COMPLETED')}}).catch(()=>{});
  });
  await page.goto(base+'/?module=budget-slow');await page.waitForFunction(()=>document.body.textContent.includes('（0/12）'));assert.equal(started,6);
  await page.getByRole('button',{name:'返回历史结果',exact:true}).click();await page.getByText('已返回历史结果',{exact:true}).waitFor();
  await page.waitForTimeout(150);pending.splice(0).forEach(resolve=>resolve());await page.waitForTimeout(100);
  assert.equal(started,6,'UNMOUNT_MUST_NOT_START_REMAINING_READS');assert.equal(aborted,6);
 }finally{pending.splice(0).forEach(resolve=>resolve());await page.close();}
});
