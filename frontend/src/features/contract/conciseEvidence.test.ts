import {test} from 'node:test';
import assert from 'node:assert/strict';
import {conciseEvidence} from './conciseEvidence.ts';
import type {SourceEvidence} from './contractAnalysisEvidenceView.ts';
const bind=(excerpt:string):SourceEvidence=>({fileId:'f',start:200,end:200+excerpt.length,excerpt,pageNumber:9});
test('a page-sized quotation focuses the actual annual price without changing wording or location',()=>{
  const quote='投标授权和通讯地址。'.repeat(40)+'我方的投标总价为：（大写）贰佰贰拾万元人民币/年。'+'其他一般约定。'.repeat(40);
  const source=bind(quote),result=conciseEvidence(source,'投标函总价每年220万元与开标一览表整体220万元计价口径不一致');
  assert.equal(result.excerpt,'我方的投标总价为：（大写）贰佰贰拾万元人民币/年。');
  assert.equal(quote.slice(result.start!-200,result.end!-200),result.excerpt);assert.equal(result.pageNumber,9);
  assert.equal(source.excerpt,quote);
});
test('negation, units and a following exception remain visible',()=>{
  const s=bind('其他无关约定。'.repeat(40)+'违约金比例不超过合同总额的10%。但最高不超过人民币50000元。'+'其他一般约定。'.repeat(20));
  assert.equal(conciseEvidence(s,'违约金比例10%').excerpt,'违约金比例不超过合同总额的10%。但最高不超过人民币50000元。');
});
test('separate sources keep their own competing prices and exact offset identities',()=>{
  const a=conciseEvidence(bind('其他条款。'.repeat(60)+'报价为220万元/年。'),'报价220万元');
  const b=conciseEvidence({...bind('其他条款。'.repeat(60)+'总报价220万元，覆盖整个服务期。'),fileId:'b'},'总报价220万元');
  assert.match(a.excerpt,/\/年/);assert.match(b.excerpt,/整个服务期/);assert.notEqual(a.fileId,b.fileId);
});
test('long unpunctuated OCR text remains bounded without fabricating precise offsets for unlocated sources',()=>{
  const text='无关内容'.repeat(120)+'合同金额100000元，含税。';
  const value=conciseEvidence({fileId:'f',start:null,end:null,excerpt:text},'合同金额100000元');
  assert(value.excerpt.length<=220);assert.match(value.excerpt,/100000元/);assert.equal(value.start,null);assert.equal(value.end,null);
  assert(text.includes(value.excerpt));
});
test('short quotes and non-BMP characters are preserved',()=>{
  const s=bind('合同金额100元。');assert.equal(conciseEvidence(s),s);
  const text='😀'.repeat(300)+'合同金额100000元';const v=conciseEvidence(bind(text),'合同金额100000元');
  assert(!/^[\uDC00-\uDFFF]|[\uD800-\uDBFF]$/.test(v.excerpt));assert(text.includes(v.excerpt));
});
