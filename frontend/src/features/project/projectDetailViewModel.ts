import type { CandidateDetail, ContractCandidate, ExtractionEvidence, ProjectContract, ProjectDetail, ProjectFile } from "./types";
import { formatRmbAmount } from "../contract/reviewFindingPresentation.ts";
import { candidatePromotionLabel, materialCategoryLabel, materialStatusLabel,
  factTypeLabel, parseProvenancePresentation } from "./projectMaterialPresentation.ts";

const PROJECT_MATERIAL_CANDIDATE_FACT_TYPES = [
  "PROJECT_NAME", "PROJECT_NO", "PURCHASER", "PROCUREMENT_AGENT",
  "BUDGET_AMOUNT", "CONTROL_PRICE", "PACKAGE_SCOPE",
] as const;
const MONEY_CANDIDATE_FACT_TYPES = new Set(["BUDGET_AMOUNT", "CONTROL_PRICE"]);

export type ProjectMaterialCandidateSource = ExtractionEvidence & {
  displayValue: string;
  sourceFileName: string;
  excerpt: string;
};

export interface ProjectMaterialCandidateGroup {
  factType: string;
  factLabel: string;
  groupKey: string | null;
  values: string[];
  conflict: boolean;
  sources: ProjectMaterialCandidateSource[];
}

function extractionDisplayValue(evidence: ExtractionEvidence): string {
  const normalized = evidence.normalizedValue?.trim();
  if (MONEY_CANDIDATE_FACT_TYPES.has(evidence.factType)) {
    const canonical = normalized ? canonicalDecimal(normalized) : null;
    if (canonical != null) return canonical;
    if (evidence.valueAmount != null) return String(evidence.valueAmount);
  }
  if (normalized) return normalized;
  if (evidence.valueAmount != null) return String(evidence.valueAmount);
  return evidence.valueText?.trim() || "—";
}

function canonicalDecimal(value: string): string | null {
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(value);
  if (!match) return null;
  const integer = match[2].replace(/^0+(?=\d)/, "");
  const fraction = (match[3] ?? "").replace(/0+$/, "");
  const zero = integer === "0" && fraction.length === 0;
  const sign = match[1] === "-" && !zero ? "-" : "";
  return `${sign}${integer}${fraction ? `.${fraction}` : ""}`;
}

export function projectMaterialCandidateGroups(
  detail: Pick<ProjectDetail, "files" | "extractionResults">,
): ProjectMaterialCandidateGroup[] {
  const filesById = new Map(detail.files.map((file) => [file.id, file.originalFilename]));
  return PROJECT_MATERIAL_CANDIDATE_FACT_TYPES.flatMap((factType) => {
    const factEvidence = detail.extractionResults.filter((evidence) => evidence.factType === factType);
    const groupKeys = [...new Set(factEvidence.map((evidence) => evidence.groupKey))]
      .sort((left, right) => left === right ? 0 : left == null ? -1 : right == null ? 1 : left < right ? -1 : 1);
    return groupKeys.map((groupKey) => {
      const sources = factEvidence
        .filter((evidence) => evidence.groupKey === groupKey)
        .map((evidence) => ({
          ...evidence,
          displayValue: extractionDisplayValue(evidence),
          sourceFileName: filesById.get(evidence.sourceFileId)
            ?? `未知来源文件（${evidence.sourceFileId}）`,
          excerpt: evidence.sourceText,
        }));
      const values = [...new Set(sources.map((source) => source.displayValue))]
        .sort((left, right) => left === right ? 0 : left < right ? -1 : 1);
      return {
        factType,
        factLabel: factTypeLabel(factType),
        groupKey,
        values,
        conflict: values.length > 1,
        sources,
      };
    });
  });
}

export function filePresentation(file: ProjectFile) {
  const categoryLabel = materialCategoryLabel(file.category);
  let statusLabel = materialStatusLabel(file);
  let action: "继续处理" | null = null;
  if (file.ingestionStatus === "UPLOAD_PENDING") action = "继续处理";
  return {
    categoryLabel, statusLabel, action,
    originalMode: file.mediaType === "application/pdf" ? "PDF" as const : "DOWNLOAD" as const,
    parsedText: file.parsedText,
    provenance: parseProvenancePresentation(file),
  };
}

export function archivedPackagePresentation(files: readonly ProjectFile[]) {
  const filenames = files
    .filter((file) => file.category === "PACKAGE" && file.ingestionStatus === "AVAILABLE")
    .map((file) => file.originalFilename);
  return filenames.length === 0 ? null : {
    label: "原始资料包已归档" as const,
    filenames,
    count: filenames.length,
  };
}

export function sourcePresentation(evidence: ExtractionEvidence[], factType: string, groupKey: string | null = null) {
  const matches = evidence.filter((value) => value.factType === factType && value.groupKey === groupKey
    && Boolean(value.sourceLocator) && Boolean(value.sourceText));
  if (matches.length !== 1) return { label: "暂无可靠来源定位", source: null } as const;
  const source = matches[0];
  return { label: "查看来源", source: {
    fileId: source.sourceFileId, pageNumber: source.pageNumber,
    paragraphIndex: source.paragraphIndex, locator: source.sourceLocator, excerpt: source.sourceText,
  } } as const;
}

export function candidateFactGroups(detail: CandidateDetail) {
  return {
    scalarStates: detail.fieldStates,
    lineItems: detail.candidate.lineItems.map((line) => ({
      ...line,
      fieldStates: detail.lineItemFieldStates[line.itemKey ?? ""] ?? {},
    })),
  };
}

export function provenPromotedContractId(
  project: Pick<ProjectDetail, "project" | "associatedContracts">,
  candidate: Pick<CandidateDetail, "candidate"> | null,
): string | null {
  if (!candidate || candidate.candidate.promotionState !== "PROMOTED"
      || !candidate.candidate.promotedContractId
      || candidate.candidate.projectId !== project.project.id) return null;
  const contractId = candidate.candidate.promotedContractId;
  return formalContractForCandidate(project, candidate.candidate.id)?.contractId === contractId ? contractId : null;
}

export function formalContractForCandidate(
  project: Pick<ProjectDetail, "project" | "associatedContracts">,
  candidateId: string,
): ProjectContract | null {
  return project.associatedContracts.find((association) => association.projectId === project.project.id
    && association.candidateId === candidateId) ?? null;
}

export function candidateWorkspaceMode(
  candidate: Pick<ContractCandidate, "promotionState">,
  formalContract: ProjectContract | null,
): "CONFIRMATION" | "EXTRACTION_AND_FORMAL" | "EXTRACTION_ONLY" {
  if (candidate.promotionState === "DRAFT") return "CONFIRMATION";
  return formalContract ? "EXTRACTION_AND_FORMAL" : "EXTRACTION_ONLY";
}

export function candidateNavigation(
  candidates: Array<Pick<ContractCandidate, "id" | "contractNo" | "contractName" | "promotionState">>,
  activeCandidateId: string | null,
) {
  return candidates.map((candidate) => ({
    id: candidate.id,
    label: candidate.contractNo || candidate.contractName || "待识别",
    active: candidate.id === activeCandidateId,
    promotionLabel: candidatePromotionLabel(candidate.promotionState),
  }));
}

export function canSelectNewUpload(busy: boolean, uncertainUpload: boolean): boolean {
  return !busy && !uncertainUpload;
}

export function formatProjectMoney(value: number | null): string {
  return value == null ? "待识别" : formatRmbAmount(value);
}
