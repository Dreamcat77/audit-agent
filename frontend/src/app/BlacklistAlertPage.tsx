import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  AlertTriangle, Ban, Bell, CheckCircle2, ChevronDown, ChevronRight,
  Database, Download, Edit3, FileUp, Loader2, Plus, RefreshCw, Search, Settings2,
  ShieldAlert, Trash2, Users, X, Zap,
} from 'lucide-react';
import {
  addBlacklistEntry, addBlacklistRule, deleteBlacklistRule, removeBlacklistEntry,
  importBlacklistEntries, listBlacklistRules, processBlacklistAlert,
  queryBlacklistAlerts, queryBlacklistEntries, updateBlacklistEntry, updateBlacklistRule,
} from '../api';
import { apiUrl } from '../api/client';
import type {
  BlacklistAlertItem, BlacklistEntry, BlacklistEntryRequest, BlacklistRule, BlacklistRuleRequest,
} from '../api/types';
import { ConfirmDialog } from './components/ConfirmDialog';
import { enumLabel } from '../utils/enumLabels';

type Tab = 'alerts' | 'entries' | 'rules';
type AlertLevel = 'ALL' | 'CRITICAL' | 'WARNING' | 'INFO';
type AlertStatus = 'ALL' | 'PENDING' | 'RESOLVED' | 'IGNORED' | 'FALSE_POSITIVE';

const inputClass = 'w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-xs text-slate-800 outline-none transition placeholder:text-slate-400 focus:border-blue-400 focus:ring-2 focus:ring-blue-50';
const types = ['行业失信名单', '被执行人名单', '本单位黑名单', '公开信用名单'];

const levelMeta: Record<string, {
  label: string;
  text: string;
  border: string;
  background: string;
  soft: string;
  icon: string;
}> = {
  CRITICAL: {
    label: '严重',
    text: 'text-red-600',
    border: 'border-red-200',
    background: 'bg-red-50',
    soft: 'bg-red-100',
    icon: 'bg-red-100 text-red-600',
  },
  WARNING: {
    label: '警告',
    text: 'text-amber-600',
    border: 'border-amber-200',
    background: 'bg-amber-50',
    soft: 'bg-amber-100',
    icon: 'bg-amber-100 text-amber-600',
  },
  INFO: {
    label: '正常',
    text: 'text-blue-600',
    border: 'border-blue-200',
    background: 'bg-blue-50',
    soft: 'bg-blue-100',
    icon: 'bg-blue-100 text-blue-600',
  },
};

