import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {cpSync,existsSync,mkdirSync,readdirSync,readFileSync,writeFileSync} from 'node:fs';
import path from 'node:path';

// This assembly is for the authorized test-server rollout, not a customer installer.
const [output,dist,previous,jarHash,indexHash,modelRoot]=process.argv.slice(2);
assert.ok(modelRoot && existsSync(path.join(modelRoot,'inference.pdiparams')));
execFileSync(process.execPath,['tools/prepare-budget-procurement-release.mjs',output,dist,previous,jarHash,indexHash],{stdio:'inherit'});
const hash=value=>createHash('sha256').update(value).digest('hex');
const copy=(source,target)=>{mkdirSync(path.dirname(path.join(output,target)),{recursive:true});cpSync(source,path.join(output,target));};
for(const name of ['ResetReviewTestData','ContractTestStorageProbe','ContractLocalMigration','Word97LocalMigration'])
  copy(`tools/local-test/${name}.java`,`tools/${name}.java`);
for(const name of ['ContractMergedDeploymentSchema','ContractMergedDeploymentSchemaTest'])copy(`tools/${name}.java`,`tools/${name}.java`);
for(const name of ['CONTRACT__project_file_processing_job.sql','CONTRACT__contract_fact_batches.sql'])copy(`db/dm8/${name}`,`db/dm8/${name}`);
for(const name of ['inference.json','inference.pdiparams','inference.yml'])
  copy(path.join(modelRoot,name),`payload/services/audit-ai/models/PP-LCNet_x1_0_doc_ori/${name}`);
let deploy=readFileSync(path.join(output,'tools/deploy.sh'),'utf8');
deploy=deploy.replaceAll('budget-procurement-20261020','rotation-20261022');
deploy=deploy.replace('guard MaterialDeadlineMigration check\n(',
  'guard MaterialDeadlineMigration check\nguard ContractMergedDeploymentSchemaTest\nguard ContractMergedDeploymentSchema check "$package" "$package/schema-journal"\n(');
deploy=deploy.replace('guard MaterialDeadlineMigration check\nif command -v restorecon',
  'guard MaterialDeadlineMigration check\nAUDIT_ALLOW_TEST_SCHEMA_UPGRADE=1 guard ContractMergedDeploymentSchema apply "$package" "$package/schema-journal"\nif command -v restorecon');
const modelCopy=`mkdir -p "$release/services/audit-ai/models"\n[[ ! -e "$release/services/audit-ai/models/PP-LCNet_x1_0_doc_ori" ]] || die ORIENTATION_MODEL_ALREADY_EXISTS\ncp -a "$package/payload/services/audit-ai/models/." "$release/services/audit-ai/models/"\nchown -R admin:admin "$release/services/audit-ai/models"\nfind "$release/services/audit-ai/models" -type d -exec chmod 0755 {} +\nfind "$release/services/audit-ai/models" -type f -exec chmod 0644 {} +\n`;
deploy=deploy.replace('cp "$package/VERSION.txt" "$release/VERSION.txt"',modelCopy+'cp "$package/VERSION.txt" "$release/VERSION.txt"');
// The merged frontend uses durable contract file jobs. Enable its worker only
// after the schema is verified; preserve all existing environment files.
const enableWorker=`mkdir -p /etc/systemd/system/audit-backend.service.d\n[[ ! -e /etc/systemd/system/audit-backend.service.d/80-merged-file-worker.conf ]] || die WORKER_OVERRIDE_ALREADY_EXISTS\nprintf '%s\\n' '[Service]' 'Environment="AUDIT_CONTRACT_FILE_PROCESSING_WORKER_ENABLED=true"' > /etc/systemd/system/audit-backend.service.d/80-merged-file-worker.conf\nsystemctl daemon-reload\n`;
deploy=deploy.replace('ln -s "$release" "$base/current.material-next.$$"',()=>enableWorker+'ln -s "$release" "$base/current.material-next.$$"');
const isolateWorker=`mkdir -p /etc/systemd/system/audit-ai.service.d\n[[ ! -e /etc/systemd/system/audit-ai.service.d/80-ocr-process.conf ]] || die OCR_OVERRIDE_ALREADY_EXISTS\nprintf '%s\\n' '[Service]' 'Environment="AUDIT_OCR_PROCESS_ISOLATION=true"' > /etc/systemd/system/audit-ai.service.d/80-ocr-process.conf\nocr_override_created=yes\n`;
deploy=deploy.replace('mkdir -p /etc/systemd/system/audit-backend.service.d',()=>isolateWorker+'mkdir -p /etc/systemd/system/audit-backend.service.d');
deploy=deploy.replace('switched=no\nstopped=no','switched=no\nstopped=no\nworker_override_created=no\nocr_override_created=no');
deploy=deploy.replace('systemctl daemon-reload\nln -s "$release"', 'worker_override_created=yes\nsystemctl daemon-reload\nln -s "$release"');
deploy=deploy.replace('if [[ $stopped == yes ]]; then systemctl restart',
  'if [[ $ocr_override_created == yes ]]; then mv /etc/systemd/system/audit-ai.service.d/80-ocr-process.conf "$backup/ocr-override.failed.conf"; systemctl daemon-reload; fi\n if [[ $worker_override_created == yes ]]; then mv /etc/systemd/system/audit-backend.service.d/80-merged-file-worker.conf "$backup/worker-override.failed.conf"; systemctl daemon-reload; fi\n if [[ $stopped == yes ]]; then systemctl restart');
assert.equal((deploy.match(/ContractMergedDeploymentSchema apply/g)||[]).length,1);
assert.equal((deploy.match(/ContractMergedDeploymentSchema check/g)||[]).length,1);
assert.ok(deploy.includes('ln -s "$release" "$base/current.material-next.$$"'));
deploy=deploy.replace('"$release/services/audit-ai/src" "$release/frontend/dist"; fi',
  '"$release/services/audit-ai/src" "$release/services/audit-ai/models" "$release/frontend/dist"; fi');
writeFileSync(path.join(output,'tools/deploy.sh'),deploy);
const commit=execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim();
writeFileSync(path.join(output,'VERSION.txt'),`commit=${commit}\nrelease=rotation-20261022\nschema_changes=contract-additive-jobs-guard-expansion\norientation_model=PP-LCNet_x1_0_doc_ori\n`);
const files=dir=>readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(path.join(dir,e.name)):[path.join(dir,e.name)]);
writeFileSync(path.join(output,'manifest.sha256'),files(output).filter(f=>path.basename(f)!=='manifest.sha256').sort().map(f=>`${hash(readFileSync(f))}  ${path.relative(output,f).replaceAll('\\','/')}`).join('\n')+'\n');
console.log('ROTATION_RELEASE_ASSEMBLED manifest=COMPLETE model=LOCAL_ONLY');
