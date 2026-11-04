import type { ProjectDataSource } from "./data/projectDataSource";
import type { Project, ProjectIntakeDraft, ProjectPreparationDraft } from "./types";
import type { ProjectIntakeStage } from "./projectIntakeOperation";

export function projectHeader(project: Project) {
  return {
    projectId: project.id,
    name: `项目名称：${project.name || "待识别"}`,
    projectNo: `项目编号：${project.projectNo || "待识别"}`,
    category: `项目类目：${project.category || "待提供"}`,
    primaryAction: "上传项目资料",
  } as const;
}

export function projectListRow(project: Project) {
  return {
    id: project.id,
    name: project.name || "待识别",
    projectNo: project.projectNo || "待识别",
    category: project.category || "待提供",
    updatedAt: project.updatedAt,
  };
}

export function projectManagementSections() {
  return {
    preprocessing: "项目资料预处理",
    intakeDrafts: "已预处理资料",
    formalProjects: "正式项目",
  } as const;
}

type ProjectCollectionSource = Pick<ProjectDataSource,
  "listProjects" | "listIntakeDrafts" | "listPreparationDrafts">;

export interface ProjectCollectionPages {
  intakeDraftPage: number;
  preparationDraftPage: number;
}

async function requestProjectCollections(dataSource: ProjectCollectionSource, signal: AbortSignal | undefined,
  pages: ProjectCollectionPages) {
  return Promise.all([
    dataSource.listProjects({ signal }),
    dataSource.listIntakeDrafts({ page: pages.intakeDraftPage, size: 20, signal }),
    dataSource.listPreparationDrafts({ page: pages.preparationDraftPage, size: 20, signal }),
  ]);
}

function lastPage(total: number, size: number) {
  return Math.max(1, Math.ceil(total / Math.max(1, size)));
}

export async function loadProjectCollections(dataSource: ProjectCollectionSource, signal?: AbortSignal,
  pages: ProjectCollectionPages = { intakeDraftPage: 1, preparationDraftPage: 1 }) {
  let [projectPage, intakeDraftPage, preparationDraftPage] =
    await requestProjectCollections(dataSource, signal, pages);
  const availablePages = {
    intakeDraftPage: lastPage(intakeDraftPage.total, intakeDraftPage.size),
    preparationDraftPage: lastPage(preparationDraftPage.total, preparationDraftPage.size),
  };
  if (pages.intakeDraftPage > availablePages.intakeDraftPage
    || pages.preparationDraftPage > availablePages.preparationDraftPage) {
    [projectPage, intakeDraftPage, preparationDraftPage] = await requestProjectCollections(dataSource, signal, {
      intakeDraftPage: Math.min(pages.intakeDraftPage, availablePages.intakeDraftPage),
      preparationDraftPage: Math.min(pages.preparationDraftPage, availablePages.preparationDraftPage),
    });
  }
  return {
    projects: projectPage.items.filter((project) => project.metadataConfirmed),
    intakeDrafts: intakeDraftPage.items,
    preparationDrafts: preparationDraftPage.items,
    projectTotal: projectPage.total,
    intakeDraftTotal: intakeDraftPage.total,
    preparationDraftTotal: preparationDraftPage.total,
    intakeDraftPage: intakeDraftPage.page,
    intakeDraftPageSize: intakeDraftPage.size,
    preparationDraftPage: preparationDraftPage.page,
    preparationDraftPageSize: preparationDraftPage.size,
  };
}

export function preparationDraftRow(draft: ProjectPreparationDraft) {
  return {
    projectId: draft.projectId,
    title: "材料已上传，系统正在整理",
    uploadedSummary: `已上传 ${draft.uploadedFileCount} 份材料`,
    createdAt: draft.createdAt,
    action: "查看处理进度",
  } as const;
}

function isCreatedToday(createdAt: string, now: Date) {
  const created = new Date(createdAt);
  return created.getFullYear() === now.getFullYear()
    && created.getMonth() === now.getMonth()
    && created.getDate() === now.getDate();
}

