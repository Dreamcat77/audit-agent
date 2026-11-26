import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const require=createRequire(path.join(root,'frontend/package.json'));
const {chromium}=require('playwright-core'),{build}=require('esbuild');
const browserPath=process.env.AUDIT_TEST_BROWSER??path.join(root,'.runtime/browser-compat-test/chrome-win/chrome.exe');
assert.ok(fs.existsSync(browserPath),'Set AUDIT_TEST_BROWSER to Chrome 109');
const asset=await build({stdin:{contents:`import React from 'react';import{createRoot}from'react-dom/client';import{OriginalEvidencePreview}from'./src/features/contract/components/OriginalEvidencePreview';
const evidence=[{fileId:'scan',start:10,end:22,excerpt:'合成扫描证据',pageNumber:1},{fileId:'scan',start:30,end:42,excerpt:'第二页证据',pageNumber:2}];
createRoot(document.getElementById('app')).render(<OriginalEvidencePreview fileId="scan" evidence={evidence} base={()=>'/files'} />);`,
 resolveDir:path.join(root,'frontend'),loader:'tsx'},bundle:true,write:false,outdir:path.join(root,'.runtime/original-evidence-rotation-test'),format:'iife',globalName:'EvidenceTest',jsx:'automatic',define:{'import.meta.env':'{}'},plugins:[{name:'font-url',setup(b){b.onResolve({filter:/\.woff2\?url$/},a=>({path:a.path,namespace:'font'}));b.onLoad({filter:/.*/,namespace:'font'},()=>({contents:'export default "/font.woff2"',loader:'js'}));}}]});
const js=Buffer.from(asset.outputFiles.find(f=>f.path.endsWith('.js')).contents).toString('utf8');
const png=fs.readFileSync(path.join(root,'.runtime/field-simulation-20261126-r3/pdf-pages/F26C24/廉洁保密协议-01.png'));let pageRequests=0;
const server=http.createServer((req,res)=>{
 if(req.url==='/'){res.setHeader('Content-Type','text/html');res.end('<meta charset="utf-8"><div id="app"></div><script>'+js+'</script>');return;}
 if(req.url.startsWith('/files/visual')){const selected=Number(new URL(req.url,'http://localhost').searchParams.get('page')||1);res.setHeader('Content-Type','application/json');res.end(JSON.stringify({code:200,message:'success',data:{status:'LOCATED',pages:[selected],anchors:[{pageNumber:selected,method:'TEXT',start:10,end:22,polygon:[{x:.2,y:.3},{x:.6,y:.3},{x:.6,y:.4},{x:.2,y:.4}]}]}}));return;}
 const match=req.url.match(/^\/files\/pages\/(\d+)\/image$/);
 if(match){pageRequests++;res.writeHead(200,{'Content-Type':'image/png'});res.end(png);return;}
 if(req.url==='/font.woff2'){res.writeHead(404).end();return;}
 res.writeHead(404).end();
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({executablePath:browserPath,headless:true});
try{
 const page=await browser.newPage({viewport:{width:900,height:700}});const errors=[],requests=[];page.on('pageerror',e=>errors.push(e.name));page.on('request',r=>requests.push(new URL(r.url()).pathname));
 await page.goto(`http://127.0.0.1:${server.address().port}`);
 const viewer=page.locator('section[aria-label="原文件片段"]');
 await page.waitForTimeout(700);
 if(await viewer.count()===0){console.log(JSON.stringify({requests,errors,html:(await page.locator('body').innerHTML()).slice(0,2000)}));throw new Error('EVIDENCE_VIEW_NOT_RENDERED');}
 if(await viewer.getByText('证据页 · 第 1 页',{exact:true}).count()===0){console.log(JSON.stringify({requests,errors,body:await viewer.innerText()}));throw new Error('EVIDENCE_PAGE_NOT_SELECTED');}
 const imageEl=viewer.locator('.original-evidence-picture img');await imageEl.evaluate(img=>img.decode());
 const initial=await viewer.locator('.original-evidence-picture').evaluate(el=>({rotation:el.dataset.pageRotation,
   width:el.getBoundingClientRect().width,height:el.getBoundingClientRect().height,highlights:el.querySelectorAll('[data-original-highlight]').length}));
 assert.equal(initial.rotation,'0');assert.equal(initial.highlights,1);assert.equal(pageRequests,1);
 const rotate=viewer.getByRole('button',{name:'顺时针旋转90度',exact:true});
 for(const expected of ['90','180','270','0']){
   await rotate.click();await page.locator(`.original-evidence-picture[data-page-rotation="${expected}"]`).waitFor();
   assert.equal(await viewer.getByText('证据页 · 第 1 页',{exact:true}).count(),1,'page label changed on rotation');
   assert.equal(await viewer.locator('[data-original-highlight]').count(),1,'highlight disappeared on rotation');
   assert.equal(pageRequests,1,'rotation re-fetched the source page');
 }
 await viewer.getByRole('button',{name:'下一证据页',exact:true}).click();
 await viewer.getByText('证据页 · 第 2 页',{exact:true}).waitFor();assert.equal(pageRequests,2);
 assert.deepEqual(errors,[]);
 console.log(JSON.stringify({status:'PASS',browser:browser.version(),originalSize:{width:initial.width,height:initial.height},highlighted:true,
   rotations:[90,180,270,0],pageLabelPreserved:true,noReload:true,noExtraFetchOnRotation:true,pageChanged:true}));
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
