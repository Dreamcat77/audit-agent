import assert from 'node:assert/strict';
import { test } from 'node:test';
import { automaticHistoryParams,automaticCheckTone,automaticPrioritySummary, automaticCheckExplanation, automaticFactLabel, automaticReviewReason, definiteUploadRejection, caseHistoryLabel } from './automaticCaseState.ts';

test('known validation rejection allows another batch but uncertain outcomes retain operation identity', () => {
  for (const status of [400, 413, 415, 422]) assert.equal(definiteUploadRejection(status), true);
  for (const status of [0, 200, 401, 403, 408, 409, 429, 500, 502, 503, 504]) assert.equal(definiteUploadRejection(status), false);
});
test('history uses recognisable material names and dates rather than a bare uuid', () => {
  const label = caseHistoryLabel({ id: 'abcd-123456', createdAt: '2026-10-27T12:00:00Z', fileNames: ['办公终端采购.docx', '预算.pdf'] });
  assert.match(label, /批次 .*2 份材料.*办公终端采购\.docx/); assert.match(label, /2026/); assert.match(label, /123456/);
});

test('ordinary readers see Chinese field labels and meaningful unknown states', () => {
  assert.equal(automaticFactLabel('projectNo'), '项目编号');
  assert.equal(automaticFactLabel('futureInternalField'), '材料信息');
  assert.equal(automaticCheckExplanation({code: 'IDENTITY_projectNo', status: 'CONSISTENT_IN_OBSERVED', explanation: 'projectNo：SYN-2026；缺少该字段的文件不作为冲突依据'}), '项目编号：SYN-2026；未载明此信息的材料不参与本项比对');
  assert.doesNotMatch(automaticCheckExplanation({code: 'IDENTITY_purchaserName', status: 'UNKNOWN', explanation: 'purchaserName：'}), /purchaserName/);
  assert.match(automaticCheckExplanation({code: 'QUOTE_BUDGET', status: 'UNKNOWN', explanation: '预算上限 null'}), /不作是否超预算的结论/);
  assert.doesNotMatch(automaticReviewReason('MODEL_INVALID_RESPONSE'), /MODEL_|已完成|通过/);
});

test('empty and partial date filters never serialize undefined or invalid instants',()=>{
  assert.equal(new URLSearchParams(automaticHistoryParams('','')).toString(),'');
  assert.deepEqual(automaticHistoryParams('  项目  ',''),{keyword:'项目'});
  for(const date of ['2026-','2026-02-30','not-a-date'])assert.deepEqual(automaticHistoryParams('',date),{});
  const params=automaticHistoryParams('','2026-10-29');
  assert.ok(new Date(params.to).getTime()>new Date(params.from).getTime());
  assert.doesNotMatch(new URLSearchParams(params).toString(),/undefined|Invalid/);
  assert.equal(automaticHistoryParams('a'.repeat(161),'').keyword.length,160);
});
test('missing or unknown results are never styled as a successful check',()=>{
  for(const status of ['UNKNOWN','NOT_EVALUATED','FAILED','SOURCE_GROUNDED_OBSERVATION','FUTURE'])assert.equal(automaticCheckTone(status),'default');
  for(const status of ['CONFLICT','BLOCKED','REVIEW','RULE_SIGNAL_WARNING','RULE_SIGNAL_CRITICAL','STATISTICAL_SIGNAL'])assert.equal(automaticCheckTone(status),'attention');
  for(const status of ['CONSISTENT_IN_OBSERVED','WITHIN_BUDGET','NO_MATCH','MATCHED'])assert.equal(automaticCheckTone(status),'clear');
});

test('presentation preserves grounded explanations and never replaces words inside business facts', () => {
  const text = '供应商报价比预算高100元，引用原文中的projectNo应原样保留';
  assert.equal(automaticCheckExplanation({code: 'BUDGET_CONFLICT', status: 'CONFLICT', explanation: text}), text);
  assert.equal(automaticCheckExplanation({code: 'IDENTITY_projectName', status: 'CONFLICT', explanation: 'projectName：projectNo测试服务'}), '项目名称：projectNo测试服务');
  assert.match(automaticReviewReason('FUTURE_ERROR'), /尚未完整完成/);
});

test('priority summary agrees with exact quote risks and preserves unknown comparisons', () => {
  assert.match(automaticPrioritySummary(false, [{status:'WITHIN_BUDGET'}], [{status:'OVER_CEILING'}]), /1 份报价超过最高限价/);
  assert.doesNotMatch(automaticPrioritySummary(false, [], [{status:'OVER_CEILING'}]), /暂无明确问题/);
  assert.match(automaticPrioritySummary(true, [{status:'OVER_BUDGET'}], [{status:'OVER_CEILING'}]), /超过材料预算.*超过最高限价/);
  assert.match(automaticPrioritySummary(false, [{status:'NOT_EVALUATED'}], []), /材料不足的事项不作结论/);
  assert.match(automaticPrioritySummary(true, [], []), /正在整理/);
});
