import {
  contractReviewProgressPresentation,
  type ContractReviewProgressFacts,
  type ContractReviewProgressTone,
} from "../contractReviewProgressPresentation.ts";

const FILL_CLASS: Record<ContractReviewProgressTone, string> = {
  WAITING: "bg-red-500",
  ACTIVE: "bg-amber-500",
  ACKNOWLEDGEMENT: "bg-amber-500",
  COMPLETE: "bg-emerald-500",
};

export default function ContractReviewProgressBar(facts: ContractReviewProgressFacts) {
  const progress = contractReviewProgressPresentation(facts);
  return (
    <div className="w-[170px] space-y-1.5" aria-label={`F02 量化核对进度：${progress.current}/${progress.total} · ${progress.statusLabel}`}>
      <div className="flex items-center gap-2">
        <div
          className="h-1.5 flex-1 overflow-hidden rounded-full bg-slate-200"
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={progress.percentage}
        >
          <div
            className={`h-full rounded-full ${FILL_CLASS[progress.tone]}`}
            style={{ width: `${progress.percentage}%` }}
          />
        </div>
        <span className="w-9 text-right text-xs font-medium tabular-nums text-slate-700">
          {progress.percentage}%
        </span>
      </div>
      <div className="whitespace-nowrap text-xs text-slate-500">
        <span className="tabular-nums">F02 {progress.current}/{progress.total}</span>
        <span> · {progress.statusLabel}</span>
      </div>
    </div>
  );
}
