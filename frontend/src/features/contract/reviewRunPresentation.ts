import type { IntelligentReviewPipelineStep } from "./intelligentReviewPipelinePresentation.ts";
import type {
  ReviewFindingDto,
  ReviewRunDto,
  ReviewStepDto,
  ReviewStepStatus,
  ReviewStepType,
} from "./reviewRunTypes.ts";

const EXECUTABLE_TYPES = new Set<ReviewStepType>([
  "F02_DETERMINISTIC", "SEMANTIC_AI", "BREACH", "TERM",
]);

const STEP_LABELS: Record<ReviewStepType, string> = {
  MATERIAL_VALIDATION: "材料确认",
  F02_DETERMINISTIC: "精确校验",
  SEMANTIC_AI: "智能语义审查",
  BREACH: "违约条款审查",
  TERM: "履约期限与终止条件审查",
};

const RUN_STATUS_TEXT = {
  PENDING: ["待开始", "审核材料已经确认，等待执行审核环节。"],
  RUNNING: ["审核进行中", "正在处理本次审核，请稍候。"],
  COMPLETED: ["审核完成", "本次审核的全部环节均已完成。"],
  PARTIALLY_COMPLETED: ["部分完成", "部分审核环节未完成，已完成结果仍可查看。"],
  FAILED: ["审核未完整完成", "本次审核存在未成功完成的环节；已完成结果仍可结合来源材料复核。"],
  UNKNOWN: ["结果状态待确认", "部分审核请求已发出，但系统暂时无法确认完整处理结果。已确认完成的审核结果仍可查看。"],
} as const;

export const REVIEW_RUN_RESTART_REQUIRED_MESSAGE =
  "审核规则或审核状态已发生变化，请重新发起本次审核。";

const SAFE_REASON_TEXT: Readonly<Record<string, string>> = {
  F02_RULE_POLICY_DRIFT: "审核规则已更新，请重新发起本次审核。",
  SEMANTIC_UNAVAILABLE: "智能分析能力当前暂不可用。",
  SEMANTIC_RESPONSE_INVALID: "智能分析结果未能通过系统校验。",
  SEMANTIC_OUTCOME_UNKNOWN: "智能分析请求已发出，但当前无法确认处理结果。",
  STEP_EXECUTOR_UNAVAILABLE: "该审核能力当前暂不可用。",
  STEP_COMPUTATION_INVALID: "本次审核数据未能通过系统校验。",
  STEP_EXECUTION_FAILED: "该审核环节未成功完成。",
  MODEL_UNAVAILABLE: "智能分析服务暂不可用，本环节未完成。",
  INVALID_MODEL_RESPONSE: "智能分析结果未通过校验，本环节未完成。",
  SCHEMA_VALIDATION_FAILED: "智能分析结果格式不完整，本环节未完成。",
  POLICY_CHANGED: "审核规则已更新，本次环节未继续执行。",
  MATERIAL_NOT_READY: "所选材料未满足审核条件，本环节未完成。",
  EVIDENCE_NOT_GROUNDED: "来源依据未能通过校验，本环节未完成。",
  OUTCOME_UNCERTAIN: "本次处理结果暂无法确认，请稍后查看审核记录。",
};

export interface DurableReviewPresentation {
  statusLabel: string;
  statusDetail: string;
  steps: readonly IntelligentReviewPipelineStep[];
  canContinue: boolean;
  canStartNew: boolean;
  selectionFrozen: boolean;
}

export function canStartNewReviewRun(status: ReviewRunDto["status"]): boolean {
  return status === "COMPLETED" || status === "PARTIALLY_COMPLETED" || status === "FAILED";
}

export function reviewStepLabel(type: ReviewStepType): string {
  return STEP_LABELS[type];
}

export function reviewFindingSourceLabel(source: ReviewFindingDto["source"]): string {
  return source === "MODEL_ASSISTED" ? "智能分析提示" : "规则校验结果";
}

export function reviewSafeReasonText(code: string | null, status: ReviewStepStatus): string {
  if (code && SAFE_REASON_TEXT[code]) return SAFE_REASON_TEXT[code];
  if (status === "UNKNOWN") return "本次请求已发出，但系统暂时无法确认处理结果，请稍后查看。";
  if (status === "FAILED") return "该审核环节暂无法正常完成，请稍后重试或联系管理员。";
  return "当前环节状态已更新。";
}

function findStep(run: ReviewRunDto, type: ReviewStepType): ReviewStepDto | null {
  return run.steps.find((step) => step.stepType === type) ?? null;
}

function simpleStatus(step: ReviewStepDto | null): IntelligentReviewPipelineStep["status"] {
  if (!step || step.status === "PENDING") return "PENDING";
  if (step.status === "RUNNING") return "RUNNING";
  if (step.status === "COMPLETED") return "COMPLETED";
  return "ATTENTION";
}

function exactDetail(step: ReviewStepDto | null): string {
  if (!step || step.status === "PENDING") return "等待开始精确校验";
  if (step.status === "RUNNING") return "正在核对金额、明细与结构化身份信息……";
  if (step.status === "COMPLETED") return "金额与结构化信息精确校验已完成";
  return reviewSafeReasonText(step.safeReasonCode, step.status);
}