const statusMeta: Record<string, { label: string; className: string }> = {
  PENDING: { label: '待处理', className: 'border-amber-200 bg-amber-50 text-amber-700' },
  RESOLVED: { label: '已处理', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  PROCESSED: { label: '已处理', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  IGNORED: { label: '已忽略', className: 'border-slate-200 bg-slate-50 text-slate-500' },
  FALSE_POSITIVE: { label: '误报', className: 'border-slate-200 bg-slate-50 text-slate-500' },
};

function formatTime(value: string | null | undefined) {
  return value ? value.replace('T', ' ').replace(/\.\d+Z?$/, '').replace(/Z$/, '') : '-';
}

function formatDateRange(entry: BlacklistEntry) {
  return '长期有效';
}

function shortId(value: string | null | undefined, fallback = '-') {
  if (!value) return fallback;
  return value.length > 22 ? `${value.slice(0, 10)}...${value.slice(-8)}` : value;
}

function download(path: string) {
  const anchor = document.createElement('a');
  anchor.href = apiUrl(path);
  anchor.download = '';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}

function StatusBadge({ value, fallback = '-' }: { value: string | undefined; fallback?: string }) {
  const meta = statusMeta[value || ''] || { label: fallback === '-' ? enumLabel(value) : fallback, className: 'border-slate-200 bg-slate-50 text-slate-500' };
  return <span className={`inline-flex items-center rounded-md border px-2 py-1 text-[11px] font-medium ${meta.className}`}>{meta.label}</span>;
}

function LevelBadge({ value }: { value: string }) {
  const meta = levelMeta[value] || levelMeta.INFO;
  return <span className={`inline-flex items-center gap-1 rounded-md border px-2 py-1 text-[11px] font-medium ${meta.border} ${meta.background} ${meta.text}`}>
    <span className={`size-1.5 rounded-full ${value === 'CRITICAL' ? 'bg-red-500' : value === 'WARNING' ? 'bg-amber-500' : 'bg-blue-500'}`} />
    {meta.label}预警
  </span>;
}

export function BlacklistAlertPage() {
  const [tab, setTab] = useState<Tab>('alerts');
  const [alerts, setAlerts] = useState<BlacklistAlertItem[]>([]);
  const [entries, setEntries] = useState<BlacklistEntry[]>([]);
  const [rules, setRules] = useState<BlacklistRule[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [alertLevel, setAlertLevel] = useState<AlertLevel>('ALL');
  const [alertStatus, setAlertStatus] = useState<AlertStatus>('ALL');
  const [batchId, setBatchId] = useState('');
  const [supplierSearch, setSupplierSearch] = useState('');
  const [entrySearch, setEntrySearch] = useState('');
  const [entryType, setEntryType] = useState('');
  const [detail, setDetail] = useState<BlacklistAlertItem | null>(null);
  const [processTarget, setProcessTarget] = useState<BlacklistAlertItem | null>(null);
  const [removeTarget, setRemoveTarget] = useState<BlacklistEntry | null>(null);
  const [entryEditor, setEntryEditor] = useState<BlacklistEntry | 'new' | null>(null);
  const [ruleEditor, setRuleEditor] = useState<BlacklistRule | 'new' | null>(null);
  const [ruleDeleteTarget, setRuleDeleteTarget] = useState<BlacklistRule | null>(null);
  const [ruleDeleteLoading, setRuleDeleteLoading] = useState(false);
  const importRef = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      if (tab === 'alerts') {
        const page = await queryBlacklistAlerts({
          batchId: batchId.trim() || undefined,
          alertLevel: alertLevel === 'ALL' ? undefined : alertLevel,
          status: alertStatus === 'ALL' ? undefined : alertStatus,
          supplierName: supplierSearch.trim() || undefined,
          page: 1,
          size: 100,
        });
        setAlerts(page.items);
      } else if (tab === 'entries') {
        const page = await queryBlacklistEntries({
          keyword: entrySearch.trim() || undefined,
          blacklistType: entryType || undefined,
          active: true,
          page: 1,
          size: 100,
        });
        setEntries(page.items);
      } else {
        setRules(await listBlacklistRules());
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载数据失败');
    } finally {
      setLoading(false);
    }
  }, [alertLevel, alertStatus, batchId, entrySearch, entryType, supplierSearch, tab]);

  useEffect(() => { void load(); }, [load]);

  const handleImport = async (file: File) => {
    setLoading(true);
    setError(null);
    try {
      const result = await importBlacklistEntries(file);
      setNotice(`导入完成：成功 ${result.imported} 条，失败 ${result.failed} 条`);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : '导入失败');
    } finally {
      setLoading(false);
      if (importRef.current) importRef.current.value = '';
    }
  };

  const tabs: { id: Tab; label: string }[] = [
    { id: 'alerts', label: '命中告警' },
    { id: 'entries', label: '黑名单名单' },
    { id: 'rules', label: '规则配置' },
  ];

  return <div className="min-h-full bg-[#f6f8fb]">
    <section className="border-b border-slate-200 bg-white px-6 pt-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <Ban size={19} className="text-red-600" />
          <h1 className="text-lg font-semibold text-slate-900">供应商黑名单</h1>
        </div>
        <div className="flex items-center gap-2">
          <button type="button" onClick={() => void load()} title="刷新当前列表" aria-label="刷新当前列表" className="inline-flex size-9 items-center justify-center rounded-md border border-slate-200 bg-white text-slate-600 hover:border-blue-300 hover:text-blue-700">
            <RefreshCw size={15} className={loading ? 'animate-spin' : ''} />
          </button>
          <button onClick={() => { setTab('entries'); setEntryEditor('new'); }} className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-3 py-2 text-xs font-medium text-white hover:bg-blue-700">
            <Plus size={14} />新增名单
          </button>
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-1">
        <div className="flex items-center gap-1">
          {tabs.map(item => <button key={item.id} onClick={() => setTab(item.id)} className={`relative px-4 py-4 text-sm font-medium transition ${tab === item.id ? 'text-blue-600' : 'text-slate-500 hover:text-slate-800'}`}>
            {item.label}
            {tab === item.id && <span className="absolute inset-x-2 bottom-0 h-0.5 bg-blue-600" />}
          </button>)}
        </div>
        <div className="ml-auto flex items-center gap-2 py-2">
          {tab === 'alerts' && <div className="relative">
            <Search size={15} className="absolute left-3 top-2.5 text-slate-400" />
            <input value={supplierSearch} onChange={e => setSupplierSearch(e.target.value)} placeholder="搜索供应商或项目..." className={`${inputClass} w-60 pl-9`} />
          </div>}
          {tab === 'entries' && <div className="relative">
            <Search size={15} className="absolute left-3 top-2.5 text-slate-400" />
            <input value={entrySearch} onChange={e => setEntrySearch(e.target.value)} placeholder="搜索供应商或统一社会信用代码..." className={`${inputClass} w-72 pl-9`} />
          </div>}
          {tab !== 'rules' && <button onClick={() => download(tab === 'alerts' ? '/api/v1/blacklist/alerts/export' : '/api/v1/blacklist/entries/export')} className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-2 text-xs font-medium text-slate-600 hover:border-blue-300 hover:text-blue-700">
            <Download size={14} />导出记录
          </button>}
        </div>
      </div>
    </section>

    <main className="space-y-4 px-6 py-4">
      {notice && <div className="flex items-center justify-between rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-700">{notice}<button onClick={() => setNotice(null)} title="关闭"><X size={14} /></button></div>}
      {error && <div className="flex items-center gap-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700"><AlertTriangle size={14} />{error}<button className="ml-auto" onClick={() => setError(null)} title="关闭"><X size={14} /></button></div>}

      {tab === 'alerts' && <AlertsView
        alerts={alerts} loading={loading} batchId={batchId} level={alertLevel} status={alertStatus}
        onBatchId={setBatchId} onLevel={setAlertLevel} onStatus={setAlertStatus}
        onView={setDetail} onProcess={setProcessTarget}
      />}
      {tab === 'entries' && <EntriesView
        entries={entries} loading={loading} type={entryType} onType={setEntryType}
        onAdd={() => setEntryEditor('new')} onEdit={setEntryEditor}
        onRemove={setRemoveTarget}
        onImport={() => importRef.current?.click()}
      />}
      {tab === 'rules' && <RulesView
        rules={rules} loading={loading} onAdd={() => setRuleEditor('new')} onEdit={setRuleEditor}
        onDelete={setRuleDeleteTarget}
      />}
    </main>

    <input ref={importRef} type="file" accept=".xls,.xlsx" className="hidden" onChange={event => {
      const file = event.target.files?.[0];
      if (file) void handleImport(file);
    }} />

    {detail && <AlertDetailDialog alert={detail} onClose={() => setDetail(null)} />}
    {processTarget && <ProcessDialog alert={processTarget} onClose={() => setProcessTarget(null)} onDone={async () => { setProcessTarget(null); await load(); }} />}
    {removeTarget && <RemoveEntryDialog
      entry={removeTarget}
      onClose={() => setRemoveTarget(null)}
      onDone={async reason => {
        await removeBlacklistEntry(removeTarget.id, reason);
        setRemoveTarget(null);
        setNotice(`已移除黑名单：${removeTarget.supplierName}`);
        await load();
      }}
    />}
    {entryEditor && <EntryDialog entry={entryEditor} onClose={() => setEntryEditor(null)} onDone={async () => { setEntryEditor(null); await load(); }} />}
    {ruleEditor && <RuleDialog rule={ruleEditor} onClose={() => setRuleEditor(null)} onDone={async () => { setRuleEditor(null); await load(); }} />}
    <ConfirmDialog
      open={ruleDeleteTarget !== null}
      title="确认删除"
      description={ruleDeleteTarget ? <>确认删除报警规则“{ruleDeleteTarget.ruleName}”吗？删除后不可恢复。</> : ""}
      destructive
      loading={ruleDeleteLoading}
      onOpenChange={open => { if (!open && !ruleDeleteLoading) setRuleDeleteTarget(null); }}
      onConfirm={() => {
        if (!ruleDeleteTarget) return;
        const target = ruleDeleteTarget;
        setRuleDeleteLoading(true);
        void deleteBlacklistRule(target.id)
          .then(async () => {
            setRuleDeleteTarget(null);
            await load();
          })
          .catch(err => setError(err instanceof Error ? err.message : '删除规则失败'))
          .finally(() => setRuleDeleteLoading(false));
      }}
    />
  </div>;
}

