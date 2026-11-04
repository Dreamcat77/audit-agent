import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';

const require = createRequire(new URL('../frontend/package.json', import.meta.url));
const { chromium } = require('playwright-core');
const [executablePath, distArg = 'frontend/dist'] = process.argv.slice(2);
assert.ok(executablePath, 'Pass the Chrome executable path');
const dist = path.resolve(distArg);
const user = { userId: 'font-test', username: 'font-test', realName: '字体兼容测试', roles: ['AUDITOR'] };
const projects = ['服务', '货物', '工程'].map((procurementType, index) => ({
  id: `00000000-0000-0000-0000-00000000001${index}`,
  projectCode: `PR-FONT-${index + 1}`,
  projectName: `字体兼容测试项目${index + 1}`,
  departmentName: '综合管理部',
  procurementType,
  budgetAmount: 180000,
  status: 'DRAFT',
  createdTime: '2026-10-08T06:00:00Z',
  sourceMaterialIds: [],
  agentReviewStatus: 'NOT_CHECKED',
  agentRiskLevel: null,
}));

const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (url.pathname.startsWith('/api/')) {
    let data = [];
    if (url.pathname === '/api/v1/auth/me' || url.pathname === '/api/v1/auth/login') data = user;
    else if (url.pathname === '/api/v1/auth/csrf') data = { headerName: 'X-CSRF-TOKEN', token: 'synthetic-csrf' };
    else if (url.pathname === '/api/v1/procurement/projects') data = { items: projects, page: 1, size: 20, total: projects.length, totalPages: 1 };
    else if (['/api/v1/contract-audit-tasks', '/api/v1/budget/validation-records', '/api/v1/projects', '/api/v1/projects/intake-drafts', '/api/v1/blacklist/entries/query'].includes(url.pathname)) data = { items: [], records: [], page: 1, size: 20, total: 0, totalPages: 0 };
    else if (url.pathname.includes('unread-count')) data = { count: 0 };
    else if (url.pathname.includes('dashboard')) data = { totalProjects: 0, totalContracts: 0, totalReviewSessions: 0, totalAuditTasks: 0, recentTasks: [], statusDistribution: [], riskDistribution: [] };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ code: 200, message: 'success', data }));
    return;
  }

  const relative = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
  if (relative.includes('..')) { res.writeHead(400); res.end(); return; }
  const file = path.join(dist, relative);
  if (!existsSync(file)) { res.writeHead(404); res.end(); return; }
  const types = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.woff2': 'font/woff2' };
  res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
});

await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ executablePath, headless: true, args: ['--disable-background-networking', '--no-first-run'] });
try {
  const results = [];
  for (const [width, scale] of [[1920, 1], [1536, 1.25], [1280, 1.5], [960, 2]]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, deviceScaleFactor: scale });
    const page = await context.newPage();
    const errors = [], external = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => { if (!request.url().startsWith(base) && /^https?:/.test(request.url())) external.push(request.url()); });
    await page.goto(base);
    await page.waitForSelector('nav button');
    await page.locator('nav button').filter({ hasText: '采购审查' }).click();
    await page.waitForSelector('.procurement-demand-table > tbody > tr');
    await page.evaluate(() => document.fonts.ready);

    const cells = page.locator('.procurement-demand-table > tbody > tr > td:nth-child(4) > span.font-mono');
    assert.deepEqual(await cells.allTextContents(), ['服务', '货物', '工程']);
    const styles = await cells.evaluateAll(nodes => nodes.map(node => {
      const range = document.createRange();
      range.selectNodeContents(node);
      const text = range.getBoundingClientRect();
      const badge = node.getBoundingClientRect();
      const cell = node.parentElement.getBoundingClientRect();
      const computed = getComputedStyle(node);
      return { text: node.textContent, font: computed.fontFamily, badge: [badge.x, badge.y, badge.width, badge.height], cell: [cell.x, cell.y, cell.width, cell.height], textBox: [text.x, text.y, text.width, text.height] };
    }));
    assert.ok(styles.every(item => item.font.startsWith('"Audit UI CJK"')));
    assert.ok(await page.evaluate(() => document.fonts.check('12px "Audit UI CJK"', '服务货物工程')));
    for (const item of styles) {
      const [bx, by, bw, bh] = item.badge;
      const [cx, cy, cw, ch] = item.cell;
      const [tx, ty, tw, th] = item.textBox;
      assert.ok(tx >= bx && ty >= by && tx + tw <= bx + bw + 0.1 && ty + th <= by + bh + 0.1, JSON.stringify(item));
      assert.ok(bx >= cx && bx + bw <= cx + cw + 0.1 && by >= cy && by + bh <= cy + ch + 0.1, JSON.stringify(item));
    }

    const cdp = await context.newCDPSession(page);
    await cdp.send('DOM.enable');
    await cdp.send('CSS.enable');
    const document = await cdp.send('DOM.getDocument');
    const nodes = await cdp.send('DOM.querySelectorAll', { nodeId: document.root.nodeId, selector: '.procurement-demand-table > tbody > tr > td:nth-child(4) > span.font-mono' });
    for (const nodeId of nodes.nodeIds) {
      const fonts = await cdp.send('CSS.getPlatformFontsForNode', { nodeId });
      assert.ok(fonts.fonts.some(font => font.isCustomFont && font.familyName === 'Audit UI CJK'), JSON.stringify(fonts));
    }
    assert.equal(errors.length, 0, errors.join('\n'));
    assert.equal(external.length, 0, external.join('\n'));
    results.push({ width, scale, styles });
    await context.close();
  }
  console.log(`BROWSER=${browser.version()}; PROCUREMENT_TYPES_LOCAL_CJK_NO_CLIP_4_DPI_SIZES_NO_EXTERNAL_REQUESTS=PASS`);
  console.log(JSON.stringify(results));
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
