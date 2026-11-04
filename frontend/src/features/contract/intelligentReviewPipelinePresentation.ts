export type IntelligentReviewPipelineStatus = "PENDING" | "RUNNING" | "COMPLETED" | "ATTENTION";

export interface IntelligentReviewPipelineStep {
  key: "MATERIAL_SELECTION" | "MATERIAL_READING" | "DETERMINISTIC" | "AI_REVIEW_GROUP" | "ASSISTANCE";
  label: string;
  detail: string;
  status: IntelligentReviewPipelineStatus;
}

export interface IntelligentReviewPipelineInput {
  selectedFileCount: number;
  aiLoading: boolean;
  aiAvailable: boolean;
  aiFailed: boolean;
  deterministicLoading: boolean;
  deterministicCompleted: boolean;
  deterministicFailed: boolean;
}

export function createIntelligentReviewPipeline(
  input: IntelligentReviewPipelineInput,
): { steps: readonly IntelligentReviewPipelineStep[] } {
  const deterministicTerminal = input.deterministicCompleted || input.deterministicFailed;
  const aiTerminal = input.aiAvailable || input.aiFailed;
  const reviewGroupStatus: IntelligentReviewPipelineStatus = input.aiLoading
    ? "RUNNING"
    : input.aiAvailable
      ? "COMPLETED"
      : input.aiFailed ? "ATTENTION" : "PENDING";
  const reviewGroupDetail = reviewGroupStatus === "RUNNING"
    ? "正在生成一次智能语义分析结果……"
    : reviewGroupStatus === "COMPLETED"
      ? "智能语义分析已完成，结果按三个业务区域展示"
      : reviewGroupStatus === "ATTENTION"
        ? "智能语义分析本次未生成完整结果，请结合来源材料复核"
        : "等待智能语义分析开始";

  let assistance: Pick<IntelligentReviewPipelineStep, "status" | "detail">;
  if (!deterministicTerminal || !aiTerminal) {
    assistance = { status: "PENDING", detail: "等待精确校验与智能语义分析返回真实结果。" };
  } else if (input.deterministicFailed) {
    assistance = { status: "ATTENTION", detail: "精确校验未完成；不能形成完整复核依据。" };
  } else if (input.aiFailed) {
    assistance = { status: "ATTENTION", detail: "智能语义分析本次未生成完整结果；精确校验结果已保留，请结合来源材料复核。" };
  } else {
    assistance = { status: "COMPLETED", detail: "已生成辅助复核信息，等待人工复核。" };
  }

  return { steps: [
    {
      key: "MATERIAL_SELECTION",
      label: "审核材料",
      detail: input.selectedFileCount > 0 ? `已选择 ${input.selectedFileCount} 份已整理资料` : "尚未选择可用资料",
      status: input.selectedFileCount > 0 ? "COMPLETED" : "PENDING",
    },
    {
      key: "MATERIAL_READING",
      label: "材料读取",
      detail: input.selectedFileCount > 0 ? "所选材料内容已就绪" : "等待选择已整理资料",
      status: input.selectedFileCount > 0 ? "COMPLETED" : "PENDING",
    },
    {
      key: "DETERMINISTIC",
      label: "精确校验",
      detail: input.deterministicLoading ? "正在核对金额、明细与结构化身份信息……"
        : input.deterministicCompleted ? "金额与结构化信息精确校验已完成"
          : input.deterministicFailed ? "精确校验未完成，智能建议不会替代该结果" : "等待开始",
      status: input.deterministicLoading ? "RUNNING"
        : input.deterministicCompleted ? "COMPLETED" : input.deterministicFailed ? "ATTENTION" : "PENDING",
    },
    {
      key: "AI_REVIEW_GROUP",
      label: "智能语义审查",
      detail: reviewGroupDetail,
      status: reviewGroupStatus,
    },
    { key: "ASSISTANCE", label: "审核建议", detail: assistance.detail, status: assistance.status },
  ] };
}
