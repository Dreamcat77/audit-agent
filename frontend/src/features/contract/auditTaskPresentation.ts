import { deriveProgressiveReplayState } from "./reviewProgressState";
import type { ReviewProgress, ReviewResult } from "./types";

export interface CompactReviewProgressPresentation {
  label: string;
  current: number | null;
  total: number | null;
}

export function compactReviewProgressPresentation(
  result: Pick<ReviewResult, "findings"> | null,
  progress: ReviewProgress | null,
  running: boolean,
): CompactReviewProgressPresentation {
  if (progress) {
    const state = deriveProgressiveReplayState(progress);
    return { label: `${state.current} / ${state.total}`, current: state.current, total: state.total };
  }
  if (result) {
    if (result.findings.some((finding) => finding.riskLevel !== "GREEN")) {
      return { label: "审核中", current: null, total: null };
    }
    const total = 1 + result.findings.length;
    return { label: `${total} / ${total}`, current: total, total };
  }
  return { label: running ? "审核中" : "待审核", current: null, total: null };
}

export function auditTaskRiskPresentation(
  findings: ReadonlyArray<{ riskLevel: "GREEN" | "YELLOW" | "RED" }>,
): { label: "无需额外复核" | "建议复核" | "建议重点复核"; tone: "SAFE" | "WARNING" | "PRIORITY" } {
  const riskCount = findings.filter((finding) => finding.riskLevel !== "GREEN").length;
  if (riskCount === 0) return { label: "无需额外复核", tone: "SAFE" };
  if (riskCount <= 2) return { label: "建议复核", tone: "WARNING" };
  return { label: "建议重点复核", tone: "PRIORITY" };
}

export function directTaskReviewPromptPresentation(
  riskSummary: ReviewProgress["riskSummary"],
): { label: "无需额外复核" | "建议复核" | "建议重点复核"; tone: "SAFE" | "WARNING" | "PRIORITY" } {
  if (riskSummary === "无风险") return { label: "无需额外复核", tone: "SAFE" };
  if (riskSummary === "低风险") return { label: "建议复核", tone: "WARNING" };
  return { label: "建议重点复核", tone: "PRIORITY" };
}

export function riskBadgeToneClass(tone: "SAFE" | "WARNING" | "PRIORITY"): string {
  if (tone === "SAFE") return "bg-emerald-50 text-emerald-700 border-emerald-200";
  if (tone === "WARNING") return "bg-amber-50 text-amber-700 border-amber-200";
  return "bg-red-200 text-slate-950 border-red-400";
}

export function shouldIncludeShowcaseRows(mode: "mock" | "http"): boolean {
  return mode === "mock";
}