function semanticGroup(run: ReviewRunDto): Pick<IntelligentReviewPipelineStep, "status" | "detail"> {
  const steps = (["SEMANTIC_AI", "BREACH", "TERM"] as const)
    .map((type) => findStep(run, type))
    .filter((step): step is ReviewStepDto => step !== null);
  if (steps.length !== 3) {
    return { status: "ATTENTION", detail: "审核记录不完整，暂时无法确认全部智能语义与条款审核结果。" };
  }
  const counts = {
    completed: steps.filter((step) => step.status === "COMPLETED").length,
    failed: steps.filter((step) => step.status === "FAILED").length,
    unknown: steps.filter((step) => step.status === "UNKNOWN").length,
    running: steps.filter((step) => step.status === "RUNNING").length,
    pending: steps.filter((step) => step.status === "PENDING").length,
  };
  if (counts.failed > 0 || counts.unknown > 0) {
    const parts = [
      counts.completed > 0 ? `已完成 ${counts.completed} 项` : null,
      counts.failed > 0 ? `未完成 ${counts.failed} 项` : null,
      counts.unknown > 0 ? `结果待确认 ${counts.unknown} 项` : null,
      counts.running > 0 ? `处理中 ${counts.running} 项` : null,
      counts.pending > 0 ? `待处理 ${counts.pending} 项` : null,
    ].filter((value): value is string => value !== null);
    return { status: "ATTENTION", detail: `${parts.join("，")}；已完成结果可继续查看。` };
  }
  if (counts.running > 0) return { status: "RUNNING", detail: "正在进行智能语义与条款审核……" };
  if (counts.pending > 0) {
    const prefix = counts.completed > 0 ? `已完成 ${counts.completed} 项，` : "";
    return { status: "PENDING", detail: `${prefix}其余环节等待开始` };
  }
  return { status: "COMPLETED", detail: "智能语义与条款审核已完成" };
}

function assistance(run: ReviewRunDto): Pick<IntelligentReviewPipelineStep, "status" | "detail"> {
  const executable = run.steps.filter((step) => EXECUTABLE_TYPES.has(step.stepType));
  if (executable.some((step) => step.status === "UNKNOWN")) {
    return { status: "ATTENTION", detail: "部分环节结果暂无法确认；已完成结果已保留，请稍后查看并人工复核。" };
  }
  if (executable.some((step) => step.status === "FAILED")) {
    return { status: "ATTENTION", detail: "部分环节未完成；已完成结果已保留，请结合来源材料人工复核。" };
  }
  if (executable.some((step) => step.status === "PENDING" || step.status === "RUNNING")) {
    return { status: "PENDING", detail: "等待全部审核环节返回持久结果。" };
  }
  return { status: "COMPLETED", detail: "已形成辅助复核信息，等待人工复核。" };
}

export function presentReviewRun(
  run: ReviewRunDto,
  restartRequired = false,
): DurableReviewPresentation {
  const material = findStep(run, "MATERIAL_VALIDATION");
  const exact = findStep(run, "F02_DETERMINISTIC");
  const semantic = semanticGroup(run);
  const finalAdvice = assistance(run);
  const requiredTypes: readonly ReviewStepType[] = [
    "MATERIAL_VALIDATION", "F02_DETERMINISTIC", "SEMANTIC_AI", "BREACH", "TERM",
  ];
  const manifestComplete = requiredTypes.every((type) =>
    run.steps.filter((step) => step.stepType === type).length === 1);
  const [storedStatusLabel, storedStatusDetail] = RUN_STATUS_TEXT[run.status];
  const statusLabel = restartRequired
    ? "需要重新发起审核"
    : manifestComplete ? storedStatusLabel : "审核记录暂无法完整显示";
  const statusDetail = restartRequired
    ? REVIEW_RUN_RESTART_REQUIRED_MESSAGE
    : manifestComplete
      ? storedStatusDetail
      : "系统读取到的审核记录不完整，请稍后刷新或联系管理员。";
  const materialStatus = simpleStatus(material);
  const materialDetail = material?.status === "COMPLETED"
    ? `已确认 ${run.materials.length} 份审核材料`
    : material ? reviewSafeReasonText(material.safeReasonCode, material.status) : "等待确认审核材料";
  return {
    statusLabel,
    statusDetail,
    selectionFrozen: true,
    canContinue: run.executionMode === "CLIENT_COMPATIBILITY" && !restartRequired && manifestComplete
      && (run.status === "PENDING" || run.status === "RUNNING")
      && run.steps.some((step) => EXECUTABLE_TYPES.has(step.stepType) && step.status === "PENDING"),
    canStartNew: manifestComplete && (restartRequired || canStartNewReviewRun(run.status)),
    steps: [
      { key: "MATERIAL_SELECTION", label: "审核材料", detail: materialDetail, status: materialStatus },
      { key: "MATERIAL_READING", label: "材料读取", detail: material?.status === "COMPLETED" ? "所选材料内容已就绪" : "等待材料确认完成", status: materialStatus },
      { key: "DETERMINISTIC", label: "精确校验", detail: exactDetail(exact), status: simpleStatus(exact) },
      { key: "AI_REVIEW_GROUP", label: "智能语义审查", detail: semantic.detail, status: semantic.status },
      { key: "ASSISTANCE", label: "审核建议", detail: finalAdvice.detail, status: finalAdvice.status },
    ],
  };
}
