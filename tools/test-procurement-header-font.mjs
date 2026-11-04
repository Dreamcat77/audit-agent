import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import sharp from '../../node_modules/sharp/dist/index.mjs';

const require = createRequire(new URL('../frontend/package.json', import.meta.url));
const { chromium } = require('playwright-core');
const [executablePath, baselineArg, overlayArg, label = '109'] = process.argv.slice(2);
const baseline = path.resolve(baselineArg);
const overlay = path.resolve(overlayArg);
const evidence = path.resolve('.runtime/header-font-browser');
mkdirSync(evidence, { recursive: true });
let patched = false;
const user = { userId: 'fixture-user', username: 'fixture-user', realName: '兼容测试用户', roles: ['AUDITOR'] };
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (url.pathname.startsWith('/api/')) {
    let data = [];
    if (url.pathname === '/api/v1/auth/me' || url.pathname === '/api/v1/auth/login') data = user;
    else if (url.pathname === '/api/v1/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'synthetic-csrf' };
    else if (url.pathname === '/api/v1/procurement/projects') data = {items:[{id:'00000000-0000-0000-0000-000000000010',projectCode:'PR-FONT-TEST',projectName:'中文表头兼容测试采购项目',departmentName:'综合管理部',procurementType:'货物',budgetAmount:180000,status:'DRAFT',createdTime:'2026-10-08T06:00:00Z',sourceMaterialIds:[],agentReviewStatus:'NOT_CHECKED',agentRiskLevel:null}],page:1,size:20,total:1,totalPages:1};
    else if (['/api/v1/contract-audit-tasks','/api/v1/budget/validation-records','/api/v1/projects','/api/v1/projects/intake-drafts','/api/v1/blacklist/entries/query'].includes(url.pathname)) data = {items:[],records:[],page:1,size:20,total:0,totalPages:0};
    else if (url.pathname.includes('unread-count')) data = {count:0};
    else if (url.pathname.includes('dashboard')) data = {totalProjects:0,totalContracts:0,totalReviewSessions:0,totalAuditTasks:0,recentTasks:[],statusDistribution:[],riskDistribution:[]};
    res.writeHead(200, {'Content-Type':'application/json'});
    res.end(JSON.stringify({code:200,message:'success',data}));
    return;
  }
  const relative = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
  if (relative.includes('..')) { res.writeHead(400); res.end(); return; }
  let file = path.join(patched ? overlay : baseline, relative);
  if (!existsSync(file)) file = path.join(baseline, relative);
  if (!existsSync(file)) { res.writeHead(404); res.end(); return; }
  const types = {'.html':'text/html','.js':'application/javascript','.css':'text/css','.woff2':'font/woff2'};
  res.writeHead(200, {'Content-Type':types[path.extname(file)] || 'application/octet-stream'});
  res.end(readFileSync(file));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({executablePath, headless:true, args:['--disable-background-networking','--no-first-run']});
try {
  const checks = [];
  for (const [width, scale] of [[1920,1],[1536,1.25],[1280,1.5],[960,2]]) {
    const context = await browser.newContext({viewport:{width,height:900},deviceScaleFactor:scale});
    const page = await context.newPage();
    const errors = [], external = [];
    page.on('pageerror', err => errors.push(err.message));
    page.on('request', req => { if (!req.url().startsWith(base) && /^https?:/.test(req.url())) external.push(req.url()); });
    const visit = async () => {
      await page.goto(base);
      await page.waitForSelector('nav button');
      await page.locator('nav button').filter({hasText:'采购审查'}).click();
      await page.waitForSelector('table thead th');
      await page.evaluate(() => document.fonts.ready);
      await page.addStyleTag({content:'*,*::before,*::after { animation:none!important; transition:none!important; caret-color:transparent!important; }'});
      await page.mouse.move(0,0);
    };
    patched = false;
    await visit();
    const bodyBefore = await page.locator('table tbody').evaluate(el => ({html:el.innerHTML,style:getComputedStyle(el).fontFamily,color:getComputedStyle(el).color}));
    const navBefore = await page.locator('nav').screenshot();
    patched = true;
    await visit();
    const bodyAfter = await page.locator('table tbody').evaluate(el => ({html:el.innerHTML,style:getComputedStyle(el).fontFamily,color:getComputedStyle(el).color}));
    assert.deepEqual(bodyAfter, bodyBefore, 'Table body markup/font/color changed');
    const navAfter = await page.locator('nav').screenshot();
    const left = await sharp(navBefore).ensureAlpha().raw().toBuffer({resolveWithObject:true});
    const right = await sharp(navAfter).ensureAlpha().raw().toBuffer({resolveWithObject:true});
    if (!right.data.equals(left.data)) {
      writeFileSync(path.join(evidence,`${label}-nav-before.png`),navBefore);
      writeFileSync(path.join(evidence,`${label}-nav-after.png`),navAfter);
    }
    assert.deepEqual(right.info,left.info);
    assert.ok(right.data.equals(left.data), 'Navigation pixels changed');
    const metrics = await page.locator('.procurement-demand-table thead th').evaluateAll(cells => cells.map(th => {
      const s=getComputedStyle(th),r=th.getBoundingClientRect(),range=document.createRange();
      range.selectNodeContents(th);
      const t=range.getBoundingClientRect();
      return {text:th.textContent,font:s.fontFamily,size:s.fontSize,weight:s.fontWeight,line:s.lineHeight,color:s.color,cell:[r.x,r.y,r.width,r.height],textBox:[t.x,t.y,t.width,t.height]};
    }));
    assert.deepEqual(metrics.map(h => h.text), ['需求编号','项目名称','申请部门','采购类型','预算金额','智能体预审','询比价报警','创建时间','操作']);
    const cdp = await context.newCDPSession(page);
    await cdp.send('DOM.enable'); await cdp.send('CSS.enable');
    const document = await cdp.send('DOM.getDocument');
    const nodes = await cdp.send('DOM.querySelectorAll',{nodeId:document.root.nodeId,selector:'.procurement-demand-table thead th'});
    const fonts = [];
    for (let i=0;i<metrics.length;i++) {
      const h=metrics[i];
      assert.ok(h.font.startsWith('"Audit Header SC"'));
      assert.equal(h.size,'12px'); assert.equal(h.weight,'500'); assert.equal(h.line,'18px');
      const [x,y,w,height]=h.cell,[tx,ty,tw,th]=h.textBox;
      assert.ok(tx>=x && ty>=y && tx+tw<=x+w+.1 && ty+th<=y+height+.1,JSON.stringify(h));
      const result=await cdp.send('CSS.getPlatformFontsForNode',{nodeId:nodes.nodeIds[i]});
      assert.ok(result.fonts.length>0 && result.fonts.every(f=>f.isCustomFont && f.familyName==='Audit Header SC'), 'A heading still uses a client fallback font: '+JSON.stringify(result));
      fonts.push(result.fonts);
    }
    assert.equal(errors.length,0,errors.join('\n'));
    assert.equal(external.length,0,'External font/resource request: '+external.join(','));
    checks.push({width,scale,metrics,fonts});
    await page.screenshot({path:path.join(evidence,`${label}-${width}-${scale}.png`)});
    await context.close();
  }
  writeFileSync(path.join(evidence,`${label}.json`),JSON.stringify({browser:browser.version(),checks},null,2));
  console.log(`BROWSER=${browser.version()}; ALL_HEADERS_CUSTOM_FONT_NO_CLIPPING_4_DPI_SIZES_NAV_PIXELS_BODY_UNCHANGED_OFFLINE=PASS`);
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