function AlertsView(props: {
  alerts: BlacklistAlertItem[];
  loading: boolean;
  batchId: string;
  level: AlertLevel;
  status: AlertStatus;
  onBatchId: (value: string) => void;
  onLevel: (value: AlertLevel) => void;
  onStatus: (value: AlertStatus) => void;
  onView: (alert: BlacklistAlertItem) => void;
  onProcess: (alert: BlacklistAlertItem) => void;
}) {
  const [expandedId, setExpandedId] = useState<string | null>(null);
  return <section className="space-y-3">
    <div className="grid grid-cols-1 items-center gap-2 sm:grid-cols-[minmax(180px,1fr)_140px_140px_auto]">
      <input value={props.batchId} onChange={e => props.onBatchId(e.target.value)} placeholder="批次ID" className={inputClass} />
      <select value={props.level} onChange={e => props.onLevel(e.target.value as AlertLevel)} className={inputClass}>
        <option value="ALL">全部级别</option><option value="CRITICAL">严重</option><option value="WARNING">警告</option><option value="INFO">正常</option>
      </select>
      <select value={props.status} onChange={e => props.onStatus(e.target.value as AlertStatus)} className={inputClass}>
        <option value="ALL">全部状态</option><option value="PENDING">待处理</option><option value="RESOLVED">已处理</option><option value="IGNORED">已忽略</option><option value="FALSE_POSITIVE">误报</option>
      </select>
      <span className="text-xs text-slate-400 sm:justify-self-end">共 {props.alerts.length} 条告警</span>
    </div>
    {props.loading && props.alerts.length === 0 && <EmptyState loading />}
    {!props.loading && props.alerts.length === 0 && <EmptyState title="暂无命中告警" description="当前筛选条件下没有匹配的黑名单告警记录" />}
    <div className="space-y-3">
      {props.alerts.map(alert => {
        const meta = levelMeta[alert.alertLevel] || levelMeta.INFO;
        const expanded = expandedId === alert.id;
        return <article key={alert.id} className={`overflow-hidden rounded-lg border ${meta.border} ${meta.background}`}>
          <button className="flex w-full items-center gap-4 px-5 py-4 text-left" onClick={() => setExpandedId(expanded ? null : alert.id)}>
            <span className={`flex size-11 shrink-0 items-center justify-center rounded-lg ${meta.icon}`}><Ban size={21} /></span>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className={`text-sm font-semibold ${meta.text}`}>{meta.label}黑名单命中</span>
                <span className="font-mono text-xs text-slate-500">{shortId(alert.id, 'BLH-记录')}</span>
                <LevelBadge value={alert.alertLevel} />
              </div>
              <p className="mt-2 truncate text-base font-semibold text-slate-900">{alert.supplierName}</p>
              <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500">
                <span>{alert.blacklistType}</span>
                <span>批次 {shortId(alert.batchId)}</span>
                {alert.rowNumber ? <span>第 {alert.rowNumber} 行</span> : null}
                <span>{formatTime(alert.createdTime)}</span>
              </div>
            </div>
            <div className="hidden shrink-0 items-end gap-2 sm:flex sm:flex-col">
              <StatusBadge value={alert.status} />
              <span className="inline-flex items-center gap-1 text-[11px] text-slate-500"><Bell size={12} />告警已推送</span>
            </div>
            {expanded ? <ChevronDown size={18} className="shrink-0 text-slate-500" /> : <ChevronRight size={18} className="shrink-0 text-slate-500" />}
          </button>
          <div className="flex items-center gap-2 border-t border-black/5 px-5 pb-3 sm:hidden"><StatusBadge value={alert.status} /><span className="text-[11px] text-slate-500"><Bell size={12} className="mr-1 inline" />告警已推送</span></div>
          {expanded && <div className="border-t border-black/5 bg-white/65 px-5 py-4">
            <div className="grid gap-3 text-xs sm:grid-cols-2">
              <InfoLine label="供应商统一信用代码" value={alert.supplierCode || '-'} mono />
              <InfoLine label="匹配方式 / 分数" value={`${enumLabel(alert.matchType)} / ${alert.matchScore ?? '-'}`} />
              <InfoLine label="命中原因" value={alert.blacklistReason || '-'} />
              <InfoLine label="处理人" value={alert.resolvedBy || alert.handler || '系统自动'} />
              <InfoLine label="处理备注" value={alert.remark || alert.handleRemark || '-'} />
              <InfoLine label="报警编号" value={alert.id} mono />
            </div>
            <div className="mt-4 flex justify-end gap-2">
              <button onClick={() => props.onView(alert)} className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600 hover:border-blue-300 hover:text-blue-700"><ShieldAlert size={13} />查看详情</button>
              {alert.status === 'PENDING' && <button onClick={() => props.onProcess(alert)} className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-3 py-2 text-xs text-white hover:bg-blue-700"><CheckCircle2 size={13} />处理告警</button>}
            </div>
          </div>}
        </article>;
      })}
    </div>
  </section>;
}

function EntriesView(props: {
  entries: BlacklistEntry[];
  loading: boolean;
  type: string;
  onType: (value: string) => void;
  onAdd: () => void;
  onEdit: (entry: BlacklistEntry) => void;
  onRemove: (entry: BlacklistEntry) => void;
  onImport: () => void;
}) {
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const sourceCounts = props.entries.reduce<Record<string, number>>((result, entry) => {
    result[entry.source] = (result[entry.source] || 0) + 1;
    return result;
  }, {});
  const sources = [
    { label: '省政府采购黑名单', source: '省政府采购黑名单', detail: '省级数据源', count: sourceCounts['省政府采购黑名单'] || 0 },
    { label: '市纪委通报黑名单', source: '市纪委通报黑名单', detail: '市级数据源', count: sourceCounts['市纪委通报黑名单'] || 0 },
    { label: '本单位内部录入', source: '内部录入', detail: '手工维护', count: sourceCounts['内部录入'] || 0 },
  ];
  return <section className="space-y-4">
    <div className="grid grid-cols-1 gap-3 lg:grid-cols-3">
      {sources.map(source => <div key={source.label} className="flex items-center gap-3 rounded-lg border border-slate-200 bg-white px-4 py-4">
        <span className="flex size-9 items-center justify-center rounded-md bg-blue-50 text-blue-600"><Database size={18} /></span>
        <div className="min-w-0 flex-1"><p className="truncate text-sm font-semibold text-slate-800">{source.label}</p><p className="mt-1 text-xs text-slate-500">收录 {source.count} 家 · {source.detail}</p></div>
        <span className="rounded-md border border-emerald-200 bg-emerald-50 px-2 py-1 text-[11px] font-medium text-emerald-700">正常</span>
      </div>)}
    </div>
    <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
      <div className="flex flex-wrap items-center gap-2 border-b border-slate-200 px-5 py-4">
        <div className="flex items-center gap-2"><ShieldAlert size={17} className="text-red-500" /><h2 className="text-sm font-semibold text-slate-900">供应商黑名单条目</h2><span className="text-xs text-slate-400">{props.entries.length} 家</span></div>
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <select value={props.type} onChange={e => props.onType(e.target.value)} className={`${inputClass} w-40`}><option value="">全部黑名单类型</option>{types.map(type => <option key={type}>{type}</option>)}</select>
          <button onClick={props.onImport} className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600 hover:border-blue-300 hover:text-blue-700"><FileUp size={14} />导入</button>
          <button onClick={props.onAdd} className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-3 py-2 text-xs text-white hover:bg-blue-700"><Plus size={14} />新增条目</button>
        </div>
      </div>
      {props.loading && props.entries.length === 0 && <EmptyState loading />}
      {!props.loading && props.entries.length === 0 && <EmptyState title="暂无黑名单条目" description="可以手工录入或导入 Excel 数据" />}
      <div className="divide-y divide-slate-100">
        {props.entries.map(entry => {
          const expanded = expandedId === entry.id;
          return <div key={entry.id}>
            <button onClick={() => setExpandedId(expanded ? null : entry.id)} className="flex w-full items-center gap-3 px-5 py-4 text-left hover:bg-slate-50">
              <span className={`flex size-8 shrink-0 items-center justify-center rounded-md text-xs font-semibold ${entry.active ? 'bg-red-50 text-red-600' : 'bg-slate-100 text-slate-500'}`}>{entry.active ? '红' : '—'}</span>
              <div className="min-w-0 flex-1"><p className="truncate text-sm font-semibold text-slate-900">{entry.supplierName}</p><p className="mt-1 text-xs text-slate-500">{entry.blacklistType} · {enumLabel(entry.source)}</p></div>
              <span className="hidden max-w-44 truncate font-mono text-xs text-slate-500 lg:block">{entry.supplierCode || entry.creditCode || '-'}</span>
              <span className="hidden text-xs text-slate-500 xl:block">{formatDateRange(entry)}</span>
              <span className="hidden rounded-md border border-red-200 bg-red-50 px-2 py-1 text-xs font-medium text-red-600 md:block">{entry.active ? '有效' : '已停用'}</span>
              {expanded ? <ChevronDown size={17} className="text-slate-400" /> : <ChevronRight size={17} className="text-slate-400" />}
            </button>
            {expanded && <div className="grid gap-4 border-t border-slate-100 bg-slate-50/70 px-5 py-4 sm:grid-cols-2">
              <div><p className="text-xs font-semibold text-slate-600">录入原因</p><p className="mt-2 text-xs leading-5 text-slate-700">{entry.reason || '未提供'}</p></div>
              <div><p className="text-xs font-semibold text-slate-600">条目信息</p><div className="mt-2 space-y-1 text-xs text-slate-600"><p>数据来源：{enumLabel(entry.source)}</p><p>录入人：{entry.createdBy || '-'}</p><p>有效期：{formatDateRange(entry)}</p></div></div>
              <div className="flex gap-2 sm:col-span-2"><button onClick={() => props.onEdit(entry)} className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600 hover:border-blue-300 hover:text-blue-700"><Edit3 size={13} />编辑条目</button><button onClick={() => props.onRemove(entry)} className="inline-flex items-center gap-1.5 rounded-md border border-red-200 bg-white px-3 py-2 text-xs text-red-600 hover:bg-red-50"><Trash2 size={13} />移除黑名单</button></div>
            </div>}
          </div>;
        })}
      </div>
    </div>
  </section>;
}

function RulesView(props: { rules: BlacklistRule[]; loading: boolean; onAdd: () => void; onEdit: (rule: BlacklistRule) => void; onDelete: (rule: BlacklistRule) => void }) {
  return <section className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1.05fr)_minmax(360px,0.95fr)]">
    <div>
      <div className="mb-3 flex items-center justify-between"><div className="flex items-center gap-2"><Zap size={18} className="text-blue-500" /><h2 className="text-base font-semibold text-slate-900">报警规则配置</h2></div><button onClick={props.onAdd} className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-3 py-2 text-xs text-white hover:bg-blue-700"><Plus size={14} />新增规则</button></div>
      <div className="space-y-3">
        {props.loading && props.rules.length === 0 && <EmptyState loading />}
        {!props.loading && props.rules.length === 0 && <EmptyState title="暂无报警规则" description="新增规则后会显示在这里" />}
        {props.rules.map((rule, index) => {
          const meta = levelMeta[rule.alertLevel] || levelMeta.INFO;
          return <div key={rule.id} className={`rounded-lg border bg-white p-4 ${meta.border}`}>
            <div className="flex items-start gap-3">
              <span className={`flex size-7 shrink-0 items-center justify-center rounded-md text-xs font-semibold ${meta.soft} ${meta.text}`}>{index + 1}</span>
              <div className="min-w-0 flex-1"><div className="flex flex-wrap items-center gap-2"><p className="text-sm font-semibold text-slate-900">{rule.ruleName}</p><span className={`rounded-full px-2 py-0.5 text-[11px] ${rule.active ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 text-slate-500'}`}>{rule.active ? '已启用' : '已停用'}</span></div><p className="mt-3 text-xs"><span className="font-medium text-blue-600">触发：</span><span className="text-slate-600">{rule.ruleType} · {rule.blacklistTypes || '供应商命中黑名单'}</span></p><p className="mt-1 text-xs"><span className="font-medium text-amber-600">动作：</span><span className="text-slate-600">{rule.autoResolve ? '自动标记并完成处置' : `${meta.label}预警 · 等待人工处理`}</span></p></div>
              <div className="flex gap-1"><button onClick={() => props.onEdit(rule)} title="编辑规则" className="rounded-md p-1.5 text-slate-400 hover:bg-blue-50 hover:text-blue-600"><Settings2 size={15} /></button><button onClick={() => props.onDelete(rule)} title="删除规则" className="rounded-md p-1.5 text-slate-400 hover:bg-red-50 hover:text-red-600"><Trash2 size={15} /></button></div>
            </div>
          </div>;
        })}
      </div>
    </div>
    <div className="space-y-4">
      <div className="flex items-center gap-2"><Bell size={18} className="text-blue-500" /><h2 className="text-base font-semibold text-slate-900">告警推送配置</h2></div>
      <div className="rounded-lg border border-slate-200 bg-white p-5">
        <PushConfig title="红色命中" detail="系统通知 + 短信" tone="red" recipients={['审计主任', '系统管理员', '经办人']} />
        <PushConfig title="黄色命中" detail="系统通知" tone="yellow" recipients={['经办人', '直属领导']} />
        <PushConfig title="日报发送" detail="邮件" tone="blue" recipients={['采购管理人员', '审计主任']} last />
      </div>
      <div className="rounded-lg border border-slate-200 bg-white p-5">
        <div className="mb-3 flex items-center justify-between"><h3 className="text-sm font-semibold text-slate-900">数据源对接状态</h3><span className="text-xs text-slate-400">当前环境</span></div>
        {['省政府采购黑名单 API', '市纪委通报库 API', '全国企业信用信息系统'].map((source, index) => <div key={source} className="flex items-center gap-3 border-b border-slate-100 py-3 last:border-0"><span className="size-2 rounded-full bg-emerald-500" /><span className="flex-1 text-xs text-slate-700">{source}</span><span className="text-xs text-slate-400">{index === 0 ? '已配置' : '未同步'}</span></div>)}
      </div>
    </div>
  </section>;
}

