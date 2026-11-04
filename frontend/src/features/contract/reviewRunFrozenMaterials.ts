import type { ProjectBackedReviewMaterialTab } from "./workspaceTypes.ts";
import type { ReviewRunMaterialDto } from "./reviewRunTypes.ts";

/** Historical review materials are limited to file IDs frozen by that exact ReviewRun. */
export function frozenReviewMaterialTabs(
  tabs: readonly ProjectBackedReviewMaterialTab[],
  frozenMaterials: readonly ReviewRunMaterialDto[] | null,
  historical: boolean,
): readonly ProjectBackedReviewMaterialTab[] {
  if (!historical) return tabs;
  if (!frozenMaterials) return [];
  const frozen = new Map(frozenMaterials.map((material) => [material.fileId, material]));
  return tabs.map(tab => ({ ...tab, files: tab.files.filter(file => {
    const exact = frozen.get(file.id);
    return exact !== undefined
      && file.fileSha256 === exact.fileSha256
      && file.parseResultId === exact.parseResultId
      && file.parseVersion === exact.parseVersion
      && file.canonicalTextSha256 === exact.canonicalTextSha256;
  }) }))
    .filter(tab => tab.files.length > 0);
}
