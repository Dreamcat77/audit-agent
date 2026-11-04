import { useEffect, useRef, useState } from "react";
import { AlertTriangle, LoaderCircle, Search, X } from "lucide-react";
import { ApiError } from "../../../api/client";
import { userFacingError } from "../../../api/userFacingError";
import { useAuth } from "../../../auth/AuthProvider";
import {
  contractCatalogDataSource,
  type ContractSummary,
  type PagedContractSummary,
} from "../data/contractCatalogDataSource";
import { contractReviewSessionDataSource } from "../data/contractReviewSessionDataSource";
import { formatRmbAmount } from "../reviewFindingPresentation";
import {
  clearSessionOperation,
  isAmbiguousSessionOperation,
  loadOrCreateSessionOperation,
} from "../sessionOperation";
import type { ContractReviewSession } from "../sessionTypes";

interface Props {
  open: boolean;
  initialContract: ContractSummary | null;
  onClose: () => void;
  onCreated: (session: ContractReviewSession, contract: ContractSummary) => void;
}

const EMPTY_PAGE: PagedContractSummary = {
  items: [], page: 1, size: 20, total: 0, totalPages: 0,
};

function suggestedName(contract: ContractSummary | null): string {
  if (!contract) return "";
  const date = new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit",
  }).format(new Date()).replaceAll("/", "-");
  return `${contract.contractName}审核-${date}`;
}

function formatTime(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
  }).format(parsed);
}

