import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import test from 'node:test';
import {build} from 'esbuild';

async function renderer(){
  const result=await build({bundle:true,format:'cjs',jsx:'automatic',platform:'node',write:false,stdin:{loader:'tsx',resolveDir:process.cwd(),sourcefile:'result-module-harness.tsx',contents:`
    import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
    import {ContractAnalysisCategories} from './src/features/contract/components/ContractAnalysisCategories.tsx';
    import {ResultProjectHeader,ResultSection,ResultItem} from './src/components/results/ResultLayout.tsx';
    export const categories=(props)=>renderToStaticMarkup(<ContractAnalysisCategories copied={null} onCopy={()=>{}} evidenceButtons={()=>null} documents={[]} {...props}/>);
    export const header=(name)=>renderToStaticMarkup(<ResultProjectHeader kind="合同审查结果" name={name}/>);
    export const section=(tone)=>renderToStaticMarkup(<ResultSection title="核对结果" tone={tone}><p>详细内容</p></ResultSection>);
    export const item=(tone)=>renderToStaticMarkup(<ResultItem title="核对项目" tone={tone}><p>详细内容</p></ResultItem>);
  `}});
  const module={exports:{} as {categories(props:object):string;header(name:string):string;section(tone:string):string;item(tone:string):string}};
  new Function('require','module','exports',result.outputFiles[0]!.text)(createRequire(import.meta.url),module,module.exports);
  return module.exports;
}

test('historic or incomplete reports retain eight module positions without inventing results',async()=>{
  const render=await renderer();const html=render.categories({categories:[]});
  assert.equal((html.match(/data-result-module=/g)??[]).length,8);
  assert.match(html,/附件清单完整性审查/);assert.match(html,/合同生效条件与份数审查/);
  assert.doesNotMatch(html,/审查通过|无风险|open=""/);
});

test('large sections retain their disclosure while small items always show their content',async()=>{
  const render=await renderer();
  for(const tone of ['clear','default'])assert.match(render.section(tone),/aria-expanded="false"/);
  assert.match(render.section('clear'),/hidden=""/);assert.match(render.section('attention'),/aria-expanded="true"/);
  assert.doesNotMatch(render.section('attention'),/hidden=""/);
  assert.match(render.section('clear'),/未见异常/);assert.doesNotMatch(render.section('default'),/未见异常|result-state-clear/);
  for(const tone of ['clear','default','attention']){
    const item=render.item(tone);
    assert.match(item,/<article/);assert.match(item,/详细内容/);
    assert.doesNotMatch(item,/<details|<summary|hidden=|aria-expanded=/);
  }
});
test('module previews preserve real findings and name only their evidence sources',async()=>{
  const render=await renderer();const html=render.categories({documents:[{fileId:'a',name:'合同书.pdf'},{fileId:'b',name:'其他文件.pdf'}],categories:[{id:'BREACH',order:3,title:'违约责任条款量化审查',status:'ATTENTION',items:[{title:'违约金超限',summary:'累计7200元，上限6000元',level:'WARNING',evidence:[{fileId:'a',start:1,end:2,excerpt:'依据'}]}]}]});
  assert.equal((html.match(/data-result-module=/g)??[]).length,8);
  assert.match(html,/累计7200元，上限6000元/);assert.match(html,/涉及材料：合同书.pdf/);assert.doesNotMatch(html,/其他文件.pdf/);
});
test('project names remain plain text even when document extraction contains markup',async()=>{
  const render=await renderer();const html=render.header('<img src=x onerror=alert(1)>');
  assert.match(html,/项目名称/);assert.match(html,/&lt;img/);assert.doesNotMatch(html,/<img/);
});
