import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";
import {formalContractsForAnalysis,formalReviewSelectionForAnalysis,reviewRunMatchesAnalysisBatch,confirmableFindingsForAnalysis} from "./contractAnalysisFormalReviewScope.ts";
import type {ProjectDetail} from "../project/types.ts";

test("formal review only includes contracts whose source body belongs to this analysis batch",()=>{
  const detail={
    candidates:[
      {id:"candidate-a",sourceContractFileId:"file-a"},
      {id:"candidate-b",sourceContractFileId:"file-b"},
    ],
    associatedContracts:[
      {candidateId:"candidate-a",contractId:"contract-a"},
      {candidateId:"candidate-b",contractId:"contract-b"},
    ],
  } as unknown as ProjectDetail;
  assert.deepEqual(formalContractsForAnalysis(detail,["file-a"]).map(item=>item.contractId),["contract-a"]);
  assert.deepEqual(formalContractsForAnalysis(detail,["unrelated-file"]),[]);
});

test("formal review material selection is limited to files from the current analysis batch",()=>{
  const material=(id:string)=>({id,projectId:"project-a",fileName:`${id}.pdf`,mediaType:"application/pdf",
    classifierCategory:"CONTRACT_BODY",effectiveRole:"CONTRACT_BODY",effectiveRoleSource:"LEGACY_EFFECTIVE",
    reviewRole:"CONTRACT_BODY",roleDecisionId:null,ingestionStatus:"AVAILABLE",parseStatus:"PARSED",
    effectiveIdentityStatus:"SAME_PROJECT",identityCurrent:true,excludedFromProjectUse:false,
    semanticEligible:true,statusLabel:"已完成",parsedText:"合同正文",provenance:{methodLabel:"文本",confidenceLabel:null,pageCountLabel:null},originalUrl:"/file"});
  const tabs=[{key:"CONTRACT",label:"合同正文",files:[material("batch-file"),material("older-project-file")]},
    {key:"TENDER",label:"采购文件",files:[]},{key:"BID",label:"投标文件",files:[]},
    {key:"AWARD",label:"中标通知",files:[]},{key:"OTHER",label:"其他",files:[]}];
  const selection=formalReviewSelectionForAnalysis(tabs as never,"project-a",["batch-file"]);
  assert.equal(selection.canStart,true);
  assert.deepEqual(selection.selectedFileIds,["batch-file"]);
  assert.ok(selection.eligibleFileIds.includes("older-project-file"),"unrelated eligible files remain discoverable but are not silently selected");
});

test("a persisted formal review is shown only for its exact batch material set",()=>{
  const run={materials:[{fileId:"file-a"},{fileId:"file-b"}]} as never;
  assert.equal(reviewRunMatchesAnalysisBatch(run,["file-b","file-a","file-a"]),true);
  assert.equal(reviewRunMatchesAnalysisBatch(run,["file-a"]),false);
  assert.equal(reviewRunMatchesAnalysisBatch(run,["file-a","file-c"]),false);
});

test("automatic analysis result page embeds persistent eight-category review and finding acknowledgement",async()=>{
  const workspace=await readFile(new URL("./components/ContractAnalysisWorkspace.tsx",import.meta.url),"utf8");
  const formal=await readFile(new URL("./components/ContractAnalysisFormalReviews.tsx",import.meta.url),"utf8");
  assert.match(workspace,/<ContractAnalysisFormalReviews/);
  assert.doesNotMatch(formal,/aria-label="项目基本信息"/);
  assert.match(formal,/<EightCategoryReviewPanel/);
  assert.match(formal,/<ContractFindingAcknowledgement/);
  assert.match(formal,/formalReviewSelectionForAnalysis\(materials,projectId,sourceFileIds\)/);
  assert.doesNotMatch(workspace,/查看原有正式审核记录/);
});

test("confirmation only targets findings supported by closeout without removing deterministic results",()=>{
  const run={outputs:[
    {step:{stepType:"MATERIAL_VALIDATION"},findings:[{findingId:"material",statusCode:"WARNING"}]},
    {step:{stepType:"F02_DETERMINISTIC"},findings:[{findingId:"money",statusCode:"FAIL"}]},
    {step:{stepType:"SEMANTIC_AI"},findings:[{findingId:"semantic",statusCode:"WARNING"},{findingId:"normal",statusCode:"NORMAL"}]},
    {step:{stepType:"BREACH"},findings:[{findingId:"breach",statusCode:"ATTENTION"}]},
    {step:{stepType:"TERM"},findings:[{findingId:"term",statusCode:"DATA_INSUFFICIENT"},{findingId:"pass",statusCode:"PASS"}]},
  ]};
  assert.deepEqual(confirmableFindingsForAnalysis(run as never).map(f=>f.findingId),["semantic","breach","term"]);
  assert.equal(run.outputs[1].findings[0].findingId,"money");
  assert.deepEqual(confirmableFindingsForAnalysis({outputs:[]}),[]);
});
