import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {copyFileSync,mkdirSync,readdirSync,readFileSync,writeFileSync} from 'node:fs';
import path from 'node:path';

// Follow up an already installed rotation release: preserve models/drop-ins and
// run schema checks only. No repeated migrations or system configuration edits.
const [output,dist,previous,jarHash,indexHash]=process.argv.slice(2);
execFileSync(process.execPath,['tools/prepare-budget-procurement-release.mjs',output,dist,previous,jarHash,indexHash],{stdio:'inherit'});
function copy(source,target){const dest=path.join(output,target);mkdirSync(path.dirname(dest),{recursive:true});copyFileSync(source,dest);}
for(const name of ['ContractMergedDeploymentSchema','ContractMergedDeploymentSchemaTest'])copy(`tools/${name}.java`,`tools/${name}.java`);
for(const name of ['ResetReviewTestData','ContractTestStorageProbe','ContractLocalMigration','Word97LocalMigration'])copy(`tools/local-test/${name}.java`,`tools/${name}.java`);
for(const name of ['CONTRACT__project_file_processing_job.sql','CONTRACT__contract_fact_batches.sql'])copy(`db/dm8/${name}`,`db/dm8/${name}`);
let deploy=readFileSync(path.join(output,'tools/deploy.sh'),'utf8');
deploy=deploy.replaceAll('budget-procurement-20261020','rotation-all-20261022');
const marker='guard MaterialDeadlineMigration check\n(';
assert.equal(deploy.split(marker).length,2);
deploy=deploy.replace(marker,'guard MaterialDeadlineMigration check\nguard ContractMergedDeploymentSchemaTest\nAUDIT_REQUIRE_CURRENT_CONTRACT_SCHEMA=1 guard ContractMergedDeploymentSchema check "$package" "$package/schema-journal"\n(');
deploy=deploy.replace('for endpoint in procurement/projects','for endpoint in projects procurement/projects');
assert.ok(!deploy.includes('ContractMergedDeploymentSchema apply'));
writeFileSync(path.join(output,'tools/deploy.sh'),deploy);
const commit=execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim();
writeFileSync(path.join(output,'VERSION.txt'),`commit=${commit}\nrelease=rotation-all-20261022\nschema_changes=none\n`);
const files=dir=>readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(path.join(dir,e.name)):[path.join(dir,e.name)]);
const hash=b=>createHash('sha256').update(b).digest('hex');
writeFileSync(path.join(output,'manifest.sha256'),files(output).filter(f=>path.basename(f)!=='manifest.sha256').sort().map(f=>hash(readFileSync(f))+'  '+path.relative(output,f).replaceAll('\\','/')).join('\n')+'\n');
console.log('ROTATION_ALL_FOLLOWUP_ASSEMBLED SCHEMA_MUTATIONS=NONE MODELS_AND_CONFIG=INHERITED');
