import { useEffect, useMemo, useState } from "react";
import {
  ArrowLeft,
  CheckCircle2,
  FileText,
  History,
  LoaderCircle,
  RefreshCw,
  Trash2,
  Upload,
} from "lucide-react";
import { useAuth } from "../../../auth/AuthProvider";
import type { ContractSummary } from "../data/contractCatalogDataSource";
import {
  displayChineseUppercaseAmount,
  formatRmbAmount,
} from "../reviewFindingPresentation";
import type { ContractReviewSession, SessionReviewDocument } from "../sessionTypes";
import { useAuthenticatedBlobPreview } from "../useAuthenticatedBlobPreview";
import { useContractReviewSessionWorkspace } from "../useContractReviewSessionWorkspace";
import { createReviewExecutionSnapshot } from "../reviewExecutionPresentation";
import type { ReviewDocumentKind } from "../workspaceTypes";
import { ConfirmDialog } from "../../../app/components/ConfirmDialog";
import F02ReviewResultPanel from "./F02ReviewResultPanel";
import ReviewProgressTimeline from "./ReviewProgressTimeline";

const KINDS: Array<{ kind: ReviewDocumentKind; label: string; required: boolean }> = [
  { kind: "CONTRACT_BODY", label: "合同正文", required: true },
  { kind: "BIDDING_DOCUMENT", label: "招标文件", required: false },
  { kind: "TENDER_DOCUMENT", label: "投标文件", required: false },
  { kind: "AWARD_NOTICE", label: "中标通知书", required: true },
  { kind: "OTHER", label: "其他材料", required: false },
];

interface Props {
  initialSession: ContractReviewSession;
  contract: ContractSummary;
  contractDisplayId: string;
  onBack: () => void;
}

interface LocalSelection {
  kind: ReviewDocumentKind;
  file: File;
  url: string;
}

interface ServerSelection {
  documentId: string;
  history: boolean;
}

function formatTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
  }).format(date);
}

