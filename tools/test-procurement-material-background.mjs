import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
const require=createRequire(new URL('../frontend/package.json',import.meta.url));
const {chromium}=require('playwright-core');
const [executablePath,distArg,label='109']=process.argv.slice(2);
const lostReviewResponse=process.argv[5]==='lost-review';
const dist=path.resolve(distArg),records=new Map(),operations=new Map(),uploads=[];
const evidence=path.resolve('.runtime/async-material-browser');mkdirSync(evidence,{recursive:true});
let finish=false,firstResponse,lateResponse,historyRequests=0;
const created=[];
let reviewCalls=0, reviewExecutions=0, finishReview, savedReport=null, projectUploadCalls=0;
const createdProject=()=>({id:'synthetic-created',projectCode:'TEST-AUTO',createdTime:'2026-10-21T00:00:00Z',status:'DRAFT',...created[0]});
const report=()=>({id:'saved-report',projectId:'synthetic-created',reportNumber:'TEST-REPORT',generatedTime:'2026-10-21T00:00:00Z',riskLevel:'INFO',overallConclusion:'本次采购材料已完成审查。',project:createdProject(),agentReview:{riskLevel:'INFO',suggestion:'请结合原材料使用参考结果。',aiSummary:null,generatedTime:'2026-10-21T00:00:00Z',items:[]},quoteCheck:null,blacklist:{alertCount:0,hits:[]},tender:{findingCount:0,findings:[]},collusion:null,splitPurchase:null});
const transfers=new Map();let partWrites=0;
const fields={projectName:'合成采购测试',projectNo:'SYNTHETIC-SIMPLE',departmentName:'测试部',procurementType:'货物',budgetAmount:'100.00',
  subjectCode:'TEST',subjectName:'测试设备',procurementMethod:'公开招标',requirementDescription:'合成材料要求',applicantName:'测试员',conflicts:[],evidence:{}};
