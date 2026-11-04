export const budgetDifferenceLabels:Record<string,string>={OVER_APPROVAL:'超支',UNAPPROVED_SUBJECT:'疑似错支',MISSING_DECLARATION:'疑似漏项'};
export function budgetDifference(status:string) {return budgetDifferenceLabels[status]??null;}
export function budgetDifferenceAmount(declared:number|null,approval:number|null) {
  if(declared==null||approval==null)return null;
  // Subtract decimal cents exactly; never use binary floating-point subtraction for money.
  const cents=(n:number)=>{const [whole,fraction]=n.toFixed(2).split('.');return BigInt(whole)*100n+BigInt(fraction);};
  const delta=cents(declared)-cents(approval),absolute=delta<0n?-delta:delta;
  return `${delta<0n?'-':''}${absolute/100n}.${String(absolute%100n).padStart(2,'0')}`;
}

type BudgetLine={status:string;declaredAmount:number|null;approvalAmount:number|null};
export const budgetMoney=(value:number)=>value.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
export function budgetAttention(line:BudgetLine):{label:string;headline:string;action:string;tone:'difference'|'uncertain'|'normal'} {
  switch(line.status){
    case 'OVER_APPROVAL': {
      const delta=budgetDifferenceAmount(line.declaredAmount,line.approvalAmount);
      return {label:'超支',headline:delta!=null?`超出批复 ${budgetMoney(Number(delta))} 元`:'申报金额超过批复金额',action:'请核对超出部分的申报依据，以及是否有更新的批复文件。',tone:'difference'};
    }
    case 'UNAPPROVED_SUBJECT':return {label:'疑似错支',headline:'申报了这一科目，但本批批复中没有找到',action:'请核对该科目是否在批复范围内，或是否还有批复材料未上传。',tone:'difference'};
    case 'MISSING_DECLARATION':return {label:'疑似漏项',headline:'批复中有这一科目，但本批申报中没有找到',action:'请核对是否漏报了这一科目，或是否还有申报材料未上传。',tone:'difference'};
    case 'WITHIN_APPROVAL':return {label:'未超批复',headline:'申报金额未超过对应批复金额',action:'该科目的金额对照未发现超支，供工作参考。',tone:'normal'};
    case 'CONFLICT':return {label:'金额或信息冲突',headline:'材料信息不一致，暂不能确认对照结果',action:'请核对项目、年度、部门及材料版本，确认适用金额。',tone:'uncertain'};
    case 'INVALID_AMOUNT':return {label:'金额待核对',headline:'金额格式或单位无法确认',action:'请核对原材料中的金额、单位和小数位数。',tone:'uncertain'};
    default:return {label:'信息待补充',headline:'现有信息不足，暂不能完成对照',action:'请核对对应的申报和批复材料是否完整。',tone:'uncertain'};
  }
}
