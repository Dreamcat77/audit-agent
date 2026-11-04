import assert from 'node:assert/strict';
import {after,before,test} from 'node:test';
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const frontend=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const require=createRequire(process.env.AUDIT_BROWSER_TEST_NODE_MODULES
  ?path.resolve(process.env.AUDIT_BROWSER_TEST_NODE_MODULES,'../package.json'):path.join(frontend,'package.json'));
const {build}=require('esbuild');
const {chromium}=require('playwright-core');
let server,browser,base;

before(async()=>{
  const fixture=`
    import React,{useState} from 'react';
    import {createRoot} from 'react-dom/client';
    import {ApplicationBootstrap} from ${JSON.stringify(path.join(frontend,'src/browser/ApplicationBootstrap.tsx'))};
    const mode=new URLSearchParams(location.search).get('case');
    if(mode==='load-failure')throw new Error('PRIVATE_CONTRACT_CONTENT');
    if(mode==='slow')await new Promise(resolve=>{window.releaseSlowStartup=resolve;});
    window.appLoaded=true;
    function App(){const [broken,setBroken]=useState(false);if(broken)throw new Error('PRIVATE_CONTRACT_CONTENT');return <><h1>业务系统已打开</h1><button onClick={()=>setBroken(true)}>模拟渲染失败</button></>}
    createRoot(document.getElementById('root')).render(<ApplicationBootstrap><App/></ApplicationBootstrap>);
  `;
  const bundle=await build({entryPoints:[path.join(frontend,'src/bootstrap.ts')],bundle:true,write:false,format:'esm',
    target:'chrome109',jsx:'automatic',nodePaths:[path.dirname(require.resolve('react/package.json'))+'/..'],
    plugins:[{name:'fixture-app',setup(builder){
      builder.onResolve({filter:/^\.\/main\.tsx$/},()=>({path:'fixture-app',namespace:'fixture'}));
      builder.onLoad({filter:/.*/,namespace:'fixture'},()=>({contents:fixture,loader:'tsx',resolveDir:frontend}));
    }}]});
  const html=(await readFile(path.join(frontend,'index.html'),'utf8')).replace('/src/bootstrap.ts','/app.js');
  server=createServer(async(request,response)=>{
    const pathname=new URL(request.url,'http://localhost').pathname;
    const body=pathname==='/app.js'?bundle.outputFiles[0].text
      :pathname==='/browser-support.js'?await readFile(path.join(frontend,'public/browser-support.js'),'utf8')
      :pathname==='/browser-check.html'?await readFile(path.join(frontend,'public/browser-check.html'),'utf8'):html;
    response.setHeader('Content-Type',pathname.endsWith('.js')?'text/javascript; charset=utf-8':'text/html; charset=utf-8');
    response.end(body);
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));base='http://127.0.0.1:'+server.address().port;
  browser=await chromium.launch({headless:true,executablePath:process.env.AUDIT_BROWSER_EXECUTABLE||'/usr/bin/chromium',args:['--no-sandbox']});
});
after(async()=>{await browser?.close();await new Promise(resolve=>server?.close(resolve));});

test('working browser opens the app and removes the independent startup notice',async()=>{
  const page=await browser.newPage();
  try{await page.goto(base);await page.getByRole('heading',{name:'业务系统已打开'}).waitFor();
    assert.equal(await page.locator('#audit-browser-support').isVisible(),false);
    assert.equal(await page.evaluate(()=>window.auditBrowserSupport.report().includes('状态: READY')),true);
  }finally{await page.close();}
});

test('emulated IE document mode gets a readable notice without executing the app',async()=>{
  const page=await browser.newPage();
  try{await page.addInitScript(()=>Object.defineProperty(document,'documentMode',{value:11}));
    await page.goto(base);await page.getByRole('heading',{name:'当前浏览器暂时无法打开系统'}).waitFor();
    assert.equal(await page.evaluate(()=>window.appLoaded),undefined);
    assert.equal(await page.locator('#root').isVisible(),false);
    await page.getByRole('button',{name:'复制检测信息',exact:true}).click();
    assert.match(await page.getByRole('textbox',{name:'浏览器检测信息'}).inputValue(),/文档模式: 11/);
  }finally{await page.close();}
});