function PushConfig({ title, detail, tone, recipients, last = false }: { title: string; detail: string; tone: 'red' | 'yellow' | 'blue'; recipients: string[]; last?: boolean }) {
  const color = tone === 'red' ? 'text-red-600 border-blue-200 bg-blue-50' : tone === 'yellow' ? 'text-amber-600 border-blue-200 bg-blue-50' : 'text-blue-600 border-blue-200 bg-blue-50';
  return <div className={`pb-4 ${last ? '' : 'mb-4 border-b border-slate-100'}`}><div className="flex items-center justify-between"><h3 className="text-sm font-semibold text-slate-800">{title}</h3><span className="text-xs text-slate-500">{detail}</span></div><div className="mt-3 flex flex-wrap gap-2">{recipients.map(item => <span key={item} className={`rounded-full border px-3 py-1 text-xs ${color}`}>{item}</span>)}<span className="rounded-full border border-dashed border-slate-300 px-3 py-1 text-xs text-slate-400">+ 添加</span></div></div>;
}

function InfoLine({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return <div><p className="text-[11px] text-slate-500">{label}</p><p className={`mt-1 break-all text-slate-800 ${mono ? 'font-mono' : ''}`}>{value}</p></div>;
}

function EmptyState({ loading = false, title = '加载中', description = '' }: { loading?: boolean; title?: string; description?: string }) {
  return <div className="rounded-lg border border-dashed border-slate-200 bg-white px-5 py-14 text-center text-slate-400">{loading ? <Loader2 size={19} className="mx-auto animate-spin" /> : <Database size={22} className="mx-auto text-slate-300" />}<p className="mt-3 text-sm text-slate-500">{loading ? '正在加载...' : title}</p>{description && <p className="mt-1 text-xs text-slate-400">{description}</p>}</div>;
}

function AlertDetailDialog({ alert, onClose }: { alert: BlacklistAlertItem; onClose: () => void }) {
  const rows = [
    ['报警编号', alert.id], ['批次 / 行号', `${alert.batchId} / ${alert.rowNumber ?? '-'}`],
    ['供应商名称', alert.supplierName], ['统一信用代码', alert.supplierCode || '-'],
    ['黑名单类型', alert.blacklistType], ['黑名单原因', alert.blacklistReason],
    ['匹配方式 / 分数', `${enumLabel(alert.matchType)} / ${alert.matchScore ?? '-'}`],
    ['报警级别', levelMeta[alert.alertLevel]?.label || enumLabel(alert.alertLevel)],
    ['处理状态', statusMeta[alert.status]?.label || enumLabel(alert.status)],
    ['处理人', alert.resolvedBy || alert.handler || '-'], ['处理备注', alert.remark || alert.handleRemark || '-'],
    ['报警时间', formatTime(alert.createdTime)],
  ];
  return <Dialog title="黑名单命中详情" icon={<EyeIcon />} onClose={onClose}><dl className="space-y-3">{rows.map(([label, value]) => <div key={label} className="flex gap-4 text-xs"><dt className="w-28 shrink-0 text-slate-500">{label}</dt><dd className="min-w-0 break-all text-slate-900">{value}</dd></div>)}</dl></Dialog>;
}

function EyeIcon() {
  return <ShieldAlert size={16} className="text-blue-600" />;
}

function ProcessDialog({ alert, onClose, onDone }: { alert: BlacklistAlertItem; onClose: () => void; onDone: () => Promise<void> }) {
  const [handler, setHandler] = useState('');
  const [remark, setRemark] = useState('');
  const [action, setAction] = useState<'RESOLVED' | 'IGNORED' | 'FALSE_POSITIVE'>('RESOLVED');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async () => {
    if (!handler.trim() || saving) return;
    setSaving(true);
    setError(null);
    try {
      await processBlacklistAlert(alert.id, { handler: handler.trim(), remark: remark.trim() || undefined, action });
      await onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : '处理失败');
    } finally {
      setSaving(false);
    }
  };
  return <Dialog title="处理报警" icon={<CheckCircle2 size={16} className="text-emerald-600" />} onClose={onClose}><div className="space-y-4">
    <div className="rounded-md border border-red-100 bg-red-50 px-3 py-2 text-xs text-red-700">{alert.supplierName} · {alert.blacklistReason}</div>
    <div className="flex gap-2">{(['RESOLVED', 'FALSE_POSITIVE', 'IGNORED'] as const).map(value => <button key={value} onClick={() => setAction(value)} className={`flex-1 rounded-md border px-2 py-2 text-xs ${action === value ? 'border-blue-300 bg-blue-50 text-blue-700' : 'border-slate-200 text-slate-500'}`}>{value === 'RESOLVED' ? '已处理' : value === 'FALSE_POSITIVE' ? '误报' : '忽略'}</button>)}</div>
    <label className="block text-xs text-slate-600">处理人<input value={handler} onChange={e => setHandler(e.target.value)} className={`${inputClass} mt-1`} placeholder="请输入处理人" /></label>
    <label className="block text-xs text-slate-600">备注<textarea value={remark} onChange={e => setRemark(e.target.value)} className={`${inputClass} mt-1 resize-none`} rows={3} /></label>
    {error && <p className="text-xs text-red-700">{error}</p>}
    <DialogActions onClose={onClose} onSubmit={() => void submit()} disabled={!handler.trim() || saving} submitting={saving} label="确认提交" />
  </div></Dialog>;
}

