import assert from 'node:assert/strict';
import { test } from 'node:test';
import { EventEmitter } from 'node:events';
import * as h from './v2-final-browser-acceptance.mjs';

const ids = ['QUANTITATIVE','DRAFT_STRUCTURE','BREACH','TERM','PARTY_QUALIFICATION','ATTACHMENT_COMPLETENESS','EFFECTIVENESS_COPIES','CLAUSE_RISK'];
const resource = '11111111-1111-4111-8111-111111111111';
const options = ['--execute','--expected-sha','a'.repeat(40),'--project-id',resource,'--contract-id',resource,'--run-id',resource,'--report-id',resource,'--scenario','V2-01'];
function run() {
  const categories=ids.map((id,index)=>({id,title:`类别${index}`,statusLabel:'待核实',conciseOpinion:'材料需核对。',summary:'完整结论',uncoveredScopes:['外部核验'],items:[
    {stableId:`row-${index}`,title:'数量核对',summary:'相符',actualValue:'150630元',expectedValue:'150630元',recommendation:'核对原文',typedFacts:['48行'],evidence:[{fileName:'合成.pdf',excerpt:'合成证据'}]}]}));
  return {runId:resource,status:'COMPLETED',businessProjection:{version:1,opinionVersion:2,runId:resource,runStatus:'COMPLETED',overallAdvice:'保留核验限制',scopeStatement:'冻结范围',categories}};
}
const marker='【联调参考意见｜金额相关模型表述尚待人工复核】';
function currentRun(mode='OBSERVE') {
  const r=run();Object.assign(r.businessProjection,{opinionVersion:4,
    overallOpinion:'本次核对合同正文和中标通知书，合同金额69,000元与成交金额67,000元存在2,000元差异。交付条件待核对，建议对照双方原文复核。',
    monetaryObservation:{protocol:'contract-money-observation-v1',mode,warnings:[{fieldPath:'summary',errorKind:'MONETARY_VALUE',category:null,textSha256:'a'.repeat(64),ruleSubtypes:['ABSOLUTE_VALUE']}],workWarnings:[]}});
  return r;
}
function copiedTexts(p) {
  const heading=[p.monetaryObservation?.mode==='OBSERVE'?marker:'',`审核记录：${resource}`].filter(Boolean).join('\n');
  const concise=p.opinionVersion===4?`${heading}\n\n${p.overallOpinion}`:
    [heading,p.overallAdvice,p.scopeStatement,...p.categories.flatMap(c=>[c.title,c.statusLabel,c.conciseOpinion,...c.uncoveredScopes])].join('\n');
  const detail=[heading,p.scopeStatement,p.overallOpinion??'',...p.categories.flatMap(c=>[c.title,c.statusLabel,c.summary,...c.uncoveredScopes,
    ...c.items.map(i=>`- ${i.title}：${i.summary}\n${i.actualValue}\n${i.expectedValue}\n${i.typedFacts[0]}\n  来源：${i.evidence[0].fileName}；摘录：${i.evidence[0].excerpt}\n${i.recommendation}`)])].join('\n');
  return {concise,detail};
}
test('default entry is offline and does not read credentials, launch browser, or dispatch HTTP',async()=>{
  const value=await h.main([]);assert.equal(value.execution,'NOT_EXECUTED');assert.equal(value.nativeClipboard,'NOT_VERIFIED');
});
test('live scope requires full candidate SHA, existing run and formal report identities',()=>{
  assert.equal(h.parseOptions(options).expectedSha,'a'.repeat(40));
  assert.throws(()=>h.parseOptions(options.filter((_,i)=>![1,2].includes(i))),/EXPECTED_SHA_REQUIRED/);
  assert.throws(()=>h.parseOptions(options.filter((_,i)=>![9,10].includes(i))),/EXACT_EXISTING_RESOURCE_REQUIRED/);
  assert.throws(()=>h.parseOptions([...options,'--run-id',resource]),/DUPLICATE_OPTION/);
  assert.throws(()=>h.parseOptions([...options,'--password','secret']),/INVALID_OPTION/);
});
test('read guard rejects encoded or malformed project identities before the server can decode them',()=>{
  const o=h.parseOptions(options);
  assert.equal(h.requestPolicy('http://127.0.0.1:5173/api/v1/projects/%32222222-2222-4222-8222-222222222222','GET',o),'BLOCK');
  assert.equal(h.requestPolicy('http://127.0.0.1:5173/api/v1/projects/not-a-project-id','GET',o),'BLOCK');
  assert.equal(h.requestPolicy('http://127.0.0.1:5173/api/v1/projects/preparation-drafts','GET',o),'READ');
});
test('runtime receipts require the same candidate and both workers, without claiming live process identity',()=>{
  const manifest={sourceSha:'a'.repeat(40),semanticCheckpointsEnabled:true,reviewWorker:true,fileProcessingWorker:true};
  const ledger={sourceSha:'a'.repeat(40),entries:['Backend','Frontend','FastAPI'].map(service=>({service,sourceSha:'a'.repeat(40)}))};
  assert.doesNotThrow(()=>h.validateRuntimeIdentity(manifest,ledger,'a'.repeat(40)));
  for(const mutate of [m=>m.sourceSha='b'.repeat(40),m=>m.semanticCheckpointsEnabled=false,m=>m.reviewWorker=false]) {
    const m={...manifest};mutate(m);assert.throws(()=>h.validateRuntimeIdentity(m,ledger,'a'.repeat(40)));
  }
  assert.throws(()=>h.validateRuntimeIdentity(manifest,{...ledger,entries:ledger.entries.slice(1)},'a'.repeat(40)));
});
test('read guard blocks business writes and wrong resources while allowing auth and exact existing reports',()=>{
  const o=h.parseOptions(options),prefix=`/api/v1/projects/${resource}/contracts/${resource}/review-runs/${resource}`;
  assert.equal(h.requestPolicy(`http://127.0.0.1:5173${prefix}/reports/${resource}/artifact`,'GET',o),'READ');
  assert.equal(h.requestPolicy('http://127.0.0.1:5173/api/v1/auth/login','POST',o),'AUTH');
  for(const [url,method] of [[`http://127.0.0.1:5173${prefix}/reports`,'POST'],[`http://127.0.0.1:5173${prefix}/reports/22222222-2222-4222-8222-222222222222/artifact`,'GET'],['https://example.invalid/','GET'],['http://127.0.0.1:5173/api/v1/projects/%2e%2e','GET']]) assert.equal(h.requestPolicy(url,method,o),'BLOCK');
});
test('projection fails closed for wrong run, stale versions, missing categories and incomplete final runs',()=>{
  assert.equal(h.summaryContract(run()).categories.length,8);
  for(const mutate of [r=>r.businessProjection.runId='wrong',r=>r.businessProjection.opinionVersion=1,r=>r.businessProjection.categories.pop(),r=>r.status='FAILED',r=>r.businessProjection.categories[1].id='QUANTITATIVE']) {
    const r=run();mutate(r);assert.throws(()=>h.summaryContract(r));
  }
});
test('copy oracle preserves literal money, limitations and every repeated row evidence',()=>{
  const r=run(),p=r.businessProjection;
  const concise=[resource,p.overallAdvice,p.scopeStatement,...p.categories.flatMap(c=>[c.title,c.statusLabel,c.conciseOpinion,...c.uncoveredScopes])].join('\n');
  const detail=[concise,...p.categories.flatMap(c=>[c.summary,...c.items.map(i=>`- ${i.title}：${i.summary}\n${i.actualValue}\n${i.expectedValue}\n${i.typedFacts[0]}\n  来源：${i.evidence[0].fileName}；摘录：${i.evidence[0].excerpt}\n${i.recommendation}`)])].join('\n');
  assert.doesNotThrow(()=>h.checkOpinions(concise,detail,p,resource));
  assert.throws(()=>h.checkOpinions(concise,detail.replaceAll('150630元','150000元'),p,resource),/FULL_COPY_DROPPED/);
  assert.throws(()=>h.checkOpinions(concise.replaceAll('外部核验',''),detail,p,resource),/CONCISE_COPY_DROPPED/);
  assert.throws(()=>h.checkOpinions(concise,detail.replace('  来源：',''),p,resource),/MISSING_EVIDENCE_REFERENCE/);
});
test('report contract rejects stale schemas, incomplete state, changed identity and missing byte metadata',()=>{
  const report={reportId:resource,reviewRunId:resource,reportSchemaVersion:3,status:'AVAILABLE',artifactSize:100};
  assert.doesNotThrow(()=>h.validateReportMetadata(report,run(),h.parseOptions(options)));
  for(const mutate of [r=>r.reportSchemaVersion=2,r=>r.status='GENERATING',r=>r.reviewRunId='wrong',r=>r.artifactSize=undefined]) {
    const r={...report};mutate(r);assert.throws(()=>h.validateReportMetadata(r,run(),h.parseOptions(options)));
  }
});
test('unsupported credential options produce a bounded safe receipt without echoing values',async()=>{
  const value=await h.main(['--password','private-secret-marker']);
  assert.equal(value.execution,'NOT_VERIFIED');assert.ok(!JSON.stringify(value).includes('private-secret-marker'));
});

