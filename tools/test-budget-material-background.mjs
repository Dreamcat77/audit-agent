import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFileSync,existsSync,mkdirSync,writeFileSync} from 'node:fs';
import path from 'node:path';
const require=createRequire(new URL('../frontend/package.json',import.meta.url)),{chromium}=require('playwright-core');
const XLSX=require('xlsx');
const [executablePath,distArg,label='budget109']=process.argv.slice(2),dist=path.resolve(distArg);
const evidence=path.resolve('.runtime/budget-background-browser');mkdirSync(evidence,{recursive:true});
const records=new Map(),uploads=[],batchMembers=new Map();let firstResponse,finish=false,referenceReads=0;
const riskHistory=new Map(),riskRequests=[];let loseRiskResponse=true;
const transfers=new Map();let transferPartWrites=0;
let repeatRiskNames=false;
const riskCandidate=row=>({source:{taskId:row.id,lineIndex:0},fileName:row.fileName,uploadedAt:row.task.createdTime,line:{project:repeatRiskNames?'同一设备项目':`合成项目 ${row.fileName}`,subject:'电子设备',subjectCode:'S1',year:'2026',department:'合成部门',declaredAmount:31200,approvalAmount:32760,status:'WITHIN_APPROVAL',opinion:'申报金额未超过材料批复，供工作参考。',sources:[]}});
const failedUploads=new Set([0,13,18]);
const unknownUploads=new Set([17]);
const historyTasks=new Map(Array.from({length:123},(_,i)=>[`history-${i}`,{
  taskId:`history-${i}`,fileName:`以前的预算-${i}.xlsx`,fileSize:100,status:i===0?'PARSED':i===1?'UNKNOWN':'REVIEWED',parsedLineCount:1,errorDetail:null,
  budgetYear:'2025',departmentCode:'FINANCE',reviewCursor:80,pendingRowNumber:null,pendingStep:null,pendingReason:null,
  createdTime:'2025-12-01T00:00:00Z',updatedTime:'2025-12-01T00:00:00Z',
}]));
const historyQueries=[],reviewReads=[];
let delayNextReview=false,lateReviewResponse,historyError=false;
const user={userId:'budget-browser-fixture',username:'fixture',realName:'预算测试',roles:['SYSTEM_ADMIN']};
const procurement={id:'synthetic-procurement',projectCode:'SYNTHETIC-PR',projectName:'仿真采购复制测试',subjectName:'电子设备',subjectCode:'S1',budgetAmount:31200,supplierName:null,status:'REVIEWING',createdTime:'2026-10-15T00:00:00Z',departmentName:'合成部门',procurementType:'GOODS',procurementMethod:'PUBLIC_BIDDING',applicantName:'合成测试',sourceMaterialIds:[],agentReviewStatus:'AVAILABLE',agentRiskLevel:'WARNING'};
const procurementReport={id:'synthetic-report',projectId:procurement.id,reportNumber:'SYNTHETIC-REPORT',generatedTime:'2026-10-15T00:00:00Z',riskLevel:'CRITICAL',overallConclusion:'仿真采购参考结论',project:procurement,agentReview:{riskLevel:'WARNING',suggestion:'仿真预审建议：核对设备参数。',aiSummary:'仿真智能摘要',items:[]},quoteCheck:null,blacklist:{alertCount:0,hits:[]},tender:{findingCount:1,findings:[{riskLevel:'CRITICAL',riskCategory:'资格限制',matchedText:'仿真风险正文',sourceLocator:'paragraph:1'}]},collusion:null,splitPurchase:null};
// Two saved rule hits with identical advice must not create duplicate report rows.
procurementReport.tender.findings.push({...procurementReport.tender.findings[0]});
procurementReport.tender.findingCount=2;
let riskConfirmation=null,confirmationWrites=0;
const server=createServer(async(req,res)=>{
  const url=new URL(req.url,'http://127.0.0.1');
  const respond=data=>{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({code:200,message:'success',data}));};
  if(url.pathname.startsWith('/api/')) {
    if(url.pathname.endsWith('/review-report/export')){assert.equal(url.searchParams.get('reportId'),procurementReport.id);res.writeHead(200,{'Content-Type':'application/vnd.openxmlformats-officedocument.wordprocessingml.document'});res.end(Buffer.from([80,75,3,4]));return;}
    if(url.pathname==='/api/v1/procurement/projects'){respond({items:[procurement],total:1,totalPages:1,page:1,size:20});return;}
    if(url.pathname.endsWith('/review-workflow')){respond({projectId:procurement.id,currentStepOrder:5,status:'IN_PROGRESS',steps:[]});return;}
    if(url.pathname.endsWith('/critical-risk-confirmation')){if(req.method==='POST'){let raw='';for await(const c of req)raw+=c;assert.ok(JSON.parse(raw).idempotencyKey);confirmationWrites++;riskConfirmation={projectId:procurement.id,reportId:procurementReport.id,confirmerName:'合成测试',confirmedTime:new Date().toISOString(),riskLevel:'CRITICAL'};}respond(riskConfirmation);return;}
    if(url.pathname.endsWith('/review-report')){respond(procurementReport);return;}
    if(url.pathname.endsWith('/review-materials/current')){respond({files:[],quote:null,collusion:null,blacklist:null,notices:[],report:procurementReport});return;}
    if(url.pathname.includes('/quote-checks/')||url.pathname.endsWith('/collusion-reviews/latest')){res.writeHead(404,{'Content-Type':'application/json'});res.end(JSON.stringify({code:404,message:'尚无结果',data:null}));return;}
    if(url.pathname==='/api/v1/budget/material-risk/candidates') {
      const focus=url.searchParams.get('taskIds')?.split(',');
      respond({items:[...records.values()].filter(r=>r.status==='PARSED'&&(!focus||focus.includes(r.id))).map(riskCandidate),hasMore:false});return;
    }
    if(url.pathname==='/api/v1/budget/material-risk/browse') {
      const keyword=url.searchParams.get('keyword')??'',offset=Number(url.searchParams.get('cursor')??0);
      const matching=[...records.values()].filter(r=>r.status==='PARSED').reverse().map(riskCandidate).filter(r=>!keyword||JSON.stringify(r).includes(keyword));
      respond({items:matching.slice(offset,offset+12),nextCursor:offset+12<matching.length?String(offset+12):null});return;
    }
    if(url.pathname==='/api/v1/budget/material-risk/history') {
      const keyword=url.searchParams.get('keyword')??'',date=url.searchParams.get('date'),page=Number(url.searchParams.get('page')??1);
      const matches=[...riskHistory.values()].reverse().filter(r=>(!keyword||JSON.stringify(r).includes(keyword))&&(!date||r.createdAt.startsWith(date)));
      respond({items:matches.slice((page-1)*6,page*6).map(r=>({id:r.id,createdAt:r.createdAt,projectCount:r.projects.length,description:r.projects.map(p=>p.line.project+' · '+p.line.subject).join('、'),totalAmount:r.splitAnalysis?.totalAmount??null,bandLabel:r.splitAnalysis?.bandLabel??null})),page,size:6,total:matches.length});return;
    }
    if(url.pathname.startsWith('/api/v1/budget/material-risk/analyses/')){respond(riskHistory.get(url.pathname.split('/').pop()));return;}
    if(url.pathname==='/api/v1/budget/material-risk/analyses') {
      if(req.method!=='POST'){respond([...riskHistory.values()].reverse());return;}
      let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);riskRequests.push(body);
      if(!riskHistory.has(body.id)) {
        const projects=body.selections.map(s=>riskCandidate(records.get(s.taskId)));
        riskHistory.set(body.id,{id:body.id,createdAt:new Date().toISOString(),projects,opinions:[],splitAnalysis:{version:'SELECTED_SUM_V2',riskLevel:'INFO',totalAmount:62400,countedItems:2,summary:'已合计所选 2 项申报金额，所属金额区间：1 万至 10 万元（含边界）。',rules:[],findings:[],exclusions:[],bandCode:'FROM_10K_TO_100K',bandLabel:'1 万至 10 万元（含边界）',complete:true}});
      }
      if(loseRiskResponse){loseRiskResponse=false;res.writeHead(200,{'Content-Type':'application/json'});res.end('{');return;}
      respond(riskHistory.get(body.id));return;
    }
    if(url.pathname==='/api/v1/budget/material-uploads/batches'&&req.method==='POST') {
      let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);
      assert.ok(body.batchId);assert.ok(body.members.length>0);
      if(batchMembers.has(body.batchId))assert.deepEqual(body,batchMembers.get(body.batchId));
      batchMembers.set(body.batchId,body);respond({batchId:body.batchId,status:'OPEN'});return;
    }
    const transferMatch=url.pathname.match(/^\/api\/v1\/material-transfers\/budget\/([^/]+)(?:\/parts\/(\d+)|\/(complete))?$/);
    if(transferMatch&&req.method==='POST'&&!transferMatch[3]) {
      let raw='';for await(const c of req)raw+=c;const spec=JSON.parse(raw),key=transferMatch[1];
      if(transfers.has(key))assert.deepEqual(transfers.get(key).spec,spec);else transfers.set(key,{spec,received:[]});
      const transfer=transfers.get(key);respond({operation:key,status:'OPEN',chunkBytes:4*1024*1024,received:transfer.received,receivedBytes:transfer.received.length*4*1024*1024});return;
    }
    if(transferMatch&&req.method==='PUT') {
      let size=0;for await(const c of req)size+=c.length;assert.ok(size>0);const transfer=transfers.get(transferMatch[1]);assert.ok(transfer);const index=Number(transferMatch[2]);transferPartWrites++;
      if(!transfer.received.includes(index))transfer.received.push(index);
      // Lost acknowledgement: the next call must replay this same part, not restart the upload.
      if(transferPartWrites===1){res.destroy();return;}
      respond({operation:transferMatch[1],status:'OPEN',chunkBytes:4*1024*1024,received:transfer.received,receivedBytes:0});return;
    }
    if(url.pathname==='/api/v1/budget/material-uploads'&&req.method==='POST'||transferMatch?.[3]==='complete') {
      const key=transferMatch?.[1]??req.headers['idempotency-key'];assert.ok(key);let size=0;
      if(transferMatch){const t=transfers.get(key);assert.equal(t.received.length,t.spec.partHashes.length);size=t.spec.size;}
      else try {for await(const chunk of req)size+=chunk.length;}catch{return;}
      if(uploads.length<19)assert.ok([...batchMembers.values()].some(batch=>batch.members.some(member=>member.operationKey===key)),'membership is durable before file upload');
      if(records.has(key)){respond(records.get(key));return;}
      const i=uploads.length;uploads.push({key,size});
      const task={taskId:key,fileName:`budget-${i}.xlsx`,fileSize:size,status:'REVIEWED',parsedLineCount:i===11?2:1,errorDetail:null,budgetYear:'2026',departmentCode:'PROCUREMENT',reviewCursor:i===11?2:1,pendingRowNumber:null,pendingStep:null,pendingReason:null,createdTime:'2026-10-10T00:00:00Z',updatedTime:'2026-10-10T00:00:00Z'};
      if(i===12)task.status='PARSED';
      const row={id:key,fileName:task.fileName,status:failedUploads.has(i)?'FAILED':unknownUploads.has(i)?'UNKNOWN':'QUEUED',errorCode:failedUploads.has(i)?'BUDGET_PARSE_FAILED':null,updatedAt:task.updatedTime,task};records.set(key,row);
      if(i===0){firstResponse=()=>respond(row);return;}
      if(i===4){res.destroy();return;}
      respond(row);return;
    }
    if(url.pathname.startsWith('/api/v1/budget/material-uploads/')) {
      const row=records.get(url.pathname.split('/').at(-1));if(row&&finish&&row.status!=='FAILED'&&row.status!=='UNKNOWN')row.status='PARSED';respond(row??null);return;
    }
    if(url.pathname.startsWith('/api/v1/budget/reviews/')) {
      const id=url.pathname.split('/').at(-1);reviewReads.push(id);
      const row=records.get(id),task=historyTasks.get(id)??(['FAILED','UNKNOWN'].includes(row.status)?{...row.task,status:row.status}:row.task);
      const items=['REVIEWED','WAITING_CONFIRMATION'].includes(task.status)?[{id:`${id}-item`,taskId:id,rowNumber:1,
        subjectCode:'SYNTHETIC-EXPORT',subjectName:'合成预算导出科目',declaredAmount:123.45,
        supplierName:'合成供应商',projectCode:'SYNTHETIC',approvalSubjectName:'合成预算导出科目',
        approvalAmount:123.45,matchStatus:'MATCHED',matchMethod:'EXACT',matchDistance:0,
        amountStatus:'PASSED',differenceAmount:0,riskLevel:'INFO',abnormalReasons:[],checks:[],
        moduleGroups:[],aiSemanticAnalysis:{available:false},requiredFieldsStatus:'PASSED',
        amountFormatStatus:'PASSED',categoryAttributionStatus:'PASSED',projectCodeStatus:'PASSED',
        projectDepartmentStatus:'PASSED',budgetYearValidityStatus:'PASSED'}]:[];
      if(task.fileName==='budget-11.xlsx')items.push({...items[0],id:`${id}-other`,rowNumber:2,projectCode:'PROJECT-B',subjectCode:'SECOND',subjectName:'合成第二项目科目'});
      let materialReference;
      if(task.fileName==='budget-12.xlsx') {
        referenceReads++;
        materialReference=referenceReads===1?{status:'PENDING',summary:'正在整理同批材料的预算参考结果，完成后会自动显示。',lines:[]}
          :{status:'COMPLETED',summary:'根据本批上传材料整理了以下预算信息与核对意见，供工作参考。',lines:[{
            project:'合成项目',subject:'合成设备',subjectCode:'S1',year:'2026',department:'合成部门',declaredAmount:31200,approvalAmount:32760,
            status:'WITHIN_APPROVAL',opinion:'材料所列申报金额未超过对应批复金额。本结果仅供工作参考，不代表正式审批结论。',
            sources:[{taskId:id,fileName:task.fileName,locator:'excel/sheet:1/row:2',field:'declaredAmount',value:'31200',excerpt:'申报金额：31200'},
              {taskId:'approval',fileName:'合成批复.docx',locator:'docx/body/1',field:'approvalAmount',value:'32760',excerpt:'批复金额：32760'}],
          },...[
            {subject:'仿真超支科目',status:'OVER_APPROVAL',declaredAmount:100.01,approvalAmount:100,opinion:'超过批复 0.01 元，请核对。'},
            {subject:'仿真错支科目',status:'UNAPPROVED_SUBJECT',declaredAmount:20,approvalAmount:null,opinion:'疑似错支，请核对材料完整性。'},
            {subject:'仿真漏项科目',status:'MISSING_DECLARATION',declaredAmount:null,approvalAmount:30,opinion:'疑似漏项，请核对材料完整性。'},
          ].map(line=>({project:'合成项目',year:'2026',department:'合成部门',subjectCode:null,sources:[{taskId:id,fileName:task.fileName,locator:'第2行',field:'subjectName',value:line.subject,excerpt:'合成'}],...line}))]};
      }
      const response=()=>respond({task,allPassed:items.length>0,totalItems:items.length,passedItems:items.length,exceptionItems:0,items,materialReference});
      if(delayNextReview){delayNextReview=false;lateReviewResponse=response;return;}
      response();return;
    }
    if(url.pathname==='/api/v1/budget/validation-records') {
      historyQueries.push(Object.fromEntries(url.searchParams));
      if(historyError){res.writeHead(500,{'Content-Type':'application/json'});res.end(JSON.stringify({code:500,message:'历史结果读取测试失败',data:null}));return;}
      const page=Number(url.searchParams.get('page')??1),size=Number(url.searchParams.get('size')??20);
      const tasks=[...[...records.values()].reverse().map(row=>({...row.task,status:row.status==='FAILED'?'FAILED':row.task.status})),...historyTasks.values()].filter(task=>
        (!url.searchParams.get('fileName')||task.fileName.toLowerCase().includes(url.searchParams.get('fileName').toLowerCase()))&&
        (!url.searchParams.get('createdFrom')||task.createdTime>=new Date(url.searchParams.get('createdFrom')+'T00:00:00+08:00').toISOString())&&
        (!url.searchParams.get('createdTo')||task.createdTime<new Date(new Date(url.searchParams.get('createdTo')+'T00:00:00+08:00').getTime()+86400000).toISOString()));
      respond({records:tasks.slice((page-1)*size,page*size).map(task=>({batchId:task.taskId,fileName:task.fileName,status:task.status,
        budgetYear:task.budgetYear,departmentCode:task.departmentCode,createdTime:task.createdTime,totalRows:80,validRows:0,criticalCount:0,warningCount:0,infoCount:0})),page,size,total:tasks.length});return;
    }
    if(url.pathname==='/api/v1/auth/me'){respond(user);return;}
    if(url.pathname==='/api/v1/auth/csrf'){respond({headerName:'X-CSRF-TOKEN',token:'fixture'});return;}
    if(url.pathname.includes('unread-count')){respond({count:0});return;}
    if(url.pathname.includes('dashboard')){respond({recentTasks:[],statusDistribution:[],riskDistribution:[]});return;}
    respond({items:[],records:[],total:0,page:1,size:20,totalPages:0});return;
  }
  const relative=url.pathname==='/'?'index.html':url.pathname.slice(1),file=path.join(dist,relative);
  if(relative.includes('..')||!existsSync(file)){res.writeHead(404);res.end();return;}
  res.writeHead(200,{'Content-Type':({'.html':'text/html','.js':'application/javascript','.css':'text/css','.woff2':'font/woff2'})[path.extname(file)]||'application/octet-stream'});res.end(readFileSync(file));
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const base=`http://127.0.0.1:${server.address().port}`;
const browser=await chromium.launch({executablePath,headless:true,args:['--disable-background-networking','--no-first-run']});
let testPage;
try {
  const page=testPage=await browser.newPage({viewport:{width:1536,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  const open=async()=>{await page.locator('nav button').filter({hasText:'预算审查'}).click();await page.getByRole('heading',{name:'预算校验',exact:true}).waitFor();};
  await page.goto(base);await page.waitForSelector('nav button');await open();
  const splitRiskTitle=page.getByText('预算项目风险分析',{exact:true});
  const risk=page.getByRole('region',{name:'预算项目风险分析',exact:true});
  const openAutoMaterial=async name=>{
    historyError=false;
    await risk.getByText(name,{exact:true}).waitFor();
    assert.equal(await risk.getByRole('button',{name:'查看材料结果',exact:true}).count(),0);
    await page.getByRole('button',{name:'历史结果',exact:true}).click();
    const list=page.getByRole('region',{name:'预算历史结果'});
    await list.getByLabel('材料名称',{exact:true}).fill(name);
    await list.getByRole('button',{name:'查询',exact:true}).click();
    await list.getByRole('row').filter({has:page.getByText(name,{exact:true})}).getByRole('button',{name:'查看结果',exact:true}).click();
    await page.getByRole('region',{name:'预算文件结果',exact:true}).getByRole('heading',{name,exact:true}).waitFor();
  };
  const assertSplitRiskVisibility=async visible=>{
    assert.equal(await splitRiskTitle.count(),1);
    assert.equal(await splitRiskTitle.isVisible(),visible);
  };
  await assertSplitRiskVisibility(true);
  const assertSimpleHome=async()=>{
    await page.getByText('上传申报材料',{exact:true}).waitFor();
    for(const name of ['查看整体结果','查看预算校验流程和规则','查看完整校验流程','查看历史记录']) {
      assert.equal(await page.getByRole('button',{name,exact:true}).count(),0);
    }
    assert.equal(await page.getByRole('heading',{name:'预算校验概览',exact:true}).count(),0);
    assert.equal(await page.getByRole('region',{name:'预算整体结果',exact:true}).count(),0);
    assert.equal(await page.getByRole('button',{name:'历史结果',exact:true}).count(),1);
  };
  await assertSimpleHome();
  assert.equal(await page.getByText('解析与校验范围',{exact:true}).count(),0);
  assert.equal(await risk.getByRole('button',{name:/进入采购审查/}).count(),0);
  await page.screenshot({path:path.join(evidence,`${label}-upload.png`),fullPage:true});
  await splitRiskTitle.scrollIntoViewIfNeeded();
  await page.screenshot({path:path.join(evidence,`${label}-home-split-risk.png`),fullPage:true});
  await page.getByText('上传申报材料',{exact:true}).evaluate(element=>{
    const files=Array.from({length:12},(_,i)=>new File([new Uint8Array(i===11?21*1024*1024:100)],`budget-${i}.xlsx`));
    const entries=files.map(file=>({name:file.name,isFile:true,isDirectory:false,file:cb=>cb(file)}));
    const folder={name:'folder',isFile:false,isDirectory:true,createReader:()=>{let done=false;return {readEntries:cb=>{cb(done?[]:entries);done=true;}};}};
    const event=new Event('drop',{bubbles:true,cancelable:true});Object.defineProperty(event,'dataTransfer',{value:{items:[{kind:'file',webkitGetAsEntry:()=>folder}],files:[]}});element.dispatchEvent(event);
  });
  await page.getByText('已选择 12 份材料',{exact:true}).first().waitFor();
  await page.getByRole('button',{name:'开始智能解析（12 份）',exact:true}).click();
  await page.getByRole('status').filter({hasText:'正在上传材料，请稍候。'}).waitFor();
  await page.getByLabel('材料解析进度').getByText('正在上传',{exact:true}).first().waitFor();
  assert.ok(await page.getByLabel('材料解析进度').locator('svg.animate-spin').count()>0);
  const progressBoxes=await page.getByLabel('材料解析进度').locator(':scope > div').evaluateAll(nodes=>nodes.slice(0,3).map(n=>{const b=n.getBoundingClientRect();return {x:b.x,y:b.y,width:b.width};}));
  assert.equal(progressBoxes[0].y,progressBoxes[1].y);assert.ok(progressBoxes[1].x>progressBoxes[0].x);assert.ok(progressBoxes[2].y>progressBoxes[0].y);
  await page.setViewportSize({width:540,height:900});assert.equal(await page.getByLabel('材料解析进度').evaluate(n=>getComputedStyle(n).gridTemplateColumns.split(' ').length),1);await page.setViewportSize({width:1536,height:1000});
  assert.equal(await page.getByLabel('智能解析进度').count(),0);
  await page.locator('nav button').filter({hasText:'采购审查'}).click();
  const deadline=Date.now()+15000;while(!firstResponse){assert.ok(Date.now()<deadline);await new Promise(r=>setTimeout(r,10));}firstResponse();
  while(uploads.length<12){assert.ok(Date.now()<deadline);await new Promise(r=>setTimeout(r,20));}
  assert.ok(uploads[11].size>20*1024*1024);finish=true;
  await page.reload();await page.waitForSelector('nav button');await open();
  await page.getByText('上传申报材料',{exact:true}).waitFor();
  await assertSplitRiskVisibility(true);
  // Completion and reload must keep the user on the upload page, without bulk result reads.
  await assertSimpleHome();
  assert.equal(await page.getByText(/^(成功文件|失败文件)：/).count(),0);
  assert.equal(await page.getByLabel('预算材料结果',{exact:true}).count(),0);
  assert.equal(reviewReads.length,0,'Home must not fetch a combined result for completed files');
  assert.equal(uploads.length,12);assert.equal(new Set(uploads.map(u=>u.key)).size,12);
  assert.deepEqual(errors,[]);
  await page.screenshot({path:path.join(evidence,`${label}-restored.png`),fullPage:true});
  await page.locator('nav button').filter({hasText:'采购审查'}).click();await open();
  await page.getByText('上传申报材料',{exact:true}).waitFor();
  await assertSplitRiskVisibility(true);
  assert.equal(await page.getByRole('button',{name:'继续上传资料',exact:true}).count(),0);
  // Start the next batch without erasing any previously saved server task.
  await page.locator('input[type=file]').first().setInputFiles({name:'next-budget.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from('synthetic next budget')});
  await page.getByText('已选择 1 份材料，请点击“开始智能解析”。',{exact:true}).waitFor();
  await page.getByRole('button',{name:'开始智能解析',exact:true}).click();
  const nextDeadline=Date.now()+15000;
  while(uploads.length<13){assert.ok(Date.now()<nextDeadline);await new Promise(r=>setTimeout(r,20));}
  const autoResult=page.getByRole('region',{name:'预算文件结果',exact:true});
  await autoResult.getByRole('heading',{name:'budget-12.xlsx',exact:true}).waitFor();
  await autoResult.getByText('申报金额：￥31,200.00；材料批复金额：￥32,760.00',{exact:true}).waitFor();
  await assertSplitRiskVisibility(false);
  assert.ok(referenceReads>=2,'Pending batch result must poll until the persisted reference is complete');
  assert.ok(reviewReads.every(id=>id===uploads[12].key),'Auto-open must only read this newly submitted file');
  const completedReadCount=reviewReads.length;
  await page.waitForTimeout(3200);
  assert.equal(reviewReads.length,completedReadCount,'Completed result must stop polling');
  await page.getByRole('button',{name:'返回',exact:true}).click();
  await assertSimpleHome();
  await risk.getByRole('button',{name:/^电子 .*项材料记录$/}).waitFor();
  assert.equal(await autoResult.count(),0,'Back must not immediately reopen the completed batch');
  await risk.getByRole('button',{name:/^电子 .*项材料记录$/}).click();
  await risk.getByText('budget-12.xlsx',{exact:true}).waitFor();
  // Classification is by subject, projects remain selectable and analysis history is durable.
  assert.equal(await risk.getByRole('button',{name:/^电子 .*项材料记录$/}).getAttribute('aria-expanded'),'true');
  await risk.getByRole('checkbox').nth(0).check();await risk.getByRole('checkbox').nth(1).check();
  await risk.getByRole('button',{name:'执行风险分析',exact:true}).click();
  await risk.getByRole('alert').waitFor();
  await risk.getByRole('button',{name:'执行风险分析',exact:true}).click();
  await page.getByRole('region',{name:'预算风险参考结果',exact:true}).waitFor();
  const splitResult=page.getByRole('region',{name:'预算风险参考结果',exact:true});
  await splitResult.getByText('金额区间：1 万至 10 万元（含边界）',{exact:true}).waitFor();
  assert.equal(await splitResult.getByLabel('本次所选申报内容',{exact:true}).locator('li').count(),2);
  assert.ok((await splitResult.textContent()).includes('62,400.00'));
  assert.equal(await splitResult.getByText(/材料批复金额/).count(),0,'Split analysis must not repeat individual approval comparison');
  assert.equal(riskRequests.length,2);assert.deepEqual(riskRequests[0],riskRequests[1],'Lost analysis response must replay the immutable operation');
  await page.getByLabel('历史分析列表').getByRole('button').first().waitFor();
  await splitResult.screenshot({path:path.join(evidence,`${label}-risk-history.png`)});
  await risk.getByRole('button',{name:/^电子 .*项材料记录$/}).click();
  await page.waitForTimeout(3200);
  assert.equal(await risk.getByRole('button',{name:/^电子 .*项材料记录$/}).getAttribute('aria-expanded'),'false','Completion must only expand once');
  await risk.getByRole('button',{name:/^电子 .*项材料记录$/}).click();
  await openAutoMaterial('budget-12.xlsx');
  const differences=page.getByLabel('预算差异清单',{exact:true});await differences.waitFor();assert.equal(await differences.locator('li').count(),3);
  assert.equal(await page.getByRole('region',{name:'预算文件结果',exact:true}).locator('article.bg-red-50').count(),3);
  const differenceDownload=page.waitForEvent('download');await page.getByRole('region',{name:'预算文件结果',exact:true}).getByRole('button',{name:'导出',exact:true}).click();
  const differenceBook=XLSX.read(readFileSync(await (await differenceDownload).path()),{type:'buffer'});assert.deepEqual(differenceBook.SheetNames,['预算参考结果','差异清单']);
  const differenceRows=XLSX.utils.sheet_to_json(differenceBook.Sheets['差异清单']);assert.equal(differenceRows.length,3);assert.deepEqual(differenceRows.map(r=>r['差异类型']),['超支','疑似错支','疑似漏项']);
  await page.getByRole('region',{name:'预算文件结果',exact:true}).screenshot({path:path.join(evidence,`${label}-differences.png`)});
  await page.getByText('申报金额：￥31,200.00；材料批复金额：￥32,760.00',{exact:true}).waitFor();
  await page.getByText('来源材料：budget-12.xlsx、合成批复.docx',{exact:true}).waitFor();
  await page.screenshot({path:path.join(evidence,`${label}-uploaded-reference.png`),fullPage:true});
  await page.getByRole('button',{name:'返回',exact:true}).click();
  await page.getByRole('region',{name:'预算历史结果'}).waitFor();await page.getByRole('button',{name:'返回',exact:true}).click();
  await assertSimpleHome();
  assert.equal(await risk.getByRole('button',{name:/^电子 .*项材料记录$/}).getAttribute('aria-expanded'),'true','History return must preserve the manually opened category');
  assert.equal(uploads.length,13);assert.equal(records.size,13);
  const readsBeforeHistory=reviewReads.length;
  await page.getByRole('button',{name:'历史结果',exact:true}).click();
  const history=page.getByRole('region',{name:'预算历史结果'});
  await history.getByText('budget-11.xlsx',{exact:true}).waitFor();
  assert.equal(await page.getByRole('heading',{name:'预算校验',exact:true}).count(),0,'History must not repeat the budget page heading');
  assert.equal(await page.getByText('待校验材料',{exact:true}).count(),0,'History must not repeat the budget summary cards');
  assert.equal(await page.getByRole('button',{name:'返回',exact:true}).count(),1,'History must retain the back button');
  await assertSplitRiskVisibility(false);
  assert.equal(await history.locator('tbody tr').count(),20);
  assert.equal(reviewReads.length,readsBeforeHistory,'Opening history must not fetch or merge every file');
  const openHistoryFile=async name=>{
    await history.getByRole('row').filter({has:page.getByText(name,{exact:true})}).getByRole('button',{name:/^查看(结果|说明)$/}).click();
  };
  const fileResult=page.getByRole('region',{name:'预算文件结果'});
  await openHistoryFile('budget-11.xlsx');
  await fileResult.getByRole('heading',{name:'budget-11.xlsx',exact:true}).waitFor();
  assert.equal(reviewReads.length,readsBeforeHistory+1);
  assert.equal(await fileResult.locator('article').count(),2);
  assert.doesNotMatch(await fileResult.innerText(),/\[F01|NOT_CHECKED|REVIEWED|MATCHED|SQL_ERROR/);
  assert.doesNotMatch(await fileResult.innerText(),/budget-(1|10|12)\.xlsx|以前的预算/);
  const downloading=page.waitForEvent('download');
  await page.getByRole('button',{name:'导出',exact:true}).click();
  const download=await downloading;
  assert.equal(download.suggestedFilename(),'预算材料参考结果.xlsx');
  const exported=XLSX.read(readFileSync(await download.path()),{type:'buffer'});
  assert.deepEqual(exported.SheetNames,['预算参考结果']);
  const exportedRows=XLSX.utils.sheet_to_json(exported.Sheets['预算参考结果']);
  assert.equal(exportedRows.length,2);
  assert.equal(exportedRows[0]['预算科目'],'合成预算导出科目');
  assert.equal(exportedRows[0]['申报金额'],123.45);
  assert.ok(exportedRows.every(row=>row['来源材料'].startsWith('budget-11.xlsx')));
  await page.screenshot({path:path.join(evidence,`${label}-file-result.png`),fullPage:true});
  await page.getByRole('button',{name:'返回',exact:true}).click();
  await history.getByText('budget-11.xlsx',{exact:true}).waitFor();
  for(const [name,message] of [['budget-0.xlsx','本次处理已结束，尚未取得可核实的分析结果，请联系管理员查看关联日志；不能据此判断材料不足或预算没有问题。'],['以前的预算-0.xlsx','这份材料待完成分析，完成后会自动显示结果。'],['以前的预算-1.xlsx','本次处理已结束，尚未取得可核实的分析结果，请联系管理员查看关联日志；不能据此判断材料不足或预算没有问题。']]) {
    await openHistoryFile(name);await fileResult.getByText(message,{exact:true}).waitFor();
    assert.equal(await fileResult.locator('article').count(),0);
    assert.equal(await fileResult.getByRole('button',{name:'导出',exact:true}).count(),0);
    await page.getByRole('button',{name:'返回',exact:true}).click();
  }
  await history.getByRole('button',{name:'下一页',exact:true}).click();
  await history.getByText('以前的预算-22.xlsx',{exact:true}).waitFor();
  await openHistoryFile('以前的预算-22.xlsx');
  await fileResult.getByRole('heading',{name:'以前的预算-22.xlsx',exact:true}).waitFor();
  await page.getByRole('button',{name:'返回',exact:true}).click();
  await history.getByText('共 136 条，第 2 / 7 页',{exact:true}).waitFor();
  await page.screenshot({path:path.join(evidence,`${label}-history.png`),fullPage:true});
  // A late file response must not reopen a result after returning to the list.
  delayNextReview=true;lateReviewResponse=undefined;
  await openHistoryFile('以前的预算-22.xlsx');
  await fileResult.getByText('正在整理预算结果，请稍候。',{exact:true}).waitFor();
  await page.getByRole('button',{name:'返回',exact:true}).click();
  const fileDeadline=Date.now()+5000;while(!lateReviewResponse){assert.ok(Date.now()<fileDeadline);await new Promise(r=>setTimeout(r,10));}
  lateReviewResponse();await page.waitForTimeout(150);
  assert.equal(await fileResult.count(),0);await history.getByText('共 136 条，第 2 / 7 页',{exact:true}).waitFor();
  const filters=history.getByRole('form',{name:'历史结果筛选'});
  const labels=await filters.locator('label').allTextContents();
  assert.match(labels[0],/^上传日期/);assert.match(labels[1],/^材料名称/);
  const dateBox=await filters.getByLabel('上传日期',{exact:true}).boundingBox();
  const nameBox=await filters.getByLabel('材料名称',{exact:true}).boundingBox();
  assert.ok(dateBox&&nameBox&&dateBox.x<nameBox.x&&nameBox.width<=340);
  await filters.getByLabel('上传日期',{exact:true}).fill('2025-12-01');
  await filters.getByLabel('材料名称',{exact:true}).fill('预算-22');
  await filters.getByRole('button',{name:'查询',exact:true}).click();
  await history.getByText('共 1 条，第 1 / 1 页',{exact:true}).waitFor();
  assert.equal(await history.locator('tbody tr').count(),1);
  await openHistoryFile('以前的预算-22.xlsx');
  await fileResult.getByRole('heading',{name:'以前的预算-22.xlsx',exact:true}).waitFor();
  await page.getByRole('button',{name:'返回',exact:true}).click();
  assert.equal(await filters.getByLabel('材料名称',{exact:true}).inputValue(),'预算-22');
  assert.equal(await filters.getByLabel('上传日期',{exact:true}).inputValue(),'2025-12-01');
  await filters.getByLabel('上传日期',{exact:true}).fill('2026-01-01');
  await filters.getByRole('button',{name:'查询',exact:true}).click();
  await history.getByText('没有符合筛选条件的材料',{exact:true}).waitFor();
  await filters.getByRole('button',{name:'重置',exact:true}).click();
  await history.getByText('共 136 条，第 1 / 7 页',{exact:true}).waitFor();
  await page.screenshot({path:path.join(evidence,`${label}-history-filters.png`),fullPage:true});
  await history.getByRole('navigation',{name:'历史结果分页'}).scrollIntoViewIfNeeded();
  await page.screenshot({path:path.join(evidence,`${label}-history-pagination.png`),fullPage:true});
  // All historical results must remain discoverable without browser upload records.
  await page.evaluate(()=>localStorage.clear());await page.reload();await page.waitForSelector('nav button');await open();
  await page.getByRole('button',{name:'历史结果',exact:true}).click();
  await history.getByText('budget-11.xlsx',{exact:true}).waitFor();
  await openHistoryFile('budget-11.xlsx');
  await fileResult.getByRole('heading',{name:'budget-11.xlsx',exact:true}).waitFor();
  assert.equal(await fileResult.locator('article').count(),2);
  await page.getByRole('button',{name:'返回',exact:true}).click();
  await history.getByText('budget-11.xlsx',{exact:true}).waitFor();
  await page.getByRole('button',{name:'返回',exact:true}).click();await page.getByText('上传申报材料',{exact:true}).waitFor();
  // Only the batch explicitly started in this mounted view may open a result.
  finish=false;
  await page.locator('input[type=file]').first().setInputFiles(Array.from({length:3},(_,i)=>({name:`partial-${i}.xlsx`,mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from(`synthetic partial ${i}`)})));
  await page.getByRole('button',{name:'开始智能解析（3 份）',exact:true}).click();
  await page.getByText('材料已上传，正在解析与分析，可离开本页，结果会自动保存。',{exact:true}).waitFor();
  assert.equal(await fileResult.count(),0,'Queued files must not open an empty result');
  await page.setViewportSize({width:1440,height:600});
  await page.locator('.portal-main').evaluate(node=>{node.scrollTop=node.scrollHeight;});
  assert.ok(await page.locator('.portal-main').evaluate(node=>node.scrollTop)>0,'Exercise completion from a scrolled upload page');
  finish=true;
  await fileResult.getByRole('heading',{name:'budget-14.xlsx',exact:true}).waitFor();
  assert.equal(await page.locator('.portal-main').evaluate(node=>node.scrollTop),0,'Auto-open must show the beginning of the result, including attention summary');
  await page.setViewportSize({width:1440,height:1000});
  await page.getByLabel('查看材料',{exact:true}).waitFor();
  assert.equal(await page.getByLabel('查看材料',{exact:true}).locator('option').count(),3,'Partial batch must retain both usable results and the ended-file explanation');
  await page.getByLabel('查看材料',{exact:true}).selectOption(uploads[15].key);
  await fileResult.getByRole('heading',{name:'budget-15.xlsx',exact:true}).waitFor();
  assert.equal(await fileResult.locator('article').count(),1,'Files must stay separate');
  await page.getByText('本次处理已结束，可用结果及未生成结果的说明已保留。',{exact:true}).waitFor();
  assert.equal(await page.getByText('解析与分析已完成，结果已保存。',{exact:true}).count(),0,'Opening one usable result must not claim the whole partial batch succeeded');
  await page.screenshot({path:path.join(evidence,`${label}-auto-result.png`),fullPage:true});
  await page.getByRole('button',{name:'返回',exact:true}).click();
  await assertSimpleHome();
  finish=false;
  await page.locator('input[type=file]').first().setInputFiles({name:'leave-to-history.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from('synthetic leave')});
  await page.getByRole('button',{name:'开始智能解析',exact:true}).click();
  await page.getByText('材料已上传，正在解析与分析，可离开本页，结果会自动保存。',{exact:true}).waitFor();
  historyError=false;await page.getByRole('button',{name:'历史结果',exact:true}).click();
  await history.waitFor();finish=true;await page.waitForTimeout(2400);
  assert.equal(await fileResult.count(),0,'Background completion must not interrupt history');
  await page.getByRole('button',{name:'返回',exact:true}).click();await assertSimpleHome();
  await page.locator('input[type=file]').first().setInputFiles({name:'unknown-budget.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from('synthetic unknown')});
  await page.getByRole('button',{name:'开始智能解析',exact:true}).click();
  await fileResult.getByRole('heading',{name:'budget-17.xlsx',exact:true}).waitFor();
  await fileResult.getByText('本次处理已结束，尚未取得可核实的分析结果，请联系管理员查看关联日志；不能据此判断材料不足或预算没有问题。',{exact:true}).waitFor();
  assert.equal(await fileResult.locator('article').count(),0,'Unknown must not be shown as a successful result');
  const unknownRecord=records.get(uploads[17].key);unknownRecord.status='PARSED';
  await fileResult.getByRole('heading',{name:'budget-17.xlsx',exact:true}).waitFor();
  await page.getByRole('button',{name:'返回',exact:true}).click();
  await assertSimpleHome();
  await page.locator('input[type=file]').first().setInputFiles({name:'failed-budget.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from('synthetic failed')});
  await page.getByRole('button',{name:'开始智能解析',exact:true}).click();
  await fileResult.getByRole('heading',{name:'budget-18.xlsx',exact:true}).waitFor();
  assert.equal(await fileResult.locator('article').count(),0,'A failed batch must not show successful results');
  await page.getByRole('button',{name:'返回',exact:true}).click();
  await assertSplitRiskVisibility(true);
  historyError=true;await page.getByRole('button',{name:'历史结果',exact:true}).click();
  await history.getByRole('alert').waitFor();assert.equal(await history.getByText('暂无历史结果',{exact:true}).count(),0);
  await page.getByRole('button',{name:'返回',exact:true}).click();await page.getByText('上传申报材料',{exact:true}).waitFor();
  assert.ok(historyQueries.some(query=>query.page==='2'));assert.ok(historyQueries.every(query=>Object.keys(query).every(key=>['page','size','fileName','createdFrom','createdTo'].includes(key))),'History must not implicitly filter to current budget year, department, or batch');
  assert.ok(historyQueries.some(query=>query.fileName==='预算-22'&&query.createdFrom==='2025-12-01'&&query.createdTo==='2025-12-01'&&query.page==='1'));
  // Re-selecting a single original file after an uncertain folder upload must
  // retain the immutable upload identity and still open its completed result.
  const recoveredKey='a8178aa2-d3cc-4ff2-bace-6b0f44a3913b';
  await page.evaluate(key=>{
    const operation={key,name:'reselected-budget.xlsx',size:4,lastModified:1234567,relativePath:'folder/reselected-budget.xlsx',context:{budgetYear:'2026',departmentCode:'PROCUREMENT'}};
    localStorage.setItem('budget-material-operation:budget-browser-fixture',JSON.stringify([operation]));
    localStorage.setItem('budget-material-batch:budget-browser-fixture',JSON.stringify([{operation,phase:'UNKNOWN',result:null,error:null}]));
  },recoveredKey);
  await page.reload();await page.waitForSelector('nav button');await open();
  await page.locator('input[type=file]').first().evaluate(input=>{
    const data=new DataTransfer();data.items.add(new File(['test'],'reselected-budget.xlsx',{lastModified:1234567}));
    input.files=data.files;input.dispatchEvent(new Event('change',{bubbles:true}));
  });
  await page.getByRole('button',{name:'开始智能解析',exact:true}).click();
  await fileResult.getByRole('heading',{name:'budget-19.xlsx',exact:true}).waitFor();
  assert.equal(uploads[19].key,recoveredKey,'Original folder identity must survive file reselection');
  await page.getByRole('button',{name:'返回',exact:true}).click();
  await assertSimpleHome();
  const liveTask={...historyTasks.get('history-2'),taskId:'history-live',fileName:'正在处理的预算.xlsx',status:'PROCESSING',createdTime:'2026-10-15T00:00:00Z'};
  historyTasks.set(liveTask.taskId,liveTask);historyError=false;
  await page.getByRole('button',{name:'历史结果',exact:true}).click();
  await filters.getByLabel('材料名称',{exact:true}).fill(liveTask.fileName);
  await filters.getByRole('button',{name:'查询',exact:true}).click();
  const liveRow=history.getByRole('row').filter({hasText:liveTask.fileName});
  await liveRow.getByText('解析中',{exact:true}).waitFor();
  assert.equal(await liveRow.getByText('已解析',{exact:true}).count(),0);
  liveTask.status='PARSED';await liveRow.getByText('待完成分析',{exact:true}).waitFor();
  await openHistoryFile(liveTask.fileName);
  await fileResult.getByText('这份材料待完成分析，完成后会自动显示结果。',{exact:true}).waitFor();
  assert.equal(await fileResult.locator('article').count(),0);
  liveTask.status='REVIEWED';
  await fileResult.getByText('系统批复金额：￥123.45',{exact:false}).waitFor();
  assert.equal(await fileResult.locator('article').count(),1);
  await page.getByRole('button',{name:'返回',exact:true}).click();
  await liveRow.getByText('已完成',{exact:true}).waitFor();
  await page.screenshot({path:path.join(evidence,`${label}-status-history.png`),fullPage:true});
  assert.deepEqual(errors,[]);
  await page.reload();await page.waitForSelector('nav button');await open();
  assert.equal(await risk.getByRole('button',{name:/^电子 .*项材料记录$/}).getAttribute('aria-expanded'),'false','Reload must open budget home without sticky expansion');
  await page.getByLabel('历史分析列表').getByRole('button').first().click();
  await page.getByRole('region',{name:'预算风险参考结果',exact:true}).waitFor();
  assert.equal(await page.getByRole('region',{name:'预算风险参考结果',exact:true}).getByLabel('本次所选申报内容',{exact:true}).locator('li').count(),2,'Saved amount analysis must reopen after reload');
  const legacyId='legacy-material-risk';riskHistory.set(legacyId,{id:legacyId,createdAt:'2020-01-01T00:00:00Z',projects:[],opinions:['旧版单项金额比较']});
  await page.reload();await page.waitForSelector('nav button');await open();
  await page.getByLabel('历史分析列表').getByRole('button').first().click();
  await page.getByRole('region',{name:'预算风险参考结果',exact:true}).getByText(/这份历史结果未包含累计金额门槛分析/).waitFor();
  // Project pages and searches preserve explicit choices; history remains bounded and searchable.
  assert.equal(await page.getByText('读取更早材料',{exact:true}).count(),0);
  await risk.getByRole('button',{name:/^电子 .*项材料记录$/}).click();
  await risk.getByRole('checkbox').first().check();
  await risk.getByLabel('项目分页',{exact:true}).getByRole('button',{name:'下一页',exact:true}).click();
  await risk.getByLabel('项目分页',{exact:true}).getByText('第 2 页 · 每页最多 12 项',{exact:true}).waitFor();
  await risk.getByRole('checkbox').first().check();
  await risk.getByText('已选择 2 条材料记录',{exact:false}).waitFor();
  await risk.getByLabel('搜索项目或材料',{exact:true}).fill('budget-12.xlsx');
  await risk.getByLabel('项目查询',{exact:true}).getByRole('button',{name:'查询',exact:true}).click();
  await risk.getByText('budget-12.xlsx',{exact:true}).waitFor();
  assert.equal(await risk.getByRole('checkbox').count(),1);
  await risk.getByText('已选择 2 条材料记录',{exact:false}).waitFor();
  await risk.getByRole('button',{name:'清空选择',exact:true}).click();
  await risk.getByText('已选择 0 条材料记录',{exact:true}).waitFor();
  for(let i=0;i<14;i++)riskHistory.set(`history-risk-${i}`,{id:`history-risk-${i}`,createdAt:'2020-01-02T12:00:00Z',projects:[{...riskCandidate(records.values().next().value),line:{project:`分页历史-${i}`,subject:'电子设备'}}],opinions:[]});
  await page.reload();await page.waitForSelector('nav button');await open();
  const analysisHistory=page.getByLabel('风险分析历史',{exact:true});
  await analysisHistory.getByLabel('历史分析列表',{exact:true}).getByRole('button').first().waitFor();
  assert.equal(await analysisHistory.getByLabel('历史分析列表',{exact:true}).getByRole('button').count(),6);
  await analysisHistory.getByLabel('历史分析分页',{exact:true}).getByRole('button',{name:'下一页',exact:true}).click();
  await analysisHistory.getByText(/共 16 条 · 第 2 页/).waitFor();
  assert.equal(await analysisHistory.getByLabel('历史分析列表',{exact:true}).getByRole('button').count(),6);
  await analysisHistory.getByLabel('分析日期',{exact:true}).fill('2020-01-02');
  await analysisHistory.getByLabel('搜索历史分析',{exact:true}).fill('分页历史-13');
  await analysisHistory.getByLabel('历史分析查询',{exact:true}).getByRole('button',{name:'查询',exact:true}).click();
  await analysisHistory.getByText(/共 1 条 · 第 1 页/).waitFor();
  assert.equal(await analysisHistory.getByLabel('历史分析列表',{exact:true}).getByRole('button').count(),1);
  await risk.screenshot({path:path.join(evidence,`${label}-search-pagination.png`)});
  repeatRiskNames=true;
  await page.reload();await page.waitForSelector('nav button');await open();
  await risk.getByRole('button',{name:/^电子 .*项材料记录$/}).click();
  await risk.getByRole('checkbox').nth(0).check();await risk.getByRole('checkbox').nth(1).check();
  await risk.getByText(/所选内容中有 1 组项目/).waitFor();
  await risk.getByRole('button',{name:'执行风险分析',exact:true}).click();
  await page.getByRole('region',{name:'预算风险参考结果',exact:true}).getByText('申报金额合计：￥62,400.00',{exact:true}).waitFor();
  assert.equal(riskRequests.at(-1).selections.length,2,'A warning must not silently remove a selected source');
  await risk.screenshot({path:path.join(evidence,`${label}-repeated-material-warning.png`)});
  await risk.getByRole('button',{name:'清空选择',exact:true}).click();
  assert.equal(await risk.getByText(/所选内容中有/).count(),0);
  await page.locator('nav button').filter({hasText:'采购审查'}).click();
  await page.getByRole('row').filter({hasText:procurement.projectName}).click();
  await page.getByText('仿真预审建议：核对设备参数。',{exact:true}).waitFor();
  const reportDownload=page.waitForEvent('download');await page.getByRole('button',{name:'导出完整报告',exact:true}).click();const reportFile=await reportDownload;assert.equal(reportFile.suggestedFilename(),'采购审查参考报告.docx');assert.deepEqual(readFileSync(await reportFile.path()),Buffer.from([80,75,3,4]));
  assert.equal(await page.getByText('提交审批',{exact:true}).count(),0);
  for(const name of ['查看全部步骤','审查中','当前操作 · 第 2 步','处理进度 2 / 2'])assert.equal(await page.getByText(name,{exact:true}).count(),0);
  const tenderSummary=page.getByLabel('风险提示',{exact:true}).getByText('招标文件合规审查 · 资格限制：仿真风险正文',{exact:true});
  await tenderSummary.waitFor();
  assert.equal(await tenderSummary.count(),1);
  assert.equal(await page.getByText(/paragraph:1/).count(),0);
  assert.equal(await page.getByText(/流程进度.*\/ 3/).count(),0);
  const reportBlock=page.locator('[data-copy-result]').filter({has:page.getByRole('heading',{name:'智能体预审',exact:true})}).last();
  await page.context().grantPermissions(['clipboard-read','clipboard-write']);
  await reportBlock.getByRole('button',{name:'复制本块结果',exact:true}).click();
  const copied=await page.evaluate(()=>navigator.clipboard.readText());
  assert.match(copied,/仿真预审建议：核对设备参数/);assert.doesNotMatch(copied,/智能体预审|风险等级|黑名单比对|询比价分析|复制/);
  await page.evaluate(()=>Object.defineProperty(window,'isSecureContext',{value:false,configurable:true}));
  const blacklistBlock=page.locator('[data-copy-result]').filter({has:page.getByRole('heading',{name:'黑名单比对',exact:true})}).last();
  await blacklistBlock.getByRole('button',{name:'复制本块结果',exact:true}).click();
  await page.waitForFunction(()=>[...document.querySelectorAll('button')].filter(n=>n.textContent==='已复制').length===1);
  assert.equal(await reportBlock.getByRole('button',{name:'复制本块结果',exact:true}).innerText(),'复制');
  const fallbackCopied=await page.evaluate(()=>navigator.clipboard.readText());
  assert.match(fallbackCopied,/未命中供应商黑名单/);assert.doesNotMatch(fallbackCopied,/仿真预审建议|复制/);
  assert.ok(await page.getByRole('button',{name:'复制本块结果',exact:true}).count()>=7);
  for(const title of ['智能体预审','询比价分析','黑名单比对','招标文件合规审查','围串标评分','拆分采购识别']){
    const block=page.locator('[data-copy-result]').filter({has:page.getByRole('heading',{name:title,exact:true})}).last();
    await block.getByRole('button',{name:'复制本块结果',exact:true}).click();
    const text=await page.evaluate(()=>navigator.clipboard.readText());assert.ok(text.trim());assert.doesNotMatch(text,/风险等级：|综合风险：|SYNTHETIC-REPORT|已复制|确认已知悉/);
    assert.ok(!text.startsWith(title+'\n'));assert.equal(await page.locator('button').filter({hasText:/^已复制$/}).count(),1);
  }
  const tenderBlock=page.locator('[data-copy-result]').filter({has:page.getByRole('heading',{name:'招标文件合规审查',exact:true})}).last();
  assert.equal(await tenderBlock.getByText('资格限制：仿真风险正文',{exact:true}).count(),1);
  await tenderBlock.getByText('风险 1 条',{exact:true}).waitFor();
  await tenderBlock.getByRole('button',{name:'确认',exact:true}).click();await page.getByRole('dialog').waitFor();
  await page.getByRole('button',{name:'确认已知悉严重风险',exact:true}).click();await tenderBlock.getByRole('button',{name:'已确认',exact:true}).waitFor();assert.equal(confirmationWrites,1);
  await page.reload();await page.waitForSelector('nav button');await page.locator('nav button').filter({hasText:'采购审查'}).click();await page.getByRole('row').filter({hasText:procurement.projectName}).click();
  await tenderBlock.getByRole('button',{name:'已确认',exact:true}).waitFor();assert.equal(confirmationWrites,1);assert.equal(await tenderBlock.getByRole('button',{name:'已确认',exact:true}).isDisabled(),true);
  await page.screenshot({path:path.join(evidence,`${label}-procurement-copy.png`),fullPage:true});
  assert.deepEqual(errors,[]);
  procurementReport.id='legacy-status-report';procurementReport.riskLevel='WARNING';procurementReport.agentReview={riskLevel:'WARNING',suggestion:'请确认采购项目当前审批状态',aiSummary:'尚未提交审批',items:[{checkType:'PROJECT_STATUS',checkName:'采购项目状态',status:'WARNING',riskLevel:'WARNING',detail:'采购项目尚未进入提交或审批通过状态',suggestion:'请确认采购项目当前审批状态'}]};procurementReport.tender={findingCount:0,findings:[]};
  await page.reload();await page.waitForSelector('nav button');await page.locator('nav button').filter({hasText:'采购审查'}).click();await page.getByRole('row').filter({hasText:procurement.projectName}).click();
  await page.getByLabel('风险提示',{exact:true}).getByText('已完成的检查未发现需要关注的问题。未提供材料或数据不足的部分，请结合原材料核实。',{exact:true}).waitFor();
  assert.doesNotMatch(await page.locator('body').innerText(),/尚未进入提交|请确认采购项目当前审批|尚未提交审批|审查中|当前操作 · 第|处理进度 2/);
  assert.equal(await page.locator('nav button').filter({hasText:'我的待办'}).count(),0);
  assert.equal(await page.getByRole('button',{name:'查看参考结果',exact:true}).count(),0);
  await page.getByRole('button',{name:'返回上传材料',exact:true}).click();
  await page.getByRole('heading',{name:'采购材料审查',exact:true}).waitFor();
  assert.equal(await page.getByRole('button',{name:'返回上传材料',exact:true}).count(),0);
  await page.getByRole('button',{name:'查看已有结果',exact:true}).click();
  await page.getByLabel('风险提示',{exact:true}).waitFor();
  await page.screenshot({path:path.join(evidence,label+'-legacy-status.png'),fullPage:true});
  assert.deepEqual(errors,[]);
  const report={browser:browser.version(),syntheticFiles:12,folderDrag:true,fileOver20MB:true,failureIsolation:true,lostUploadResponseRecovered:true,leaveAndReloadKeepsHome:true,fileNameStatusSpinner:true,noScopeDescription:true,autoExpandCurrentCompletedMaterialsOnce:true,collapseReturnReloadNotReexpanded:true,subjectCategories:true,riskHistoryReopensAfterReload:true,lostAnalysisResponseSameIdentity:true,noProcurementApprovalStage:true,noWorkflowStepBar:true,riskSummaryNamesSectionAndEvidence:true,legacyStatusWarningRemovedWithoutRewritingReport:true,copyEachOfSixReportSections:true,copySecureClipboardAndHttpFallback:true,previousHistoryFiltersPaginationBackExportRegression:true,unknownAndFailedNotSuccess:true,historyNotInterrupted:true,errors};
  writeFileSync(path.join(evidence,`${label}.json`),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
} catch(error) {
  if(testPage){writeFileSync(path.join(evidence,`${label}-failure.txt`),await testPage.locator('body').innerText());await testPage.screenshot({path:path.join(evidence,`${label}-failure.png`),fullPage:true});}
  throw error;
} finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
