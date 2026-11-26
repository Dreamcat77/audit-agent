import assert from 'node:assert/strict';
import {test} from 'node:test';
import {indexProcurementSource,locateProcurementCitation,procurementSourceText} from './procurementSourceView.ts';
import {highlightSource,sourceWindows} from '../contract/contractAnalysisEvidenceView.ts';

const citation={documentId:'one',fileName:'投标文件.pdf',sha256:'a'.repeat(64),locator:'pdf/page:9/text/part:1',excerpt:'报价为220万元/年。'};
const index=indexProcurementSource({fileName:citation.fileName,sha256:citation.sha256,segments:[
  {locator:'pdf/page:1/text/part:1',text:'同样的文字：'+citation.excerpt},
  {locator:citation.locator,text:'😀投标函\n'+citation.excerpt+'\n其他约定。'}]});

test('procurement opens the cited page, preserving UTF-16 positions and original highlighted text',()=>{
  const located=locateProcurementCitation(citation,index);
  assert.equal(located.pageNumber,9);assert.equal(index.text.slice(located.start!,located.end!),citation.excerpt);
  assert(located.start!>index.segments[1].start);
  const window=sourceWindows([located])[0];
  assert.deepEqual(highlightSource(window,procurementSourceText(index,window.start,window.end-window.start)).pieces.filter(p=>p.highlight).map(p=>p.text),[citation.excerpt]);
});
test('missing or changed source and a different locator never highlight matching words elsewhere',()=>{
  for(const c of [citation,{...citation,sha256:'b'.repeat(64)},{...citation,locator:'pdf/page:99/text/part:1'},{...citation,excerpt:'原文不存在'}]){
    const e=locateProcurementCitation(c,c===citation?undefined:index);assert.equal(e.start,null);assert.equal(e.excerpt,c.excerpt);
  }
});
test('repeated words in the same locator are ambiguous, while different pages remain independent',()=>{
  const repeated=indexProcurementSource({fileName:citation.fileName,sha256:citation.sha256,segments:[{locator:citation.locator,text:citation.excerpt+'\n'+citation.excerpt}]});
  assert.equal(locateProcurementCitation(citation,repeated).start,null);
  assert.notEqual(locateProcurementCitation(citation,index).start,null);
});
test('context reads are bounded and cannot overflow into arbitrary offsets',()=>{
  for(const [start,size] of [[-1,10],[0,0],[0,20001],[0.5,2],[index.text.length+1,2]])assert.throws(()=>procurementSourceText(index,start,size));
  assert.equal(procurementSourceText(index,index.text.length,100).text,'');
});
