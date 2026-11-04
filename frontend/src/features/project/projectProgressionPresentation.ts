import type { ProjectFileProgression, ProjectProgressionAction } from "./types";

const STAGE_LABELS: Record<string, string> = {
  UPLOADED: "材料已上传，等待解析",
  PARSED: "解析完成，系统正在识别材料类型",
  NEEDS_ROLE_CONFIRMATION: "系统正在识别材料类型",
  ROLE_CONFIRMED: "材料类型已识别，系统正在判断关联",
  NEEDS_IDENTITY_CONFIRMATION: "系统正在判断材料关联",
  IDENTITY_CONFIRMED: "系统已整理项目关联",
  CANDIDATE_NEEDS_CONFIRMATION: "合同信息已提取，系统正在整理",
  CANDIDATE_READY: "合同信息已整理，正在生成正式合同",
  AUTOMATIC_PROCESSING: "系统正在整理合同结果",
  OTHER_MATERIAL: "材料已处理，未纳入合同分析",
  MATERIAL_CLASSIFIED: "材料已识别",
  CONTRACT_INFORMATION_INSUFFICIENT: "材料已处理，合同信息不完整",
  REVIEW_READY: "正式合同已生成，可开始审核",
  PARSE_FAILED: "材料解析失败",
};

const ACTION_LABELS: Record<ProjectProgressionAction, string> = {
  CONFIRM_ROLE: "系统识别材料类型",
  CONFIRM_PROJECT_IDENTITY: "系统判断材料关联",
  CONFIRM_CONTRACT_CANDIDATE: "系统整理合同信息",
  PROMOTE_CONTRACT: "系统生成正式合同",
  START_REVIEW: "开始合同审核",
  RESUME_ROLE_ASSESSMENT: "继续识别材料类型",
  RESUME_IDENTITY_ASSESSMENT: "继续判断项目归属",
  RESUME_CANDIDATE_MATERIALIZATION: "继续整理合同信息",
  RESUME_FACT_EXTRACTION: "继续提取关键事实",
};

const BLOCKER_PRESENTATION: Record<string, { statusLabel: string; detailLabel: string }> = {
  PROJECT_IDENTITY_NOT_CONFIRMED: {
    statusLabel: "系统正在整理材料关联",
    detailLabel: "项目或材料版本已变化，系统会按当前材料重新判断。",
  },
  ROLE_ASSESSMENT_PENDING: {
    statusLabel: "材料类型识别尚未完成",
    detailLabel: "解析已完成，可安全接续当前材料的类型识别。",
  },
  IDENTITY_ASSESSMENT_PENDING: {
    statusLabel: "系统正在判断材料关联",
    detailLabel: "材料类型已识别，系统会继续整理当前项目的材料。",
  },
  IDENTITY_ASSESSMENT_STALE: {
    statusLabel: "项目归属判断已失效",
    detailLabel: "项目或材料输入已变化，需要按当前版本重新判断项目归属。",
  },
  CANDIDATE_MATERIALIZATION_PENDING: {
    statusLabel: "合同候选尚未形成",
    detailLabel: "材料归属已确认，合同信息仍在整理或需要继续处理。",
  },
  FACT_EXTRACTION_PENDING: {
    statusLabel: "关键事实提取尚未完成",
    detailLabel: "材料归属已确认，可继续提取有原文依据的关键事实候选。",
  },
  PROJECT_CONTRACT_ASSOCIATION_UNPROVEN: {
    statusLabel: "正式合同关联暂未完成",
    detailLabel: "系统暂时无法证明材料与正式合同的持久关联，已停止进入审核。",
  },
  AUTOMATIC_RESULT_NOT_READY: {
    statusLabel: "系统正在整理合同结果",
    detailLabel: "材料已自动识别；信息完整后会生成正式合同，信息不足时保留为材料结果。",
  },
  AUTOMATIC_CLASSIFICATION_PENDING: {
    statusLabel: "系统正在识别材料",
    detailLabel: "无需人工确认，系统会根据材料内容自动判断。",
  },
  AUTOMATIC_PROJECT_MATCH_PENDING: {
    statusLabel: "系统正在判断材料关联",
    detailLabel: "系统会自动处理项目归属，不需要人工选择。",
  },
  NOT_INCLUDED_IN_CONTRACT_ANALYSIS: {
    statusLabel: "材料已处理，未纳入合同分析",
    detailLabel: "系统判断该材料不属于当前合同分析范围。",
  },
  NOT_INCLUDED_IN_PROJECT_ANALYSIS: {
    statusLabel: "材料已处理，未纳入当前项目",
    detailLabel: "系统未找到足够的项目关联依据，因此没有把它并入当前项目。",
  },
  CONTRACT_EVIDENCE_INCOMPLETE: {
    statusLabel: "材料已处理，合同信息不完整",
    detailLabel: "原文依据不足以安全生成正式合同，已保留识别结果。",
  },
  FACTS_NOT_VERIFIED: {
    statusLabel: "材料已处理，未找到可确认的合同信息",
    detailLabel: "原文已保留；没有依据的合同字段不会补填，也不会据此生成正式合同。",
  },
};

export function projectFileProgressionPresentation(progression: ProjectFileProgression) {
  const resumable = progression.availableActions.some((action) => action.startsWith("RESUME_"));
  const recoveryWaiting = progression.blocker === "ROLE_ASSESSMENT_PENDING"
    || progression.blocker === "IDENTITY_ASSESSMENT_PENDING"
    || progression.blocker === "IDENTITY_ASSESSMENT_STALE"
    || progression.blocker === "FACT_EXTRACTION_PENDING";
  const blocker = progression.blocker && (!recoveryWaiting || resumable)
    ? BLOCKER_PRESENTATION[progression.blocker] : undefined;
  return {
    statusLabel: blocker?.statusLabel ?? STAGE_LABELS[progression.stage] ?? "材料处理中",
    ...(blocker ? { detailLabel: blocker.detailLabel } : {}),
    actions: progression.availableActions.map((key) => ({ key, label: ACTION_LABELS[key] })),
  };
}

export function hasProgressionAction(file: { progression: ProjectFileProgression },
  action: ProjectProgressionAction): boolean {
  return file.progression.availableActions.includes(action);
}
