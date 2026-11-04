import type { ProjectDataSource, ProjectMetadataConfirmation } from "./data/projectDataSource";
import type { RetainedOperation } from "./projectOperation";
import type { ExtractionEvidence, ProjectDetail } from "./types";

export interface ProjectMetadataDraft {
  expectedVersion: number;
  name: string;
  projectNo: string;
  category: string;
}

function durable(value: string | null): string | null {
  const normalized = value?.trim() ?? "";
  return normalized || null;
}

function uniqueSuggestion(evidence: ExtractionEvidence[], factType: string): string {
  const values = evidence
    .filter((fact) => fact.factType === factType)
    .map((fact) => durable(fact.normalizedValue) ?? durable(fact.valueText ?? null))
    .filter((value): value is string => value !== null);
  const distinct = [...new Set(values)];
  return distinct.length === 1 ? distinct[0] : "";
}

export function projectMetadataDraft(
  detail: Pick<ProjectDetail, "project" | "extractionResults">,
): ProjectMetadataDraft {
  return {
    expectedVersion: detail.project.versionNo,
    name: durable(detail.project.name) ?? uniqueSuggestion(detail.extractionResults, "PROJECT_NAME"),
    projectNo: durable(detail.project.projectNo) ?? uniqueSuggestion(detail.extractionResults, "PROJECT_NO"),
    category: durable(detail.project.category) ?? "",
  };
}

type MetadataReconciliationSource = Pick<ProjectDataSource, "confirmMetadata" | "getProject">;

export async function confirmMetadataAndReload(
  dataSource: MetadataReconciliationSource,
  projectId: string,
  payload: ProjectMetadataConfirmation,
  idempotencyKey: string,
  operation?: Pick<RetainedOperation, "clear">,
): Promise<{ outcome: "CONFIRMED" | "CONFLICT"; project: ProjectDetail }> {
  let outcome: "CONFIRMED" | "CONFLICT" = "CONFIRMED";
  try {
    await dataSource.confirmMetadata(projectId, payload, { idempotencyKey });
  } catch (error) {
    if ((error as { status?: number })?.status !== 409) throw error;
    outcome = "CONFLICT";
  }
  const project = await dataSource.getProject(projectId);
  if (outcome === "CONFIRMED" || project.project.versionNo !== payload.expectedVersion) {
    operation?.clear();
  }
  return { outcome, project };
}
