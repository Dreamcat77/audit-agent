import { AlertTriangle, Brain, CheckCircle2, Circle, LoaderCircle } from "lucide-react";

import type {
  IntelligentReviewPipelineStatus,
  IntelligentReviewPipelineStep,
} from "../intelligentReviewPipelinePresentation";

interface IntelligentReviewPipelineProps {
  steps: readonly IntelligentReviewPipelineStep[];
  canStart: boolean;
  running: boolean;
  buttonLabel?: string;
  onStart: () => void;
}

function StepIcon({ status }: { status: IntelligentReviewPipelineStatus }) {
  if (status === "RUNNING") return <LoaderCircle size={15} className="animate-spin text-blue-700" />;
  if (status === "COMPLETED") return <CheckCircle2 size={15} className="text-emerald-600" />;
  if (status === "ATTENTION") return <AlertTriangle size={15} className="text-amber-700" />;
  return <Circle size={15} className="text-slate-400" />;
}

export function IntelligentReviewPipeline({
  steps,
  canStart,
  running,
  buttonLabel = "开始智能审核",
  onStart,
}: IntelligentReviewPipelineProps) {
  return (
    <section className="rounded-xl border border-blue-200 bg-white p-4" aria-labelledby="intelligent-review-pipeline-title">
      <h3 id="intelligent-review-pipeline-title" className="text-[15px] font-semibold text-slate-900">审核进度</h3>
      <ol className="mt-3 space-y-2.5">
        {steps.map((step, index) => (
          <li key={step.key} className="flex items-start gap-3">
            <span className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-slate-50">
              <StepIcon status={step.status} />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-xs font-medium text-slate-900">{index + 1}. {step.label}</p>
              <p className="mt-0.5 text-xs leading-5 text-slate-600">{step.detail}</p>
            </div>
          </li>
        ))}
      </ol>
      <button
        type="button"
        onClick={onStart}
        disabled={!canStart || running}
        className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-lg border border-blue-700 bg-blue-700 px-4 py-2.5 text-sm font-medium text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {running ? <LoaderCircle size={15} className="animate-spin" /> : <Brain size={15} />}
        {running ? "智能审核进行中" : buttonLabel}
      </button>
    </section>
  );
}
