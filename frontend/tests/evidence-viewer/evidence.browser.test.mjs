import assert from 'node:assert/strict';
import {after,before,test} from 'node:test';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {existsSync} from 'node:fs';

// Optional isolated tool directory permits component tests while a different
// application dependency is network-blocked. It does not validate a full build.
const require=createRequire(process.env.AUDIT_BROWSER_TEST_NODE_MODULES
  ?path.resolve(process.env.AUDIT_BROWSER_TEST_NODE_MODULES,'../package.json'):new URL('../../package.json',import.meta.url));
const {createServer}=await import(pathToFileURL(path.join(path.dirname(require.resolve('vite/package.json')),'dist/node/index.js')));
const {chromium}=require('playwright-core');
const root=path.dirname(fileURLToPath(import.meta.url));
let server,browser,url;
before(async()=>{
  server=await createServer({configFile:false,root,logLevel:'error',cacheDir:path.resolve(root,'../../../.runtime/evidence-vite-cache'),
    esbuild:{jsx:'automatic'},resolve:{alias:[
      {find:/^react$/,replacement:require.resolve('react')},
      {find:'react/jsx-runtime',replacement:require.resolve('react/jsx-runtime')},
      {find:'react/jsx-dev-runtime',replacement:require.resolve('react/jsx-dev-runtime')},
      {find:'react-dom/client',replacement:require.resolve('react-dom/client')},
    ]},server:{host:'127.0.0.1',port:0,fs:{allow:[path.resolve(root,'../..'),path.dirname(require.resolve('react'))]}}});
  await server.listen();url='http://127.0.0.1:'+server.httpServer.address().port;
  browser=await chromium.launch({headless:true,args:['--no-sandbox'],
    executablePath:process.env.AUDIT_BROWSER_EXECUTABLE??(existsSync('/usr/bin/chromium')?'/usr/bin/chromium':undefined)});
});
after(async()=>{await browser?.close();await server?.close();});

async function open(mode=''){
  const page=await browser.newPage();page.setDefaultTimeout(5000);await page.goto(url+'/?case='+mode);
  await page.locator('#open').click();await page.getByText('正在定位相关原文…').waitFor({state:'hidden'});
  return page;
}

test('browser shows three exact highlights from six citations, preserving both files and distant positions',async()=>{
  const page=await open();
  try{
    assert.deepEqual(await page.locator('mark').allTextContents(),[
      '甲方应于验收合格后十日内支付尾款。','乙方逾期交付应承担违约责任。','旧付款条款由本条替换，其他约定继续有效。']);
    assert.equal(await page.locator('article').count(),3);
    assert.equal(await page.evaluate(()=>window.evidenceTestCalls.length),3);
    assert.equal(await page.getByRole('heading',{name:'采购合同.docx'}).count(),1);
    assert.equal(await page.getByRole('heading',{name:'付款补充协议.docx'}).count(),1);
    await page.keyboard.press('Escape');
    assert.equal(await page.getByRole('dialog').count(),0);
    assert.equal(await page.evaluate(()=>document.activeElement.id),'open');
  }finally{await page.close();}
});

test('browser refuses wrong offsets and still displays the other verified source',async()=>{
  const page=await open('bad');
  try{
    assert.deepEqual(await page.locator('mark').allTextContents(),['旧付款条款由本条替换，其他约定继续有效。']);
    assert.equal(await page.getByText('此处引用尚未与原文位置核对一致，暂不高亮，以免定位错误。').count(),1);
  }finally{await page.close();}
});

test('browser with no locator displays the quote honestly without requesting the document beginning',async()=>{
  const page=await open('missing');
  try{
    assert.equal(await page.locator('mark').count(),0);
    assert.equal(await page.locator('blockquote').textContent(),'甲方应于验收合格后十日内');
    assert.equal(await page.evaluate(()=>window.evidenceTestCalls.length),0);
  }finally{await page.close();}
});

test('browser pages many locations and does not fetch already displayed windows again',async()=>{
  const page=await open('many');
  try{
    assert.equal(await page.locator('mark').count(),6);
    assert.equal(await page.evaluate(()=>window.evidenceTestCalls.length),6);
    await page.getByRole('button',{name:'继续查看其余 3 处引用'}).click();
    await page.waitForFunction(()=>document.querySelectorAll('mark').length===9);
    assert.equal(await page.evaluate(()=>window.evidenceTestCalls.length),9);
    assert.equal(new Set(await page.locator('mark').allTextContents()).size,9);
  }finally{await page.close();}
});

test('browser keeps the other file available when one source fails',async()=>{
  const page=await open('error');
  try{
    assert.deepEqual(await page.locator('mark').allTextContents(),['旧付款条款由本条替换，其他约定继续有效。']);
    assert.equal(await page.getByText('此处原文暂时无法读取，其他位置仍可核对。').count(),2);
  }finally{await page.close();}
});
