import { AlertTriangle, Check, CircleHelp, Dot, LoaderCircle } from "lucide-react";
import type { ReviewExecutionStepStatus } from "../reviewExecutionPresentation";

export const reviewStepStatusLabels: Record<ReviewExecutionStepStatus, string> = {
  PENDING: "待执行", RUNNING: "执行中", PASS: "核对完成", WARNING: "待知悉", UNDETERMINED: "未评估", FAIL: "待知悉",
};

export const reviewStepStatusClasses: Record<ReviewExecutionStepStatus, string> = {
  PENDING: "border-slate-300 bg-slate-100 text-slate-500",
  RUNNING: "border-blue-500 bg-blue-50 text-blue-700",
  PASS: "border-emerald-500 bg-emerald-50 text-emerald-700",
  WARNING: "border-amber-500 bg-amber-50 text-amber-700",
  UNDETERMINED: "border-slate-400 bg-slate-100 text-slate-700",
  FAIL: "border-amber-500 bg-amber-50 text-amber-800",
};

function StatusIcon({ status }: { status: ReviewExecutionStepStatus }) {
  if (status === "RUNNING") return <LoaderCircle size={13} className="animate-spin" />;
  if (status === "PASS") return <Check size={14} strokeWidth={2.5} />;
  if (status === "WARNING") return <AlertTriangle size={13} strokeWidth={2.2} />;
  if (status === "FAIL") return <AlertTriangle size={14} strokeWidth={2.4} />;
  if (status === "UNDETERMINED") return <CircleHelp size={13} />;
  return <Dot size={17} strokeWidth={3} />;
}

export default function ReviewStepIndicator({ label, status, size = "compact", statusLabel }: {
  label: string;
  status: ReviewExecutionStepStatus;
  size?: "compact" | "timeline";
  statusLabel?: string;
}) {
  const accessibleLabel = `${label}：${statusLabel ?? reviewStepStatusLabels[status]}`;
  return (
    <span className={`${size === "compact" ? "size-[22px]" : "size-7"} inline-flex shrink-0 items-center justify-center rounded-full border ${reviewStepStatusClasses[status]}`} title={accessibleLabel} aria-label={accessibleLabel}>
      <StatusIcon status={status} />
    </span>
  );
}
