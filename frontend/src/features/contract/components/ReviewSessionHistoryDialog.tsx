import { History, LoaderCircle, X } from "lucide-react";
import { useEffect, useState } from "react";
import * as DialogPrimitive from "@radix-ui/react-dialog";
import { userFacingError } from "../../../api/userFacingError";
import type { ContractOverviewItem } from "../contractOverviewTypes";
import { contractReviewSessionDataSource } from "../data/contractReviewSessionDataSource";
import type { ContractReviewSession } from "../sessionTypes";

interface Props {
  contract: ContractOverviewItem | null;
  onClose: () => void;
  onOpen: (session: ContractReviewSession, contract: ContractOverviewItem) => void;
}

function formatTime(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(parsed);
}

export default function ReviewSessionHistoryDialog({ contract, onClose, onOpen }: Props) {
  const [sessions, setSessions] = useState<ContractReviewSession[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!contract?.apiId) {
      setSessions([]);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void contractReviewSessionDataSource
      .listSessions(contract.apiId, { signal: controller.signal })
      .then((loaded) => {
        setSessions([...loaded].sort(
          (left, right) => new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime(),
        ));
      })
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name === "AbortError") return;
        setError(userFacingError(cause, "审核记录暂时无法读取，请稍后重试。"));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [contract]);

  return (
    <DialogPrimitive.Root open={Boolean(contract)} onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-slate-950/35" />
        <DialogPrimitive.Content className="contract-light fixed inset-y-0 right-0 z-50 flex w-full max-w-lg flex-col overflow-hidden border-l border-slate-200 bg-[#F6F8FB] shadow-2xl focus:outline-none sm:w-[min(32rem,calc(100vw-2rem))]"
          aria-describedby="review-session-history-description">
        <div className="flex items-start justify-between border-b border-slate-200 bg-white px-5 py-4">
          <div>
            <DialogPrimitive.Title className="inline-flex items-center gap-2 text-base font-semibold text-slate-900">
              <History size={15} />审核记录
            </DialogPrimitive.Title>
            <DialogPrimitive.Description id="review-session-history-description" className="mt-1 text-xs text-slate-500">
              {contract ? `${contract.displayId} · ${contract.contract?.contractName}` : "合同审核记录"}
            </DialogPrimitive.Description>
          </div>
          <DialogPrimitive.Close aria-label="关闭审核记录"
            className="flex size-9 items-center justify-center rounded border border-slate-200 bg-white text-slate-600 hover:bg-slate-50">
            <X size={15} />
          </DialogPrimitive.Close>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto p-5">
          {loading && (
            <div className="py-10 text-center text-xs text-slate-500">
              <LoaderCircle size={16} className="animate-spin inline mr-2" />正在读取审核记录
            </div>
          )}
          {!loading && error && (
            <div className="rounded border border-red-200 bg-red-50 px-3 py-3 text-xs text-red-700">
              {error}
            </div>
          )}
          {!loading && !error && sessions.length === 0 && (
            <div className="py-10 text-center text-xs text-slate-500">暂无历史审核记录</div>
          )}
          {!loading && !error && sessions.length > 0 && (
            <div className="space-y-2">
              {sessions.map((session) => (
                <button key={session.sessionId} type="button" onClick={() => onOpen(session, contract)}
                  className="w-full rounded-lg border border-slate-200 bg-white px-4 py-3 text-left transition-colors hover:border-blue-300 hover:bg-blue-50">
                  <div className="flex items-start justify-between gap-4">
                    <div className="min-w-0">
                      <div className="truncate text-sm font-medium text-slate-900">{session.name}</div>
                      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-slate-500">
                        <span>创建：{formatTime(session.createdAt)}</span>
                        <span>创建人：{session.createdByName ?? "—"}</span>
                      </div>
                    </div>
                    <span className={`shrink-0 rounded border px-2 py-1 text-xs ${
                      session.status === "READY"
                        ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                        : "border-blue-200 bg-blue-50 text-blue-700"
                    }`}>
                      {session.status === "READY" ? "材料已确认" : "资料准备中"}
                    </span>
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
