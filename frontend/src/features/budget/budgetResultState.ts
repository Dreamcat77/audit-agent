// PARSED only proves that rows were read. The analysis may still be running.
export function budgetResultStatus(status: string): string {
  return ({ OPEN: '批次处理中', WAITING_UPLOAD: '待上传', UPLOADED: '待处理', QUEUED: '待处理', PROCESSING: '处理中', PARSED: '待完成分析',
    WAITING_CONFIRMATION: '需要核对', REVIEWED: '已完成', PARTIAL: '部分完成', FAILED: '已结束', UNKNOWN: '待核实' } as Record<string,string>)[status] ?? '暂无法读取状态';
}

export function budgetResultEndedWithoutResult(status: string): boolean {
  return status === 'FAILED' || status === 'UNKNOWN';
}

export function budgetResultExplanation(status: string, explanation?: string | null): string | null {
  if (!budgetResultEndedWithoutResult(status)) return null;
  return explanation || '本次处理已结束，尚未取得可核实的分析结果，请联系管理员查看关联日志；不能据此判断材料不足或预算没有问题。';
}

export function budgetResultPending(status: string): boolean {
  return ['OPEN', 'WAITING_UPLOAD', 'UPLOADED', 'QUEUED', 'PROCESSING', 'PARSED'].includes(status);
}

// "Complete" describes the checked extraction, never proof of exhaustive source coverage.
export function budgetCoverageLabel(coverage?: string | null): string {
  if (coverage === 'PARTIAL') return '部分信息待核对';
  if (coverage === 'COMPLETE') return '已核对提取的信息';
  return '请结合原材料核对';
}

export function budgetApprovalAmount(value: number | null, matchStatus: string): string {
  if (value != null) return `￥${value.toFixed(2)}`;
  if (matchStatus === 'UNMATCHED') return '未匹配到系统批复';
  if (matchStatus === 'SUSPECTED') return '对应批复待确认';
  if (matchStatus === 'MATCHED') return '系统批复未配置金额';
  return '尚未完成核对';
}
