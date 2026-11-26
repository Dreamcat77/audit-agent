import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const require=createRequire(path.join(root,'frontend/package.json'));
const {build}=require('esbuild'),{chromium}=require('playwright-core');
const JSZip=require('jszip'),XLSX=require('xlsx');
const browserPath=process.env.AUDIT_TEST_BROWSER??[
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
].find(p=>fs.existsSync(p));
assert.ok(browserPath,'Set AUDIT_TEST_BROWSER to an existing Chromium executable');
const quote='待核对金额：120000元';
const zip=new JSZip();
zip.file('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>');
zip.file('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>');
const paragraph=text=>`<w:p><w:r><w:t>${text}</w:t></w:r></w:p>`;
zip.file('word/document.xml',`<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${paragraph('合成预算明细表')}<w:tbl><w:tblPr><w:tblW w:w="9000" w:type="dxa"/></w:tblPr><w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="7000"/></w:tblGrid>${Array.from({length:500},(_,i)=>`<w:tr><w:tc><w:tcPr><w:vMerge${i%2===0?' w:val="restart"':''}/></w:tcPr>${paragraph(i%2===0?`类别${i/2}`:'')}</w:tc><w:tc>${paragraph(i===440?quote:`合成预算明细${i}，金额${i+1}元`)}</w:tc></w:tr>`).join('')}</w:tbl><w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`);
const word=await zip.generateAsync({type:'nodebuffer'});
const shortZip=await JSZip.loadAsync(word);
shortZip.file('word/document.xml',`<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${paragraph('单页合成原文')}${paragraph('可见原文内容')}</w:body></w:document>`);
const shortWord=await shortZip.generateAsync({type:'nodebuffer'});
const book=XLSX.utils.book_new();
for(let i=1;i<=3;i++)XLSX.utils.book_append_sheet(book,XLSX.utils.aoa_to_sheet([
  ['项目名称','预算金额','审定参考'],['合成支出',i===2?quote:'无关金额100元',i===2?quote:'其他参考']
]),`明细${i}`);
const excel=XLSX.write(book,{type:'buffer',bookType:'xlsx'});
const result=await build({stdin:{contents:`import React from 'react';import{createRoot}from'react-dom/client';import{AnalysisEvidenceDialog}from'./src/features/contract/components/AnalysisEvidenceDialog';import{ResultMaterialList}from'./src/components/results/ResultMaterialList';import{BudgetSubjectComparison}from'./src/features/budget/BudgetSubjectComparison';
const root=createRoot(document.getElementById('app'));let kind='word';const quote=${JSON.stringify(quote)};
window.renderCase=k=>{kind=k;render();};window.closeEvidence=()=>root.render(null);window.reopenEvidence=()=>render();
function render(){const excel=kind==='excel',unmatched=kind==='word-unmatched',fileId=excel?'excel':unmatched?'short-word':'word';const item={fileId,start:0,end:quote.length,excerpt:unmatched?'缺失的合成引用':quote,sourceLocator:excel?'excel/sheet:2/row:2/part:7/col:2':null};root.render(<AnalysisEvidenceDialog evidence={[item]} documents={[{fileId,name:excel?'合成预算.xlsx':'合成预算.docx'}]} loadText={async()=>({text:quote,start:0,totalChars:quote.length})} visualBase={id=>'/files/'+id} onClose={()=>root.render(null)}/>);}
window.renderSummary=()=>root.render(<><ResultMaterialList names={['合成预算.docx','合成预算.xlsx']} originalUrls={['/files/word/original','/files/excel/original']}/><BudgetSubjectComparison lines={[{subject:'终端更新',declaredAmount:2200000,approvalAmount:2050000,status:'OVER_APPROVAL',opinion:'申报超过下达金额',sources:[{field:'subjectLevel1Name',value:'信息化建设',fileName:'合成预算.xlsx'},{field:'subjectLevel2Name',value:'设备购置',fileName:'合成预算.xlsx'},{field:'subjectLevel3Name',value:'终端更新',fileName:'合成预算.xlsx'},{field:'functionalClassificationName',value:'信息设备支出',fileName:'合成预算.xlsx'},{field:'thirdPartyReviewAmount',value:'2100000元',fileName:'合成预算.xlsx'}]}]} sourceButton={()=>null}/></>);
window.renderCase('word');`,resolveDir:path.join(root,'frontend'),loader:'tsx'},
  bundle:true,write:false,outdir:path.join(root,'.runtime/office-preview-test'),format:'esm',jsx:'automatic',
  define:{'import.meta.env':'{}'},plugins:[{name:'font-url',setup(b){
    b.onResolve({filter:/\.woff2\?url$/},args=>({path:args.path,namespace:'font'}));
    b.onLoad({filter:/.*/,namespace:'font'},()=>({contents:'export default "/font.woff2"',loader:'js'}));
  }}]});