test('current and historical opinion versions are accepted while V4 requires the actual frontend metadata contract',()=>{
  for(const version of [2,3,4]) {
    const r=version===4?currentRun():run();r.businessProjection.opinionVersion=version;
    assert.equal(h.summaryContract(r).opinionVersion,version);
  }
  for(const change of [p=>p.overallOpinion=' ',p=>p.monetaryObservation.mode='OFF',p=>p.monetaryObservation.protocol='unknown',
    p=>p.monetaryObservation.warnings[0].fieldPath='hints[0].description',p=>p.monetaryObservation.warnings[0].ruleSubtypes=['invented']]) {
    const r=currentRun();change(r.businessProjection);assert.throws(()=>h.summaryContract(r));
  }
});

test('V4 concise copy is exactly the canonical paragraph without all eight category summaries',()=>{
  const r=currentRun(),p=r.businessProjection,{concise,detail}=copiedTexts(p);
  assert.doesNotThrow(()=>h.checkOpinions(concise,detail,p,resource));
  assert.throws(()=>h.checkOpinions(concise.replace('2,000元','3,000元'),detail,p,resource),/CANONICAL/);
  assert.throws(()=>h.checkOpinions(`${concise}\n额外编造的结论。`,detail,p,resource),/CANONICAL/);
  assert.throws(()=>h.checkOpinions(concise,detail.replaceAll('150630元','150000元'),p,resource),/FULL_COPY_DROPPED/);
});