export default function CreateReviewSessionDialog({
  open,
  initialContract,
  onClose,
  onCreated,
}: Props) {
  const { user } = useAuth();
  const [catalog, setCatalog] = useState<PagedContractSummary>(EMPTY_PAGE);
  const [catalogPage, setCatalogPage] = useState(1);
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState("");
  const [name, setName] = useState("");
  const [remark, setRemark] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preparingSessions, setPreparingSessions] = useState<ContractReviewSession[]>([]);
  const [discoveryLoading, setDiscoveryLoading] = useState(false);
  const [discoveryError, setDiscoveryError] = useState<string | null>(null);
  const [createAnother, setCreateAnother] = useState(false);
  const [discoveryNonce, setDiscoveryNonce] = useState(0);
  const createControllerRef = useRef<AbortController | null>(null);
  const pendingRef = useRef(false);

  const selected = catalog.items.find((item) => item.id === selectedId)
    ?? (initialContract?.id === selectedId ? initialContract : null);

  useEffect(() => {
    if (!open) return;
    setCatalogPage(1);
    setKeywordInput("");
    setKeyword("");
    setSelectedId(initialContract?.id ?? "");
    setName(suggestedName(initialContract));
    setRemark("");
    setError(null);
    setPreparingSessions([]);
    setDiscoveryError(null);
    setCreateAnother(false);
  }, [initialContract, open]);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setCatalogLoading(true);
    setCatalogError(null);
    void contractCatalogDataSource.listContracts({
      page: catalogPage,
      size: 20,
      status: "READY_FOR_REVIEW",
      keyword,
      signal: controller.signal,
    }).then((result) => {
      if (controller.signal.aborted) return;
      setCatalog(result);
      const preferred = result.items.find((item) => item.id === selectedId)
        ?? (initialContract && result.items.find((item) => item.id === initialContract.id))
        ?? result.items[0]
        ?? null;
      setSelectedId(preferred?.id ?? "");
      setName(suggestedName(preferred));
    }).catch((cause: unknown) => {
      if (cause instanceof Error && cause.name === "AbortError") return;
      setCatalogError(userFacingError(cause, "合同目录暂时无法读取，请稍后重试。"));
    }).finally(() => {
      if (!controller.signal.aborted) setCatalogLoading(false);
    });
    return () => controller.abort();
  }, [catalogPage, initialContract, keyword, open]);

  useEffect(() => {
    if (!open || !selected?.id) {
      setPreparingSessions([]);
      setDiscoveryLoading(false);
      return;
    }
    const controller = new AbortController();
    setDiscoveryLoading(true);
    setDiscoveryError(null);
    setPreparingSessions([]);
    setCreateAnother(false);
    void contractReviewSessionDataSource.listSessions(selected.id, { signal: controller.signal })
      .then((sessions) => setPreparingSessions(sessions
        .filter((session) => session.status === "PREPARING_MATERIALS")
        .sort((left, right) => new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime())))
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name === "AbortError") return;
        setDiscoveryError(userFacingError(cause, "暂时无法检查未完成的材料准备，请稍后重试。"));
      }).finally(() => {
        if (!controller.signal.aborted) setDiscoveryLoading(false);
      });
    return () => controller.abort();
  }, [discoveryNonce, open, selected?.id]);

  useEffect(() => () => createControllerRef.current?.abort(), []);

  if (!open) return null;

  const submit = async () => {
    if (!selected?.id || !user?.userId || pendingRef.current || !name.trim()) return;
    pendingRef.current = true;
    setSubmitting(true);
    setError(null);
    const operationKey = loadOrCreateSessionOperation(user.userId, selected.id, "create");
    createControllerRef.current?.abort();
    const controller = new AbortController();
    createControllerRef.current = controller;
    try {
      const session = await contractReviewSessionDataSource.createSession(selected.id, {
        name: name.trim(), remark: remark.trim(), idempotencyKey: operationKey, signal: controller.signal,
      });
      clearSessionOperation(user.userId, selected.id, "create");
      onCreated(session, selected);
    } catch (cause) {
      if (cause instanceof Error && cause.name === "AbortError") return;
      if (!isAmbiguousSessionOperation(cause)) clearSessionOperation(user.userId, selected.id, "create");
      setError(cause instanceof ApiError && cause.status === 409
        ? isAmbiguousSessionOperation(cause)
          ? "创建请求仍在处理中，请稍后重试。"
          : "创建请求状态已变化，请重新操作。"
        : userFacingError(cause, "内审事项暂时无法创建，请稍后重试。"));
    } finally {
      pendingRef.current = false;
      setSubmitting(false);
    }
  };

  return (
    <div className="contract-light fixed inset-0 z-50 bg-slate-900/35 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="w-full max-w-4xl max-h-[92vh] overflow-y-auto rounded-xl border border-slate-200 bg-white shadow-2xl">
        <div className="px-5 py-4 border-b border-slate-200 flex items-center justify-between">
          <div><h3 className="text-sm font-semibold text-slate-900">从已录入合同创建</h3><p className="text-[11px] text-muted-foreground mt-1">当前支持基于系统已录入合同准备审核材料。</p></div>
          <button type="button" onClick={onClose} disabled={submitting} className="size-8 rounded border border-slate-200 flex items-center justify-center text-muted-foreground hover:text-slate-700"><X size={15} /></button>
        </div>
        <div className="p-5 space-y-4 text-xs">
          <form onSubmit={(event) => { event.preventDefault(); setCatalogPage(1); setKeyword(keywordInput.trim()); }} className="flex gap-2">
            <input value={keywordInput} maxLength={128} onChange={(event) => setKeywordInput(event.target.value)} placeholder="搜索合同编号、名称、甲乙方" className="min-w-0 flex-1 rounded border border-slate-200 bg-slate-50 px-3 py-2.5 text-slate-900" />
            <button type="submit" className="inline-flex items-center gap-1.5 rounded border border-blue-200 bg-blue-50 px-3 text-blue-700"><Search size={13} />搜索</button>
          </form>
          {catalogError && <div className="rounded border border-red-200 bg-red-50 p-3 text-red-700">{catalogError}</div>}
          <div className="rounded border border-slate-200 overflow-hidden">
            {catalogLoading ? <div className="p-5 text-muted-foreground inline-flex items-center gap-2"><LoaderCircle size={13} className="animate-spin" />正在读取合同目录</div> : catalog.items.length === 0 ? <div className="p-5 text-muted-foreground">暂无可发起内审的合同。</div> : catalog.items.map((contract) => (
              <button key={contract.id} type="button" onClick={() => { setSelectedId(contract.id); setName(suggestedName(contract)); setError(null); }} className={`w-full grid grid-cols-[120px_minmax(180px,1fr)_minmax(150px,1fr)_130px] gap-3 border-b border-slate-200 px-3 py-3 text-left last:border-b-0 ${selectedId === contract.id ? "bg-blue-50" : "hover:bg-slate-50"}`}>
                <span className="font-mono text-blue-700">{contract.contractNo}</span><span className="text-slate-900">{contract.contractName}</span><span className="truncate text-muted-foreground">{contract.partyB}</span><span className="font-mono text-emerald-700">{formatRmbAmount(contract.contractAmount)}</span>
              </button>
            ))}
          </div>
          <div className="flex items-center justify-between text-[10px] text-muted-foreground">
            <span>共 {catalog.total} 份合同 · 第 {catalog.page} / {Math.max(catalog.totalPages, 1)} 页</span>
            <div className="flex gap-2"><button type="button" disabled={catalogPage <= 1 || catalogLoading} onClick={() => setCatalogPage((value) => Math.max(1, value - 1))} className="rounded border border-slate-200 px-3 py-1.5 disabled:opacity-35">上一页</button><button type="button" disabled={catalogPage >= catalog.totalPages || catalogLoading} onClick={() => setCatalogPage((value) => value + 1)} className="rounded border border-slate-200 px-3 py-1.5 disabled:opacity-35">下一页</button></div>
          </div>
          {discoveryLoading && <div className="rounded border border-slate-200 bg-slate-50 px-3 py-3 text-muted-foreground inline-flex items-center gap-2"><LoaderCircle size={13} className="animate-spin" />正在检查未完成的材料准备</div>}
          {!discoveryLoading && discoveryError && <div className="rounded border border-amber-200 bg-amber-500/[0.07] p-3 text-amber-700"><div className="flex items-start gap-2"><AlertTriangle size={13} className="mt-0.5" />{discoveryError}</div><button type="button" onClick={() => setDiscoveryNonce((value) => value + 1)} className="mt-2 text-blue-700">重新检查</button></div>}
          {!discoveryLoading && preparingSessions.length > 0 && <div className="rounded border border-blue-200 bg-blue-50 p-4"><div className="flex justify-between"><div><p className="font-medium text-slate-900">未完成的材料准备</p><p className="mt-1 text-[10px] text-muted-foreground">以下内审任务均已保存，可随时继续准备。</p></div><span className="text-blue-700">{preparingSessions.length} 条</span></div><div className="mt-3 max-h-56 space-y-2 overflow-y-auto">{preparingSessions.map((session) => <div key={session.sessionId} className="rounded border border-slate-200 bg-slate-50 p-3 flex items-center justify-between gap-3"><div className="min-w-0"><p className="truncate text-slate-700">{session.name}</p><p className="mt-1 text-[10px] text-muted-foreground">创建时间：{formatTime(session.createdAt)}</p></div><button type="button" onClick={() => selected && onCreated(session, selected)} className="rounded bg-blue-600 px-3 py-2 text-white">继续准备</button></div>)}</div>{!createAnother && <button type="button" onClick={() => setCreateAnother(true)} className="mt-3 rounded border border-slate-200 px-3 py-2 text-slate-700">新建另一条</button>}</div>}
          {(preparingSessions.length === 0 || createAnother) && <><label className="block space-y-1.5"><span className="text-muted-foreground">内审任务名称 *</span><input value={name} maxLength={256} onChange={(event) => setName(event.target.value)} className="w-full rounded border border-slate-200 bg-slate-50 px-3 py-2.5 text-slate-900" /></label><div className="grid grid-cols-2 gap-3"><div className="rounded border border-slate-200 p-3"><div className="text-muted-foreground mb-1">审核类型</div><div>合同条款量化一致性审查</div></div><div className="rounded border border-slate-200 p-3"><div className="text-muted-foreground mb-1">审核范围</div><div>合同正文与招投标/中标材料</div></div></div><label className="block space-y-1.5"><span className="text-muted-foreground">备注</span><textarea value={remark} maxLength={1000} rows={3} onChange={(event) => setRemark(event.target.value)} className="w-full resize-none rounded border border-slate-200 bg-slate-50 px-3 py-2.5 text-slate-900" /></label></>}
          {error && <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-red-700">{error}</div>}
        </div>
        <div className="px-5 py-4 border-t border-slate-200 flex justify-end gap-2"><button type="button" onClick={onClose} disabled={submitting} className="px-4 py-2 rounded border border-slate-200 text-muted-foreground hover:bg-slate-50">取消</button><button type="button" onClick={() => void submit()} disabled={submitting || discoveryLoading || Boolean(discoveryError) || !selected || !name.trim() || Boolean(preparingSessions.length > 0 && !createAnother)} className="px-4 py-2 rounded bg-blue-600 text-white disabled:opacity-40 inline-flex items-center gap-2">{submitting && <LoaderCircle size={13} className="animate-spin" />}开始准备审核材料</button></div>
      </div>
    </div>
  );
}
