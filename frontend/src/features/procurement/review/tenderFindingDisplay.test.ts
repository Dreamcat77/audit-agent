import test from 'node:test';
import assert from 'node:assert/strict';
import { uniqueTenderDisplayFindings, tenderExplanationText } from './tenderFindingDisplay.ts';
import { procurementReferenceView } from './procurementReferenceView.ts';

const brand = {
  ruleCode: 'brand-required', riskCategory: '指定品牌', riskLevel: 'CRITICAL',
  matchedText: '台式计算机必须使用星桥品牌，仅限星桥品牌参与。',
  sourceLocator: 'docx/body/paragraph:9', remediationSuggestion: '建议删除品牌指定，改为性能参数要求',
};

test('historical duplicate findings without advice cannot crash the report', () => {
  for (const absent of [undefined, null]) {
    const finding={...brand,remediationSuggestion:absent} as any;
    const shown=uniqueTenderDisplayFindings([finding,{...finding,riskCategory:'资格限制'}]);
    assert.equal(shown.length,1);
    assert.match(shown[0].riskCategory,/指定品牌.*资格限制/);
    assert.equal(shown[0].remediationSuggestion,'');
  }
});

test('model explanations retain both clause values without exposing technical paths', () => {
  assert.equal(tenderExplanationText('docx/body/paragraph:5 为90天，docx/body/paragraph:7 为30天。'), '原文条款 为90天，原文条款 为30天。');
  assert.equal(tenderExplanationText('前文90天，后文30天，请核对。'), '前文90天，后文30天，请核对。');
  const source={...brand,riskReason:'docx/body/paragraph:5 为90天，docx/body/paragraph:7 为30天。'};
  const shown=uniqueTenderDisplayFindings([source])[0];
  assert.match(shown.riskReason!,/90天.*30天/);
  assert.doesNotMatch(shown.riskReason!,/docx\/body/);
  assert.match(source.riskReason,/docx\/body/);
});

test('F10 identical advice from different rules is displayed once without modifying evidence', () => {
  const findings = [brand, {...brand, ruleCode: 'brand-only'}];
  const before = JSON.stringify(findings);
  assert.deepEqual(uniqueTenderDisplayFindings(findings), [brand]);
  const report = {
    riskLevel:'CRITICAL',agentReview:{riskLevel:'INFO',items:[],suggestion:''},
    quoteCheck:null,blacklist:{hits:[]},tender:{findings},collusion:null,splitPurchase:null,
  } as any;
  const view = procurementReferenceView(report);
  assert.equal(view.lines.length, 1);
  assert.equal(view.tenderFindings.length, 1);
  assert.match(view.lines[0], /指定品牌.*星桥品牌.*建议删除/);
  assert.doesNotMatch(view.lines[0], /docx\/body|paragraph:/);
  assert.equal(JSON.stringify(findings), before);
});

test('different categories, severity, locations, evidence text and advice remain visible', () => {
  const findings = [brand,
    {...brand,riskCategory:'地域限制'},
    {...brand,riskLevel:'WARNING'},
    {...brand,sourceLocator:'docx/body/paragraph:10'},
    {...brand,matchedText:'必须使用另一个品牌'},
    {...brand,remediationSuggestion:'请核对其他限制'},
  ];
  const shown=uniqueTenderDisplayFindings(findings);
  assert.equal(shown.length, 3);
  assert.match(shown[0].riskCategory,/指定品牌.*地域限制/);
  assert.match(shown[0].remediationSuggestion,/建议删除品牌指定/);
  assert.equal(shown[0].riskLevel,'CRITICAL');
  assert.match(shown[0].remediationSuggestion,/请核对其他限制/);
});

test('F10 OCR context windows enclosing the same long clause display once and preserve source records', () => {
  const short = {...brand, matchedText:'项目编号：DEMO-ROT-P01\n预算金额：100000.00元\n台式计算机必须使用星桥品牌，仅限星桥品牌参与。\n验收后付款。'};
  const long = {...short, matchedText:'办公设备采购\n'+short.matchedText, riskLevel:'WARNING'};
  const source = JSON.stringify([short,long]);
  const shown = uniqueTenderDisplayFindings([short,long]);
  assert.equal(shown.length,1);
  assert.equal(shown[0].matchedText,long.matchedText);
  assert.equal(shown[0].riskLevel,'CRITICAL');
  assert.equal(JSON.stringify([short,long]),source);
});

test('context overlap never hides distinct prices, advice, short text or source locations', () => {
  const long = {...brand, matchedText:'办公设备采购项目预算100000元。台式计算机必须使用星桥品牌，仅限星桥品牌参与。'};
  for(const other of [
    {...long,matchedText:long.matchedText.replace('100000','200000')},
    {...long,sourceLocator:'pdf/page:2'},
    {...long,matchedText:'补充上下文：'+long.matchedText,remediationSuggestion:'保留规格并核对例外依据'},
    {...long,matchedText:'必须使用星桥品牌'},
  ])assert.equal(uniqueTenderDisplayFindings([long,other]).length,2);
});
