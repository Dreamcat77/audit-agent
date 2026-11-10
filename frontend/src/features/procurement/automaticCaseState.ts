// Only pre-acceptance validation rejections permit choosing a new upload operation.
export function definiteUploadRejection(status: number): boolean {
  return [400, 413, 415, 422].includes(status);
}
export function caseHistoryLabel(value: { id: string; createdAt?: string; fileNames: string[] }): string {
  const date = value.createdAt ? new Date(value.createdAt).toLocaleDateString('zh-CN') : '';
  const first = value.fileNames[0] || '采购材料';
  return `批次 ${date} · ${value.fileNames.length} 份材料 · ${first.length > 30 ? first.slice(0, 30) + '…' : first} · ${value.id.slice(-6)}`.trim();
}

const factLabels: Record<string, string> = {
  documentType: '材料类型', projectName: '项目名称', projectNo: '项目编号', purchaserName: '采购单位', agencyName: '采购代理机构', packageName: '采购包',
  budgetAmount: '预算金额', ceilingAmount: '最高限价', supplierName: '供应商', quoteAmount: '报价总额',
  awardAmount: '中标金额', productDescription: '采购内容', itemName: '明细名称', quantity: '数量',
  unitPrice: '单价', lineAmount: '明细金额',
};
export function automaticFactLabel(type: string): string {
  return factLabels[type] || '材料信息';
}
export function automaticCheckTitle(code: string): string {
  if (code.startsWith('IDENTITY_')) return automaticFactLabel(code.slice(9));
  const titles: Record<string,string> = {
    IDENTITY:'项目及参与单位', BUDGET_VS_AWARD:'预算、报价与成交金额', QUANTITY:'采购内容与数量', DATE_AND_PLAN:'采购节点与履约安排',
    TECHNICAL_REQUIREMENTS:'采购需求与限制条件', SCORING_METHOD:'评审办法与评分依据', PAYMENT_CLARIFICATION:'付款、验收及澄清',
    BUDGET_CONFLICT:'预算金额与适用范围', QUANTITY_CONFLICT:'数量与配置范围', QUOTE_BUDGET:'报价与预算',
    QUOTE_LINE_ARITHMETIC:'报价明细核算', QUOTE_CONCENTRATION:'报价分布', AWARD_QUOTE_MISMATCH:'成交结果与供应商报价', PRICE_BASIS:'金额适用范围',
  };
  return titles[code] || '核对意见';
}
export function automaticCheckExplanation(check: { code: string; status: string; explanation: string }): string {
  if (check.code.startsWith('IDENTITY_')) {
    const field = check.code.slice('IDENTITY_'.length);
    if (check.status === 'UNKNOWN') return `本批材料尚不足以核对${automaticFactLabel(field)}，不据此认定材料之间存在冲突。`;
    // Translate only the system-owned prefix. Never rewrite source quotations or arbitrary values.
    const prefix = field + '：';
    if (check.explanation.startsWith(prefix)) return automaticFactLabel(field) + check.explanation.slice(field.length).replace('；缺少该字段的文件不作为冲突依据','；未载明此信息的材料不参与本项比对');
  }
  if (check.code === 'QUOTE_BUDGET' && check.status === 'UNKNOWN') {
    return '本批材料尚不足以核实预算上限，已保留报价信息，本项不作是否超预算的结论。';
  }
  return check.explanation.replace(/\bJava[ \t]+checks\b|系统\s*checks\b/g, '系统核算结果');
}

/** Omit empty filters: URLSearchParams otherwise sends the literal word undefined. */
export function automaticHistoryParams(search:string,date:string):Record<string,string> {
  const params:Record<string,string>={};
  if(search.trim())params.keyword=search.trim().slice(0,160);
  if(/^\d{4}-\d{2}-\d{2}$/.test(date)){
    const start=new Date(`${date}T00:00:00`);
    if(Number.isFinite(start.getTime())&&start.getFullYear()===Number(date.slice(0,4))&&start.getMonth()+1===Number(date.slice(5,7))&&start.getDate()===Number(date.slice(8,10))){
      params.from=start.toISOString();params.to=new Date(start.getFullYear(),start.getMonth(),start.getDate()+1).toISOString();
    }
  }
  return params;
}

export function automaticCheckTone(status:string):'attention'|'clear'|'default' {
  if(['CONFLICT','BLOCKED','REVIEW','STATISTICAL_SIGNAL','FAIL'].includes(status)||/^RULE_SIGNAL_(?:WARNING|CRITICAL|HIGH|MEDIUM|LOW)$/.test(status))return 'attention';
  if(['CONSISTENT_IN_OBSERVED','WITHIN_BUDGET','WITHIN_CEILING','PASS','CLEAR','NO_MATCH','MATCHED'].includes(status))return 'clear';
  return 'default';
}
export function automaticReviewReason(code: string): string {
  const reasons: Record<string, string> = {
    SEMANTIC_OR_SOURCE_COVERAGE_INCOMPLETE: '部分材料或原文依据不足，当前展示已核实的分析内容。',
    NO_READABLE_SOURCE: '本次材料未能读取到可供分析的正文。',
    MODEL_CAPACITY_BUSY: '分析服务暂时繁忙，尚未取得完整结果。',
    MODEL_TIMEOUT_OUTCOME_UNKNOWN: '等待分析超时，结果暂时无法核实。',
    MODEL_INTERRUPTED_OUTCOME_UNKNOWN: '分析过程被中断，结果暂时无法核实。',
    MODEL_CALL_FAILED: '分析服务未返回可用结果，已保留完成的核对内容。',
    MODEL_INVALID_RESPONSE: '分析服务返回的内容无法可靠核实，已保留完成的核对内容。',
  };
  return reasons[code] || '本项分析尚未完整完成，请以当前已核实的结果为参考。';
}

export function automaticPrioritySummary(processing: boolean, budget: {status: string}[], ceiling: {status: string}[]): string {
  const overBudget = budget.filter(c => c.status === 'OVER_BUDGET').length;
  const overCeiling = ceiling.filter(c => c.status === 'OVER_CEILING').length;
  const issues = [overBudget ? `${overBudget} 份报价超过材料预算` : '', overCeiling ? `${overCeiling} 份报价超过最高限价` : ''].filter(Boolean);
  if (issues.length) return `请关注：${issues.join('；')}。详见下方报价比对。`;
  return processing ? '正在整理审查意见…' : '已核实范围内暂无明确问题；材料不足的事项不作结论。';
}
