import type { BudgetReviewItemResponse, BudgetReviewResponse } from '../../api/types';

export interface BudgetResultSource { taskId: string; fileName: string; rowNumber: number | null; }
export interface BudgetProjectLine { item: BudgetReviewItemResponse; sources: BudgetResultSource[]; complete: boolean; }
export interface BudgetProjectResult {
  key: string; projectCode: string | null; year: string | null; department: string | null;
  lines: BudgetProjectLine[]; conflictingAmounts: boolean;
}

// Exact project identity and scope only. Never infer a project from a file or a subject name.
export function groupBudgetProjects(reviews: BudgetReviewResponse[]): BudgetProjectResult[] {
  const groups = new Map<string, BudgetProjectResult>();
  for (const review of reviews) {
    const task = review.task;
    if (!['REVIEWED', 'WAITING_CONFIRMATION', 'PARSED'].includes(task.status)) continue;
    const complete = task.status === 'REVIEWED' && review.items.length === task.parsedLineCount;
    for (const item of review.items) {
      const code = item.projectCode?.trim() || null;
      const identified = code && item.projectDepartmentStatus === 'PASSED';
      const key = JSON.stringify([task.budgetYear, task.departmentCode,
        identified ? code : [task.taskId, item.id]]);
      let group = groups.get(key);
      if (!group) {
        group = { key, projectCode: identified ? code : null, year: task.budgetYear,
          department: task.departmentCode, lines: [], conflictingAmounts: false };
        groups.set(key, group);
      }
      const source = { taskId: task.taskId, fileName: task.fileName, rowNumber: item.rowNumber };
      // Identical facts/checks may share provenance. Different values or outcomes remain visible.
      const fingerprint = (value: BudgetReviewItemResponse) => JSON.stringify([
        value.subjectCode, value.subjectName, value.declaredAmount, value.supplierName,
        value.approvalAmount, value.matchStatus, value.amountStatus, value.riskLevel,
        value.abnormalReasons, value.checks,
      ]);
      const same = group.lines.find(line => line.complete === complete && fingerprint(line.item) === fingerprint(item));
      if (same) {
        if (!same.sources.some(s => s.taskId === source.taskId && s.rowNumber === source.rowNumber)) same.sources.push(source);
      } else group.lines.push({ item, sources: [source], complete });
    }
  }
  for (const group of groups.values()) {
    group.conflictingAmounts = group.lines.some((line, index) => group.lines.slice(index + 1).some(other =>
      !!line.item.subjectCode && line.item.subjectCode === other.item.subjectCode
      && line.item.declaredAmount !== other.item.declaredAmount));
  }
  return [...groups.values()];
}

export function budgetLineReference(line: BudgetProjectLine): string {
  const item = line.item;
  const reasons: string[] = [];
  if (item.requiredFieldsStatus === 'FAILED') reasons.push('材料中的必要信息不完整，请核对原文');
  if (item.amountFormatStatus === 'FAILED') reasons.push('申报金额无法准确识别，请核对金额');
  if (item.matchStatus === 'UNMATCHED') reasons.push('尚未找到对应的预算批复科目，无法确认额度');
  if (item.matchStatus === 'SUSPECTED') reasons.push('存在多个可能对应的批复科目，需要核对');
  if (item.amountStatus === 'OVER_BUDGET') reasons.push('申报金额超过对应的预算批复金额');
  if (item.categoryAttributionStatus === 'FAILED') reasons.push('科目归属需要核对');
  if (item.projectDepartmentStatus === 'FAILED') reasons.push('项目编号或所属部门需要核对');
  if (item.budgetYearValidityStatus === 'FAILED') reasons.push('科目与所选预算年度不一致');
  for (const reason of item.abnormalReasons ?? []) {
    if (/供应商/.test(reason)) reasons.push('供应商信息需要核对');
    if (/编号重复/.test(reason)) reasons.push('材料中有多条相同项目编号，请核对是否为同一项目的不同科目');
    if (/编码/.test(reason) && !/项目/.test(reason)) reasons.push('预算科目编码需要核对');
  }
  if (reasons.length) return [...new Set(reasons)].join('；') + '。';
  const unchecked = (item.checks ?? []).some(check => check.status !== 'PASSED');
  if (!line.complete || unchecked || item.amountStatus !== 'PASSED'
      || item.matchStatus !== 'MATCHED' || item.riskLevel !== 'INFO') {
    return '现有信息不足以完成全部核对，请结合原材料和预算批复确认。';
  }
  return '已检查的信息与预算批复一致，申报金额未超过对应额度。';
}