function formatSize(size: number): string {
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KiB`;
  return `${(size / 1024 / 1024).toFixed(1)} MiB`;
}

function documentStatusLabel(status: SessionReviewDocument["status"]): string {
  if (status === "AVAILABLE") return "可查看";
  if (status === "UPLOAD_PENDING") return "等待恢复";
  return "上传失败";
}

function SessionInfo({ session, contract }: { session: ContractReviewSession; contract: ContractSummary }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 space-y-3 shadow-sm">
      <h3 className="text-[15px] font-semibold text-slate-900">审核对象摘要</h3>
      <dl className="grid grid-cols-[112px_1fr] gap-y-2 text-[13px]">
        <dt className="text-slate-500">合同名称</dt><dd className="text-slate-900">{contract.contractName}</dd>
        <dt className="text-slate-500">合同编号</dt><dd className="font-mono text-slate-700">{contract.contractNo}</dd>
        <dt className="text-slate-500">内审任务</dt><dd className="text-slate-700">{session.name}</dd>
        <dt className="text-slate-500">供应商名称</dt><dd className="text-slate-900">{contract.partyB}</dd>
        <dt className="text-slate-500">统一社会信用代码</dt><dd className="font-mono text-slate-700">{contract.partyBCreditCode || "—"}</dd>
        <dt className="text-slate-500">合同金额</dt><dd className="text-slate-900">{formatRmbAmount(contract.contractAmount)}</dd>
        <dt className="text-slate-500">金额大写</dt><dd className="text-slate-900">{displayChineseUppercaseAmount(contract.amountInWords)}</dd>
        <dt className="text-slate-500">发起人</dt><dd className="text-slate-900">{session.createdByName ?? "—"}</dd>
        <dt className="text-slate-500">创建时间</dt><dd className="text-slate-900">{formatTime(session.createdAt)}</dd>
        <dt className="text-slate-500">备注</dt><dd className="text-slate-900">{session.remark || "—"}</dd>
      </dl>
    </div>
  );
}

export default function ContractReviewSessionWorkspace({
  initialSession,
  contract,
  contractDisplayId,
  onBack,
}: Props) {
  const { user } = useAuth();
  const workspace = useContractReviewSessionWorkspace(initialSession.sessionId, user?.userId ?? "");
  const session = workspace.session ?? initialSession;
  const ready = session.status === "READY";
  const [serverSelection, setServerSelection] = useState<ServerSelection | null>(null);
  const [localSelection, setLocalSelection] = useState<LocalSelection | null>(null);
  const [historyVisible, setHistoryVisible] = useState(false);
  const [confirmMaterialsOpen, setConfirmMaterialsOpen] = useState(false);
  const [readyKind, setReadyKind] = useState<ReviewDocumentKind>("CONTRACT_BODY");
  const [viewerMode, setViewerMode] = useState<"ORIGINAL" | "TEXT">("ORIGINAL");

  useEffect(() => () => {
    if (localSelection) URL.revokeObjectURL(localSelection.url);
  }, [localSelection]);

  useEffect(() => {
    if (!ready || !localSelection) return;
    URL.revokeObjectURL(localSelection.url);
    setLocalSelection(null);
  }, [localSelection, ready]);

  useEffect(() => {
    if (ready) return;
    const selectedExists = serverSelection && (serverSelection.history
      ? workspace.documents.history
      : workspace.documents.currentMaterials)
      .some((document) => document.documentId === serverSelection.documentId);
    if (!selectedExists) {
      const first = workspace.documents.currentMaterials[0];
      setServerSelection(first ? { documentId: first.documentId, history: false } : null);
    }
  }, [ready, serverSelection, workspace.documents.currentMaterials, workspace.documents.history]);

  const currentByKind = useMemo(() => {
    const map = new Map<ReviewDocumentKind, SessionReviewDocument[]>();
    workspace.documents.currentMaterials.forEach((document) => {
      map.set(document.documentKind, [...(map.get(document.documentKind) ?? []), document]);
    });
    return map;
  }, [workspace.documents.currentMaterials]);

  useEffect(() => {
    if (!ready) return;
    const materials = currentByKind.get(readyKind) ?? [];
    const selectedExists = serverSelection
      && !serverSelection.history
      && materials.some((document) => document.documentId === serverSelection.documentId);
    if (!selectedExists) {
      setServerSelection(materials[0]
        ? { documentId: materials[0].documentId, history: false }
        : null);
    }
  }, [currentByKind, ready, readyKind, serverSelection]);

  const activeDocument = serverSelection
    ? (serverSelection.history ? workspace.documents.history : workspace.documents.currentMaterials)
      .find((document) => document.documentId === serverSelection.documentId) ?? null
    : null;
  const serverContentUrl = !localSelection && activeDocument?.status === "AVAILABLE"
    ? workspace.contentUrl(session.sessionId, activeDocument.documentId, serverSelection?.history ?? false)
    : null;
  const serverPreview = useAuthenticatedBlobPreview(serverContentUrl, activeDocument?.contentType ?? "");
  const previewUrl = localSelection?.url ?? serverPreview.objectUrl;
  const previewType = localSelection?.file.type || serverPreview.contentType || activeDocument?.contentType || "";
  const hasCore = Boolean(currentByKind.get("CONTRACT_BODY")?.length)
    && Boolean(currentByKind.get("AWARD_NOTICE")?.length);

  const selectServerDocument = (document: SessionReviewDocument, history: boolean) => {
    setLocalSelection(null);
    setServerSelection({ documentId: document.documentId, history });
  };

  const chooseFile = (kind: ReviewDocumentKind, file: File | null) => {
    if (!file || ready) return;
    setLocalSelection((current) => {
      if (current) URL.revokeObjectURL(current.url);
      return { kind, file, url: URL.createObjectURL(file) };
    });
    setServerSelection(null);
  };

  const uploadSelected = async () => {
    if (!localSelection || ready) return;
    const uploaded = await workspace.upload(localSelection.kind, localSelection.file);
    if (uploaded) {
      URL.revokeObjectURL(localSelection.url);
      setLocalSelection(null);
    }
  };

  const confirmMaterials = async () => {
    if (ready || !hasCore) return;
    setConfirmMaterialsOpen(true);
  };

  const confirmMaterialsAction = async () => {
    await workspace.confirm();
  };

  if (ready) {
    const readyMaterials = currentByKind.get(readyKind) ?? [];
    const readySnapshot = createReviewExecutionSnapshot({ result: null, state: "NOT_STARTED" });
    return (
      <div className="contract-light h-full min-h-[680px] flex flex-col bg-slate-100 text-slate-700">
        <header className="flex shrink-0 items-center justify-between gap-4 border-b border-slate-200 bg-white px-4 py-3">
          <div className="flex items-center gap-3 min-w-0">
            <button type="button" onClick={onBack} className="flex size-9 items-center justify-center rounded border border-slate-200 text-slate-600 hover:bg-slate-50 hover:text-slate-900" aria-label="返回内审任务列表"><ArrowLeft size={15} /></button>
            <div className="min-w-0"><h2 className="truncate text-base font-semibold text-slate-900">{contract.contractName}</h2><p className="mt-1 truncate text-[13px] text-slate-600">合同编号：{contractDisplayId} · 供应商：{contract.partyB}</p></div>
          </div>
          <span className="whitespace-nowrap rounded border border-slate-200 bg-slate-100 px-2.5 py-1 text-xs text-slate-600">待审核</span>
        </header>
        <div className="flex-1 min-h-0 grid grid-cols-1 lg:grid-cols-[minmax(0,3fr)_minmax(340px,2fr)]">
          <section className="flex min-h-0 flex-col border-r border-slate-200 bg-slate-100">
            <div className="border-b border-slate-200 bg-white">
              <div className="flex items-center justify-between px-4 py-3"><div><h3 className="text-[15px] font-semibold text-slate-900">审核材料</h3><p className="mt-1 text-xs text-slate-600">材料已由系统保存，重新进入后仍可查看</p></div><button type="button" onClick={() => void workspace.refresh()} disabled={workspace.loading} className="flex size-8 items-center justify-center rounded border border-slate-200 text-slate-600 disabled:opacity-40" aria-label="刷新审核材料"><RefreshCw size={14} className={workspace.loading ? "animate-spin" : ""} /></button></div>
              <div className="flex overflow-x-auto px-3">{KINDS.map(({ kind, label }) => <button key={kind} type="button" onClick={() => { setReadyKind(kind); setViewerMode("ORIGINAL"); const first = currentByKind.get(kind)?.[0]; setServerSelection(first ? { documentId: first.documentId, history: false } : null); }} className={`shrink-0 border-b-2 px-3 py-2.5 text-[13px] ${readyKind === kind ? "border-blue-600 bg-blue-50 text-blue-800" : "border-transparent text-slate-600"}`}>{label}<span className="ml-1 text-xs">{currentByKind.get(kind)?.length ?? 0}</span></button>)}</div>
            </div>
            {readyMaterials.length > 1 && <div className="flex gap-2 overflow-x-auto border-b border-slate-200 bg-white px-4 py-2">{readyMaterials.map((document) => <button key={document.documentId} type="button" onClick={() => selectServerDocument(document, false)} className={`max-w-52 truncate rounded border px-2.5 py-1.5 text-xs ${activeDocument?.documentId === document.documentId ? "border-blue-200 bg-blue-50 text-blue-800" : "border-slate-200 text-slate-600"}`}>{document.originalFileName}</button>)}</div>}
            <div className="flex items-center justify-between border-b border-slate-200 bg-white px-4 py-2.5"><span className="truncate text-xs text-slate-600">{activeDocument?.originalFileName ?? "当前分类暂无材料"}</span><div className="flex rounded border border-slate-200 bg-slate-50 p-0.5"><button type="button" onClick={() => setViewerMode("ORIGINAL")} className={`rounded px-3 py-1.5 text-[13px] ${viewerMode === "ORIGINAL" ? "bg-white text-blue-700 shadow-sm" : "text-slate-600"}`}>原始文件</button><button type="button" onClick={() => setViewerMode("TEXT")} className={`rounded px-3 py-1.5 text-[13px] ${viewerMode === "TEXT" ? "bg-white text-blue-700 shadow-sm" : "text-slate-600"}`}>识别文本</button></div></div>
            <div className="flex-1 min-h-[480px] flex items-center justify-center p-4">
              {viewerMode === "TEXT" ? <div className="rounded border border-slate-200 bg-white px-5 py-4 text-xs text-slate-600">识别文本待材料解析能力接入。</div> : serverPreview.loading ? <div className="text-center text-slate-600"><LoaderCircle size={24} className="mx-auto animate-spin text-blue-700" /><p className="mt-3 text-xs">正在读取服务器材料</p></div> : serverPreview.error ? <div role="alert" className="max-w-sm rounded border border-red-200 bg-red-50 p-4 text-center text-xs text-red-700">{serverPreview.error}</div> : !previewUrl ? <div className="text-center text-slate-600"><FileText size={34} className="mx-auto text-slate-300" /><p className="mt-3 text-xs">当前分类暂无可预览原件</p></div> : previewType.toLowerCase().startsWith("application/pdf") ? <object data={previewUrl} type="application/pdf" className="h-full min-h-[480px] w-full rounded bg-white" aria-label="审核材料 PDF 预览"><p className="p-5 text-sm text-slate-700">当前浏览器无法内嵌预览该 PDF。</p></object> : previewType.toLowerCase().startsWith("image/") ? <img src={previewUrl} alt="审核材料原件" className="max-h-full max-w-full rounded object-contain" /> : <div className="text-center text-amber-800"><FileText size={30} className="mx-auto" /><p className="mt-3 text-xs">当前文件类型不支持内嵌预览。</p></div>}
            </div>
          </section>
          <aside className="min-h-0 space-y-4 overflow-y-auto bg-[#F6F8FB] p-4">
            <SessionInfo session={session} contract={contract} />
            <ReviewProgressTimeline contractReady contractLoading={false} contractFailed={false} reviewLoading={false} result={null} snapshot={readySnapshot} />
            <F02ReviewResultPanel result={null} reviewLoading={false} resultLoading={false} resultUnavailable={false} snapshot={readySnapshot} />
            {workspace.error && <div className="rounded border border-red-200 bg-red-50 p-3 text-xs text-red-700">{workspace.error}</div>}
          </aside>
        </div>
      </div>
    );
  }


  return (
    <div className="contract-light h-full min-h-[680px] flex flex-col bg-slate-100 text-slate-700">
      <header className="flex shrink-0 items-center justify-between gap-4 border-b border-slate-200 bg-white px-4 py-3">
        <div className="flex items-center gap-3 min-w-0">
          <button type="button" onClick={onBack}
            className="flex size-9 items-center justify-center rounded border border-slate-200 text-slate-600 hover:bg-slate-50 hover:text-slate-900"
            aria-label="返回内审任务列表"><ArrowLeft size={15} /></button>
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <h2 className="truncate text-base font-semibold text-slate-900">{contract.contractName}</h2>
            </div>
            <p className="mt-1 text-[13px] text-slate-600">合同编号：{contractDisplayId} · 供应商：{contract.partyB}</p>
          </div>
        </div>
        <span className="whitespace-nowrap rounded border border-amber-200 bg-amber-50 px-2.5 py-1 text-xs text-amber-800">材料准备中</span>
      </header>

      <div className="flex-1 min-h-0 grid grid-cols-1 lg:grid-cols-[minmax(0,3fr)_minmax(320px,2fr)]">
        <section className="flex min-h-0 flex-col border-r border-slate-200">
          <div className="flex items-center justify-between border-b border-slate-200 bg-white px-4 py-3">
            <div>
              <h3 className="text-[15px] font-semibold text-slate-900">审核材料</h3>
              <p className="mt-1 text-xs text-slate-600">材料已由系统保存，重新进入后仍可查看</p>
            </div>
            <button type="button" onClick={() => void workspace.refresh()} disabled={workspace.loading}
              aria-label="刷新服务器材料"
              className="flex size-8 items-center justify-center rounded border border-slate-200 text-slate-600 disabled:opacity-40">
              <RefreshCw size={13} className={workspace.loading ? "animate-spin" : ""} />
            </button>
          </div>
          <div className="grid grid-cols-1 xl:grid-cols-[280px_minmax(0,1fr)] flex-1 min-h-0">
            <div className="space-y-2 overflow-y-auto border-r border-slate-200 bg-slate-50 p-3">
              {KINDS.map(({ kind, label, required }) => {
                const materials = currentByKind.get(kind) ?? [];
                const current = materials[0] ?? null;
                return (
                  <div key={kind} className="space-y-2 rounded-lg border border-slate-200 bg-white p-3">
                    <div className="flex items-center justify-between gap-2">
                      <div className="text-[13px] text-slate-900">{label}</div>
                      <span className={`text-xs ${current ? "text-emerald-700" : "text-slate-500"}`}>{current ? "已上传" : "未上传"}</span>
                    </div>
                    {materials.map((document) => (
                      <div key={document.documentId} className="flex items-center gap-1.5">
                        <button type="button" onClick={() => selectServerDocument(document, false)}
                          className={`min-w-0 flex-1 rounded border bg-white px-2.5 py-2 text-left ${serverSelection?.documentId === document.documentId && !serverSelection.history ? "border-blue-300 bg-blue-50" : "border-slate-200 hover:border-blue-300"}`}>
                          <div className="truncate text-[13px] text-slate-800">{document.originalFileName}</div>
                          <div className="mt-1 text-xs text-slate-500">{formatSize(document.fileSize)}</div>
                        </button>
                        <button type="button" onClick={() => void workspace.remove(document)}
                          disabled={Boolean(workspace.action)} title="移除当前材料"
                          className="size-7 shrink-0 rounded border border-red-500/15 text-red-700 hover:bg-red-50 flex items-center justify-center disabled:opacity-35">
                          <Trash2 size={11} />
                        </button>
                      </div>
                    ))}
                    <label className="inline-flex cursor-pointer items-center gap-1.5 text-xs text-blue-700 hover:text-blue-800">
                      <Upload size={11} />{current && kind !== "TENDER_DOCUMENT" && kind !== "OTHER" ? "选择替换文件" : "选择文件"}
                      <input type="file" className="hidden" accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
                        onChange={(event) => { chooseFile(kind, event.target.files?.[0] ?? null); event.target.value = ""; }} />
                    </label>
                  </div>
                );
              })}
            </div>

            <div className="flex min-h-[420px] flex-col bg-slate-100">
              <div className="flex items-center justify-between gap-3 border-b border-slate-200 bg-white px-4 py-2.5">
                <span className="truncate text-xs text-slate-600">
                  {localSelection?.file.name ?? activeDocument?.originalFileName ?? "请选择材料"}
                </span>
                {localSelection && (
                  <button type="button" onClick={() => void uploadSelected()} disabled={Boolean(workspace.action)}
                    className="inline-flex items-center gap-1.5 rounded bg-blue-700 px-3 py-1.5 text-xs text-white disabled:opacity-40">
                    {workspace.action === "上传材料" ? <LoaderCircle size={11} className="animate-spin" /> : <Upload size={11} />}
                    上传当前文件
                  </button>
                )}
              </div>
              <div className="flex-1 min-h-0 flex items-center justify-center p-4">
                {!localSelection && serverPreview.loading ? (
                  <div className="text-center text-muted-foreground"><LoaderCircle size={24} className="mx-auto animate-spin text-blue-700" /><p className="text-xs mt-3">正在读取服务器材料</p></div>
                ) : !localSelection && serverPreview.error ? (
                  <div role="alert" className="max-w-sm rounded border border-red-200 bg-red-500/[0.06] p-4 text-center text-xs text-red-700">{serverPreview.error}</div>
                ) : !previewUrl ? (
                  <div className="text-center text-slate-600"><FileText size={34} className="mx-auto text-slate-300" /><p className="mt-3 text-xs">暂无可预览原件</p></div>
                ) : previewType.toLowerCase().startsWith("application/pdf") ? (
                  <object data={previewUrl} type="application/pdf" className="w-full h-full min-h-[420px] rounded bg-white" aria-label="审核材料 PDF 预览">
                    <p className="p-5 text-sm text-slate-700">当前浏览器无法内嵌预览该 PDF。</p>
                  </object>
                ) : previewType.toLowerCase().startsWith("image/") ? (
                  <img src={previewUrl} alt="审核材料原件" className="max-w-full max-h-full object-contain rounded" />
                ) : (
                  <div className="text-center text-amber-700"><FileText size={30} className="mx-auto" /><p className="text-xs mt-3">当前文件类型不支持内嵌预览。</p></div>
                )}
              </div>
            </div>
          </div>
        </section>

        <aside className="min-h-0 space-y-4 overflow-y-auto bg-[#F6F8FB] p-4">
          <SessionInfo session={session} contract={contract} />
          {workspace.error && <div className="rounded border border-red-200 bg-red-50 p-3 text-xs text-red-700">{workspace.error}</div>}
          <div className="rounded-lg border border-amber-200 bg-amber-500/[0.05] p-4">
            <div className="flex items-start gap-3">
              <CheckCircle2 size={17} className="text-amber-700" />
              <div className="min-w-0">
                <h3 className="text-[15px] font-semibold text-slate-900">确认核心材料</h3>
                <p className="mt-1 text-xs leading-5 text-slate-600">合同正文和中标通知书均可用后，方可确认审核输入。</p>
                <p className="mt-2 text-xs leading-5 text-amber-800">确认后，本次审核输入将锁定，不能再上传、替换或删除材料。</p>
              </div>
            </div>
            <button type="button" onClick={() => void confirmMaterials()}
              disabled={!hasCore || Boolean(workspace.action)}
              className="w-full mt-3 py-2 rounded bg-emerald-600 text-white text-xs disabled:opacity-35">
              {workspace.action === "确认材料" ? "正在确认…" : "确认审核材料"}
            </button>
          </div>

          <div className="rounded-lg border border-slate-200 bg-white">
            <button type="button" onClick={() => setHistoryVisible((value) => !value)}
              className="flex w-full items-center justify-between p-4 text-[13px] text-slate-900">
              <span className="inline-flex items-center gap-2"><History size={13} />材料历史</span>
              <span className="text-muted-foreground">{workspace.documents.history.length}</span>
            </button>
            {historyVisible && (
              <div className="space-y-2 border-t border-slate-200 p-3">
                {workspace.documents.history.length === 0 ? (
                  <p className="text-xs text-slate-600">暂无历史材料</p>
                ) : workspace.documents.history.map((document) => (
                  <div key={document.documentId} className={`rounded border bg-white p-2.5 ${serverSelection?.history && serverSelection.documentId === document.documentId ? "border-blue-300" : "border-slate-200"}`}>
                    {document.status === "AVAILABLE" ? (
                      <button type="button" onClick={() => selectServerDocument(document, true)}
                        className="block w-full truncate text-left text-xs text-slate-800 hover:text-blue-700">
                        {document.originalFileName}
                      </button>
                    ) : (
                      <div className="truncate text-xs text-slate-800">{document.originalFileName}</div>
                    )}
                    <div className="mt-1 flex items-center justify-between gap-2">
                      <span className="text-xs text-slate-500">{documentStatusLabel(document.status)}</span>
                      {document.status === "UPLOAD_PENDING" && (
                        <button type="button" onClick={() => void workspace.recover(document)}
                          disabled={Boolean(workspace.action)} className="text-xs text-amber-800 disabled:opacity-35">恢复上传</button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </aside>
        <ConfirmDialog
          open={confirmMaterialsOpen}
          title="确认审核材料"
          description="确认后，本次审核输入将锁定，不能再上传、替换或删除材料。"
          confirmLabel="确认"
          loading={workspace.action === "确认材料"}
          onOpenChange={open => { if (!open && workspace.action !== "确认材料") setConfirmMaterialsOpen(false); }}
          onConfirm={() => { void confirmMaterialsAction(); }}
        />
      </div>
    </div>
  );
}
