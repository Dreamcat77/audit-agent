import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

// Assemble only tested application artifacts; deployment configuration is inherited.
const [outputArg, distArg, previous, jarHash, indexHash] = process.argv.slice(2);
assert.ok(outputArg && distArg && /^\/opt\/audit-agent\/releases\/[A-Za-z0-9_.-]+$/.test(previous));
assert.ok([jarHash, indexHash].every(v => /^[a-f0-9]{64}$/.test(v)));
const output = path.resolve(outputArg);
assert.ok(!existsSync(output), 'Preserve existing artifacts');
const hash = data => createHash('sha256').update(data).digest('hex');
function copy(source, target) {
  mkdirSync(path.dirname(path.join(output, target)), { recursive: true });
  cpSync(source, path.join(output, target), { recursive: true,
    filter: file => !['__pycache__', '.pytest_cache'].includes(path.basename(file)) && !file.endsWith('.pyc') });
}
copy('backend/target/audit-backend-0.1.0-SNAPSHOT.jar', 'payload/backend/audit-backend-0.1.0-SNAPSHOT.jar');
copy('services/audit-ai/src', 'payload/services/audit-ai/src');
copy(distArg, 'payload/frontend/dist');
assert.match(readFileSync(path.join(output, 'payload/frontend/dist/index.html'), 'utf8'), /chrome109-currentcolor/);
const checks = ['MaterialDeploymentReadyCheck', 'BudgetUploadedBatchPlatformCheck',
  'BudgetMaterialRiskPlatformCheck', 'MaterialTransferPlatformCheck', 'MaterialExtractionPlatformCheck',
  'ProcurementReportSnapshotPlatformCheck', 'MaterialDeadlineMigration', 'BudgetBackgroundRunner'];
for (const name of checks) copy(`tools/${name}.java`, `tools/${name}.java`);
copy('tools/test-deployed-reliability26.py', 'tools/test-deployed-reliability26.py');
for (const name of readdirSync('db/dm8')) {
  if (/^V05[2-6]__/.test(name)) copy(`db/dm8/${name}`, `db/${name}`);
}
let deploy = readFileSync('tools/deploy-material-timeout.sh', 'utf8').replaceAll('\r\n', '\n');
assert.equal((deploy.match(/guard MaterialDeadlineMigration apply/g) || []).length, 1);
deploy = deploy.replace('guard MaterialDeadlineMigration apply', '# V057 already deployed; this release performs no schema mutation.');
deploy = deploy.replaceAll('material-timeout-20261019', 'budget-procurement-20261020')
  .replaceAll('MATERIAL_TIMEOUT_DEPLOY', 'BUDGET_PROCUREMENT_DEPLOY');
const baseline = '(cd "$previous" && sha256sum -c "$package/previous-jar.sha256" >/dev/null) || die PREVIOUS_CODE_CHANGED';
assert.ok(deploy.includes(baseline));
deploy = deploy.replace(baseline, baseline + '\n(cd "$previous" && sha256sum -c "$package/previous-index.sha256" >/dev/null) || die PREVIOUS_FRONTEND_CHANGED');
writeFileSync(path.join(output, 'tools/deploy.sh'), deploy);
writeFileSync(path.join(output, 'previous-release.txt'), previous + '\n');
writeFileSync(path.join(output, 'previous-jar.sha256'), jarHash + '  backend/audit-backend-0.1.0-SNAPSHOT.jar\n');
writeFileSync(path.join(output, 'previous-index.sha256'), indexHash + '  frontend/dist/index.html\n');
const commit = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
assert.equal(execFileSync('git', ['status', '--porcelain', '--untracked-files=no'], { encoding: 'utf8' }).trim(), '', 'Commit tested application changes first');
writeFileSync(path.join(output, 'VERSION.txt'), `commit=${commit}\nrelease=budget-procurement-20261020\nschema_changes=none\n`);
function files(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => entry.isDirectory()
    ? files(path.join(dir, entry.name)) : [path.join(dir, entry.name)]);
}
writeFileSync(path.join(output, 'manifest.sha256'), files(output).sort().map(file =>
  `${hash(readFileSync(file))}  ${path.relative(output, file).replaceAll('\\', '/')}`).join('\n') + '\n');
console.log(JSON.stringify({ commit, output, files: files(output).length,
  jarSha256: hash(readFileSync(path.join(output, 'payload/backend/audit-backend-0.1.0-SNAPSHOT.jar'))) }));