function EntryDialog({ entry, onClose, onDone }: { entry: BlacklistEntry | 'new'; onClose: () => void; onDone: () => Promise<void> }) {
  const existing = entry === 'new' ? null : entry;
  const [form, setForm] = useState<BlacklistEntryRequest>({
    supplierName: existing?.supplierName || '', supplierCode: existing?.supplierCode || existing?.creditCode || '',
    blacklistType: existing?.blacklistType || '本单位黑名单', reason: existing?.reason || '', source: existing?.source || '内部录入',
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (key: keyof BlacklistEntryRequest, value: string) => setForm(old => ({ ...old, [key]: value }));
  const submit = async () => {
    if (!form.supplierName.trim() || !form.reason.trim() || saving) return;
    setSaving(true);
    setError(null);
    try {
      if (existing) await updateBlacklistEntry(existing.id, form);
      else await addBlacklistEntry(form);
      await onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };
  return <Dialog title={existing ? '编辑黑名单条目' : '新增黑名单条目'} icon={<Ban size={16} className="text-red-600" />} onClose={onClose}><div className="grid grid-cols-2 gap-3">
    <label className="text-xs text-slate-600">供应商名称<input value={form.supplierName} onChange={e => set('supplierName', e.target.value)} className={`${inputClass} mt-1`} /></label>
    <label className="text-xs text-slate-600">统一社会信用代码<input value={form.supplierCode || ''} onChange={e => set('supplierCode', e.target.value)} className={`${inputClass} mt-1`} /></label>
    <label className="text-xs text-slate-600">黑名单类型<select value={form.blacklistType} onChange={e => set('blacklistType', e.target.value)} className={`${inputClass} mt-1`}>{types.map(type => <option key={type}>{type}</option>)}</select></label>
    <label className="text-xs text-slate-600">数据来源<input value={form.source} onChange={e => set('source', e.target.value)} className={`${inputClass} mt-1`} /></label>
    <div className="col-span-2 rounded-md border border-blue-100 bg-blue-50 px-3 py-2 text-xs text-blue-700">入库后长期有效，仅支持人工移除。</div>
    <label className="col-span-2 text-xs text-slate-600">列入原因<textarea value={form.reason} onChange={e => set('reason', e.target.value)} className={`${inputClass} mt-1 resize-none`} rows={3} /></label>
  </div>{error && <p className="mt-3 text-xs text-red-700">{error}</p>}<DialogActions onClose={onClose} onSubmit={() => void submit()} disabled={!form.supplierName.trim() || !form.reason.trim() || saving} submitting={saving} label="保存条目" /></Dialog>;
}

function RemoveEntryDialog({
  entry, onClose, onDone,
}: { entry: BlacklistEntry; onClose: () => void; onDone: (reason: string) => Promise<void> }) {
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async () => {
    if (!reason.trim() || saving) return;
    setSaving(true);
    setError(null);
    try {
      await onDone(reason.trim());
    } catch (err) {
      setError(err instanceof Error ? err.message : '移除失败');
    } finally {
      setSaving(false);
    }
  };
  return <Dialog title="移除黑名单" icon={<Trash2 size={16} className="text-red-600" />} onClose={onClose}>
    <div className="space-y-4">
      <div className="rounded-md border border-red-100 bg-red-50 px-3 py-2 text-xs leading-5 text-red-700">
        将停用“{entry.supplierName}”的黑名单状态，历史数据不会删除。
      </div>
      <label className="block text-xs text-slate-600">移除原因<textarea value={reason} onChange={e => setReason(e.target.value)} className={`${inputClass} mt-1 resize-none`} rows={4} placeholder="请填写人工移除原因" /></label>
      {error && <p className="text-xs text-red-700">{error}</p>}
      <DialogActions onClose={onClose} onSubmit={() => void submit()} disabled={!reason.trim() || saving} submitting={saving} label="确认移除" />
    </div>
  </Dialog>;
}

function RuleDialog({ rule, onClose, onDone }: { rule: BlacklistRule | 'new'; onClose: () => void; onDone: () => Promise<void> }) {
  const existing = rule === 'new' ? null : rule;
  const [form, setForm] = useState<BlacklistRuleRequest>({
    ruleName: existing?.ruleName || '', ruleType: existing?.ruleType || 'CATEGORY',
    blacklistTypes: existing?.blacklistTypes || '', alertLevel: (existing?.alertLevel as 'CRITICAL' | 'WARNING' | 'INFO') || 'WARNING',
    autoResolve: existing?.autoResolve || false,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async () => {
    if (!form.ruleName.trim() || saving) return;
    setSaving(true);
    setError(null);
    try {
      if (existing) await updateBlacklistRule(existing.id, form);
      else await addBlacklistRule(form);
      await onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };
  return <Dialog title={existing ? '编辑报警规则' : '新增报警规则'} icon={<Settings2 size={16} className="text-blue-600" />} onClose={onClose}><div className="space-y-3">
    <label className="block text-xs text-slate-600">规则名称<input value={form.ruleName} onChange={e => setForm(old => ({ ...old, ruleName: e.target.value }))} className={`${inputClass} mt-1`} /></label>
    <div className="grid grid-cols-2 gap-3"><label className="text-xs text-slate-600">规则类型<input value={form.ruleType} onChange={e => setForm(old => ({ ...old, ruleType: e.target.value }))} className={`${inputClass} mt-1`} /></label><label className="text-xs text-slate-600">报警级别<select value={form.alertLevel} onChange={e => setForm(old => ({ ...old, alertLevel: e.target.value as 'CRITICAL' | 'WARNING' | 'INFO' }))} className={`${inputClass} mt-1`}><option value="CRITICAL">严重</option><option value="WARNING">警告</option><option value="INFO">正常</option></select></label></div>
    <label className="block text-xs text-slate-600">适用黑名单类型<input value={form.blacklistTypes || ''} onChange={e => setForm(old => ({ ...old, blacklistTypes: e.target.value }))} className={`${inputClass} mt-1`} placeholder="逗号分隔，留空表示全部" /></label>
    <label className="flex items-center gap-2 text-xs text-slate-600"><input type="checkbox" checked={form.autoResolve} onChange={e => setForm(old => ({ ...old, autoResolve: e.target.checked }))} />命中后自动标记已处理</label>
    {error && <p className="text-xs text-red-700">{error}</p>}
    <DialogActions onClose={onClose} onSubmit={() => void submit()} disabled={!form.ruleName.trim() || saving} submitting={saving} label="保存规则" />
  </div></Dialog>;
}

function Dialog({ title, icon, onClose, children }: { title: string; icon: ReactNode; onClose: () => void; children: ReactNode }) {
  return <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/35 p-4" onClick={onClose}><div className="max-h-[90vh] w-full max-w-xl overflow-y-auto rounded-lg border border-slate-200 bg-white shadow-xl" onClick={e => e.stopPropagation()}>
    <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4"><div className="flex items-center gap-2 text-sm font-semibold text-slate-900">{icon}{title}</div><button onClick={onClose} title="关闭" className="text-slate-400 hover:text-slate-800"><X size={17} /></button></div>
    <div className="p-5">{children}</div>
  </div></div>;
}

function DialogActions({ onClose, onSubmit, disabled, submitting, label }: { onClose: () => void; onSubmit: () => void; disabled: boolean; submitting: boolean; label: string }) {
  return <div className="mt-5 flex justify-end gap-2"><button onClick={onClose} className="rounded-md border border-slate-200 px-3 py-2 text-xs text-slate-600 hover:bg-slate-50">取消</button><button onClick={onSubmit} disabled={disabled} className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-4 py-2 text-xs text-white disabled:cursor-not-allowed disabled:opacity-50">{submitting && <Loader2 size={12} className="animate-spin" />}{submitting ? '提交中...' : label}</button></div>;
}
