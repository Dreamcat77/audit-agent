import assert from 'node:assert/strict';
import {after,before,test} from 'node:test';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {existsSync,readdirSync} from 'node:fs';
import {wordFixture,sheetFixture} from './office-fixtures.mjs';

// Optional isolated tool directory permits component tests while a different
// application dependency is network-blocked. It does not validate a full build.
const require=createRequire(process.env.AUDIT_BROWSER_TEST_NODE_MODULES
  ?path.resolve(process.env.AUDIT_BROWSER_TEST_NODE_MODULES,'../package.json'):new URL('../../package.json',import.meta.url));
const {createServer}=await import(pathToFileURL(path.join(path.dirname(require.resolve('vite/package.json')),'dist/node/index.js')));
const {chromium}=require('playwright-core');
const root=path.dirname(fileURLToPath(import.meta.url));
let server,browser,url;
const assetRoot=path.resolve(root,'../../dist/assets');
const productionCss=existsSync(assetRoot)?readdirSync(assetRoot).filter(name=>name.endsWith('.css')).map(name=>path.join(assetRoot,name)):[];
before(async()=>{
  server=await createServer({configFile:false,root,logLevel:'error',cacheDir:path.resolve(root,'../../../.runtime/evidence-vite-cache'),
    optimizeDeps:{include:['react','react-dom/client','docx-preview','xlsx']},esbuild:{jsx:'automatic'},resolve:{alias:[
      {find:/^react$/,replacement:require.resolve('react')},
      {find:'react/jsx-runtime',replacement:require.resolve('react/jsx-runtime')},
      {find:'react/jsx-dev-runtime',replacement:require.resolve('react/jsx-dev-runtime')},
      {find:'react-dom/client',replacement:require.resolve('react-dom/client')},
    ]},server:{host:'127.0.0.1',port:0,fs:{allow:[path.resolve(root,'../..'),path.dirname(require.resolve('react'))]}}});
  await server.listen();url='http://127.0.0.1:'+server.httpServer.address().port;
  browser=await chromium.launch({headless:true,args:['--no-sandbox'],
    executablePath:process.env.AUDIT_BROWSER_EXECUTABLE??(existsSync('/usr/bin/chromium')?'/usr/bin/chromium':undefined)});
  const warmup=await browser.newPage();await warmup.goto(url);await warmup.waitForLoadState('networkidle');await warmup.close();
});
after(async()=>{await browser?.close();await server?.close();});

