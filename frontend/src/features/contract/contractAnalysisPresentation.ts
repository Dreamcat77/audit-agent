export type AnalysisState="QUEUED"|"RUNNING"|"COMPLETED"|"PARTIAL"|"DATA_INSUFFICIENT"|"FAILED"|"UNKNOWN";
export const analysisBusy=(status:AnalysisState)=>status==="QUEUED"||status==="RUNNING";
export const factLabels:Record<string,string>={CONTRACT_NAME:"合同名称",CONTRACT_TYPE:"合同类型",PROJECT_NAME:"项目名称",CONTRACT_NO:"合同编号",PROJECT_NO:"项目编号",CONTRACT_REFERENCE:"关联合同",PARTY_A:"甲方",PARTY_B:"乙方",TOTAL_AMOUNT:"合同总价",AMOUNT_WORDS:"金额大写",AWARD_AMOUNT:"中标金额",AWARD_SUPPLIER:"中标供应商",BID_AMOUNT:"投标报价",BIDDER:"投标人",PURCHASER:"采购人",AGENCY:"采购代理",BUDGET_AMOUNT:"预算金额",CEILING_AMOUNT:"最高限价",SIGN_DATE:"签订日期",TAX_BASIS:"税费口径",CURRENCY:"币种"};
export const topicLabels:Record<string,string>={PAYMENT:"付款安排",DELIVERY:"交付履约",ACCEPTANCE:"验收要求",BREACH:"违约责任",TERM:"合同期限",TERMINATION:"解除终止",DISPUTE:"争议解决",WARRANTY:"质保要求",CONFIDENTIALITY:"保密义务",SUBJECT:"合同标的",STRUCTURE:"合同结构",QUALIFICATION:"主体资格",ATTACHMENT:"附件清单",EFFECTIVENESS:"生效与份数",OTHER:"其他约定"};
export function analysisTone(status:AnalysisState){return status==="COMPLETED"?"delivery-alert-neutral":analysisBusy(status)?"delivery-alert-neutral":"delivery-alert-warning";}
export function contractCheckLabel(status:string){return status==="PASS"?"已核对一致":status==="FAIL"?"需要关注":"依据不足，未作判断";}
export function partitionAnalysisFindings<T extends {level:string}>(findings:T[]){
  return {risks:findings.filter(f=>f.level==="WARNING"),followUps:findings.filter(f=>f.level!=="WARNING")};
}
export type AnalysisEvidence={fileId:string};
export type AnalysisSourceDocument={fileId:string;name:string;facts:{type:string;value:string}[];clauses:{topic:string;summary:string}[]};
export type AnalysisFindingForCopy={title:string;explanation:string;suggestion:string;evidence:AnalysisEvidence[]};
export function findingSourceNames(finding:AnalysisFindingForCopy,documents:AnalysisSourceDocument[]){
  const ids=new Set(finding.evidence.map(item=>item.fileId));
  return [...new Set(documents.filter(document=>ids.has(document.fileId)).map(document=>document.name))];
}
export function findingCopyText(finding:AnalysisFindingForCopy,sourceNames:string[]){
  return [`问题项：${finding.title}`,sourceNames.length?`涉及材料：${sourceNames.join("、")}`:"涉及材料：未能定位到具体文件",
    `问题说明：${finding.explanation}`,`建议：${finding.suggestion}`].join("\n");
}
export function aggregateMaterialHighlights(documents:AnalysisSourceDocument[]){
  return documents.flatMap(document=>[
    ...document.facts.map((fact,index)=>({key:`${document.fileId}:fact:${fact.type}:${index}`,sourceName:document.name,title:fact.type,text:fact.value})),
    ...document.clauses.map((clause,index)=>({key:`${document.fileId}:clause:${clause.topic}:${index}`,sourceName:document.name,title:clause.topic,text:clause.summary})),
  ]);
}
