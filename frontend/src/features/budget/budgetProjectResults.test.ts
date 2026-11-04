import assert from 'node:assert/strict';
import { test } from 'node:test';
import { budgetLineReference, groupBudgetProjects } from './budgetProjectGrouping.ts';
import type { BudgetReviewResponse } from '../../api/types';

function review(id: string, project = 'PROJECT-A', amount = 100, year = '2026'): BudgetReviewResponse {
  return { task: { taskId: id, fileName: `${id}.xlsx`, budgetYear: year, departmentCode: 'FINANCE',
    status: 'REVIEWED', parsedLineCount: 1 }, items: [{ id: `${id}-row`, projectCode: project,
    subjectCode: 'ABC', subjectName: '办公设备', declaredAmount: amount, approvalAmount: 100,
    supplierName: '合成供应商', projectDepartmentStatus: 'PASSED', matchStatus: 'MATCHED',
    amountStatus: 'PASSED', riskLevel: 'INFO', checks: [], abnormalReasons: [], rowNumber: 2 }] } as BudgetReviewResponse;
}

test('one project combines material sources without adding duplicate amounts', () => {
  const groups = groupBudgetProjects([review('first'), review('second')]);
  assert.equal(groups.length, 1); assert.equal(groups[0].lines.length, 1);
  assert.equal(groups[0].lines[0].item.declaredAmount, 100);
  assert.deepEqual(groups[0].lines[0].sources.map(s => s.fileName), ['first.xlsx', 'second.xlsx']);
});
test('one material can contain multiple projects; year and department stay separate', () => {
  const first = review('first'); first.items.push({ ...review('other', 'PROJECT-B').items[0] });
  first.task.parsedLineCount = 2;
  const department = review('department'); department.task.departmentCode = 'GENERAL';
  assert.equal(groupBudgetProjects([first, review('prior', 'PROJECT-A', 100, '2025'), department]).length, 4);
});
test('unidentified or invalid project rows are never merged using file or subject name', () => {
  const a = review('first', ''); const b = review('second', '');
  const c = review('third'); c.items[0].projectDepartmentStatus = 'FAILED';
  const groups = groupBudgetProjects([a, b, c]);
  assert.equal(groups.length, 3); assert.ok(groups.every(g => g.projectCode === null));
});
test('conflicting amounts remain separate and are marked for verification', () => {
  const groups = groupBudgetProjects([review('first'), review('second', 'PROJECT-A', 200)]);
  assert.equal(groups[0].lines.length, 2); assert.equal(groups[0].conflictingAmounts, true);
});
test('incomplete, pending or unknown checks cannot produce a passed reference', () => {
  const partial = review('first'); partial.task.status = 'WAITING_CONFIRMATION';
  assert.match(budgetLineReference(groupBudgetProjects([partial])[0].lines[0]), /不足/);
  const unknown = review('second'); unknown.task.status = 'UNKNOWN';
  assert.equal(groupBudgetProjects([unknown]).length, 0);
  const missing = review('third'); missing.items[0].checks = [{ status: 'NOT_CHECKED' } as never];
  assert.match(budgetLineReference(groupBudgetProjects([missing])[0].lines[0]), /不足/);
  missing.items[0].checks = [{ status: 'FAILED' } as never];
  assert.match(budgetLineReference(groupBudgetProjects([missing])[0].lines[0]), /不足/);
});
test('reference prose never prints technical status or raw exception text', () => {
  const failure = review('first'); failure.items[0].riskLevel = 'CRITICAL';
  failure.items[0].amountStatus = 'OVER_BUDGET';
  failure.items[0].abnormalReasons = ['SQL_ERROR vendor-secret raw content'];
  const prose = budgetLineReference(groupBudgetProjects([failure])[0].lines[0]);
  assert.match(prose, /申报金额超过/); assert.doesNotMatch(prose, /SQL_ERROR|vendor|OVER_BUDGET/);
});
