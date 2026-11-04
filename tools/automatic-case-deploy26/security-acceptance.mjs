import {createRequire} from 'node:module';
import fs from 'node:fs';import path from 'node:path';
const require=createRequire(new URL('../../frontend/package.json',import.meta.url));
const {request}=require('playwright-core');
let input='';for await(const chunk of process.stdin)input+=chunk;const config=JSON.parse(input);
const accounts=JSON.parse(fs.readFileSync(config.credentials,'utf8'));
const operation=JSON.parse(fs.readFileSync(path.join(config.evidence,'browser-operation.json'),'utf8'));
const uploaded=JSON.parse(fs.readFileSync(path.join(config.evidence,'browser-upload.json'),'utf8')).body.data;
const assert=(x,m)=>{if(!x)throw Error(m)}; const results={};
const anonymous=await request.newContext({baseURL:config.base});
const primary=await request.newContext({baseURL:config.base});const secondary=await request.newContext({baseURL:config.base});
try {
  results.anonymousCases=(await anonymous.get('/api/v1/procurement/cases')).status();assert(results.anonymousCases===401,'anonymous access accepted');
  results.loginWithoutCsrf=(await anonymous.post('/api/v1/auth/login',{data:accounts.secondary})).status();assert(results.loginWithoutCsrf===403,'CSRF disabled');
  for(const [context,account] of [[primary,accounts.primary],[secondary,accounts.secondary]]) {
    const csrf=(await (await context.get('/api/v1/auth/csrf')).json()).data;
    const response=await context.post('/api/v1/auth/login',{headers:{[csrf.headerName]:csrf.token},data:{username:account.username,password:account.password}});
    assert(response.status()===200,'real account login failed');
  }
  const id=operation.caseId,file=uploaded.documents[0].id;
  const own=await primary.get('/api/v1/procurement/cases/'+id);results.ownCase=own.status();assert(results.ownCase===200,'owner cannot read case');
  const ownBody=await own.json();fs.writeFileSync(path.join(config.evidence,'current-case.json'),JSON.stringify(ownBody,null,2));
  console.log('CURRENT_CASE_STATE',JSON.stringify({status:ownBody.data.status,documents:ownBody.data.documents.map(d=>({name:d.name,status:d.status,error:d.errorCode}))}));
  results.crossUser={};
  for(const [name,url] of Object.entries({case:'/api/v1/procurement/cases/'+id,evidence:`/api/v1/procurement/cases/${id}/files/${file}/evidence`,original:`/api/v1/procurement/cases/${id}/files/${file}/original`})) {
    const response=await secondary.get(url,{headers:{'X-User-ID':accounts.primary.id,'X-Username':accounts.primary.username}});
    results.crossUser[name]=response.status();assert([403,404].includes(response.status()),'cross user '+name+' leaked');
  }
  const op=await secondary.get('/api/v1/procurement/cases/operations/'+operation.operationKey);
  results.crossUser.operation={status:op.status(),data:(await op.json()).data};assert(results.crossUser.operation.data===null,'cross-user operation exposed');
  const list=(await (await secondary.get('/api/v1/procurement/cases')).json()).data;
  assert(!list.some(c=>c.id===id),'other user sees case listing');results.crossUser.listDoesNotContainCase=true;
  results.uploadWithoutCsrf=(await primary.post('/api/v1/procurement/cases',{headers:{'Idempotency-Key':operation.operationKey},multipart:{files:{name:'rejected.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-test')}}})).status();
  assert(results.uploadWithoutCsrf===403,'authenticated upload bypassed CSRF');
  results.legacyApi=(await primary.get('/api/v1/contracts')).status();assert(results.legacyApi===404,'legacy APIs exposed');
  results.invalidOrigin=(await anonymous.post('/api/v1/auth/login',{headers:{Origin:'http://invalid.example'},data:{username:accounts.secondary.username,password:'not-a-real-password'}})).status();assert(results.invalidOrigin===403,'untrusted CORS origin accepted');
  const state=await primary.storageState();results.sessionCookies=state.cookies.map(c=>({name:c.name,httpOnly:c.httpOnly,secure:c.secure,sameSite:c.sameSite}));
  assert(results.sessionCookies.some(c=>c.name==='AUTOPROC_PILOT_SESSION'&&c.httpOnly&&c.sameSite==='Lax'),'pilot cookie is not isolated');
  const csrf=(await (await primary.get('/api/v1/auth/csrf')).json()).data;
  results.logout=(await primary.post('/api/v1/auth/logout',{headers:{[csrf.headerName]:csrf.token}})).status();assert(results.logout===200,'logout failed');
  results.afterLogout=(await primary.get('/api/v1/procurement/cases/'+id)).status();assert(results.afterLogout===401,'session remained authenticated after logout');
  fs.writeFileSync(path.join(config.evidence,'security-acceptance.json'),JSON.stringify(results,null,2));console.log('SECURITY_ACCEPTANCE_PASS',JSON.stringify(results));
}finally {await anonymous.dispose();await primary.dispose();await secondary.dispose();}
