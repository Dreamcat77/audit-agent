import type { FindingSourceAnchor } from "./types";

interface MaterialTabOwner {
  key: string;
  files: ReadonlyArray<{ id: string; projectId: string }>;
}

export interface ProjectMaterialSourceSelection {
  tabKey: string;
  fileId: string;
  viewerMode: "LOCATION";
}

function nonblank(value: string | null | undefined): value is string {
  return Boolean(value?.trim());
}

export function selectProjectMaterialSource(
  tabs: readonly MaterialTabOwner[],
  source: FindingSourceAnchor | null,
): ProjectMaterialSourceSelection | null {
  if (!source || !nonblank(source.projectId) || !nonblank(source.fileId)
      || !nonblank(source.locator) || !nonblank(source.excerpt)) return null;
  const owner = tabs.find((tab) => tab.files.some((file) =>
    file.id === source.fileId && file.projectId === source.projectId));
  if (!owner) return null;
  return {
    tabKey: owner.key,
    fileId: source.fileId,
    viewerMode: "LOCATION",
  };
}
