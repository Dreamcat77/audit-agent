import {useEffect,useState} from 'react';
import {listBudgetMaterialBatches} from '../../api';
import type {BudgetMaterialBatchHistoryPage} from '../../api';
import {HistoryFilterBar,HistoryList,HistoryRow,HistoryFooter,HistoryPagination,historyDate,historyStatus} from '../../components/history/HistoryResults';
import {budgetResultEndedWithoutResult,budgetResultPending} from './budgetResultState';
export {budgetResultStatus} from './budgetResultState';

export function BudgetResultHistory({onOpen}:{onOpen:(entry:BudgetMaterialBatchHistoryPage['records'][number])=>void}){
  const empty={fileName:'',uploadDate:''};
  const [draft,setDraft]=useState(empty),[query,setQuery]=useState({...empty,page:1});
  const page=query.page;
  const [data,setData]=useState<BudgetMaterialBatchHistoryPage|null>(null),[error,setError]=useState<string|null>(null);
  useEffect(()=>{
    let current=true;let timer:ReturnType<typeof setTimeout>|undefined;
    setData(null);setError(null);
    const load=()=>{void listBudgetMaterialBatches({page,size:20,
      ...(query.fileName?{fileName:query.fileName}:{}),
      ...(query.uploadDate?{createdFrom:query.uploadDate,createdTo:query.uploadDate}:{})}).then(result=>{
      if(current){setData(result);setError(null);if(result.records.some(row=>budgetResultPending(row.status)))timer=setTimeout(load,3000);}
    }).catch(()=>{if(current)setError('历史结果暂时无法读取，请稍后重新查询。');});};
    load();return()=>{current=false;clearTimeout(timer);};
  },[query]);
  const totalPages=Math.max(1,Math.ceil((data?.total??0)/20));
  const department=(code:string|null)=>({PROCUREMENT:'采购管理部',FINANCE:'财务部',GENERAL:'综合管理部'} as Record<string,string>)[code??'']??code;
  const batchName=(row:BudgetMaterialBatchHistoryPage['records'][number])=>{
    const preview=row.previewFileNames.join('、');
    return row.fileCount>row.previewFileNames.length?`${preview} 等 ${row.fileCount} 份材料`:preview||`本批材料（${row.fileCount} 份）`;
  };
  return <section aria-label="预算历史结果" className="history-results">
    <HistoryFilterBar keyword={draft.fileName} date={draft.uploadDate} keywordLabel="材料名称" placeholder="输入材料名称关键词" maxLength={255}
      onKeywordChange={fileName=>setDraft(old=>({...old,fileName}))} onDateChange={uploadDate=>setDraft(old=>({...old,uploadDate}))}
      onQuery={()=>setQuery({...draft,fileName:draft.fileName.trim(),page:1})} onReset={()=>{setDraft(empty);setQuery({...empty,page:1});}}/>
    <HistoryList label="预算历史记录" loading={!data&&!error} error={error} empty={!data?.records.length}>
      {data?.records.map(row=><HistoryRow key={row.batchId??row.taskId} data-budget-history-id={row.batchId??row.taskId}
        name={row.projectName||batchName(row)} details={row.projectName?batchName(row):undefined}
        metadata={<><span>{historyDate(row.createdAt)}</span><span>{row.fileCount} 份材料</span>{row.budgetYear&&<span>{row.budgetYear} 年度</span>}{department(row.departmentCode)&&<span>{department(row.departmentCode)}</span>}</>}
        status={historyStatus(row.status).label} tone={historyStatus(row.status).tone}
        actionLabel={budgetResultEndedWithoutResult(row.status)?'查看说明':row.batchId?'查看批次结果':'查看历史结果'} onOpen={()=>onOpen(row)}/>)}
    </HistoryList>
    {data&&<HistoryFooter summary={`共 ${data.total} 条记录 · 第 ${page} / ${totalPages} 页`}>
      <HistoryPagination page={page} totalPages={totalPages} hasNext={page<totalPages} onPageChange={page=>setQuery({...query,page})}/>
    </HistoryFooter>}
  </section>;
}