test('OBSERVE marker loss is rejected in each overall and category copy outlet',()=>{
  const r=currentRun(),p=r.businessProjection,{concise,detail}=copiedTexts(p);
  assert.throws(()=>h.checkOpinions(concise.replace(marker,''),detail,p,resource),/OBSERVE_MARKER/);
  assert.throws(()=>h.checkOpinions(concise,detail.replace(marker,''),p,resource),/OBSERVE_MARKER/);
  assert.equal(typeof h.checkCategoryOpinions,'function');
  for(const c of p.categories) {
    const a=`${marker}\n${resource}\n${c.conciseOpinion}`;
    const b=[marker,resource,p.scopeStatement,c.title,c.statusLabel,c.summary,...c.uncoveredScopes,
      ...c.items.flatMap(i=>[i.title,i.summary,i.actualValue,i.expectedValue,i.recommendation,...i.typedFacts,
        `  来源：${i.evidence[0].fileName}；摘录：${i.evidence[0].excerpt}`])].join('\n');
    assert.doesNotThrow(()=>h.checkCategoryOpinions(a,b,c,p,resource));
    for(const [short,long] of [[a.replace(marker,''),b],[a,b.replace(marker,'')]])
      assert.throws(()=>h.checkCategoryOpinions(short,long,c,p,resource),/OBSERVE_MARKER/);
  }
});

test('visible page and HTML must preserve the frozen paragraph and marker',()=>{
  assert.equal(typeof h.checkPageOpinion,'function');assert.equal(typeof h.checkHtmlOpinion,'function');
  const p=currentRun().businessProjection;
  assert.doesNotThrow(()=>h.checkPageOpinion(p.overallOpinion,[marker],p));
  assert.throws(()=>h.checkPageOpinion(p.overallOpinion,[],p),/OBSERVE_MARKER/);
  assert.throws(()=>h.checkPageOpinion('失去关键差额。',[marker],p),/CANONICAL/);
  assert.doesNotThrow(()=>h.checkHtmlOpinion(`${marker}\n${p.overallOpinion}`,p));
  assert.throws(()=>h.checkHtmlOpinion(p.overallOpinion,p),/OBSERVE_MARKER/);
  assert.throws(()=>h.checkHtmlOpinion(marker,p),/CANONICAL/);
});

