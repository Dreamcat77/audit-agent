import { useEffect, useState } from 'react';
import { listBudgetMaterialBatches } from '../../api';
import type { BudgetMaterialBatchHistoryPage } from '../../api';
import { FileStack, ChevronLeft, ChevronRight } from 'lucide-react';
import { ChineseDateInput } from '../../app/components/ui/chinese-date-input';
import { budgetResultEndedWithoutResult, budgetResultPending, budgetResultStatus } from './budgetResultState';
export { budgetResultStatus } from './budgetResultState';

export function BudgetResultHistory({ onOpen }: { onOpen: (entry: BudgetMaterialBatchHistoryPage['records'][number]) => void }) {
  const empty = { fileName: '', uploadDate: '' };
  const [draft, setDraft] = useState(empty);
  const [query, setQuery] = useState({ ...empty, page: 1 });
  const page = query.page;
  const [data, setData] = useState<BudgetMaterialBatchHistoryPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let current = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setData(null); setError(null);
    const load = () => { void listBudgetMaterialBatches({ page, size: 20,
      ...(query.fileName ? { fileName: query.fileName } : {}),
      ...(query.uploadDate ? { createdFrom: query.uploadDate, createdTo: query.uploadDate } : {}) }).then(result => {
      if (current) {
        setData(result); setError(null);
        if (result.records.some(row => budgetResultPending(row.status))) timer = setTimeout(load, 3000);
      }
    }).catch(() => {
      if (current) setError('历史结果暂时无法读取，请稍后重新查询。');
    }); };
    load();
    return () => { current = false; clearTimeout(timer); };
  }, [query]);
  const totalPages = Math.max(1, Math.ceil((data?.total ?? 0) / 20));
  const firstPage = Math.max(1, Math.min(page - 2, totalPages - 4));
  const pages = Array.from({ length: Math.min(5, totalPages) }, (_, index) => firstPage + index);
  const buttonClass = 'inline-flex items-center justify-center gap-1 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-600 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40';
  const dateText = (value: string) => {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', {
      timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
  };
  const department = (code: string | null) => ({ PROCUREMENT: '采购管理部', FINANCE: '财务部', GENERAL: '综合管理部' } as Record<string,string>)[code ?? ''] ?? code ?? '-';
  const batchName = (row: BudgetMaterialBatchHistoryPage['records'][number]) => {
    const preview = row.previewFileNames.join('、');
    return row.fileCount > row.previewFileNames.length
      ? `${preview} 等 ${row.fileCount} 份材料`
      : preview || `本批材料（${row.fileCount} 份）`;
  };
  return <section aria-label="预算历史结果" className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm sm:p-7">
    <div className="flex flex-wrap items-start justify-between gap-3"><div><h2 className="text-lg font-bold text-slate-800">历史结果</h2><p className="mt-1 text-sm text-slate-500">按上传批次查看，同批材料的预算参考结果会合并展示。</p></div><span className="rounded-full bg-orange-50 px-3 py-1 text-xs text-orange-700">批次视图</span></div>
    <form aria-label="历史结果筛选" className="mt-5 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-slate-50 p-4"
      onSubmit={event => {
        event.preventDefault();
        setQuery({ ...draft, fileName: draft.fileName.trim(), page: 1 });
      }}>
      <label className="w-full text-sm text-slate-600 sm:w-48">上传日期
        <ChineseDateInput ariaLabel="上传日期" value={draft.uploadDate} onChange={value => setDraft({ ...draft, uploadDate: value })}
          className="mt-2 block w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800" />
      </label>
      <label className="w-full text-sm text-slate-600 sm:w-80">材料名称
        <input value={draft.fileName} maxLength={255} placeholder="输入材料名称关键字" onChange={event => setDraft({ ...draft, fileName: event.target.value })}
          className="mt-2 w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800" />
      </label>
      <button type="submit" className="rounded-lg border border-orange-200 bg-orange-50 px-5 py-2 text-sm font-medium text-orange-700 hover:bg-orange-100">查询</button>
      <button type="button" className={buttonClass} onClick={() => { setDraft(empty); setQuery({ ...empty, page: 1 }); }}>重置</button>
    </form>
    {error ? <p role="alert" className="mt-4 text-sm text-red-700">{error}</p>
      : !data ? <p role="status" className="mt-4 text-sm text-slate-500">正在读取历史结果...</p>
      : <>
        {data.records.length === 0 ? <p className="py-12 text-center text-sm text-slate-500">{query.fileName || query.uploadDate ? '没有符合筛选条件的批次' : '暂无历史结果'}</p>
          : <div className="mt-5 overflow-x-auto rounded-xl border border-slate-200"><table className="w-full text-left text-sm text-slate-600">
            <thead className="border-b border-slate-200 bg-slate-50"><tr>
              {['项目及材料', '上传时间', '预算年度', '申报部门', '处理状态', '操作'].map(label => <th key={label} className="whitespace-nowrap px-4 py-3 font-medium">{label}</th>)}
            </tr></thead>
            <tbody>{data.records.map(row => <tr key={row.batchId ?? row.taskId} className="border-b border-slate-100 last:border-b-0 hover:bg-slate-50">
              <td className="max-w-sm break-words px-4 py-4"><span className="flex items-start gap-2"><FileStack size={16} aria-hidden="true" className="mt-0.5 shrink-0 text-slate-400" /><span className="font-medium text-slate-800">{row.projectName || batchName(row)}</span></span>{row.projectName && <span className="mt-1 block text-xs text-slate-500">{batchName(row)}</span>}<span className="mt-1 block text-xs text-slate-400">{row.batchId ? `本批 ${row.fileCount} 份材料` : '早期单文件记录'}</span></td>
              <td className="whitespace-nowrap px-4 py-4 text-xs text-slate-500">{dateText(row.createdAt)}</td>
              <td className="px-3 py-3">{row.budgetYear || '-'}</td>
              <td className="px-3 py-3">{department(row.departmentCode)}</td>
              <td className="whitespace-nowrap px-3 py-3"><span className={`inline-flex rounded-full px-3 py-1 text-xs ${row.status === 'REVIEWED' ? 'bg-emerald-50 text-emerald-700' : row.status === 'PARTIAL' ? 'bg-amber-50 text-amber-700' : 'bg-slate-100 text-slate-600'}`}>{budgetResultStatus(row.status)}</span>{row.status === 'PARTIAL' && <span className="mt-1 block text-xs text-slate-500">部分材料未完成</span>}</td>
              <td className="whitespace-nowrap px-4 py-4"><button type="button" onClick={() => onOpen(row)} className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600 hover:border-orange-200 hover:bg-orange-50 hover:text-orange-700">{budgetResultEndedWithoutResult(row.status)?'查看说明':row.batchId?'查看批次结果':'查看历史结果'}</button></td>
            </tr>)}</tbody>
          </table></div>}
        <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-4 text-sm text-slate-500">
          <span>共 {data.total} 条历史记录，第 {page} / {totalPages} 页</span>
          <div className="flex items-center gap-2" role="navigation" aria-label="历史结果分页">
            <button type="button" disabled={page <= 1} onClick={() => setQuery({ ...query, page: page - 1 })} className={buttonClass}><ChevronLeft size={14} aria-hidden="true" />上一页</button>
            {pages.map(value => <button key={value} type="button" aria-label={`第 ${value} 页`} aria-current={value === page ? 'page' : undefined}
              onClick={() => setQuery({ ...query, page: value })} className={`h-9 min-w-9 rounded-lg border px-2 text-sm ${value === page ? 'border-orange-200 bg-orange-50 font-medium text-orange-700' : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'}`}>{value}</button>)}
            <button type="button" disabled={page >= totalPages} onClick={() => setQuery({ ...query, page: page + 1 })} className={buttonClass}>下一页<ChevronRight size={14} aria-hidden="true" /></button>
          </div>
        </div>
      </>}
  </section>;
}
