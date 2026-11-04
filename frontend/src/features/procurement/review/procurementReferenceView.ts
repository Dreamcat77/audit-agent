import type { ProcurementReviewReport } from '../../../api/types';
import { uniqueTenderDisplayFindings } from './tenderFindingDisplay.ts';

const rank=(level:string|null|undefined)=>level==='CRITICAL'?3:level==='WARNING'?2:1;
const highest=(levels:(string|null|undefined)[])=>levels.reduce<string>((best,next)=>rank(next)>rank(best)?next! :best,'INFO');
const raised=(level:string)=>rank(level)>1;

/** Presentation of saved evidence; never rewrites a report or its acknowledgement fact. */
export function procurementReferenceView(report:ProcurementReviewReport) {
  const hadWorkflowCheck=report.agentReview.items.some(item=>item.checkType==='PROJECT_STATUS');
  const items=report.agentReview.items.filter(item=>item.checkType!=='PROJECT_STATUS');
  const itemRisk=highest(items.map(item=>item.riskLevel));
  const agentRisk=hadWorkflowCheck?itemRisk:highest([report.agentReview.riskLevel,itemRisk]);
  const agentReview={...report.agentReview,items,riskLevel:agentRisk,
    suggestion:hadWorkflowCheck?items.filter(item=>raised(item.riskLevel)).map(item=>item.suggestion||item.detail).filter(Boolean).join('；')||'已检查的基础信息未发现需要核对的问题。':report.agentReview.suggestion,
    aiSummary:hadWorkflowCheck?null:report.agentReview.aiSummary};
  const lines:string[]=[];
  for(const item of items.filter(item=>raised(item.riskLevel)))lines.push(`智能体预审 · ${item.checkName}：${item.detail}${item.suggestion&&item.suggestion!==item.detail?`；${item.suggestion}`:''}`);
  if(raised(agentRisk)&&!items.some(item=>raised(item.riskLevel)))lines.push(`智能体预审：${agentReview.suggestion||'预审记录了需关注内容，请核对本板块的材料信息。'}`);
  const quote=report.quoteCheck;
  if(quote&&raised(quote.riskLevel)) {
    const over=quote.quotes.filter(item=>item.overBudget);
    lines.push(`询比价分析：${over.length?`${over.map(item=>item.supplierName).join('、')}的报价超过预算，请核对报价明细。`:`供应商报价存在异常，报价离散程度为 ${quote.coefficientOfVariation.toFixed(4)}，请核对报价明细。`}`);
  }
  for(const hit of report.blacklist.hits.filter(item=>raised(item.alertLevel)))lines.push(`黑名单比对：${hit.supplierName}，${hit.blacklistReason}。`);
  const tenderFindings=uniqueTenderDisplayFindings(report.tender.findings);
  for(const finding of tenderFindings.filter(item=>raised(item.riskLevel)))lines.push(`招标文件合规审查 · ${finding.riskCategory}：${finding.matchedText}${finding.remediationSuggestion?`；${finding.remediationSuggestion}`:''}`);
  if(report.collusion&&raised(report.collusion.riskLevel))lines.push(`围串标评分：风险评分 ${report.collusion.riskScore} 分，最高文件相似度 ${(report.collusion.maxDocumentSimilarity*100).toFixed(1)}%，重复 IP ${report.collusion.duplicateIpGroups} 组、重复经办人 ${report.collusion.duplicateHandlerGroups} 组，请结合材料核实。`);
  if(report.splitPurchase&&raised(report.splitPurchase.riskLevel)) {
    for(const finding of report.splitPurchase.findings.filter(item=>raised(item.riskLevel)))lines.push(`拆分采购识别：${finding.supplierName}，30 天累计金额 ${finding.totalAmount.toLocaleString('zh-CN')} 元，${finding.thresholdName}为 ${finding.thresholdAmount.toLocaleString('zh-CN')} 元，请结合采购内容核实。`);
    if(!report.splitPurchase.findings.some(item=>raised(item.riskLevel)))lines.push('拆分采购识别：分析记录了需关注风险，但未保存对应明细，请核对本板块材料后重新分析。');
  }
  const riskLevel=highest([agentRisk,quote?.riskLevel,...report.blacklist.hits.map(item=>item.alertLevel),...report.tender.findings.map(item=>item.riskLevel),report.collusion?.riskLevel,report.splitPurchase?.riskLevel]);
  // An unexplained historical aggregate is not evidence of a clean result. Only
  // the exactly identified obsolete status rule may cease to affect the view.
  const legacyStatusRisk=highest(report.agentReview.items.filter(item=>item.checkType==='PROJECT_STATUS').map(item=>item.riskLevel));
  const unexplained=rank(report.riskLevel)>rank(riskLevel)&&!(hadWorkflowCheck&&rank(legacyStatusRisk)>=rank(report.riskLevel));
  if(unexplained)lines.push('历史风险记录：报告保存了额外风险等级，但现有明细未能定位对应问题，请核对原材料或重新分析。');
  return {agentReview,tenderFindings,riskLevel:unexplained?report.riskLevel:riskLevel,lines};
}
