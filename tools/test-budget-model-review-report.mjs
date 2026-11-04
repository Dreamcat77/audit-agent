import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdirSync,writeFileSync,readFileSync} from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const require=createRequire(new URL('../frontend/package.json',import.meta.url));
const {chromium}=require('playwright-core');
const [executable,reportPath,jsonPath,outputPath]=process.argv.slice(2);
const cases=JSON.parse(readFileSync(jsonPath,'utf8'));
mkdirSync(outputPath,{recursive:true});
const browser=await chromium.launch({executablePath:executable,headless:true});
try{
 const page=await browser.newPage({viewport:{width:1440,height:1080}}),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.goto(pathToFileURL(path.resolve(reportPath)).href);
 assert.equal(await page.locator('main>section').count(),cases.length);
 assert.equal(await page.locator('article').count(),cases.flatMap(c=>c.files).length);
 await page.screenshot({path:path.join(outputPath,'report-wide.png')});
 await page.locator('details').first().locator('summary').click();
 assert.ok((await page.locator('pre').first().innerText()).length>0);
 await page.setViewportSize({width:390,height:844});
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 await page.screenshot({path:path.join(outputPath,'report-narrow.png')});
 assert.deepEqual(errors,[]);
 const result={browser:browser.version(),cases:cases.length,files:cases.flatMap(c=>c.files).length,viewports:[1440,390],errors};
 writeFileSync(path.join(outputPath,'report-check.json'),JSON.stringify(result,null,2));
 console.log(JSON.stringify(result));
}finally{await browser.close();}
