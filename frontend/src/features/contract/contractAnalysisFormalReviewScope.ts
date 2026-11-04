import type {ProjectDetail,ProjectContract} from "../project/types.ts";
import type {ProjectBackedReviewMaterialTab} from "./workspaceTypes.ts";
import {deriveProjectReviewMaterialSelection} from "./projectReviewMaterialSelection.ts";
import type {ReviewRunDto} from "./reviewRunTypes.ts";

export function formalContractsForAnalysis(detail:ProjectDetail,sourceFileIds:readonly string[]):ProjectContract[]{
  const files=new Set(sourceFileIds);
  const candidates=new Set(detail.candidates.filter(item=>files.has(item.sourceContractFileId)).map(item=>item.id));
  return detail.associatedContracts.filter(item=>candidates.has(item.candidateId));
}

export function formalReviewSelectionForAnalysis(
  tabs:readonly ProjectBackedReviewMaterialTab[],projectId:string,sourceFileIds:readonly string[],
){
  return deriveProjectReviewMaterialSelection(tabs,projectId,sourceFileIds);
}

export function reviewRunMatchesAnalysisBatch(run:Pick<ReviewRunDto,"materials">,selectedFileIds:readonly string[]):boolean{
  const runIds=[...new Set(run.materials.map(item=>item.fileId))].sort();
  const batchIds=[...new Set(selectedFileIds)].sort();
  return runIds.length===batchIds.length&&runIds.every((id,index)=>id===batchIds[index]);
}

/** Match CloseoutOwnership: material/F02 checks remain visible in the eight categories. */
export function confirmableFindingsForAnalysis(run:Pick<ReviewRunDto,"outputs">){
  return run.outputs.filter(output=>output.step.stepType!=="MATERIAL_VALIDATION"&&output.step.stepType!=="F02_DETERMINISTIC")
    .flatMap(output=>output.findings.filter(finding=>finding.statusCode!=="NORMAL"&&finding.statusCode!=="PASS"));
}