const user={userId:'fixture-user',username:'fixture-user',realName:'兼容测试用户',roles:['SYSTEM_ADMIN']};
const server=createServer(async(req,res)=>{
  const url=new URL(req.url,'http://127.0.0.1');
  const respond=data=>{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({code:200,message:'success',data}));};
  if(url.pathname.startsWith('/api/')){
    if(url.pathname.startsWith('/api/v1/procurement/quote-checks/')) {
      res.writeHead(404,{'Content-Type':'application/json'});res.end(JSON.stringify({code:404,message:'not found',data:null}));return;
    }
    if(url.pathname==='/api/v1/procurement/materials'&&req.method==='GET') historyRequests++;
    const transferMatch=url.pathname.match(/^\/api\/v1\/material-transfers\/procurement\/([^/]+)(?:\/parts\/(\d+)|\/(complete))?$/);
    if(transferMatch&&req.method==='POST'&&!transferMatch[3]) {
      let raw='';for await(const c of req)raw+=c;const spec=JSON.parse(raw),key=transferMatch[1];
      if(transfers.has(key))assert.deepEqual(transfers.get(key).spec,spec);else transfers.set(key,{spec,received:[]});
      const transfer=transfers.get(key);respond({operation:key,status:'OPEN',chunkBytes:4*1024*1024,received:transfer.received,receivedBytes:0});return;
    }
    if(transferMatch&&req.method==='PUT') {
      let size=0;for await(const c of req)size+=c.length;assert.ok(size>0);const transfer=transfers.get(transferMatch[1]);assert.ok(transfer);const index=Number(transferMatch[2]);partWrites++;
      if(!transfer.received.includes(index))transfer.received.push(index);
      if(partWrites===1){res.destroy();return;}
      respond({operation:transferMatch[1],status:'OPEN',chunkBytes:4*1024*1024,received:transfer.received,receivedBytes:0});return;
    }
    if(url.pathname==='/api/v1/procurement/materials'&&req.method==='POST'||transferMatch?.[3]==='complete'){
      let bytes=0;
      if(transferMatch){const transfer=transfers.get(transferMatch[1]);assert.equal(transfer.received.length,transfer.spec.partHashes.length);bytes=transfer.spec.size;}
      else for await(const chunk of req) bytes+=chunk.length;
      const key=transferMatch?.[1]??req.headers['idempotency-key'];assert.ok(key);
      if(operations.has(key)){respond(operations.get(key));return;}
      uploads.push({key,bytes});
      const i=uploads.length-1;
      const row={id:key,fileName:`test-${i}.pdf`,status:i===0?'FAILED':'QUEUED',fields:null,
        errorCode:i===0?'AI_NO_FIELDS':null,parseMethod:null,createdAt:'2026-10-10T00:00:00Z',updatedAt:'2026-10-10T00:00:00Z',leaseUntil:'2026-10-10T00:20:00Z'};
      records.set(key,row);operations.set(key,row);
      if(i===0){firstResponse=()=>respond(row);return;}
      if(i===12){lateResponse=()=>respond({...row,status:'PARSED',fields});return;}
      if(i===4){res.destroy();return;} // Durable upload, lost response.
      respond(row);return;
    }
    if(url.pathname.startsWith('/api/v1/procurement/materials/')){
      const id=url.pathname.split('/').at(-1),row=records.get(id)||operations.get(id);
      if(finish&&row&&row.status!=='FAILED') {row.status='PARSED';row.fields=fields;}
      respond(row||null);return;
    }
    let data=[];
    if(url.pathname==='/api/v1/procurement/projects'&&req.method==='POST'){
      let body='';for await(const chunk of req) body+=chunk.toString();created.push(JSON.parse(body));respond({id:'synthetic-created'});return;
    }
    if(url.pathname.startsWith('/api/v1/procurement/projects/synthetic-created/')) {
      if(req.method==='POST'&&/uploads|\/batch$/.test(url.pathname))projectUploadCalls++;
      if(url.pathname.endsWith('/from-intake/analyze')) {
        assert.equal(req.method,'POST'); reviewCalls++;
        // Chromium may retry a POST when a reused keep-alive socket loses its ACK.
        // Mirror the server's durable report/content identities, rather than
        // pretending every transport retry executes the model again.
        if(savedReport){respond({files:[],quote:null,collusion:null,blacklist:null,notices:[],report:savedReport});return;}
        reviewExecutions++;
        finishReview=()=>{savedReport=report();if(lostReviewResponse)res.destroy();else respond({files:[],quote:null,collusion:null,blacklist:null,notices:[],report:savedReport});};return;
      }
      if(url.pathname.endsWith('/review-report')) {
        if(savedReport)respond(savedReport);else {res.writeHead(404,{'Content-Type':'application/json'});res.end(JSON.stringify({code:404,message:'not found',data:null}));}return;
      }
      if(url.pathname.includes('/collusion-reviews')) {
        res.writeHead(404,{'Content-Type':'application/json'});res.end(JSON.stringify({code:404,message:'not found',data:null}));return;
      }
      if(url.pathname.includes('review-workflow')){respond({id:'workflow',projectId:'synthetic-created',currentStepOrder:savedReport?7:5,status:savedReport?'COMPLETED':'IN_PROGRESS',steps:[]});return;}
      if(url.pathname.endsWith('/current')){respond({files:[],quote:null,collusion:null,blacklist:null,notices:[],report:null});return;}
    }
    if(url.pathname==='/api/v1/auth/me') data=user;
    else if(url.pathname==='/api/v1/auth/csrf') data={headerName:'X-CSRF-TOKEN',token:'synthetic-csrf'};
    else if(url.pathname==='/api/v1/procurement/projects') data={items:created.length?[createdProject()]:[],page:1,size:20,total:created.length,totalPages:1};
    else if(url.pathname.startsWith('/api/v1/budget/material-risk/')) data={items:[],page:1,size:6,total:0,nextCursor:null};
    else if(['/api/v1/contract-audit-tasks','/api/v1/budget/validation-records','/api/v1/projects','/api/v1/projects/intake-drafts','/api/v1/blacklist/entries/query'].includes(url.pathname)) data={items:[],records:[],page:1,size:20,total:0,totalPages:0};
    else if(url.pathname.includes('unread-count')) data={count:0};
    else if(url.pathname.includes('dashboard')) data={totalProjects:0,totalContracts:0,totalReviewSessions:0,totalAuditTasks:0,recentTasks:[],statusDistribution:[],riskDistribution:[]};
    respond(data);return;
  }
  const relative=url.pathname==='/'?'index.html':url.pathname.slice(1);
  const file=path.join(dist,relative);
  if(relative.includes('..')||!existsSync(file)){res.writeHead(404);res.end();return;}
  res.writeHead(200,{'Content-Type':({'.html':'text/html','.css':'text/css','.js':'application/javascript','.woff2':'font/woff2'})[path.extname(file)]||'application/octet-stream'});res.end(readFileSync(file));
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const base=`http://127.0.0.1:${server.address().port}`;
const browser=await chromium.launch({executablePath,headless:true,args:['--disable-background-networking','--no-first-run']});
try{
  const page=await browser.newPage({viewport:{width:1536,height:1000}}),errors=[],external=[];
  page.on('pageerror',err=>errors.push(err.message));
  page.on('request',req=>{if(/^https?:/.test(req.url())&&!req.url().startsWith(base))external.push(req.url());});
  const open=async()=>{await page.locator('nav button').filter({hasText:'采购审查'}).click();await page.getByRole('button',{name:'新建采购需求',exact:true}).click();};
  await page.goto(base);await page.waitForSelector('nav button');await open();
  await page.evaluate(()=>document.fonts.ready);
  const headers=await page.locator('.procurement-demand-table thead th').evaluateAll(cells=>cells.map(cell=>{
    const s=getComputedStyle(cell);return {font:s.fontFamily,size:s.fontSize,line:s.lineHeight};
  }));
  assert.equal(headers.length,9);assert.ok(headers.every(h=>h.font.startsWith('"Audit Header SC"')&&h.size==='12px'&&h.line==='18px'));
  const cdp=await page.context().newCDPSession(page);await cdp.send('DOM.enable');await cdp.send('CSS.enable');
  const dom=await cdp.send('DOM.getDocument');
  const nodes=await cdp.send('DOM.querySelectorAll',{nodeId:dom.root.nodeId,selector:'.procurement-demand-table thead th'});
  for(const nodeId of nodes.nodeIds){const fonts=await cdp.send('CSS.getPlatformFontsForNode',{nodeId});assert.ok(fonts.fonts.length>0&&fonts.fonts.every(f=>f.isCustomFont&&f.familyName==='Audit Header SC'));}
  assert.equal(await page.getByText(/1 至 10|单文件最大 20 MB|总计不超过 40/).count(),0);
  assert.equal(await page.getByText(/一次上传本次采购的全部材料|其他服务端材料记录|选择整个文件夹/).count(),0);
  // Browser directory entries exercise the same recursive reader as native OS dragging.
  await page.getByRole('button',{name:'选择采购材料',exact:false}).evaluate(element=>{
    const fileEntries=Array.from({length:12},(_,i)=>{
      const file=new File([new Uint8Array(i===11?21*1024*1024:100)],`test-${i}.pdf`,{lastModified:i});
      return {isFile:true,isDirectory:false,name:file.name,file:cb=>cb(file)};
    });
    const nested={isFile:false,isDirectory:true,name:'nested',createReader:()=>{let read=false;return {readEntries:cb=>{cb(read?[]:fileEntries.slice(6));read=true;}};}};
    const folder={isFile:false,isDirectory:true,name:'fixture-folder',createReader:()=>{let read=false;return {readEntries:cb=>{cb(read?[]:[...fileEntries.slice(0,6),nested]);read=true;}};}};
    const event=new Event('drop',{bubbles:true,cancelable:true});
    Object.defineProperty(event,'dataTransfer',{value:{items:[{kind:'file',webkitGetAsEntry:()=>folder}],files:[]}});element.dispatchEvent(event);
  });
  await page.getByText('已选择 12 份采购材料').waitFor();
  await page.getByRole('button',{name:'提取 12 份材料信息'}).click();
  await page.getByText('正在上传，请勿刷新或关闭浏览器；可以关闭此窗口或切换功能。上传完成后，服务端会继续解析。',{exact:true}).waitFor();
  await page.getByRole('button',{name:'关闭',exact:true}).first().click();
  assert.equal(await page.getByText('新建采购需求',{exact:true}).count(),1); // Toolbar only.
  await page.locator('nav button').filter({hasText:'预算审查'}).click();
  await page.waitForFunction(()=>true); // UI navigation is allowed before upload response.
  const deadline=Date.now()+30000;while(!firstResponse){assert.ok(Date.now()<deadline);await new Promise(r=>setTimeout(r,20));}firstResponse();
  while(uploads.length<12){assert.ok(Date.now()<deadline,`Remaining uploads stopped outside dialog: uploads=${uploads.length}, parts=${partWrites}, errors=${JSON.stringify(errors)}`);await new Promise(r=>setTimeout(r,50));}
  assert.ok(uploads[11].bytes>20*1024*1024);assert.equal(new Set(uploads.map(x=>x.key)).size,12);
  await open();await page.getByText(/本批 12 份/).waitFor();
  await page.getByRole('status').filter({hasText:/失败 1 份/}).waitFor();
  await page.getByRole('button',{name:'关闭',exact:true}).first().click();finish=true;
  await page.reload();await page.waitForSelector('nav button');await open();
  await page.getByText('本批 12 份：成功 11 份，失败 1 份，处理中 0 份。',{exact:true}).waitFor({timeout:15000});
  assert.equal(await page.getByText(/刷新进度|重试此文件|已接收.*份采购材料|AI_NO_FIELDS/).count(),0);
  assert.equal(await page.getByText(/^(成功文件|失败文件)：/).count(),0);
  const scrollableMaterialAreas=await page.locator('form section').evaluateAll(sections=>sections.flatMap(section=>[section,...section.querySelectorAll('*')]).filter(element=>['auto','scroll'].includes(getComputedStyle(element).overflowY)).length);
  assert.equal(scrollableMaterialAreas,0,'Material panel must not create nested scroll areas');
  assert.equal(uploads.length,12,'Refresh created another upload identity');assert.deepEqual(errors,[]);assert.deepEqual(external,[]);
  assert.equal(historyRequests,0,'Removed history entry must not fetch other material records');
  await page.screenshot({path:path.join(evidence,`${label}-batch-restored.png`)});
  assert.equal(await page.getByPlaceholder('请输入采购项目名称').inputValue(),fields.projectName);
  assert.equal(await page.getByRole('button',{name:'保存草稿',exact:true}).count(),0);
  await page.evaluate(()=>sessionStorage.setItem('procurement-material-operation:fixture-user',localStorage.getItem('procurement-material-operation:fixture-user')));
  await page.getByRole('button',{name:'清空填充',exact:true}).click();
  assert.equal(await page.getByPlaceholder('请输入采购项目名称').inputValue(),'');
  assert.equal(await page.locator('form textarea').inputValue(),'');
  assert.equal(await page.locator('form input:not([type=file]), form select').evaluateAll(nodes=>nodes.every(node=>node.value==='')),true);
  assert.equal(await page.getByText(/本批 12 份/).count(),0);
  assert.equal(await page.getByText(/^(成功文件|失败文件)：/).count(),0);
  await page.reload();await page.waitForSelector('nav button');await open();
  assert.equal(await page.getByPlaceholder('请输入采购项目名称').inputValue(),'');
  assert.equal(await page.getByText(/本批 12 份/).count(),0);
  // Clearing during a delayed upload must also clear not-yet-uploaded files.
  await page.locator('form input[type=file]').setInputFiles([
    {name:'old-late.pdf',mimeType:'application/pdf',buffer:Buffer.from('old')},
    {name:'old-unsent.pdf',mimeType:'application/pdf',buffer:Buffer.from('unsent')},
  ]);
  await page.getByRole('button',{name:'提取 2 份材料信息'}).click();
  await page.getByText('正在上传，请勿刷新或关闭浏览器；可以关闭此窗口或切换功能。上传完成后，服务端会继续解析。',{exact:true}).waitFor();
  while(!lateResponse)await new Promise(resolve=>setTimeout(resolve,10));
  await page.getByRole('button',{name:'清空填充',exact:true}).click();lateResponse();
  await page.locator('form input[type=file]').setInputFiles({name:'new-demand.pdf',mimeType:'application/pdf',buffer:Buffer.from('new')});
  await page.getByRole('button',{name:'提取 1 份材料信息'}).click();
  await page.getByText('本批 1 份：成功 1 份，失败 0 份，处理中 0 份。',{exact:true}).waitFor();
  assert.equal(await page.getByText(/^(成功文件|失败文件)：/).count(),0);
  assert.equal(uploads.length,14,'Old unsent file must not upload after clearing');
  assert.equal(await page.getByPlaceholder('请输入采购项目名称').inputValue(),fields.projectName);
  await page.getByPlaceholder('请输入申请部门').fill('人工核对部门');
  await page.getByRole('button',{name:'保存并查看结果',exact:true}).click();
  const submittedDeadline=Date.now()+5000;while(!created.length){assert.ok(Date.now()<submittedDeadline);await new Promise(resolve=>setTimeout(resolve,20));}
  assert.deepEqual(created[0].sourceMaterialIds,[uploads[13].key]);
  assert.equal(created[0].sourceProjectId,uploads[13].key);
  assert.equal(created[0].submitForApproval,false);
  assert.equal(created[0].departmentName,'人工核对部门');
  await page.getByRole('status').filter({hasText:'采购需求已保存，正在分析已上传的材料'}).waitFor();
  const reviewDeadline=Date.now()+5000;while(!finishReview){assert.ok(Date.now()<reviewDeadline);await new Promise(r=>setTimeout(r,20));}
  await page.locator('nav button').filter({hasText:'预算审查'}).click();
  finishReview();
  await page.locator('nav button').filter({hasText:'采购审查'}).click();
  await page.locator('tr').filter({hasText:'TEST-AUTO'}).click();
  await page.getByText('TEST-REPORT',{exact:true}).waitFor().catch(async cause=>{
    console.log(JSON.stringify({reviewCalls,projectUploadCalls,errors,body:(await page.locator('body').innerText()).slice(-2200)}));throw cause;
  });
  const completedReviewCalls=reviewCalls;
  await page.reload();await page.waitForSelector('nav button');
  await page.locator('nav button').filter({hasText:'采购审查'}).click();
  await page.locator('tr').filter({hasText:'TEST-AUTO'}).click();
  await page.getByText('TEST-REPORT',{exact:true}).waitFor();
  assert.equal(reviewExecutions,1,'Transport retry must reuse the saved report');
  assert.equal(reviewCalls,completedReviewCalls,'Reopen and reload must not repeat the analysis command');
  assert.equal(projectUploadCalls,0,'Saved intake files must never be uploaded a second time');
  await page.screenshot({path:path.join(evidence,`${label}-save-auto-review.png`)});
  await page.getByRole('button',{name:'返回采购项目列表',exact:true}).click();
  await page.getByRole('button',{name:'新建采购需求',exact:true}).click();
  assert.equal(await page.getByPlaceholder('请输入采购项目名称').inputValue(),'');
  assert.equal(await page.getByText(/本批 1 份/).count(),0,'Next new demand must not reuse previous files');
  assert.deepEqual(errors,[]);
  assert.ok(partWrites>1,'Large procurement files must use resumable chunk upload');
  const report={browser:browser.version(),files:12,firstFailed:true,noSuccessFailureFileLists:true,lostResponseRecovered:true,folderDrag:true,nineHeadersCustomFont:true,resumableLostPartAck:true,largeFileBytes:uploads[11].bytes,clearDuringUpload:true,clearedReloadEmpty:true,newDemandOnlyNewMaterial:true,errors,external};
  writeFileSync(path.join(evidence,`${label}.json`),JSON.stringify(report,null,2));
  console.log(JSON.stringify({...report,saveAutoReview:true,reviewCalls,reviewExecutions,projectUploadCalls,lostReviewResponse}));console.log('FOLDER12_CLOSE_NAVIGATE_FAILURE_ISOLATION_RELOAD_SAME_IDS=PASS');
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
