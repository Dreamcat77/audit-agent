import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
const root=path.resolve(process.argv[2]??'.runtime/managed-review-20261020');
const actual=JSON.parse(readFileSync(path.join(root,'actual-results.json'),'utf8'));
const expected=JSON.parse(readFileSync(path.join(root,'expected.json'),'utf8'));
assert.equal(actual.length,9);assert.equal(actual.flatMap(c=>c.files).length,12);
const businessNames=new Set(expected.map(c=>c.label));
const formats=actual.filter(c=>!businessNames.has(c.label));assert.equal(formats.length,4);
for(const c of actual)for(const file of c.files)if(!file.failureCode){assert.equal(file.modelResultReused,false);assert.equal(file.sourceTextReused,false);}
writeFileSync(path.join(root,'format-results.json'),JSON.stringify(formats,null,2)+'\n');
execFileSync(process.execPath,['tools/verify-budget-extended-results.mjs',path.join(root,'actual-results.json'),path.join(root,'expected.json'),path.join(root,'business-checks.json')],{stdio:'inherit'});
execFileSync(process.execPath,['tools/verify-budget-extended-formats.mjs',path.join(root,'format-results.json'),path.join(root,'format-checks.json')],{stdio:'inherit'});
console.log('MANAGED_REAL_PROVIDER_9_CASES_12_FILES_PASS_WITH_EXPLICIT_UNCERTAINTY');
