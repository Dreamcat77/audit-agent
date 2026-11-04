import assert from 'node:assert/strict';
import { test } from 'node:test';
import { budgetApprovalAmount, budgetCoverageLabel, budgetResultExplanation, budgetResultPending, budgetResultStatus } from './budgetResultState.ts';

test('coverage distinguishes unresolved information without claiming exhaustive extraction or a passed budget', () => {
  assert.equal(budgetCoverageLabel('PARTIAL'), '部分信息待核对');
  assert.equal(budgetCoverageLabel('COMPLETE'), '已核对提取的信息');
  for (const value of [undefined, null, 'UNKNOWN', '']) {
    assert.equal(budgetCoverageLabel(value), '请结合原材料核对');
  }
  assert.doesNotMatch(budgetCoverageLabel('COMPLETE'), /完整|通过|无误/);
});

test('only a reviewed task is labelled completed; reading rows does not complete analysis', () => {
  for (const status of ['UPLOADED', 'QUEUED', 'PROCESSING', 'PARSED', 'WAITING_CONFIRMATION', 'FAILED', 'UNKNOWN', 'UNRECOGNIZED']) {
    assert.doesNotMatch(budgetResultStatus(status), /已解析|已完成/);
  }
  assert.equal(budgetResultStatus('REVIEWED'), '已完成');
  assert.equal(budgetResultStatus('FAILED'), '已结束');
  assert.equal(budgetResultStatus('UNKNOWN'), '待核实');
  assert.match(budgetResultExplanation('UNKNOWN')!, /本次处理已结束/);
  assert.equal(budgetResultExplanation('FAILED','智能分析返回的信息未能与材料原文核对一致。'), '智能分析返回的信息未能与材料原文核对一致。');
  assert.equal(budgetResultExplanation('PROCESSING','不得显示为结束'), null);
  assert.equal(budgetResultPending('PARSED'), true);
  assert.equal(budgetResultPending('OPEN'), true);
  assert.equal(budgetResultPending('WAITING_UPLOAD'), true);
  for (const status of ['FAILED', 'UNKNOWN', 'WAITING_CONFIRMATION', 'REVIEWED']) assert.equal(budgetResultPending(status), false);
});

test('missing system approval is explained as a matching/configuration outcome rather than failed extraction', () => {
  assert.equal(budgetApprovalAmount(null, 'UNMATCHED'), '未匹配到系统批复');
  assert.equal(budgetApprovalAmount(null, 'SUSPECTED'), '对应批复待确认');
  assert.equal(budgetApprovalAmount(null, 'MATCHED'), '系统批复未配置金额');
  assert.equal(budgetApprovalAmount(null, 'PENDING'), '尚未完成核对');
  assert.equal(budgetApprovalAmount(0, 'MATCHED'), '￥0.00');
});
