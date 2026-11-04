import type { SourcePreparationFactItem, SourcePreparationSection } from "../contract/reviewRulePresentation";
import { formatRmbAmount } from "../contract/reviewFindingPresentation";
import { factTypeLabel, fieldStateLabel, materialCategoryLabel } from "./projectMaterialPresentation";
import type { ContractCandidate, FieldState, Project, ProjectFile } from "./types";

interface ProjectSourcePreparationInput {
  project: Project;
  candidate: ContractCandidate;
  fieldStates: Record<string, FieldState>;
  files: readonly ProjectFile[];
}

function fact(
  label: string,
  value: string | number | null | undefined,
  state: FieldState | undefined,
): SourcePreparationFactItem {
  const missing = value === null || value === undefined || String(value).trim() === "";
  return {
    label,
    value: missing ? "未识别" : String(value),
    statusLabel: fieldStateLabel(state ?? (missing ? "MISSING" : "CONFIRMED")),
  };
}

function section(title: string, items: SourcePreparationFactItem[]): SourcePreparationSection {
  return { title, summary: "核对完成", items };
}

export function buildProjectSourcePreparationSections({
  project,
  candidate,
  fieldStates,
  files,
}: ProjectSourcePreparationInput): SourcePreparationSection[] {
  const materialLabels = [...new Set(files.map((file) => materialCategoryLabel(file.category)))];
  return [
    section("项目信息", [
      fact("项目名称", project.name, fieldStates.PROJECT_NAME),
      fact("项目编号", project.projectNo, fieldStates.PROJECT_NO),
    ]),
    section("合同基本信息", [
      fact("合同编号", candidate.contractNo, fieldStates.CONTRACT_NO),
      fact("合同名称", candidate.contractName, fieldStates.CONTRACT_NAME),
      fact("甲方", candidate.partyA, fieldStates.PARTY_A),
      fact("乙方", candidate.partyB, fieldStates.PARTY_B),
      fact("统一社会信用代码", candidate.partyBCreditCode, fieldStates.CREDIT_CODE),
    ]),
    section("合同金额", [
      fact("数字金额", candidate.contractAmount == null ? null : formatRmbAmount(candidate.contractAmount), fieldStates.AMOUNT_NUMERIC),
      fact("金额大写", candidate.amountInWords, fieldStates.AMOUNT_UPPERCASE),
    ]),
    section("合同明细", [
      fact("识别结果", candidate.lineItems.length > 0 ? `已识别 ${candidate.lineItems.length} 项` : null,
        candidate.lineItems.length > 0 ? "CONFIRMED" : "MISSING"),
    ]),
    section("中标信息", [
      fact(factTypeLabel("AWARD_AMOUNT"), candidate.awardAmount == null ? null : formatRmbAmount(candidate.awardAmount), fieldStates.AWARD_AMOUNT),
      fact(factTypeLabel("AWARD_SUPPLIER"), candidate.awardSupplier, fieldStates.AWARD_SUPPLIER),
    ]),
    section("来源材料", materialLabels.length > 0
      ? materialLabels.map((label) => fact(label, label, "CONFIRMED"))
      : [fact("来源材料", null, "MISSING")]),
  ];
}
