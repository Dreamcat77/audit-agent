import {useMemo,useState,type ReactNode} from 'react';
import type {BudgetSourceEvidence} from '../../api/types';
import {budgetAttention,budgetDifferenceAmount,budgetMoney} from './budgetDifferences';
import {budgetComparisonRows,type BudgetComparisonLine,type ComparisonFilter} from './budgetComparisonRows';

export function BudgetSubjectComparison({lines,sourceButton}:{lines:BudgetComparisonLine[];sourceButton:(sources:BudgetSourceEvidence[],focus:string)=>ReactNode}) {
  const [keyword,setKeyword]=useState(''),[filter,setFilter]=useState<ComparisonFilter>('all');
  const [page,setPage]=useState(1),[size,setSize]=useState(15);
  const rows=useMemo(()=>budgetComparisonRows(lines,keyword,filter),[lines,keyword,filter]);
  const pages=Math.max(1,Math.ceil(rows.length/size)),current=Math.min(page,pages),offset=(current-1)*size;
  return <div className="budget-subject-comparison">
    <div className="budget-comparison-toolbar">
      <span className="budget-comparison-count">共 {lines.length} 项</span>
      <label><span className="sr-only">搜索预算科目</span><input aria-label="搜索预算科目" placeholder="搜索科目名称或编号" value={keyword} onChange={e=>{setKeyword(e.target.value);setPage(1);}}/></label>
      <label><span className="sr-only">科目核对范围</span><select aria-label="科目核对范围" value={filter} onChange={e=>{setFilter(e.target.value as ComparisonFilter);setPage(1);}}><option value="all">全部科目</option><option value="attention">需关注</option><option value="clear">未超批复</option></select></label>
    </div>
    <div className="budget-comparison-scroll" tabIndex={0} role="region" aria-label="预算科目明细表">
      <table className="budget-comparison-table">
        <caption className="sr-only">预算科目与批复比对，金额单位为元</caption>
        <thead><tr><th scope="col">序号</th><th scope="col">预算科目</th><th scope="col">申报金额（元）</th><th scope="col">批复金额（元）</th><th scope="col">差额（元）</th><th scope="col">核对结果</th><th scope="col">原文依据</th></tr></thead>
        <tbody>{rows.slice(offset,offset+size).map(({line,index},position)=>{
          const attention=budgetAttention(line),difference=budgetDifferenceAmount(line.declaredAmount,line.approvalAmount);
          const names=[...new Set(line.sources.map(s=>s.fileName))];
          return <tr key={index} data-budget-subject-row data-tone={attention.tone}>
            <td className="budget-comparison-index">{offset+position+1}</td>
            <th scope="row"><span className="budget-comparison-subject">{line.subject??line.subjectCode??'预算信息'}</span>
              <span className="budget-comparison-meta">{[line.subject&&line.subjectCode?line.subjectCode:null,line.year?`${line.year}`.replace(/(?:年度|年)?$/, '年度'):null,line.department].filter(Boolean).join(' · ')}</span>
              {line.sources.some(s=>s.field==='subjectLevel1Name')&&<span className="budget-comparison-meta">{['subjectLevel1Name','subjectLevel2Name','subjectLevel3Name'].flatMap(field=>[...new Set(line.sources.filter(s=>s.field===field).map(s=>s.value))]).join(' / ')}</span>}
              {line.sources.some(s=>s.field==='functionalClassificationName')&&<span className="budget-comparison-meta">功能分类：{[...new Set(line.sources.filter(s=>s.field==='functionalClassificationName').map(s=>s.value))].join('、')}</span>}
            </th>
            <td className="budget-comparison-money">{line.declaredAmount==null?'—':budgetMoney(line.declaredAmount)}</td>
            <td className="budget-comparison-money">{line.approvalAmount==null?'—':budgetMoney(line.approvalAmount)}
              {line.sources.some(s=>s.field==='thirdPartyReviewAmount')&&<span className="budget-comparison-meta">审定参考：{[...new Set(line.sources.filter(s=>s.field==='thirdPartyReviewAmount').map(s=>s.value))].join('、')}</span>}
            </td>
            <td className={`budget-comparison-money${line.status==='OVER_APPROVAL'?' budget-comparison-excess':''}`}>{difference==null?'—':budgetMoney(Number(difference))}</td>
            <td><span className={`budget-comparison-badge budget-comparison-badge-${attention.tone}`}>{attention.label}</span>
              <details className="budget-comparison-detail"><summary>核对说明</summary><p>{attention.headline}</p>{line.opinion&&line.opinion!==attention.headline&&<p>{line.opinion}</p>}<p>{attention.action}</p></details>
            </td>
            <td>{sourceButton(line.sources,`${line.subject??''} ${attention.headline}`)}
              {names.length>0&&<details className="budget-comparison-detail"><summary>来源材料（{names.length}）</summary><ul>{names.map(name=><li key={name}>{name}</li>)}</ul></details>}
            </td>
          </tr>;
        })}</tbody>
      </table>
      {rows.length===0&&<p className="budget-comparison-empty">暂无匹配科目</p>}
    </div>
    <nav aria-label="预算科目分页" className="budget-comparison-pagination">
      <span>共 {rows.length} 项 · 第 {current} / {pages} 页</span>
      <div><label>每页 <select aria-label="每页科目数量" value={size} onChange={e=>{setSize(Number(e.target.value));setPage(1);}}>{[15,30,50].map(n=><option key={n} value={n}>{n} 项</option>)}</select></label>
        <button type="button" disabled={current<=1} onClick={()=>setPage(current-1)}>上一页</button><button type="button" disabled={current>=pages} onClick={()=>setPage(current+1)}>下一页</button>
      </div>
    </nav>
  </div>;
}
