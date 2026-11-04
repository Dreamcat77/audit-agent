import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';

const require = createRequire(new URL('../frontend/package.json', import.meta.url));
const { chromium } = require('playwright-core');
const [executablePath, distArg = 'frontend/dist'] = process.argv.slice(2);
assert.ok(executablePath, 'Chrome executable path is required');
const dist = path.resolve(distArg);
const user = { userId: 'font-v4', username: 'font-v4', realName: '字体回归测试', roles: ['AUDITOR'] };
const projects = ['服务', '货物', '工程'].map((procurementType, i) => ({
  id: `00000000-0000-0000-0000-00000000001${i}`, projectCode: `FONT-${i}`,
  projectName: `字体回归项目${i}`, departmentName: '测试部', procurementType,
  budgetAmount: 123456, status: 'DRAFT', createdTime: '2026-10-23T00:00:00Z',
  sourceMaterialIds: [], agentReviewStatus: 'NOT_CHECKED', agentRiskLevel: null,
}));
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (url.pathname.startsWith('/api/')) {
    let data = [];
    if (url.pathname === '/api/v1/auth/me' || url.pathname === '/api/v1/auth/login') data = user;
    else if (url.pathname === '/api/v1/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'font-v4' };
    else if (url.pathname === '/api/v1/procurement/projects') data = { items: projects, page: 1, size: 20, total: 3, totalPages: 1 };
    else if (url.pathname.includes('dashboard')) data = { totalProjects: 0, totalContracts: 0, totalReviewSessions: 0, totalAuditTasks: 0, recentTasks: [], statusDistribution: [], riskDistribution: [] };
    else if (url.pathname.includes('unread-count')) data = { count: 0 };
    else data = { items: [], records: [], page: 1, size: 20, total: 0, totalPages: 0 };
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ code: 200, message: 'success', data })); return;
  }
  const file = path.join(dist, url.pathname === '/' ? 'index.html' : url.pathname.slice(1));
  if (!existsSync(file) || url.pathname.includes('..')) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.woff2': 'font/woff2' }[path.extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ executablePath, headless: true, args: ['--disable-background-networking', '--no-first-run'] });
try {
  for (const [width, scale] of [[1920, 1], [1280, 1.5], [960, 2]]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, deviceScaleFactor: scale });
    const page = await context.newPage(); const errors = []; const external = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('request', r => { if (/^https?:/.test(r.url()) && !r.url().startsWith(base)) external.push(r.url()); });
    await page.goto(base); await page.locator('nav button').filter({ hasText: '采购审查' }).click();
    await page.waitForSelector('.procurement-demand-table tbody td');
    await page.evaluate(() => {
      const pre = document.createElement('pre'); pre.className = 'whitespace-pre-wrap select-text text-xs';
      pre.textContent = '材料预览：预算金额 壹拾贰万叁仟肆佰伍拾陆元。'; document.body.append(pre);
    });
    await page.evaluate(() => document.fonts.ready);
    const selectors = [
      '.font-mono.uppercase.tracking-wider',
      '.procurement-demand-table > tbody > tr > td:nth-child(6) .font-mono',
      '.procurement-demand-table > tbody > tr > td:nth-child(7) .font-mono',
      'pre.whitespace-pre-wrap.select-text.text-xs',
      '.procurement-demand-table > tbody > tr > td:nth-child(4) > span.font-mono',
    ];
    const results = await page.evaluate((selectors) => selectors.map(selector => [...document.querySelectorAll(selector)].map(el => {
      const s = getComputedStyle(el), r = el.getBoundingClientRect(), range = document.createRange(); range.selectNodeContents(el);
      const t = range.getBoundingClientRect();
      return { text: el.textContent, font: s.fontFamily, custom: s.fontFamily.includes('Audit UI CJK'), fits: t.left >= r.left - .1 && t.right <= r.right + .1 && t.top >= r.top - .1 && t.bottom <= r.bottom + .1 };
    })), selectors);
    assert.ok(results.flat().length >= 6, JSON.stringify(results));
    assert.ok(results.flat().every(item => item.custom && item.fits), JSON.stringify(results));
    assert.equal(errors.length, 0, errors.join('\n')); assert.equal(external.length, 0, external.join('\n'));
    await context.close();
  }
  console.log(`BROWSER=${browser.version()}; CUMULATIVE_FONT_GROUPS_NO_CLIPPING_3_DPR_NO_EXTERNAL_REQUESTS=PASS`);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
