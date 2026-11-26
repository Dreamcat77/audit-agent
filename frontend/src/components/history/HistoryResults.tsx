import type {ComponentPropsWithoutRef,ReactNode} from 'react';
import {ChineseDateInput} from '../../app/components/ui/chinese-date-input';
import '../../styles/history-results.css';
export {historyStatus} from './historyPresentation';

export type HistoryTone='complete'|'processing'|'warning'|'failed'|'neutral';
export function historyDate(value?:string):string{
  const date=value?new Date(value):null;
  return !date||Number.isNaN(date.getTime())?'—':date.toLocaleString('zh-CN',{
    timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});
}

/** Controls share presentation; query state and API semantics belong to each module. */
export function HistoryFilterBar({keyword,date,onKeywordChange,onDateChange,onQuery,onReset,
  keywordLabel='项目名称',placeholder='输入项目名称关键词',maxLength=160}:{
  keyword:string;date:string;onKeywordChange:(value:string)=>void;onDateChange:(value:string)=>void;
  onQuery:()=>void;onReset:()=>void;keywordLabel?:string;placeholder?:string;maxLength?:number;
}){
  return <form className="history-filter" aria-label="历史结果筛选" onSubmit={event=>{event.preventDefault();onQuery();}}>
    <label className="history-filter-field history-filter-date"><span className="history-filter-label">上传日期</span>
      <ChineseDateInput ariaLabel="上传日期" value={date} onChange={onDateChange} className="history-input"/>
    </label>
    <label className="history-filter-field history-filter-keyword"><span className="history-filter-label">{keywordLabel}</span>
      <input className="history-input" value={keyword} maxLength={maxLength} placeholder={placeholder} onChange={event=>onKeywordChange(event.target.value)}/>
    </label>
    <div className="history-filter-actions"><button type="submit" className="history-button history-button-primary">查询</button>
      <button type="button" className="history-button" onClick={onReset}>重置</button></div>
  </form>;
}
export function HistoryList({label,loading,error,empty,children}:{label:string;loading?:boolean;error?:string|null;empty?:boolean;children:ReactNode}){
  return <div className="history-list" role="list" aria-label={label} aria-busy={loading||undefined}>
    {error?<p role="alert" className="history-list-message history-list-error">{error}</p>:loading?<p role="status" className="history-list-message">正在读取历史结果…</p>:empty?<p className="history-list-message">暂无符合条件的记录</p>:children}
  </div>;
}
export function HistoryRow({name,metadata,details,status,tone,statusDetail,onOpen,actionLabel='查看结果',extraActions,...props}:
  Omit<ComponentPropsWithoutRef<'article'>,'title'> & {name:string;metadata:ReactNode;details?:string;status:string;tone:HistoryTone;
    statusDetail?:ReactNode;onOpen:()=>void;actionLabel?:string;extraActions?:ReactNode}){
  return <article {...props} role="listitem" className={`history-row ${props.className??''}`}>
    <div className="history-row-main"><h3 className="history-row-name" title={name}>{name}</h3>
      <div className="history-row-meta">{metadata}</div>{details&&<p className="history-row-details" title={details}>{details}</p>}
    </div>
    <div className="history-row-state"><span className={`history-status history-status-${tone}`}>{status}</span>{statusDetail&&<span className="history-row-status-detail">{statusDetail}</span>}</div>
    <div className="history-row-actions"><button type="button" className="history-button" onClick={onOpen}>{actionLabel}</button>{extraActions}</div>
  </article>;
}
export function HistoryFooter({summary,children}:{summary:ReactNode;children:ReactNode}){
  return <footer className="history-footer"><span>{summary}</span><nav className="history-pagination" aria-label="历史结果分页">{children}</nav></footer>;
}
/** Cursor APIs do not expose total pages; show reachable pages without inventing totals. */
export function HistoryPagination({page,totalPages,hasNext,busy,onPageChange}:{page:number;totalPages?:number;hasNext:boolean;busy?:boolean;onPageChange:(page:number)=>void}){
  const end=totalPages??page+(hasNext?1:0);
  const first=Math.max(1,Math.min(page-2,end-4));
  const pages=Array.from({length:Math.min(5,end)},(_,i)=>first+i);
  return <><button type="button" className="history-button" disabled={busy||page<=1} onClick={()=>onPageChange(page-1)}>上一页</button>
    {pages.map(value=><button key={value} type="button" className="history-button" aria-label={`第 ${value} 页`} aria-current={page===value?'page':undefined}
      disabled={busy||page===value} onClick={()=>onPageChange(value)}>{value}</button>)}
    <button type="button" className="history-button" disabled={busy||!hasNext} onClick={()=>onPageChange(page+1)}>下一页</button></>;
}
