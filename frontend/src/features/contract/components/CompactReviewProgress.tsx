import { compactReviewProgressPresentation } from "../auditTaskPresentation";
import type { ReviewExecutionSnapshot } from "../reviewExecutionPresentation";
import type { ReviewProgress, ReviewResult } from "../types";

interface Props {
  result: ReviewResult | null;
  progress?: ReviewProgress | null;
  running?: boolean;
  snapshot?: ReviewExecutionSnapshot;
}
export default function CompactReviewProgress({ result, progress = null, running = false, snapshot }: Props) {
  const presentation = compactReviewProgressPresentation(result, progress, running);
  const label = snapshot && !progress && !result && snapshot.state === "RUNNING"
    ? "审核中" : presentation.label;
  return (
    <span className="inline-flex min-w-20 items-center justify-center rounded border border-slate-200 bg-slate-50 px-2.5 py-1 text-xs tabular-nums text-slate-700"
      aria-label={`合同审核进度：${label}`}>
      {label}
    </span>
  );
}
