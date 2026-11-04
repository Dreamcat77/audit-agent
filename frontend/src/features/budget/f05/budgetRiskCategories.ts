import type { BudgetReviewResponse } from '../../../api/types';
type Line=NonNullable<BudgetReviewResponse['materialReference']>['lines'][number];
export interface RiskCandidate {source:{taskId:string;lineIndex:number};fileName:string;uploadedAt:string;line:Line}
export interface SplitRiskRule {code:string;name:string;version:number;individualLimit:number;threshold:number;minimumCount:number;windowDays:number}
export interface MaterialSplitAnalysis {
  version:string;riskLevel:string;totalAmount:number;countedItems:number;summary:string;rules:SplitRiskRule[];
  findings:{rule:SplitRiskRule;year:string|null;department:string|null;totalAmount:number;riskLevel:string;explanation:string;projects:RiskCandidate[]}[];
  exclusions:{project:RiskCandidate;reasonCode:string;reason:string}[];
  bandCode?:string|null;bandLabel?:string|null;complete?:boolean;
}
export interface RiskResult {id:string;createdAt:string;projects:RiskCandidate[];opinions:string[];splitAnalysis?:MaterialSplitAnalysis|null}
export const candidateKey=(r:RiskCandidate)=>`${r.source.taskId}:${r.source.lineIndex}`;
export function repeatedMaterialGroups(rows:RiskCandidate[]):RiskCandidate[][] {
  const groups=new Map<string,RiskCandidate[]>();
  for(const row of rows){
    const project=row.line.project?.trim(),subject=(row.line.subject??row.line.subjectCode)?.trim(),amount=row.line.declaredAmount;
    if(!project||!subject||amount==null)continue;
    const identity=JSON.stringify([project,subject,row.line.year?.trim()??'',row.line.department?.trim()??'',amount]);
    groups.set(identity,[...(groups.get(identity)??[]),row]);
  }
  return [...groups.values()].filter(group=>new Set(group.map(row=>row.source.taskId)).size>1);
}
export function budgetRiskCategory(subject:string|null|undefined):string {
  if(!subject?.trim())return '科目待核对';
  if(/装修|装潢|装饰/.test(subject))return '装潢';
  if(/工程|建筑|修缮/.test(subject))return '工程';
  if(/电子|电脑|计算机|信息化|软件|网络/.test(subject))return '电子';
  return '其他';
}
export function groupRiskCandidates(rows:RiskCandidate[]) {
  const groups=new Map<string,RiskCandidate[]>([['电子',[]],['工程',[]],['装潢',[]],['其他',[]],['科目待核对',[]]]);
  for(const row of rows){const name=budgetRiskCategory(row.line.subject??row.line.subjectCode);groups.set(name,[...(groups.get(name)??[]),row]);}
  return [...groups].map(([name,rows])=>({name,rows}));
}
