import type {BudgetMaterialReference} from '../../api/types';
import {budgetAttention} from './budgetDifferences.ts';

export type BudgetComparisonLine=BudgetMaterialReference['lines'][number];
export type ComparisonFilter='all'|'attention'|'clear';
const normal=(value:string)=>value.normalize('NFKC').replace(/\s+/g,'').toLocaleLowerCase();

/** Keep source order within each group and never merge genuinely different rows. */
export function budgetComparisonRows(lines:readonly BudgetComparisonLine[],keyword:string,filter:ComparisonFilter) {
  const term=normal(keyword);
  return lines.map((line,index)=>({line,index})).filter(({line})=>
    (!term||normal([line.subject,line.subjectCode,line.department,line.year].filter(Boolean).join(' ')).includes(term))
    &&(filter==='all'||filter==='clear'&&line.status==='WITHIN_APPROVAL'||filter==='attention'&&line.status!=='WITHIN_APPROVAL'))
    .sort((a,b)=>Number(budgetAttention(a.line).tone==='normal')-Number(budgetAttention(b.line).tone==='normal')||a.index-b.index);
}
