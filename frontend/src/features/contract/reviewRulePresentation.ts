export const SOURCE_PREPARATION_TITLE = "资料读取与来源核对";

const RULE_TITLES: Readonly<Record<string, string>> = {
  F02_AMOUNT_WORDS_MATCH: "合同金额大小写一致性核对",
  F02_LINE_ITEM_ARITHMETIC: "合同分项金额计算核对",
  F02_LINE_SUM_MATCH: "合同分项合计与合同总额核对",
  F02_AWARD_AMOUNT_MATCH: "合同金额与中标金额一致性核对",
  F02_AWARD_SUPPLIER_MATCH: "合同乙方与中标供应商一致性核对",
};

export interface SourcePreparationFactItem {
  label: string;
  value: string;
  statusLabel: "已识别" | "未识别" | "存在待确认差异";
}

export interface SourcePreparationSection {
  title: string;
  summary: string;
  items: SourcePreparationFactItem[];
}

export function reviewRuleTitle(ruleCode: string): string {
  if (ruleCode === "SOURCE_PREPARATION") return SOURCE_PREPARATION_TITLE;
  return RULE_TITLES[ruleCode] ?? "其他合同一致性核对";
}