const assets=new Map(result.outputFiles.map(f=>['/'+path.basename(f.path),f.contents]));
const js=[...assets.keys()].find(p=>p.endsWith('.js')),css=[...assets.keys()].find(p=>p.endsWith('.css'));
const requests={word:0,excel:0};
const server=http.createServer((req,res)=>{
  if(req.url==='/'){res.setHeader('Content-Type','text/html');res.end(`<html><head><meta charset="utf-8">${css?`<link rel="stylesheet" href="${css}">`:''}</head><body><div id="app"></div><script type="module" src="${js}"></script></body></html>`);return;}
  if(req.url==='/files/word/original'||req.url==='/files/short-word/original'||req.url==='/files/excel/original'){
    const kind=req.url.includes('/excel/')?'excel':'word';requests[kind]++;res.setHeader('Content-Type','application/octet-stream');res.end(req.url.includes('short-word')?shortWord:kind==='word'?word:excel);return;
  }
  if(req.url==='/font.woff2'){res.setHeader('Content-Type','font/woff2');res.end(fs.readFileSync(path.join(root,'frontend/src/assets/fonts/ui-cjk.woff2')));return;}
  if(assets.has(req.url)){res.setHeader('Content-Type',req.url.endsWith('.css')?'text/css':'application/javascript');res.end(assets.get(req.url));return;}
  res.statusCode=404;res.end();
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({executablePath:browserPath,headless:true});
try{
  const page=await browser.newPage({viewport:{width:1280,height:800}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  const frame=page.frameLocator('iframe');
  await frame.locator('mark[data-source-highlight]').first().waitFor({timeout:30000});
  const wordState=await frame.locator('body').evaluate(body=>({
    totalPages:body.querySelectorAll('section.office-word').length,
    visiblePages:[...body.querySelectorAll('section.office-word')].filter(p=>p.style.display!=='none').length,
    marked:[...body.querySelectorAll('mark[data-source-highlight]')].map(m=>m.textContent).join('')
  }));
  assert.ok(wordState.totalPages>10,JSON.stringify(wordState));
  assert.equal(wordState.visiblePages,1);assert.equal(wordState.marked,quote);
  await page.getByRole('link',{name:'打开 / 下载原文件'}).waitFor();
  await page.evaluate(()=>window.renderCase('word'));
  await page.waitForTimeout(300);assert.equal(requests.word,1);
  await page.getByRole('button',{name:'关闭',exact:true}).click();
  await page.evaluate(()=>window.reopenEvidence());
  await frame.locator('mark[data-source-highlight]').first().waitFor();
  assert.equal(requests.word,2);
  await page.evaluate(()=>window.renderCase('word-unmatched'));
  await page.getByRole('status').filter({hasText:'原文页已打开，证据位置未标记'}).waitFor({timeout:30000});
  const fallbackState=await frame.locator('body').evaluate(body=>({
    totalPages:body.querySelectorAll('section.office-word').length,
    visiblePages:[...body.querySelectorAll('section.office-word')].filter(p=>p.style.display!=='none').length,
    highlights:body.querySelectorAll('mark[data-source-highlight]').length,
  }));
  assert.equal(fallbackState.totalPages,1);assert.equal(fallbackState.visiblePages,1);assert.equal(fallbackState.highlights,0);
  await page.evaluate(()=>window.renderCase('excel'));
  await page.locator('iframe[title="合成预算.xlsx原文件证据页"]').waitFor();
  await frame.locator('mark[data-source-highlight]').first().waitFor();
  const excelState=await frame.locator('body').evaluate(body=>({
    visibleTitles:[...body.querySelectorAll('section')].filter(p=>p.style.display!=='none').map(p=>p.querySelector('h3')?.textContent),
    marked:[...body.querySelectorAll('mark')].map(m=>m.textContent).join(''),
    markedColumns:[...body.querySelectorAll('mark')].map(m=>m.closest('td')?.dataset.officeColumn)
  }));
  assert.deepEqual(excelState.visibleTitles,['明细2']);assert.equal(excelState.marked,quote);
  assert.deepEqual(excelState.markedColumns,['2']);
  for(let i=0;i<3;i++)await page.evaluate(()=>window.renderCase('excel'));
  await page.waitForTimeout(300);assert.equal(requests.excel,1);
  await page.evaluate(()=>window.renderSummary());
  await page.getByRole('link',{name:'查看原文件：合成预算.xlsx'}).waitFor();
  assert.equal(await page.getByRole('link',{name:'查看原文件：合成预算.docx'}).getAttribute('href'),'/files/word/original');
  const row=page.locator('[data-budget-subject-row]');
  assert.equal(await row.count(),1);
  assert.ok((await row.textContent()).includes('信息化建设 / 设备购置 / 终端更新'));
  assert.ok((await row.textContent()).includes('审定参考：2100000元'));
  assert.ok((await row.textContent()).includes('2,050,000'));
  assert.ok((await row.textContent()).includes('150,000'));
  assert.doesNotMatch(await row.textContent(),/thirdPartyReviewAmount|functionalClassificationName/);
  assert.deepEqual(errors,[]);
  console.log(JSON.stringify({status:'PASS',browser:browser.version(),wordPages:wordState.totalPages,visibleEvidencePages:1,unmatchedCitationFallback:fallbackState,visibleExcelSheets:1,requests}));
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
