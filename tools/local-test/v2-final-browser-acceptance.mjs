/** Root-operated final V2 probe, adapted from closeout-20261016/acceptance_browser.mjs.
 * Default is entirely offline. --execute reads only an EXISTING completed Run/report.
 * Headed ephemeral browser; native clipboard + real Ctrl+V, never Clipboard/API mocks.
 * Only login/logout writes are admitted. Root owns actual PID/DM8/model provenance.
 */
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { StringDecoder } from 'node:string_decoder';
import { assertReviewOpinionMetadata } from '../../frontend/src/features/contract/reviewRunOpinionMetadata.ts';

const ROOT=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const RUNTIME=path.join(ROOT,'.runtime/contract-repair-test');
const OUTPUT=path.join(RUNTIME,'v2-final-acceptance-20261017');
const UI='http://127.0.0.1:5173';
const API='http://127.0.0.1:8888';
const IDS=['QUANTITATIVE','DRAFT_STRUCTURE','BREACH','TERM','PARTY_QUALIFICATION','ATTACHMENT_COMPLETENESS','EFFECTIVENESS_COPIES','CLAUSE_RISK'];
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
class SafeFailure extends Error {}
const OBSERVE_MARKER='【联调参考意见｜金额相关模型表述尚待人工复核】';
const check=(value,code)=>{if(!value)throw new SafeFailure(code);};
const sha=value=>createHash('sha256').update(value).digest('hex');
const normalized=value=>value.replace(/\r\n?/g,'\n');
const contains=(text,value)=>normalized(text).includes(normalized(value));
const words=values=>values.filter(value=>typeof value==='string'&&value.length>0);
const occurrences=(text,value)=>normalized(text).split(normalized(value)).length-1;
const conciseValues=p=>words([p.scopeStatement,p.overallAdvice,p.overallOpinion,...p.categories.flatMap(c=>[c.title,c.statusLabel,c.conciseOpinion,...(c.uncoveredScopes??[])])]);
const detailValues=p=>[...conciseValues(p),...p.categories.flatMap(c=>words([c.summary,...(c.items??[]).flatMap(i=>
  [i.title,i.summary,i.actualValue,i.expectedValue,i.recommendation,...(i.typedFacts??[]),...(i.evidence??[]).flatMap(e=>[e.fileName,e.excerpt])])].flat()))];