test('missing layout capability is detected even with a modern browser user agent',async()=>{
  const page=await browser.newPage();
  try{await page.addInitScript(()=>Object.defineProperty(window,'CSSLayerBlockRule',{value:undefined}));
    await page.goto(base);await page.getByRole('heading',{name:'当前浏览器暂时无法打开系统'}).waitFor();
    assert.equal(await page.evaluate(()=>window.appLoaded),undefined);
    assert.match(await page.evaluate(()=>window.auditBrowserSupport.report()),/样式层: 不支持/);
  }finally{await page.close();}
});

test('module failure shows a recoverable load error without exposing exception or URL contents',async()=>{
  const page=await browser.newPage();
  try{await page.goto(base+'/?case=load-failure&secret=PRIVATE_SESSION_VALUE');
    await page.getByRole('heading',{name:'暂时无法打开系统'}).waitFor();
    const report=await page.evaluate(()=>window.auditBrowserSupport.report());
    assert.match(report,/状态: LOAD_FAILED/);assert.doesNotMatch(report,/PRIVATE_|http:\/\/|case=/);
    assert.doesNotMatch(await page.locator('#audit-browser-support').innerText(),/PRIVATE_CONTRACT_CONTENT/);
  }finally{await page.close();}
});

test('a render failure after startup restores the independent notice',async()=>{
  const page=await browser.newPage();
  try{await page.goto(base);await page.getByRole('button',{name:'模拟渲染失败'}).click();
    await page.getByRole('heading',{name:'暂时无法打开系统'}).waitFor();
    assert.match(await page.evaluate(()=>window.auditBrowserSupport.report()),/状态: RENDER_FAILED/);
    assert.equal(await page.locator('#root').isVisible(),false);
  }finally{await page.close();}
});

test('slow startup stays visible and is not misreported as an unsupported browser',async()=>{
  const page=await browser.newPage();
  try{await page.clock.install();await page.goto(base+'/?case=slow');await page.clock.fastForward(21000);
    await page.getByRole('heading',{name:'页面加载时间较长'}).waitFor();
    assert.match(await page.evaluate(()=>window.auditBrowserSupport.report()),/状态: STARTUP_SLOW/);
    await page.evaluate(()=>window.releaseSlowStartup());
    await page.getByRole('heading',{name:'业务系统已打开'}).waitFor();
    assert.equal(await page.locator('#audit-browser-support').isVisible(),false);
  }finally{await page.close();}
});

test('failure of the diagnostic script does not hide an otherwise working app',async()=>{
  const page=await browser.newPage();
  try{await page.route('**/browser-support.js',route=>route.abort());await page.goto(base);
    await page.getByRole('heading',{name:'业务系统已打开'}).waitFor();
    assert.equal(await page.locator('#audit-browser-support').isVisible(),false);
  }finally{await page.close();}
});

test('diagnostic page works without the app and offers manual copy when clipboard is blocked',async()=>{
  const page=await browser.newPage({viewport:{width:390,height:844}});
  try{await page.addInitScript(()=>{
      Object.defineProperty(document,'execCommand',{value:()=>false});
      localStorage.setItem('private-account','PRIVATE_ACCOUNT');document.cookie='secret=PRIVATE_COOKIE';
    });
    await page.goto(base+'/browser-check.html?private=PRIVATE_QUERY');
    await page.getByRole('heading',{name:'浏览器检测',exact:true}).waitFor();
    await page.getByRole('button',{name:'复制检测信息',exact:true}).click();
    assert.equal(await page.getByRole('button',{name:'请全选下方信息复制'}).isVisible(),true);
    const area=page.getByRole('textbox',{name:'浏览器检测信息'});
    assert.equal(await area.isVisible(),true);assert.doesNotMatch(await area.inputValue(),/PRIVATE_/);
    assert.equal(await page.evaluate(()=>window.appLoaded),undefined);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  }finally{await page.close();}
});

test('disabled JavaScript still displays a static notice instead of an empty page',async()=>{
  const context=await browser.newContext({javaScriptEnabled:false});const page=await context.newPage();
  try{await page.goto(base);assert.equal(await page.getByRole('heading',{name:'正在打开系统'}).isVisible(),true);
    assert.match(await page.locator('#audit-browser-support').innerText(),/浏览器已禁用脚本/);
  }finally{await context.close();}
});
