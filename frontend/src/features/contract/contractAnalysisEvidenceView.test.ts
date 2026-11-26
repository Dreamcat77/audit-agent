import assert from 'node:assert/strict';
import test from 'node:test';
import {uniqueSourceEvidence,sourceWindows,highlightSource,type SourceEvidence} from './contractAnalysisEvidenceView.ts';

function anchor(text:string,quote:string,start=text.indexOf(quote),fileId='contract'):SourceEvidence {
  return {fileId,start,end:start+quote.length,excerpt:quote};
}
function resolve(text:string,items:SourceEvidence[]){
  const window=sourceWindows(items)[0];
  return highlightSource(window,{start:window.start,text:text.slice(window.start,window.end),totalChars:text.length});
}

test('same source identity is shown once, not one button per repeated citation',()=>{
  const a=anchor('前文\n验收合格后付款\n后文','验收合格后付款');
  assert.equal(uniqueSourceEvidence([a,{...a},a]).length,1);
  assert.equal(sourceWindows([a,a]).length,1);
});

test('overlapping quotes share one context and one contiguous highlight without repeated words',()=>{
  const text='前文\n甲方验收合格后十日内付款。\n后文';
  const items=[anchor(text,'甲方验收合格后'),anchor(text,'验收合格后十日内付款。')];
  assert.equal(sourceWindows(items).length,1);
  const view=resolve(text,items);
  assert.equal(view.pieces.map(p=>p.text).join(''),text);
  assert.deepEqual(view.pieces.filter(p=>p.highlight).map(p=>p.text),['甲方验收合格后十日内付款。']);
});

test('distant quotes stay separate windows and never include the intervening unrelated pages',()=>{
  const text='交货期限30日。'+'其他条款'.repeat(200)+'验收后支付尾款。';
  const windows=sourceWindows([anchor(text,'验收后支付尾款。'),anchor(text,'交货期限30日。')]);
  assert.equal(windows.length,2);
  assert.ok(windows[0].end<windows[1].start);
  assert.ok(windows.every(w=>w.end-w.start<=20000));
});

test('identical wording at different locations remains distinct and highlights only the bound occurrence',()=>{
  const quote='违约金每日万分之五';const text=quote+'其他说明'.repeat(80)+quote;
  const first=anchor(text,quote,0),second=anchor(text,quote,text.lastIndexOf(quote));
  assert.equal(uniqueSourceEvidence([first,second]).length,2);
  const view=resolve(text,[second]);
  assert.deepEqual(view.pieces.filter(p=>p.highlight).map(p=>p.text),[quote]);
  assert.ok(sourceWindows([second])[0].start>0);
});

test('same name or same quote in different files is not merged',()=>{
  const text='验收合格后付款';const a=anchor(text,text,0,'contract'),b=anchor(text,text,0,'annex');
  assert.equal(uniqueSourceEvidence([a,b]).length,2);
  assert.equal(sourceWindows([a,b]).length,2);
});

test('stale offsets, mismatched server window and changed quote never guess another occurrence',()=>{
  const text='验收合格后付款\n其他说明';const e=anchor(text,'验收合格后付款');
  assert.equal(resolve('文字已发生改变',[e]).pieces.length,0);
  const w=sourceWindows([e])[0];
  assert.deepEqual(highlightSource(w,{text,start:1,totalChars:100}),{pieces:[],unverified:1});
  assert.deepEqual(resolve(text,[{...e,start:1,end:e.end!+1}]).pieces,[]);
});

test('UTF-16 positions, multiline clauses and whitespace remain exact',()=>{
  const text='📄合同\n乙方应交付\n并完成安装调试。\n甲方验收。';
  const e=anchor(text,'乙方应交付\n并完成安装调试。');const view=resolve(text,[e]);
  assert.equal(view.unverified,0);
  assert.deepEqual(view.pieces.filter(p=>p.highlight).map(p=>p.text),[e.excerpt]);
  assert.equal(view.pieces.map(p=>p.text).join(''),text);
});

test('missing locators never default to the first 1200 characters',()=>{
  const e={fileId:'contract',start:null,end:null,excerpt:'付款约定'};
  assert.deepEqual(sourceWindows([e]),[]);
  assert.equal(uniqueSourceEvidence([e,e]).length,1);
  for(const start of [-1,NaN,Infinity])assert.deepEqual(sourceWindows([{...e,start,end:10}]),[]);
  assert.equal(uniqueSourceEvidence([{...e,pageNumber:1},{...e,pageNumber:2}]).length,2);
});

test('a bad citation does not suppress an independent valid citation',()=>{
  const text='甲方验收合格后付款';const a=anchor(text,'验收合格后付款');
  const b={...a,start:0,end:3,excerpt:'错误文字'};const view=resolve(text,[a,b]);
  assert.equal(view.unverified,1);
  assert.deepEqual(view.pieces.filter(p=>p.highlight).map(p=>p.text),[a.excerpt]);
});
