export function reportStatusLabel(status: string): string {
  switch (status) {
    case "PENDING": return "等待生成";
    case "GENERATING": return "正在生成";
    case "AVAILABLE": return "可下载";
    case "FAILED": return "生成未完成";
    default: return "结果状态待确认";
  }
}
// A report is a durable closeout record even when the review itself ended with
// a known failure or an unproven outcome. It explains what could and could not
// be completed instead of leaving the user at a dead end.
export function canGenerateReport(runStatus: string) {
  return ["COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "UNKNOWN"].includes(runStatus);
}
export function closeoutError(_error: unknown) {
  return "暂时无法确认操作结果，请刷新查询后重试。请勿改变原处理意见或重复发起新操作。";
}
