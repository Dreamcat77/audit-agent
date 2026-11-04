import type { CandidateDetail, ProjectFile } from "./types";

/** UX filtering only. The command endpoint independently verifies every authority tuple. */
export function eligibleCandidateSupportFiles(files: ProjectFile[], projectId: string): ProjectFile[] {
  return files.filter((file) => file.projectId === projectId
    && file.ingestionStatus === "AVAILABLE" && file.parseStatus === "PARSED"
    && file.effectiveRole === "AWARD_NOTICE" && file.effectiveRoleSource === "HUMAN_CONFIRMATION"
    && file.identityDecisionStatus === "CONFIRMED" && file.effectiveIdentityStatus === "SAME_PROJECT"
    && file.identityCurrent === true && file.excludedFromProjectUse === false);
}

export function primaryOnlySupportAuthorityState(detail: CandidateDetail) {
  return {
    authorityMode: detail.authorityMode ?? "PRIMARY_ONLY_COMPAT",
    supportAuthorityVersionNo: detail.supportAuthorityVersionNo ?? null,
    supportAuthorityFingerprint: detail.supportAuthorityFingerprint ?? null,
    supportAuthorities: detail.supportAuthorities ?? [],
  };
}

export function candidateSupportAuthorityPresentation(detail: CandidateDetail, files: ProjectFile[],
  selection?: readonly string[]) {
  const supports = primaryOnlySupportAuthorityState(detail).supportAuthorities;
  const assignedIds = [...new Set(supports.map((row) => row.sourceFileId))].sort();
  const selectedIds = [...new Set(selection ?? assignedIds)].sort();
  const eligible = eligibleCandidateSupportFiles(files, detail.candidate.projectId);
  const eligibleIds = new Set(eligible.map((file) => file.id));
  const rowIds = [...new Set([...eligibleIds, ...assignedIds])].sort();
  const editable = detail.candidate.promotionState === "DRAFT";
  return {
    assignedIds, selectedIds, editable,
    canSave: editable && JSON.stringify(assignedIds) !== JSON.stringify(selectedIds)
      && selectedIds.every((id) => eligibleIds.has(id)),
    canClear: editable && selectedIds.length > 0,
    awardAmount: detail.candidate.awardAmount,
    awardSupplier: detail.candidate.awardSupplier,
    amountState: detail.fieldStates.AWARD_AMOUNT ?? "MISSING",
    supplierState: detail.fieldStates.AWARD_SUPPLIER ?? "MISSING",
    rows: rowIds.map((id) => {
      const file = files.find((value) => value.id === id && value.projectId === detail.candidate.projectId);
      const support = supports.find((value) => value.sourceFileId === id);
      return { id, filename: file?.originalFilename ?? support?.originalFilename ?? "来源文件暂不可读取",
        effectiveRole: file?.effectiveRole ?? "UNKNOWN", eligible: eligibleIds.has(id),
        current: support ? support.current === true : eligibleIds.has(id),
        assigned: assignedIds.includes(id), selected: selectedIds.includes(id) };
    }),
  };
}