async function open(mode=''){
  const page=await browser.newPage();page.setDefaultTimeout(5000);await page.goto(url+'/?case='+mode);
  // Use the customer build's actual typography/utilities, rather than unstyled DOM tests.
  assert(productionCss.length,'Run npm run build:customer before browser layout verification');
  for(const file of productionCss)await page.addStyleTag({path:file});
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

test('all cited locations load once without a separate more-content action',async()=>{
  const page=await open('many');try{
    assert.equal(await page.locator('mark').count(),9);
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

async function openOriginal(mode='original',viewport){
  const page=await browser.newPage(viewport?{viewport}:{});page.setDefaultTimeout(8000);
  // Actual image pixels, independent of the SVG coordinates. Metadata is mocked only for component layout tests.
  const pixels=await page.evaluate(()=>{
    const c=document.createElement('canvas');c.width=600;c.height=900;const x=c.getContext('2d');
    x.fillStyle='white';x.fillRect(0,0,600,900);x.fillStyle='black';x.font='24px sans-serif';
    for(let i=0;i<20;i++)x.fillText('Original document page '+i,50,70+i*35);
    return c.toDataURL('image/png').split(',')[1];
  });
  const reads=[];page.originalReads=reads;
  await page.route('**/synthetic-original/**',async route=>{
    reads.push(route.request().url());
    if(route.request().url().includes('/visual')){
      const params=new URL(route.request().url()).searchParams;const start=Number(params.get('start')),end=Number(params.get('end'));
      const selectedPage=mode==='original-multi'&&start>100?9:2;
      await route.fulfill({json:{code:200,message:'success',data:mode==='original-page'?{status:'PAGE_ONLY',anchors:[],pages:[4]}:
        {status:'LOCATED',pages:[selectedPage],anchors:[{pageNumber:selectedPage,method:'OCR',start,end,confidence:.95,verification:'NOT_RECHECKED',polygon:[{x:.08,y:.15},{x:.85,y:.15},{x:.85,y:.2},{x:.08,y:.2}]}]}}});
    }else await route.fulfill({status:200,contentType:'image/png',headers:{'X-Page-Count':'274'},body:Buffer.from(pixels,'base64')});
  });
  await page.goto(url+'/?case='+mode);for(const file of productionCss)await page.addStyleTag({path:file});
  await page.locator('#open').click();
  if(mode==='original-none')await page.getByText('暂无可定位的证据页。').waitFor();
  else await page.locator('.original-evidence-picture img').first().waitFor();return page;
}

test('original evidence opens directly; single-page evidence has no document browsing or mode controls',async()=>{
  const page=await openOriginal();try{
    assert.equal(await page.locator('[data-original-highlight]').count(),1);
    assert.equal(await page.getByText('证据页 · 第 2 页').count(),1);
    assert.equal(await page.getByRole('button',{name:/原文件|关键片段|解析文字|完整引用|上一证据页|下一证据页/}).count(),0);
    assert.equal(await page.locator('blockquote').count(),0);
    const before=await page.locator('.original-evidence-picture').boundingBox();await page.getByRole('button',{name:'放大原页'}).click();
    const after=await page.locator('.original-evidence-picture').boundingBox();assert(after.width>before.width);
    const alignment=await page.evaluate(()=>{const a=document.querySelector('.original-evidence-picture img').getBoundingClientRect(),b=document.querySelector('.original-evidence-picture svg').getBoundingClientRect();return Math.abs(a.width-b.width)+Math.abs(a.height-b.height);});assert(alignment<1);
    await page.getByRole('button',{name:'关闭',exact:true}).click();await page.locator('#open').click();
    await page.locator('.original-evidence-picture img').waitFor();assert.equal(await page.getByText('100%',{exact:true}).count(),1);
  }finally{await page.close();}
});

test('nonadjacent evidence pages navigate directly from page 2 to page 9, never into other document pages',async()=>{
  const page=await openOriginal('original-multi');try{
    assert.equal(await page.getByRole('button',{name:'上一证据页'}).isDisabled(),true);
    await page.getByRole('button',{name:'下一证据页'}).click();await page.getByText('证据页 · 第 9 页').waitFor();
    await page.locator('.original-evidence-picture img').waitFor();
    assert.equal(await page.getByRole('button',{name:'下一证据页'}).isDisabled(),true);
    assert.equal(await page.locator('[data-original-highlight]').count(),1);
    await page.getByRole('button',{name:'上一证据页'}).click();await page.getByText('证据页 · 第 2 页').waitFor();
    assert(page.originalReads.filter(u=>u.includes('/image')).every(u=>/pages\/(2|9)\/image/.test(u)));
  }finally{await page.close();}
});

test('whole-page citation renders original pixels but highlights only its verified price clause',async()=>{
  const page=await openOriginal('original-concise');try{
    const params=new URL(page.originalReads.find(u=>u.includes('/visual'))).searchParams;
    assert(Number(params.get('end'))-Number(params.get('start'))<=220);assert(Number(params.get('start'))>200);
    assert.equal(await page.locator('[data-original-highlight]').count(),1);assert.equal(await page.locator('blockquote').count(),0);
    assert.equal(await page.getByRole('button',{name:/关键片段|解析文字|完整引用/}).count(),0);await assertContained(page);
  }finally{await page.close();}
});

test('unknown evidence page never fetches document page one',async()=>{
  const page=await openOriginal('original-none');try{assert.equal(page.originalReads.length,0);}finally{await page.close();}
});

test('unproven coordinates show page only without a guessed highlight',async()=>{
  const page=await openOriginal('original-page');try{
    assert.equal(await page.getByText('证据页 · 第 4 页').count(),1);
    assert.equal(await page.locator('[data-original-highlight]').count(),0);
    assert.equal(await page.getByText('仅定位到页面，未标记具体位置').count(),1);
  }finally{await page.close();}
});

test('original highlight cannot cross header at top middle bottom on a small viewport',async()=>{
  const page=await openOriginal('original',{width:760,height:560});try{
    for(const position of [0,.5,1]){
      await page.locator('.original-evidence-viewport').evaluate((el,f)=>el.scrollTop=f*(el.scrollHeight-el.clientHeight),position);
      await assertContained(page);
    }
  }finally{await page.close();}
});

async function assertContained(page){
  const value=await page.evaluate(()=>{
    const panel=document.querySelector('.source-evidence-panel'),header=panel.querySelector('.source-evidence-header'),body=panel.querySelector('.source-evidence-body');
    const p=panel.getBoundingClientRect(),h=header.getBoundingClientRect(),b=body.getBoundingClientRect();
    const points=[h.top+2,h.top+h.height/2,h.bottom-2,p.top+2];
    const leaked=points.some(y=>[p.left+30,p.left+p.width/2,p.right-30].some(x=>body.contains(document.elementFromPoint(x,y))));
    return {top:p.top,bottom:p.bottom,viewport:innerHeight,bodyTop:b.top,headerBottom:h.bottom,bodyBottom:b.bottom,panelScroll:panel.scrollTop,
      leaked,overflow:getComputedStyle(panel).overflowY,bodyOverflow:getComputedStyle(body).overflowY,headerShrink:getComputedStyle(header).flexShrink,
      bodyMin:getComputedStyle(body).minHeight,headerTop:h.top,bodyScroll:body.scrollTop,bodyHeight:body.clientHeight,bodyContent:body.scrollHeight,pageScroll:scrollY};
  });
  assert(value.top>=19&&value.bottom<=value.viewport-19,'PANEL_OUTSIDE_VIEWPORT');
  assert(value.bodyTop>=value.headerBottom-.5&&value.bodyBottom<=value.bottom+.5,'HEADER_BODY_OVERLAP');
  assert.equal(value.leaked,false,'BODY_PAINTED_OVER_HEADER');assert.equal(value.panelScroll,0);assert.equal(value.overflow,'hidden');
  assert.equal(value.bodyOverflow,'auto');assert.equal(value.headerShrink,'0');assert.equal(value.bodyMin,'0px');assert.equal(value.pageScroll,0);
  return value;
}

test('single page stays below a fixed header and reopens with unchanged text and page number',async()=>{
  const page=await open('single');
  try{
    const initial=await assertContained(page);assert(initial.bodyContent<=initial.bodyHeight+1);
    assert.equal(await page.locator('mark').textContent(),'本页原文：验收合格后十日内支付尾款。');
    assert((await page.getByRole('region',{name:'原文内容'}).innerText()).includes('第 1 页'));
    await page.getByRole('button',{name:'关闭',exact:true}).click();await page.locator('#open').click();await page.locator('mark').waitFor();
    await assertContained(page);assert.equal(await page.locator('mark').count(),1);
  }finally{await page.close();}
});

test('twelve pages scroll only inside Body, never paint highlights over Header, and fit small viewports',async()=>{
  const page=await open('long');
  try{
    assert.equal(await page.locator('mark').count(),12);
    assert.equal(await page.evaluate(()=>window.evidenceTestCalls.length),12);
    const quotes=await page.locator('mark').allTextContents();assert( quotes.every((s,i)=>s.startsWith(`第${i+1}页审查依据`)) );
    const initial=await assertContained(page);assert(initial.bodyContent>initial.bodyHeight);
    for(const viewport of [{width:1280,height:800},{width:800,height:500},{width:375,height:320}]){
      await page.setViewportSize(viewport);
      const headerTop=(await assertContained(page)).headerTop;
      for(const fraction of [0,.5,1,0]){
        await page.locator('.source-evidence-body').evaluate((body,f)=>{body.scrollTop=(body.scrollHeight-body.clientHeight)*f;},fraction);
        const state=await assertContained(page);assert.equal(state.headerTop,headerTop,'HEADER_MOVED_WITH_BODY');
      }
      const body=page.locator('.source-evidence-body');await body.hover();await page.mouse.wheel(0,-10000);await assertContained(page);
      const first=page.getByRole('button',{name:'关闭',exact:true});await body.focus();await page.keyboard.press('Tab');assert(await first.evaluate(n=>document.activeElement===n));
      await page.keyboard.press('Shift+Tab');assert(await body.evaluate(n=>document.activeElement===n));
    }
    await page.keyboard.press('Escape');assert.equal(await page.getByRole('dialog').count(),0);assert.equal(await page.evaluate(()=>document.activeElement.id),'open');
    await page.locator('#open').click();await page.locator('mark').first().waitFor();await assertContained(page);assert.equal(await page.locator('mark').count(),12);
  }finally{await page.close();}
});

async function openOffice(mode='office-word',fixtureOptions={}){
 const page=await browser.newPage();page.setDefaultTimeout(12000);page.officeErrors=[];page.on('pageerror',e=>page.officeErrors.push(e.message));
 const data=mode.includes('sheet')?sheetFixture(mode.endsWith('xls')):await wordFixture(fixtureOptions);
 await page.route('**/synthetic-office/**',r=>r.request().url().endsWith('/office-html')?r.fulfill({json:{code:200,message:'success',data:'<html><head><style>p{line-height:1.8}</style></head><body><div><p>原件中的服务合同标题</p><p>前面的验收约定和服务范围。</p><p>甲方应于验收合格后十日内支付尾款。</p><table><tr><td>服务范围</td><td>平台运维</td></tr></table><p>后面的签章栏和附件清单。</p></div></body></html>'}}):r.fulfill({status:mode==='office-denied'?404:200,contentType:'application/octet-stream',body:data}));
 await page.goto(url+'/?case='+mode);for(const css of productionCss)await page.addStyleTag({path:css});await page.locator('#open').click();
 await page.locator('.office-evidence').waitFor();await page.getByText('正在读取原页…',{exact:true}).waitFor({state:'hidden'});
 return page;
}

test('Word displays the whole original evidence page including surrounding paragraphs and table, without other pages',async()=>{
 const page=await openOffice();try{
  const frame=page.frameLocator('.office-evidence-frame');await frame.locator('mark').first().waitFor();
  assert.equal(await frame.locator('mark').allTextContents().then(v=>v.join('')),'甲方应于验收合格后十日内支付尾款。');
  assert.equal(await frame.getByText('第2页原文件标题',{exact:true}).isVisible(),true);
  assert.equal(await frame.getByText('前后文2条0：原件中的验收、服务和付款约定。',{exact:true}).isVisible(),true);
  assert.equal(await frame.getByText('平台运维',{exact:true}).filter({visible:true}).count(),1);
  assert.equal(await frame.getByText('第1页原文件标题',{exact:true}).isVisible(),false);
  assert.equal(await page.getByRole('button',{name:'下一证据页'}).count(),0);
  assert.deepEqual(page.officeErrors,[]);await assertContained(page);
  for(const fraction of [0,.5,1]){await frame.locator('body').evaluate((_,f)=>window.scrollTo(0,(document.body.scrollHeight-innerHeight)*f),fraction);await assertContained(page);}
  await page.getByRole('button',{name:'关闭',exact:true}).click();await page.locator('#open').click();await page.frameLocator('.office-evidence-frame').locator('mark').first().waitFor();await assertContained(page);
 }finally{await page.close();}
});

test('Excel displays the complete evidence worksheet with formatted amounts, merged title and surrounding rows',async()=>{
 const page=await openOffice('office-sheet');try{
  const frame=page.frameLocator('.office-evidence-frame');await frame.locator('mark').first().waitFor();
  assert.equal(await frame.locator('mark').allTextContents().then(v=>v.join('')),'项目预算2,200,000.00');
  assert.equal(await frame.locator('td[colspan="2"]').filter({visible:true}).textContent(),'原始预算批复表');
  assert.equal(await frame.getByText('原始明细第60行',{exact:true}).filter({visible:true}).count(),1);
  assert.equal(await frame.locator('section').filter({visible:true}).count(),1);
  assert((await frame.locator('table.office-sheet').filter({visible:true}).locator('thead th').first().boundingBox()).width<60,'ROW_NUMBER_COLUMN_TOO_WIDE');
  assert.equal(await page.getByText('工作表 · 预算批复',{exact:true}).count(),1);
  assert.equal(await page.getByRole('button',{name:'下一证据页'}).count(),0);
  for(const viewport of [{width:1440,height:1000},{width:760,height:560},{width:375,height:500}]){await page.setViewportSize(viewport);await assertContained(page);}
  assert.deepEqual(page.officeErrors,[]);
 }finally{await page.close();}
});

test('Office rejects repeated or mismatched quotations and denied originals without inventing a highlight',async()=>{
 for(const [mode,options] of [['office-word',{duplicate:true}],['office-mismatch',{}],['office-denied',{}]]){
  const page=await openOffice(mode,options);try{
   assert.equal(await page.frameLocator('.office-evidence-frame').locator('mark').count(),0);
   assert.equal(await page.getByRole('link',{name:'下载原文件'}).count(),1);assert.deepEqual(page.officeErrors,[]);
  }finally{await page.close();}
 }
});

test('Word lacking saved page breaks is paginated from original blocks instead of showing the full document',async()=>{
 const page=await openOffice('office-word',{continuous:true});try{
  const frame=page.frameLocator('.office-evidence-frame');await frame.locator('mark').first().waitFor();
  assert.equal(await frame.locator('section.office-word').filter({visible:true}).count(),1);
  const count=await frame.locator('section.office-word').count();assert(count>5);
  assert((await frame.locator('section.office-word').filter({visible:true}).innerText()).length<3000);
  assert.deepEqual(page.officeErrors,[]);
 }finally{await page.close();}
});

test('legacy DOC native layout and binary XLS both keep full context with verified highlights',async()=>{
 for(const mode of ['office-legacy','office-sheet-xls']){
  const page=await openOffice(mode);try{
   const frame=page.frameLocator('.office-evidence-frame');await frame.locator('mark').first().waitFor();
   assert((await frame.locator('body').innerText()).includes(mode==='office-legacy'?'后面的签章栏和附件清单。':'原始明细第60行'));
   assert.deepEqual(page.officeErrors,[]);await assertContained(page);
   await frame.locator('body').evaluate(body=>{body.tabIndex=0;body.focus();});await page.keyboard.press('Escape');assert.equal(await page.getByRole('dialog').count(),0);
  }finally{await page.close();}
 }
});
