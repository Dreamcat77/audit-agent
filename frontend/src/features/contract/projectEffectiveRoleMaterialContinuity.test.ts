import assert from "node:assert/strict";
import test from "node:test";

import { projectMaterialTabs } from "../project/projectMaterialPresentation.ts";
import type { ProjectFile } from "../project/types.ts";
import { deriveProjectReviewMaterialSelection } from "./projectReviewMaterialSelection.ts";

const PROJECT_ID = "00000000-0000-0000-0000-000000000201";

function projectFile(overrides: Partial<ProjectFile> = {}): ProjectFile {
  return {
    id: "00000000-0000-0000-0000-000000000301",
    projectId: PROJECT_ID,
    parentArchiveFileId: null,
    normalizedRelativePath: "001.docx",
    originalFilename: "001.docx",
    mediaType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    category: "OTHER",
    ingestionStatus: "AVAILABLE",
    parseStatus: "PARSED",
    sizeBytes: 1024,
    parsedText: "采购合同。甲乙双方约定履行、违约和终止条款。",
    parseMethod: "DOCX_TEXT",
    parseEngine: "apache-poi",
    parseConfidence: null,
    pageCount: 1,
    parseDurationMs: 8,
    safeReasonCode: null,
    createdAt: "2026-09-18T00:00:00Z",
    updatedAt: "2026-09-18T00:00:01Z",
    parseResultId: "00000000-0000-0000-0000-000000000401",
    parseVersion: 1,
    parseOrigin: "CURRENT_PIPELINE",
    roleAssessmentId: "00000000-0000-0000-0000-000000000501",
    suggestedRole: "CONTRACT_BODY",
    roleConfidence: 0.99,
    roleExecutionStatus: "COMPLETED",
    roleAssessmentSource: "MACHINE_DETERMINISTIC",
    roleSafeReasonCode: null,
    roleModelProvider: null,
    roleModelName: null,
    roleModelRevision: null,
    rolePromptVersion: null,
    roleSchemaVersion: null,
    aiRoleAssessmentId: null,
    aiRoleExecutionStatus: null,
    aiRoleSafeReasonCode: null,
    roleDecisionStatus: "CONFIRMED",
    effectiveRole: "CONTRACT_BODY",
    effectiveRoleSource: "HUMAN_CONFIRMATION",
    roleDecisionId: "00000000-0000-0000-0000-000000000601",
    roleDecisionVersion: 0,
    identityAssessmentId: "00000000-0000-0000-0000-000000000701",
    identityAssessmentStatus: "COMPLETED",
    identityDecisionStatus: "CONFIRMED",
    effectiveIdentityStatus: "SAME_PROJECT",
    identityDecisionId: "00000000-0000-0000-0000-000000000801",
    identityDecisionVersion: 0,
    identityCurrent: true,
    excludedFromProjectUse: false,
    progression: {
      stage: "REVIEW_READY",
      blocker: null,
      availableActions: ["START_REVIEW"],
      candidateId: "00000000-0000-0000-0000-000000000901",
      contractId: "00000000-0000-0000-0000-000000001001",
    },
    ...overrides,
  };
}

function reviewTabs(files: readonly ProjectFile[]) {
  return projectMaterialTabs(files, PROJECT_ID).map((tab) => ({
    ...tab,
    files: tab.files.map((file) => ({
      ...file,
      originalUrl: `/api/v1/projects/${PROJECT_ID}/files/${file.id}/content`,
    })),
  }));
}

test("human-confirmed effective role carries 001.docx from OTHER into CONTRACT and ReviewRun selection", () => {
  const packageFile = projectFile({
    id: "00000000-0000-0000-0000-000000000302",
    originalFilename: "materials.zip",
    normalizedRelativePath: "materials.zip",
    mediaType: "application/zip",
    category: "PACKAGE",
  });
  const tabs = reviewTabs([projectFile(), packageFile]);
  const contractMaterial = tabs.find((tab) => tab.key === "CONTRACT")?.files[0];

  assert.equal(contractMaterial?.id, "00000000-0000-0000-0000-000000000301");
  assert.equal(contractMaterial?.classifierCategory, "OTHER");
  assert.equal(contractMaterial?.reviewRole, "CONTRACT_BODY");
  assert.equal(tabs.find((tab) => tab.key === "OTHER")?.files.length, 0);
  assert.equal(tabs.flatMap((tab) => tab.files).some((file) => file.id === packageFile.id), false);

  const selection = deriveProjectReviewMaterialSelection(tabs, PROJECT_ID);
  assert.equal(selection.canStart, true);
  assert.deepEqual(selection.selectedFileIds, ["00000000-0000-0000-0000-000000000301"]);
});

test("review role follows server authority and never falls back to immutable classifier category", () => {
  const noAuthority = projectFile({
    id: "00000000-0000-0000-0000-000000000303",
    category: "CONTRACT_BODY",
    effectiveRole: "UNKNOWN",
    effectiveRoleSource: "NO_AUTHORITATIVE_ROLE",
    roleDecisionId: null,
    roleDecisionVersion: null,
  });
  const staleHumanProjection = projectFile({
    id: "00000000-0000-0000-0000-000000000304",
    category: "CONTRACT_BODY",
    effectiveRole: "CONTRACT_BODY",
    effectiveRoleSource: "HUMAN_CONFIRMATION",
    roleDecisionId: null,
    roleDecisionVersion: null,
  });
  const legacyEffective = projectFile({
    id: "00000000-0000-0000-0000-000000000305",
    category: "CONTRACT_BODY",
    effectiveRole: "CONTRACT_BODY",
    effectiveRoleSource: "LEGACY_EFFECTIVE",
    roleDecisionId: null,
    roleDecisionVersion: null,
  });

  const unauthorizedTabs = reviewTabs([noAuthority, staleHumanProjection]);
  assert.equal(unauthorizedTabs.find((tab) => tab.key === "CONTRACT")?.files.length, 0);
  assert.deepEqual(
    unauthorizedTabs.find((tab) => tab.key === "OTHER")?.files.map((file) => file.reviewRole),
    ["UNKNOWN", "UNKNOWN"],
  );
  assert.equal(deriveProjectReviewMaterialSelection(unauthorizedTabs, PROJECT_ID).canStart, false);

  const legacyTabs = reviewTabs([legacyEffective]);
  assert.equal(legacyTabs.find((tab) => tab.key === "CONTRACT")?.files[0]?.reviewRole, "CONTRACT_BODY");
  assert.equal(deriveProjectReviewMaterialSelection(legacyTabs, PROJECT_ID).canStart, true);
});
