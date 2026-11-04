// Real HTTP integration against an isolated local-profile backend. Only synthetic materials are uploaded.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { createServer, request as httpRequest } from 'node:http';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import path from 'node:path';

const require = createRequire(new URL('../frontend/package.json', import.meta.url));
const { chromium } = require('playwright-core');
const XLSX = require('xlsx');
const [executable, distArg, backendArg, outputArg] = process.argv.slice(2);
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(backendArg).hostname), 'this synthetic test requires an isolated local backend');
const dist = path.resolve(distArg), output = path.resolve(outputArg);
mkdirSync(output, { recursive: true });
let raw = '';
for await (const chunk of process.stdin) raw += chunk;
const credentials = JSON.parse(raw);
raw = '';

const server = createServer((req, res) => {
  if (req.url.startsWith('/api/')) {
    const upstream = httpRequest(new URL(req.url, backendArg), {
      method: req.method, headers: { ...req.headers, host: new URL(backendArg).host },
    }, response => { res.writeHead(response.statusCode, response.headers); response.pipe(res); });
    upstream.on('error', () => { res.writeHead(502); res.end(); });
    req.pipe(upstream);
    return;
  }
  const relative = new URL(req.url, 'http://localhost').pathname;
  const file = path.resolve(dist, relative === '/' ? 'index.html' : '.' + relative);
  if (!file.startsWith(dist + path.sep) || !existsSync(file)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': ({ '.html': 'text/html', '.js': 'application/javascript',
    '.css': 'text/css', '.woff2': 'font/woff2' })[path.extname(file)] ?? 'application/octet-stream' });
  res.end(readFileSync(file));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ executablePath: executable, headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1100 }, acceptDownloads: true });
  const api = context.request;
  const read = async url => {
    const response = await api.get(base + url);
    assert.equal(response.status(), 200, url);
    return (await response.json()).data;
  };
  let csrf = await read('/api/v1/auth/csrf');
  const login = await api.post(base + '/api/v1/auth/login', { data: credentials, headers: { [csrf.headerName]: csrf.token } });
  assert.equal(login.status(), 200, 'local synthetic login');
  delete credentials.password;
  csrf = await read('/api/v1/auth/csrf');
  const headers = { [csrf.headerName]: csrf.token };
  const uploaded = [];
  const createBatch = async files => {
    const batchId = randomUUID();
    const members = files.map(file => ({ operationKey: randomUUID(), fileName: file.name, fileSize: file.bytes.length }));
    const register = await api.post(base + '/api/v1/budget/material-uploads/batches', {
      headers, data: { batchId, members, budgetYear: '2026', departmentCode: 'PROCUREMENT' },
    });
    assert.equal(register.status(), 200);
    for (const [index, file] of files.entries()) {
      const result = await api.post(base + '/api/v1/budget/material-uploads', {
        headers: { ...headers, 'Idempotency-Key': members[index].operationKey },
        multipart: { batchId, budgetYear: '2026', departmentCode: 'PROCUREMENT',
          file: { name: file.name, mimeType: 'application/octet-stream', buffer: file.bytes } },
      });
      assert.equal(result.status(), 200);
      uploaded.push((await result.json()).data.id);
    }
    await assertEventually(async () => (await read(`/api/v1/budget/material-uploads/batches/${batchId}`)).status === 'REVIEWED');
    return batchId;
  };
  const body = text => Buffer.from(text, 'utf8');
  const primary = await createBatch([
    { name: '合成办公设备项目_申报.txt', bytes: body('项目编号：SYNTHETIC-BATCH；科目名称：终端设备；申报金额：12000元\n项目编号：SYNTHETIC-BATCH；科目名称：办公软件；申报金额：3000元') },
    { name: '合成办公设备项目_批复.txt', bytes: body('项目编号：SYNTHETIC-BATCH；科目名称：终端设备；批复金额：10000元\n项目编号：SYNTHETIC-BATCH；科目名称：办公软件；批复金额：5000元') },
  ]);
  const failed = await createBatch([{ name: '合成损坏材料.xlsx', bytes: Buffer.from([1, 2, 3]) }]);
  const history = await read('/api/v1/budget/material-uploads/batches?size=20');
  assert.equal(history.total, 2);
  assert.equal(history.records.find(row => row.batchId === primary).fileCount, 2);
  assert.equal(history.records.find(row => row.batchId === failed).status, 'FAILED');
  const filtered = await read('/api/v1/budget/material-uploads/batches?fileName=' + encodeURIComponent('办公设备项目_批复'));
  assert.equal(filtered.total, 1);
  assert.equal(filtered.records[0].fileCount, 2);
  const expected = new Map();
  for (const id of uploaded.slice(0, 2)) {
    const review = await read(`/api/v1/budget/reviews/${id}`);
    for (const line of review.materialReference.lines) expected.set(JSON.stringify([line.subject, line.declaredAmount, line.approvalAmount]), line);
  }
  assert.equal(expected.size, 2, 'cross-file results must describe both subjects');
  const page = await context.newPage(), errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(base);
  await page.locator('nav button').filter({ hasText: '预算审查' }).click();
  await page.getByRole('button', { name: '历史结果', exact: true }).click();
  const historyRegion = page.getByRole('region', { name: '预算历史结果', exact: true });
  await historyRegion.getByText('共 2 条历史记录', { exact: false }).waitFor();
  assert.equal(await historyRegion.locator('tbody tr').count(), 2);
  await historyRegion.screenshot({ path: path.join(output, 'batch_history_desktop.png') });
  await page.getByRole('row').filter({ hasText: '合成办公设备项目_申报.txt' }).getByRole('button', { name: '查看批次结果' }).click();
  const region = page.getByRole('region', { name: '预算文件结果', exact: true });
  await region.getByText('已汇总本批材料', { exact: false }).waitFor();
  assert.equal(await region.locator('article').count(), 2, 'one subject appears once across files');
  assert.equal(await region.locator('.budget-project-result-group').count(), 1, 'one project heading per batch');
  assert.match(await region.locator('article').first().innerText(), /终端设备/);
  assert.match(await region.locator('article').first().innerText(), /超出批复 2,000.00 元/);
  await region.getByText('查看本批 2 份材料及处理状态', { exact: true }).click();
  assert.equal(await region.locator('.budget-batch-files li').count(), 2);
  assert.equal(await page.getByRole('button', { name: '返回', exact: true }).count(), 1);
  await page.setViewportSize({ width: 1440, height: 1800 });
  await region.screenshot({ path: path.join(output, 'batch_results_desktop.png') });
  const downloaded = page.waitForEvent('download');
  await region.getByRole('button', { name: '导出', exact: true }).click();
  const download = await downloaded;
  const exportPath = path.join(output, 'batch_results.xlsx');
  await download.saveAs(exportPath);
  const workbook = XLSX.readFile(exportPath);
  assert.equal(XLSX.utils.sheet_to_json(workbook.Sheets['预算参考结果']).length, 2);
  assert.equal(XLSX.utils.sheet_to_json(workbook.Sheets['差异清单']).length, 1);
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await region.evaluate(element => element.scrollWidth <= element.clientWidth), 'mobile results do not overflow');
  await page.setViewportSize({ width: 390, height: 2800 });
  await region.screenshot({ path: path.join(output, 'batch_results_mobile.png') });
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.getByRole('button', { name: '返回', exact: true }).click();
  await page.getByRole('row').filter({ hasText: '合成损坏材料.xlsx' }).getByRole('button', { name: '查看说明' }).click();
  await region.getByText('本批有 1 份材料未能生成完整结果', { exact: true }).waitFor();
  assert.equal(await region.locator('article').count(), 0);
  await page.getByRole('button', { name: '返回', exact: true }).click();
  await page.getByRole('row').filter({ hasText: '合成办公设备项目_申报.txt' }).getByRole('button', { name: '查看批次结果' }).click();
  await region.getByText('已汇总本批材料', { exact: false }).waitFor();
  assert.equal(await region.locator('article').count(), 2, 'reopen from durable batch membership');
  assert.deepEqual(errors, []);
  const report = { status: 'PASS', environment: 'isolated local profile, real HTTP, in-memory repositories, fake external adapters',
    browser: browser.version(), batches: history.total, subjects: expected.size, exportedRows: 2,
    checks: ['one history entry per batch', 'member-name filter returns whole batch', 'cross-file deduplication',
      'project grouping', 'priority differences', 'file membership states', 'single return', 'xlsx export',
      '390px no overflow', 'failed member warning', 'batch reopen'], errors };
  writeFileSync(path.join(output, 'browser-check.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}

async function assertEventually(check) {
  const deadline = Date.now() + 120000;
  while (!await check()) {
    assert.ok(Date.now() < deadline, 'background worker did not finish within local test deadline');
    await new Promise(resolve => setTimeout(resolve, 500));
  }
}