export function parseOptions(args) {
  const o={execute:false,clipboard:'native',minItems:0,minEvidence:0,testAccountStdin:false};
  const names={'--expected-sha':'expectedSha','--scenario':'scenario','--project-id':'runProjectId','--contract-id':'contractId','--run-id':'runId',
    '--report-id':'reportId','--clipboard':'clipboard','--min-items':'minItems','--min-evidence':'minEvidence','--expected-report-sha256':'expectedReportSha256'};
  const seen=new Set();
  for(let n=0;n<args.length;n++) {
    const arg=args[n];check(!seen.has(arg),'DUPLICATE_OPTION');seen.add(arg);
    if(arg==='--execute')o.execute=true;
    else if(arg==='--test-account-stdin')o.testAccountStdin=true;
    else {check(Object.hasOwn(names,arg)&&args[n+1]&&!args[n+1].startsWith('--'),'INVALID_OPTION');o[names[arg]]=args[++n];}
  }
  check(['native','denied'].includes(o.clipboard),'INVALID_CLIPBOARD_MODE');
  for(const key of ['minItems','minEvidence']) {check(/^\d+$/.test(String(o[key])),'INVALID_MINIMUM');o[key]=Number(o[key]);check(Number.isSafeInteger(o[key]),'INVALID_MINIMUM');}
  if(o.expectedReportSha256)check(/^[a-f0-9]{64}$/.test(o.expectedReportSha256),'INVALID_REPORT_HASH');
  if(o.execute) {
    check(/^[a-f0-9]{40}$/.test(o.expectedSha??''),'EXPECTED_SHA_REQUIRED');
    for(const key of ['runProjectId','contractId','runId','reportId'])check(UUID.test(o[key]??''),'EXACT_EXISTING_RESOURCE_REQUIRED');
    check(/^V2-0[1-6]$/.test(o.scenario??''),'EXACT_V2_SCENARIO_REQUIRED');
  }
  return o;
}
export function requestPolicy(address,method,o) {
  try {
    if(/%(?:2e|2f|5c)|\\/i.test(address))return 'BLOCK';
    const url=new URL(address);if(![UI,API].includes(url.origin)||url.username||url.password)return 'BLOCK';
    if(method==='POST'&&['/api/v1/auth/login','/api/v1/auth/logout'].includes(url.pathname)&&!url.search)return 'AUTH';
    if(!['GET','HEAD'].includes(method))return 'BLOCK';
    if(!url.pathname.startsWith('/api/v1/'))return url.origin===UI?'READ':'BLOCK';
    const parts=url.pathname.split('/').filter(Boolean);
    if(parts[2]==='projects'&&parts[3]&&!['preparation-drafts','intake-drafts'].includes(parts[3])
      &&(!UUID.test(parts[3])||parts[3]!==o.runProjectId))return 'BLOCK';
    if(parts.includes('review-runs')) {
      const n=parts.indexOf('review-runs');
      if(parts[3]!==o.runProjectId||parts[n-1]!==o.contractId||(parts[n+1]&&![o.runId,'latest'].includes(parts[n+1])))return 'BLOCK';
      const report=parts.indexOf('reports');if(report>=0&&parts[report+1]&&parts[report+1]!==o.reportId)return 'BLOCK';
    }
    return 'READ';
  }catch{return 'BLOCK';}
}
export function validateRuntimeIdentity(manifest,ledger,expectedSha) {
  check(manifest?.sourceSha===expectedSha&&ledger?.sourceSha===expectedSha&&manifest.semanticCheckpointsEnabled===true
    &&manifest.reviewWorker===true&&manifest.fileProcessingWorker===true,'CANDIDATE_RUNTIME_RECEIPTS_MISMATCH');
  check(Array.isArray(ledger.entries)&&['Backend','Frontend','FastAPI'].every(service=>ledger.entries.filter(e=>e.service===service&&e.sourceSha===expectedSha).length===1),
    'CANDIDATE_RUNTIME_RECEIPTS_MISMATCH');
}
export function summaryContract(run) {
  const p=run?.businessProjection;
  check(run?.status==='COMPLETED'&&p?.version===1&&[2,3,4].includes(p.opinionVersion)&&p.runId===run.runId&&p.runStatus===run.status,'FROZEN_OPINION_VERSION_OR_RUN_UNPROVEN');
  try{assertReviewOpinionMetadata(p);}catch{throw new SafeFailure('FROZEN_OPINION_METADATA_INVALID');}
  if(p.opinionVersion===4)check(typeof p.overallOpinion==='string'&&p.overallOpinion.trim(),'CANONICAL_OPINION_UNAVAILABLE');
  check(typeof p.overallAdvice==='string'&&p.overallAdvice.trim()&&typeof p.scopeStatement==='string','OVERALL_ADVICE_UNAVAILABLE');
  check(p.categories?.length===8&&p.categories.every((c,index)=>c.id===IDS[index]&&typeof c.conciseOpinion==='string'&&c.conciseOpinion.trim()&&Array.isArray(c.items)),
    'EIGHT_SUMMARIES_UNAVAILABLE');return p;
}
function checkObserveMarker(text,p,code) {
  if(p.monetaryObservation?.mode==='OBSERVE')check(occurrences(text,OBSERVE_MARKER)===1,code);
}
export function checkOpinions(concise,detail,p,runId) {
  check(contains(concise,runId)&&contains(detail,runId),'COPY_RUN_ID_MISSING');
  checkObserveMarker(concise,p,'CONCISE_COPY_OBSERVE_MARKER_MISSING');checkObserveMarker(detail,p,'FULL_COPY_OBSERVE_MARKER_MISSING');
  if(p.opinionVersion===4) {
    const text=normalized(concise),separator=text.indexOf('\n\n');
    check(separator>=0&&text.slice(separator+2)===normalized(p.overallOpinion),'CANONICAL_PARAGRAPH_COPY_DIFFERS');
  }else check(conciseValues(p).every(v=>contains(concise,v)),'CONCISE_COPY_DROPPED_BUSINESS_CONCLUSION');
  check(detailValues({...p,overallAdvice:''}).filter(v=>!p.categories.some(c=>c.conciseOpinion===v)).every(v=>contains(detail,v)),'FULL_COPY_DROPPED_FACT_OR_EVIDENCE');
  const rows=[...new Map(p.categories.flatMap(c=>c.items??[]).map(i=>[i.stableId,i])).values()],titles=new Map();
  for(const item of rows){const line=`- ${item.title}：${item.summary}`;titles.set(line,(titles.get(line)??0)+1);}
  for(const [line,expected]of titles)check(occurrences(detail,line)>=expected,'FULL_COPY_MISSING_REPEATED_CHECK');
  check(occurrences(detail,'  来源：')>=rows.reduce((n,i)=>n+(i.evidence?.length??0),0),'FULL_COPY_MISSING_EVIDENCE_REFERENCE');
}
export function checkCategoryOpinions(concise,detail,category,p,runId) {
  checkObserveMarker(concise,p,'CATEGORY_CONCISE_OBSERVE_MARKER_MISSING');checkObserveMarker(detail,p,'CATEGORY_FULL_OBSERVE_MARKER_MISSING');
  check(contains(concise,category.conciseOpinion)&&contains(concise,runId)&&contains(detail,runId),'CATEGORY_COPY_BINDING_MISMATCH');
  check(detailValues({scopeStatement:p.scopeStatement,categories:[category]}).filter(v=>v!==category.conciseOpinion).every(v=>contains(detail,v)),'CATEGORY_FULL_DETAIL_DROPPED');
  check(occurrences(detail,'  来源：')===category.items.reduce((n,i)=>n+(i.evidence?.length??0),0),'CATEGORY_EVIDENCE_REFERENCE_COUNT_DIFFERS');
}
export function checkPageOpinion(paragraph,markers,p) {
  if(p.opinionVersion===4)check(typeof paragraph==='string'&&normalized(paragraph)===normalized(p.overallOpinion),'PAGE_CANONICAL_PARAGRAPH_DIFFERS');
  if(p.monetaryObservation?.mode==='OBSERVE')check(markers.length===1&&markers[0]===OBSERVE_MARKER,'PAGE_OBSERVE_MARKER_MISSING');
  else check(markers.length===0,'PAGE_UNEXPECTED_OBSERVE_MARKER');
}
export function checkHtmlOpinion(text,p) {
  checkObserveMarker(text,p,'HTML_OBSERVE_MARKER_MISSING');
  if(p.opinionVersion===4)check(contains(text,p.overallOpinion),'HTML_CANONICAL_PARAGRAPH_DIFFERS');
}
export function checkHtmlReport(html,p) {
  checkHtmlOpinion(html.text,p);
  const facts=p.opinionVersion===4?{...p,overallAdvice:''}:p;
  check(detailValues(facts).every(v=>contains(html.text,v))&&html.details===8
    &&html.articles===p.categories.reduce((n,c)=>n+c.items.length,0)
    &&html.evidence===p.categories.reduce((n,c)=>n+c.items.reduce((m,i)=>m+(i.evidence?.length??0),0),0),'HTML_CONCISE_OR_FULL_DETAIL_DIFFERS');
}
export function validateReportMetadata(report,run,o) {
  check(run.status==='COMPLETED'&&report?.reportId===o.reportId&&report.status==='AVAILABLE'&&report.reviewRunId===run.runId&&report.reportSchemaVersion===3
    &&Number.isSafeInteger(report.artifactSize)&&report.artifactSize>0,'EXISTING_COMPLETED_V3_REPORT_REQUIRED_NO_GENERATION');
}
async function readApi(page,relative,bytes=false) {
  const value=await page.evaluate(async({relative,bytes})=>{
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),10000);
    try {
      const response=await fetch(relative,{credentials:'include',redirect:'error',signal:controller.signal});if(response.status!==200)return null;
      const body=await response.arrayBuffer();if(body.byteLength>16_000_000)return null;
      if(bytes)return {bytes:Array.from(new Uint8Array(body)),type:response.headers.get('content-type')??''};
      const data=JSON.parse(new TextDecoder().decode(body));return data.code===200?data.data:null;
    }catch{return null;}finally{clearTimeout(timer);}
  },{relative,bytes});check(value!==null,'READ_API_UNPROVEN');return value;
}
async function manualLogin(page) {
  process.stderr.write('Complete the authorized test account login in the visible browser. No credentials are read by this probe.\n');
  await page.getByRole('navigation',{name:'业务工具',exact:true}).waitFor({timeout:180000});
  check((await readApi(page,'/api/v1/auth/me')).username==='test_admin','APPROVED_ACCOUNT_REQUIRED');
}
export async function readHiddenTestAccountPassword(input=process.stdin,output=process.stderr,environment=process.env) {
  check(!environment.PWDEBUG&&!/pw|\*/i.test(environment.DEBUG??''),'TEST_ACCOUNT_STDIN_DEBUG_LOGGING_UNSUPPORTED');
  check(input.isTTY&&typeof input.setRawMode==='function','TEST_ACCOUNT_STDIN_REQUIRES_TTY');
  const wasRaw=Boolean(input.isRaw),wasPaused=input.isPaused(),decoder=new StringDecoder('utf8');
  let password='';
  output.write('Authorized test_admin password (hidden): ');
  return await new Promise((resolve,reject)=>{
    const cleanup=()=>{input.removeListener('data',data);input.removeListener('end',ended);input.removeListener('error',failed);
      input.setRawMode(wasRaw);if(wasPaused)input.pause();output.write('\n');};
    const stop=code=>{cleanup();password='';reject(new SafeFailure(code));};
    const ended=()=>stop('TEST_ACCOUNT_STDIN_ENDED'),failed=()=>stop('TEST_ACCOUNT_STDIN_UNAVAILABLE');
    const data=chunk=>{
      const text=typeof chunk==='string'?chunk:decoder.write(chunk);
      for(const character of text) {
        if(character==='\u0003'||character==='\u0004'){stop('TEST_ACCOUNT_STDIN_CANCELLED');return;}
        if(character==='\r'||character==='\n') {
          if(!password.trim()){stop('TEST_ACCOUNT_STDIN_EMPTY');return;}
          const value=password;cleanup();password='';resolve(value);return;
        }
        if(character==='\u007f'||character==='\b'){password=Array.from(password).slice(0,-1).join('');continue;}
        if(character.charCodeAt(0)<32)continue;
        password+=character;if(password.length>4096){stop('TEST_ACCOUNT_STDIN_BOUND');return;}
      }
    };
    input.on('data',data);input.once('end',ended);input.once('error',failed);input.setRawMode(true);input.resume();
  });
}
async function testAccountLogin(page,password) {
  if(typeof password!=='string')return await manualLogin(page);
  await page.locator('#login-username').fill('test_admin');await page.locator('#login-password').fill(password);
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.getByRole('navigation',{name:'业务工具',exact:true}).waitFor({timeout:180000});
  check((await readApi(page,'/api/v1/auth/me')).username==='test_admin','APPROVED_ACCOUNT_REQUIRED');
}
async function openRun(page,run,prefix,observer) {
  observer.stage='RUN_NAVIGATION';
  const nav=page.getByRole('navigation',{name:'业务工具',exact:true});
  await nav.getByRole('button',{name:'工作台',exact:true}).click();await nav.getByRole('button',{name:'合同审核',exact:true}).click();
  await page.getByRole('navigation',{name:'合同审核中心',exact:true}).getByRole('button',{name:'审核记录',exact:true}).click();
  const snapshot=JSON.parse(run.contractSnapshotCanonicalJson),keyword=snapshot.contractNo??snapshot.contract?.contractNo;
  check(typeof keyword==='string'&&keyword.length>0,'FROZEN_SEARCH_IDENTITY_UNKNOWN');
  const response=page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/v1/contract-audit-tasks'&&new URL(r.url()).searchParams.get('keyword')===keyword);
  await page.getByPlaceholder('项目、合同或供应商',{exact:true}).fill(keyword);
  const listed=(await(await response).json()).data,index=listed?.items?.findIndex(item=>item.reviewRun?.runId===run.runId);
  check(index>=0,'EXACT_RUN_NOT_IN_VISIBLE_HISTORY_PAGE');
  await page.getByText('正在同步内审任务',{exact:true}).waitFor({state:'hidden'});
  const rows=page.locator('tbody tr'),offset=await rows.count()-listed.items.length;check(offset>=0,'HISTORY_ROW_IDENTITY_UNKNOWN');
  const selected=page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname===prefix);
  await rows.nth(index+offset).getByRole('button',{name:'查看',exact:true}).click();check((await(await selected).json()).data?.runId===run.runId,'WRONG_RUN_OPENED');
  const panel=page.locator('section[aria-labelledby="eight-category-title"]');await panel.waitFor({state:'attached'});await panel.scrollIntoViewIfNeeded();await panel.waitFor({state:'visible'});
}
export async function preview(panel,label,summary) {
  // Native closed details excludes its textarea from role queries; open the real summary.
  const box=panel.locator(`textarea[aria-label=${JSON.stringify(label)}]`),details=box.locator('..');
  if(!await details.getAttribute('open')&&!await box.isVisible())await details.locator('summary').filter({hasText:summary}).click();
  const value=await box.inputValue();check(await box.getAttribute('readonly')!==null,'PREVIEW_NOT_READONLY');
  await box.focus();check(await box.evaluate(n=>n.selectionStart===0&&n.selectionEnd===n.value.length),'PREVIEW_NOT_FULLY_SELECTABLE');return value;
}
async function copy(page,panel,button,expected,mode) {
  if(mode==='native') {
    try{await page.evaluate(()=>navigator.clipboard.writeText('v2-final-native-copy-sentinel'));}catch{throw new SafeFailure('NATIVE_CLIPBOARD_PERMISSION_UNAVAILABLE');}
  }
  await button.click();
  if(mode==='denied') {
    const fallback=panel.getByRole('textbox',{name:'复制失败后的手动复制文本',exact:true});await fallback.waitFor();
    check(await fallback.inputValue()===expected&&await fallback.getAttribute('readonly')!==null,'FALLBACK_COPY_MISMATCH');
    await fallback.focus();check(await fallback.evaluate(n=>n.selectionStart===0&&n.selectionEnd===n.value.length),'FALLBACK_NOT_SELECTABLE');
  }else {
    try{await page.waitForFunction(async expected=>await navigator.clipboard.readText()===expected,expected);}catch{throw new SafeFailure('NATIVE_CLIPBOARD_WRITE_OR_READ_NOT_VERIFIED');}
    await page.evaluate(()=>{const n=document.createElement('textarea');n.id='v2-final-native-paste-target';n.setAttribute('aria-label','临时系统剪贴板粘贴验收');document.body.append(n);});
    const target=page.locator('#v2-final-native-paste-target');
    try{await target.focus();await page.keyboard.press('Control+V');check(await target.inputValue()===expected,'NATIVE_OS_PASTE_MISMATCH');}
    finally{await target.evaluate(n=>n.remove());}
  }
  return {sha256:sha(expected),codepoints:[...expected].length};
}
async function snapshot(page,o,observer,round) {
  observer.stage='RUN_READBACK';const prefix=`/api/v1/projects/${o.runProjectId}/contracts/${o.contractId}/review-runs/${o.runId}`;
  const run=await readApi(page,prefix);check(run?.projectId===o.runProjectId&&run.contractId===o.contractId&&run.runId===o.runId,'RUN_BINDING_INVALID');
  const p=summaryContract(run),frozenHash=sha(JSON.stringify({status:run.status,projection:p,attempts:run.attempts,outputs:run.outputs}));
  await openRun(page,run,prefix,observer);const panel=page.locator('section[aria-labelledby="eight-category-title"]');
  check(await panel.locator('[data-category-row]').count()===8,'PAGE_NOT_EIGHT_CATEGORIES');
  const markerNodes=panel.locator('[data-monetary-observation-marker]'),markerTexts=[];
  for(let index=0;index<await markerNodes.count();index++) {
    check(await markerNodes.nth(index).isVisible(),'PAGE_OBSERVE_MARKER_HIDDEN');markerTexts.push(await markerNodes.nth(index).textContent());
  }
  checkPageOpinion(p.opinionVersion===4?await panel.locator('[data-overall-opinion]').textContent():null,markerTexts,p);
  const concise=await preview(panel,'综合意见手动复制','预览综合意见（简版）'),detail=await preview(panel,'综合完整明细手动复制','预览综合完整明细');
  checkOpinions(concise,detail,p,run.runId);const copies=[],summarySizes=[];
  if(round==='initial') {
    observer.stage='NATIVE_COPY_ALL';
    copies.push(await copy(page,panel,panel.getByRole('button',{name:'复制综合意见',exact:true}),concise,o.clipboard));
    copies.push(await copy(page,panel,panel.getByRole('button',{name:'复制综合完整明细',exact:true}),detail,o.clipboard));
  }
  for(const c of p.categories) {
    observer.stage='CATEGORY_COPY_AND_DETAIL';const row=panel.locator(`[data-category-row="${c.id}"]`);
    const sentences=c.conciseOpinion.split(/[。！？]+/).filter(value=>value.trim()).length;check(sentences>=1&&sentences<=3,'CATEGORY_BUSINESS_SUMMARY_NOT_ONE_TO_THREE_SENTENCES');
    summarySizes.push({categoryId:c.id,codepoints:[...c.conciseOpinion].length,sentences});
    const header=await row.innerText();check(header.includes(c.title)&&header.includes(c.statusLabel),'CATEGORY_STATUS_MISMATCH');
    if(await row.getAttribute('aria-expanded')==='true')await row.click();
    check(await panel.locator(`[data-category-summary="${c.id}"]`).innerText()===c.conciseOpinion,'CATEGORY_CONCISE_MISMATCH');await row.click();
    const section=panel.locator(`[data-category-content="${c.id}"]`),a=await preview(section,`${c.title}简版手动复制`,'预览本项意见（简版）'),b=await preview(section,`${c.title}完整明细手动复制`,'预览本项完整明细');
    checkCategoryOpinions(a,b,c,p,run.runId);
    if(round==='initial')for(const[name,text]of[['复制本项意见',a],['复制本项完整明细',b]])copies.push(await copy(page,panel,section.getByRole('button',{name,exact:true}),text,o.clipboard));
  }
  if(round==='initial')check(copies.length===18,'EIGHTEEN_COPY_OUTLETS_NOT_VERIFIED');
  const itemCount=new Set(p.categories.flatMap(c=>c.items.map(i=>i.stableId))).size,evidenceCount=p.categories.reduce((n,c)=>n+c.items.reduce((n,i)=>n+(i.evidence?.length??0),0),0);
  check(itemCount>=o.minItems&&evidenceCount>=o.minEvidence,'EXPECTED_DETAIL_CAPACITY_NOT_PROVEN');observer.stage='OFFICIAL_REPORT';
  const reports=await readApi(page,`${prefix}/reports`),report=(Array.isArray(reports)?reports:reports.items??[]).find(r=>r.reportId===o.reportId);validateReportMetadata(report,run,o);
  const address=`${prefix}/reports/${o.reportId}/artifact`,link=page.locator(`section[aria-label="合同审查报告"] a[download][href*="${o.reportId}"]`);await link.waitFor();
  check(new URL(await link.getAttribute('href'),UI).pathname===address,'REPORT_LINK_WRONG_IDENTITY');
  const first=await readApi(page,address,true),second=await readApi(page,address,true),bytes=Buffer.from(first.bytes),reportHash=sha(bytes);
  check(first.type.includes('text/html')&&second.type.includes('text/html')&&reportHash===sha(Buffer.from(second.bytes))&&bytes.length===report.artifactSize
    &&(!o.expectedReportSha256||reportHash===o.expectedReportSha256),'FROZEN_REPORT_BYTES_CHANGED');
  const html=await page.evaluate(text=>{const d=new DOMParser().parseFromString(text,'text/html');return {text:d.body.textContent??'',details:d.querySelectorAll('section > details').length,
    articles:d.querySelectorAll('article').length,evidence:[...d.querySelectorAll('article > p')].filter(n=>n.textContent.startsWith('冻结证据：')).length};},bytes.toString('utf8'));
  checkHtmlReport(html,p);
  let downloadSha256=null;
  if(round==='initial') {
    observer.stage='OFFICIAL_DOWNLOAD';const pending=page.waitForEvent('download');await link.click();const download=await pending;
    check(await download.failure()===null,'OFFICIAL_REPORT_DOWNLOAD_FAILED');const file=await download.path();check(file,'OFFICIAL_REPORT_DOWNLOAD_UNPROVEN');
    downloadSha256=sha(await readFile(file));check(downloadSha256===reportHash,'OFFICIAL_DOWNLOAD_BYTES_DIFFER');
  }
  check(observer.blockedRequests===0&&observer.pageErrors===0,'UNEXPECTED_REQUEST_OR_PAGE_ERROR');
  return {proof:{frozenHash,conciseSha256:sha(concise),detailSha256:sha(detail),reportHash,downloadSha256,reportBytes:bytes.length,itemCount,evidenceCount,summarySizes,copies,
    clipboard:o.clipboard==='native'?'REAL_API_AND_OS_PASTE_VERIFIED':'NATIVE_DENIED_FALLBACK_ONLY'},projection:p,concise,detail,reportBytes:bytes};
}
export function buildBrowserReceipt(o,initial,refreshed,relogged,observer,manifestBytes,ledgerBytes) {
  return {execution:'ROOT_REAL_BROWSER',scenario:o.scenario,projectId:o.runProjectId,contractId:o.contractId,runId:o.runId,reportId:o.reportId,expectedSourceSha:o.expectedSha,
    opinionVersion:initial.projection.opinionVersion,monetaryMode:initial.projection.monetaryObservation?.mode??'STRICT',status:'READONLY_UI_OBSERVED',
    initial:initial.proof,refresh:refreshed.proof,relogin:relogged.proof,network:observer,businessWritesDispatched:0,
    manifestSha256:sha(manifestBytes),pidLedgerSha256:sha(ledgerBytes),runtimeProvenance:'ROOT_CURRENT_PID_AND_DM8_LEDGER_REQUIRED',nativeClipboard:initial.proof.clipboard};
}
function inside(parent,child){const r=path.relative(parent,child);return r!==''&&!r.startsWith('..')&&!path.isAbsolute(r);}
async function outputDirectory(o) {
  await mkdir(OUTPUT,{recursive:true});const output=await realpath(OUTPUT);check(inside(await realpath(ROOT),output),'UNSAFE_OUTPUT_DIRECTORY');
  const destination=path.join(output,`${o.scenario}-${o.runId}-${Date.now()}-${o.clipboard}`);await mkdir(destination);
  check(inside(output,await realpath(destination)),'UNSAFE_OUTPUT_DIRECTORY');return destination;
}
async function persistDelivery(directory,o,result,receipt) {
  const body=[`# ${o.scenario} 八大项最终结果`,'',`Run: ${o.runId}`,`Report: ${o.reportId}`,`展示候选 SHA: ${o.expectedSha}`,'',result.projection.overallOpinion??'',result.projection.scopeStatement,'',result.projection.overallAdvice];
  for(const c of result.projection.categories)body.push('',`## ${c.title} · ${c.statusLabel}`,'',c.conciseOpinion,'',...(c.uncoveredScopes??[]).map(s=>`范围限制：${s}`));
  for(const [name,value]of[['八大项最终结果.json',JSON.stringify(result.projection,null,2)],['八大项最终结果.md',body.join('\n')],['可复制简版.txt',result.concise],['完整明细.txt',result.detail],['正式报告.html',result.reportBytes],['browser-receipt.json',JSON.stringify(receipt,null,2)]])
    await writeFile(path.join(directory,name),value,{flag:'wx'});
}
export async function main(args=[]) {
  let browser,context,page,originalClipboard,observer,o,testAccountPassword;
  try {
    o=parseOptions(args);const require=createRequire(path.join(ROOT,'frontend/package.json'));require.resolve('playwright-core');
    if(!o.execute)return {execution:'NOT_EXECUTED',status:'OFFLINE_PREPARED',nativeClipboard:'NOT_VERIFIED',browserAndModelCalls:0};
    const manifestBytes=await readFile(path.join(RUNTIME,'build-manifest.json')),ledgerBytes=await readFile(path.join(RUNTIME,'pids.json'));
    validateRuntimeIdentity(JSON.parse(manifestBytes),JSON.parse(ledgerBytes),o.expectedSha);
    if(o.testAccountStdin) {
      testAccountPassword=await readHiddenTestAccountPassword();
    }
    const {chromium}=require('playwright-core'),executablePath=['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(existsSync);
    check(executablePath,'EXISTING_BROWSER_REQUIRED_NO_INSTALL');
    browser=await chromium.launch({executablePath,headless:false,args:['--no-first-run','--disable-background-networking','--disable-component-update','--disable-sync']});
    context=await browser.newContext({serviceWorkers:'block',acceptDownloads:true,viewport:{width:1500,height:1000}});
    observer={blockedRequests:0,readRequests:0,authWrites:0,pageErrors:0,stage:'BROWSER_LAUNCHED'};
    await context.route('**/*',async route=>{const request=route.request(),decision=requestPolicy(request.url(),request.method(),o);
      if(decision==='BLOCK'){observer.blockedRequests++;await route.abort('blockedbyclient');return;}
      if(decision==='AUTH') {
        if(new URL(request.url()).pathname.endsWith('/login')) {
          let value;try{value=request.postDataJSON();}catch{}
          if(value?.username!=='test_admin'||typeof value.password!=='string'||!value.password){observer.blockedRequests++;await route.abort('blockedbyclient');return;}
        }
        observer.authWrites++;
      }else observer.readRequests++;await route.continue();});
    if(context.routeWebSocket)await context.routeWebSocket('**/*',socket=>socket.close());
    if(o.clipboard==='native')await context.grantPermissions(['clipboard-read','clipboard-write'],{origin:UI});
    page=await context.newPage();page.setDefaultTimeout(30000);page.on('pageerror',()=>observer.pageErrors++);
    observer.stage='INITIAL_LOGIN';await page.goto(UI);await testAccountLogin(page,testAccountPassword);
    if(o.clipboard==='native')originalClipboard=await page.evaluate(()=>navigator.clipboard.readText());
    else {
      const session=await browser.newBrowserCDPSession(),target=await context.newCDPSession(page),{targetInfo}=await target.send('Target.getTargetInfo');await target.detach();
      try{await session.send('Browser.grantPermissions',{permissions:[],origin:UI,...(targetInfo.browserContextId?{browserContextId:targetInfo.browserContextId}:{})});}finally{await session.detach();}
      check(await page.evaluate(async()=>(await navigator.permissions.query({name:'clipboard-write'})).state)==='denied','REAL_CLIPBOARD_DENIAL_UNSUPPORTED');
    }
    const initial=await snapshot(page,o,observer,'initial');observer.stage='RELOAD';await page.reload();
    const refreshed=await snapshot(page,o,observer,'refresh');observer.stage='LOGOUT';await page.getByRole('button',{name:'退出登录',exact:true}).click();
    observer.stage='RELOGIN';await page.locator('#login-username').waitFor();await testAccountLogin(page,testAccountPassword);const relogged=await snapshot(page,o,observer,'relogin');
    for(const current of [refreshed,relogged])for(const key of ['frozenHash','conciseSha256','detailSha256','reportHash'])check(current.proof[key]===initial.proof[key],'HISTORY_REFRESH_OR_RELOGIN_CHANGED_FROZEN_CONTENT');
    const receipt=buildBrowserReceipt(o,initial,refreshed,relogged,observer,manifestBytes,ledgerBytes);
    const directory=await outputDirectory(o);await persistDelivery(directory,o,initial,receipt);return {...receipt,deliveryDirectory:path.relative(ROOT,directory)};
  }catch(error){
    const failure={execution:'NOT_VERIFIED',safeCode:error instanceof SafeFailure?error.message:'BROWSER_PROBE_UNKNOWN',stage:observer?.stage??'PREREQUISITES',
      network:observer?{blockedRequests:observer.blockedRequests,readRequests:observer.readRequests,authWrites:observer.authWrites,pageErrors:observer.pageErrors}:undefined,nativeClipboard:'NOT_VERIFIED'};
    if(o?.execute) {
      Object.assign(failure,{scenario:o.scenario,runId:o.runId,reportId:o.reportId,expectedSourceSha:o.expectedSha});
      try{const directory=await outputDirectory(o);await writeFile(path.join(directory,'browser-failure.json'),JSON.stringify(failure,null,2),{flag:'wx'});failure.failureDirectory=path.relative(ROOT,directory);}catch{failure.failureReceiptPersistence='NOT_VERIFIED';}
    }
    return failure;
  }
  finally {
    testAccountPassword=undefined;
    if(typeof originalClipboard==='string')await page?.evaluate(value=>navigator.clipboard.writeText(value),originalClipboard).catch(()=>{});originalClipboard=undefined;
    await context?.close().catch(()=>{});await browser?.close().catch(()=>{});
  }
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const result=await main(process.argv.slice(2));process.stdout.write(JSON.stringify(result)+'\n');process.exitCode=result.execution==='NOT_VERIFIED'?2:0;
}