test('V4 HTML uses canonical paragraph instead of legacy advice while retaining all category facts and evidence',()=>{
  assert.equal(typeof h.checkHtmlReport,'function');
  const p=currentRun().businessProjection,{detail}=copiedTexts(p);
  const html={text:[detail,...p.categories.map(c=>c.conciseOpinion)].join('\n'),details:8,articles:8,evidence:8};
  assert.doesNotThrow(()=>h.checkHtmlReport(html,p));
  assert.throws(()=>h.checkHtmlReport({...html,text:html.text.replaceAll('150630元','150000元')},p),/HTML_CONCISE_OR_FULL_DETAIL/);
  assert.throws(()=>h.checkHtmlReport({...html,evidence:7},p),/HTML_CONCISE_OR_FULL_DETAIL/);
  const legacy=run().businessProjection;
  const legacyHtml={...html,text:html.text.replace(marker,'').replace(p.overallOpinion,'')};
  assert.throws(()=>h.checkHtmlReport(legacyHtml,legacy),/HTML_CONCISE_OR_FULL_DETAIL/);
});

test('receipt records the actual frozen opinion version and mode instead of a hardcoded legacy version',()=>{
  assert.equal(typeof h.buildBrowserReceipt,'function');
  for(const version of [2,3,4]) {
    const r=version===4?currentRun():run();r.businessProjection.opinionVersion=version;
    const snapshot={projection:r.businessProjection,proof:{clipboard:'REAL_API_AND_OS_PASTE_VERIFIED'}};
    const receipt=h.buildBrowserReceipt(h.parseOptions(options),snapshot,snapshot,snapshot,{},Buffer.from('manifest'),Buffer.from('ledger'));
    assert.equal(receipt.opinionVersion,version);
    assert.equal(receipt.monetaryMode,version===4?'OBSERVE':'STRICT');
  }
});

test('stdin credential option is an opt-in boolean and remains inert during offline preparation',async()=>{
  assert.equal(h.parseOptions([...options,'--test-account-stdin']).testAccountStdin,true);
  assert.equal(h.parseOptions([]).testAccountStdin,false);
  assert.throws(()=>h.parseOptions([...options,'--test-account-stdin','--test-account-stdin']),/DUPLICATE_OPTION/);
  const value=await h.main(['--test-account-stdin']);assert.equal(value.execution,'NOT_EXECUTED');
});

test('hidden TTY password input is never echoed and restores terminal state after input or cancellation',async()=>{
  assert.equal(typeof h.readHiddenTestAccountPassword,'function');
  function terminal() {
    const input=new EventEmitter();Object.assign(input,{isTTY:true,isRaw:false,isPaused:()=>true,
      setRawMode(value){this.isRaw=value;},resume(){},pause(){}});
    const output={text:'',write(value){this.text+=value;}};return {input,output};
  }
  const {input,output}=terminal(),pending=h.readHiddenTestAccountPassword(input,output);
  input.emit('data',Buffer.from('synthetic-secret\r'));
  assert.equal(await pending,'synthetic-secret');assert.equal(input.isRaw,false);
  assert.ok(!output.text.includes('synthetic-secret'));assert.equal(input.listenerCount('data'),0);
  const cancelled=terminal(),waiting=h.readHiddenTestAccountPassword(cancelled.input,cancelled.output);
  cancelled.input.emit('data',Buffer.from('\u0003'));await assert.rejects(waiting,/CANCELLED/);
  assert.equal(cancelled.input.isRaw,false);assert.equal(cancelled.input.listenerCount('data'),0);
  const empty=terminal(),blank=h.readHiddenTestAccountPassword(empty.input,empty.output);
  empty.input.emit('data',Buffer.from('\r'));await assert.rejects(blank,/EMPTY/);
  await assert.rejects(h.readHiddenTestAccountPassword({isTTY:false},output),/REQUIRES_TTY/);
  for(const environment of [{DEBUG:'*'},{DEBUG:'pw:api'},{PWDEBUG:'1'}]) {
    const debug=terminal(),blocked=h.readHiddenTestAccountPassword(debug.input,debug.output,environment);
    debug.input.emit('data',Buffer.from('synthetic-secret\r'));
    await assert.rejects(blocked,/DEBUG_LOGGING/);assert.equal(debug.output.text,'');
  }
});
