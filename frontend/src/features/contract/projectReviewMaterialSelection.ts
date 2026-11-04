import type {
  ProjectBackedReviewMaterial,
  ProjectBackedReviewMaterialTab,
} from "./workspaceTypes";

const CROSS_MATERIAL_CATEGORIES = new Set([
  "TENDER_DOCUMENT",
  "BID_RESPONSE",
  "AWARD_NOTICE",
]);

export interface ProjectReviewMaterialSelection {
  eligibleMaterials: readonly ProjectBackedReviewMaterial[];
  eligibleFileIds: readonly string[];
  selectedFileIds: readonly string[];
  canStart: boolean;
  blockingMessage: string | null;
  warningMessage: string | null;
  duplicateWarningMessage: string | null;
  completeness: readonly MaterialCompletenessItem[];
}

export type MaterialCompletenessStatus = "UPLOADED" | "MISSING" | "UNREADABLE" | "OPTIONAL";

export interface MaterialCompletenessItem {
  key: ProjectBackedReviewMaterialTab["key"];
  label: string;
  status: MaterialCompletenessStatus;
}

const COMPLETENESS_LABELS: Record<ProjectBackedReviewMaterialTab["key"], string> = {
  CONTRACT: "合同正文",
  TENDER: "采购/招标文件",
  BID: "投标/响应文件",
  AWARD: "中标通知书",
  OTHER: "其他附件",
};

function normalizedFileName(fileName: string): string {
  return fileName.normalize("NFKC").trim().toLocaleLowerCase("zh-CN");
}

function hasSameNameAndCategory(
  materials: readonly ProjectBackedReviewMaterial[],
): boolean {
  const identities = new Set<string>();
  for (const material of materials) {
    const identity = JSON.stringify([normalizedFileName(material.fileName), material.reviewRole]);
    if (identities.has(identity)) return true;
    identities.add(identity);
  }
  return false;
}

export function isProjectReviewMaterialEligible(
  material: ProjectBackedReviewMaterial,
  projectId: string,
): boolean {
  return material.projectId === projectId
    && material.classifierCategory !== "PACKAGE"
    && material.reviewRole !== "UNKNOWN"
    && material.effectiveIdentityStatus === "SAME_PROJECT"
    && material.identityCurrent
    && !material.excludedFromProjectUse
    && material.ingestionStatus === "AVAILABLE"
    && material.parseStatus === "PARSED"
    && material.semanticEligible
    && Boolean(material.parsedText?.trim());
}

export function deriveProjectReviewMaterialSelection(
  tabs: readonly ProjectBackedReviewMaterialTab[],
  projectId: string,
  requestedFileIds?: readonly string[],
): ProjectReviewMaterialSelection {
  const eligibleMaterials: ProjectBackedReviewMaterial[] = [];
  const seen = new Set<string>();
  for (const material of tabs.flatMap((tab) => tab.files)) {
    if (seen.has(material.id) || !isProjectReviewMaterialEligible(material, projectId)) continue;
    seen.add(material.id);
    eligibleMaterials.push(material);
  }

  const requested = requestedFileIds === undefined
    ? new Set(eligibleMaterials.map((material) => material.id))
    : new Set(requestedFileIds);
  const selectedMaterials = eligibleMaterials.filter((material) => requested.has(material.id));
  const selectedFileIds = selectedMaterials.map((material) => material.id);
  const tabsByKey = new Map(tabs.map((tab) => [tab.key, tab]));
  const completeness = (Object.keys(COMPLETENESS_LABELS) as ProjectBackedReviewMaterialTab["key"][])
    .map((key): MaterialCompletenessItem => {
      const projectFiles = (tabsByKey.get(key)?.files ?? []).filter((material) =>
        material.projectId === projectId && material.classifierCategory !== "PACKAGE");
      const status: MaterialCompletenessStatus = key === "OTHER"
        ? "OPTIONAL"
        : projectFiles.length === 0
        ? "MISSING"
        : projectFiles.some((material) => isProjectReviewMaterialEligible(material, projectId))
          ? "UPLOADED"
          : "UNREADABLE";
      return { key, label: COMPLETENESS_LABELS[key], status };
    });

  const hasContractBody = selectedMaterials.some(
    (material) => material.reviewRole === "CONTRACT_BODY",
  );
  const hasCrossMaterial = selectedMaterials.some(
    (material) => CROSS_MATERIAL_CATEGORIES.has(material.reviewRole),
  );
  const blockingMessage = selectedFileIds.length === 0
    ? "请至少选择一份已处理材料。"
    : !hasContractBody
        ? "缺少可用合同正文，请先返回项目资料补充或完成解析。"
        : null;

  return {
    eligibleMaterials,
    eligibleFileIds: eligibleMaterials.map((material) => material.id),
    selectedFileIds,
    canStart: blockingMessage === null,
    blockingMessage,
    warningMessage: blockingMessage === null && !hasCrossMaterial
      ? "当前缺少招投标或中标依据，跨材料核对依据不足；仍可继续文字语义与精确校验。"
      : null,
    duplicateWarningMessage: hasSameNameAndCategory(eligibleMaterials)
      ? "检测到同名同类别材料，请确认是否为重复版本"
      : null,
    completeness,
  };
}