export function projectManagementPresentation(
  collections: {
    projects: readonly Project[];
    intakeDrafts: readonly ProjectIntakeDraft[];
    projectTotal: number | null;
    intakeDraftTotal: number | null;
  } | null,
  now: Date,
) {
  const loadedAnomalies = collections?.intakeDrafts.filter((draft) => draft.failedFileCount > 0) ?? [];
  const reminderDraft = loadedAnomalies[0]
    ?? collections?.intakeDrafts.find((draft) => draft.intakeStatus === "AWAITING_UPLOAD")
    ?? null;
  return {
    stats: {
      formalTotal: collections?.projectTotal ?? null,
      loadedDrafts: collections?.intakeDrafts.length ?? null,
      loadedAnomalies: collections ? loadedAnomalies.length : null,
      loadedMaterials: collections?.intakeDrafts.reduce((total, draft) => total + draft.parsedFileCount, 0) ?? null,
    },
    today: {
      formalProjects: collections?.projects.filter((project) => isCreatedToday(project.createdAt, now)).length ?? null,
      loadedDrafts: collections?.intakeDrafts.filter((draft) => isCreatedToday(draft.createdAt, now)).length ?? null,
    },
    scopeLabel: "当前已加载预处理资料",
    reminder: reminderDraft ? {
      projectId: reminderDraft.projectId,
      reason: reminderDraft.failedFileCount > 0 ? "存在待检查材料" : "等待资料上传",
    } : null,
  } as const;
}

const INTAKE_STATUS_LABEL: Record<string, string> = {
  AWAITING_UPLOAD: "等待资料上传",
  UPLOADED: "资料已归档",
  PARSED: "预处理完成",
  CANDIDATES_AVAILABLE: "预处理完成",
};

export function intakeDraftRow(draft: ProjectIntakeDraft) {
  return {
    projectId: draft.projectId,
    packageFilename: draft.packageFilename,
    statusLabel: INTAKE_STATUS_LABEL[draft.intakeStatus] ?? "处理中",
    parsedSummary: `已整理 ${draft.parsedFileCount} 份资料`,
    warning: draft.failedFileCount > 0 ? `${draft.failedFileCount} 份材料建议检查` : null,
    candidateProjectName: draft.candidateProjectName,
    candidateProjectNo: draft.candidateProjectNo,
    createdAt: draft.createdAt,
    viewAction: "查看资料",
    formalizeAction: "用于新建项目",
  } as const;
}

const PROJECT_INTAKE_STAGE_LABEL: Record<ProjectIntakeStage, string> = {
  IDLE: "等待选择项目资料包",
  FILE_SELECTED: "已选择项目资料包",
  CREATING_PROJECT: "正在建立资料批次",
  UPLOADING_PACKAGE: "正在接收项目资料包",
  REFRESHING_DURABLE_STATE: "正在整理并读取资料",
  COMPLETE: "项目资料已整理完成",
  ACCEPTED: "文件已接收，解析/OCR在后台排队，可离开页面后查看进度",
  FAILED: "项目资料接收失败，请重新选择资料包",
  UNCERTAIN: "资料处理结果尚未确认，请使用同一资料包继续重试",
};

export function projectIntakeError(stage: ProjectIntakeStage) {
  return stage === "FAILED"
    ? "项目资料包接入失败，请重新选择资料包后再试。"
    : "项目资料包处理结果尚未确认；请保留当前页面并使用同一资料包继续重试。";
}

export function projectLandingIntake(stage: ProjectIntakeStage, filename: string | null) {
  return {
    title: "项目资料预处理",
    description: "项目资料包将在此完成安全检查、材料整理、内容读取和业务信息识别。完成后可在合同、预算等业务环节直接选择已整理资料，无需重复上传。",
    primaryAction: "导入 ZIP 资料包",
    secondaryAction: "新建空白项目",
    stageLabel: PROJECT_INTAKE_STAGE_LABEL[stage],
    filename,
    busy: stage === "CREATING_PROJECT" || stage === "UPLOADING_PACKAGE"
      || stage === "REFRESHING_DURABLE_STATE",
    retryable: stage === "UNCERTAIN",
  } as const;
}
