import * as DialogPrimitive from "@radix-ui/react-dialog";
import { CheckCircle2, CircleAlert, History, X } from "lucide-react";
import type { ShowcaseAuditTaskRow } from "../auditTaskViewModel";
import { formatReviewFactTime } from "../reviewHistoryViewModel";
import { reviewRuleTitle } from "../reviewRulePresentation";
import { reviewFindingDetailPresentation } from "../reviewFindingPresentation";

interface Props {
  row: ShowcaseAuditTaskRow | null;
  onClose: () => void;
}

export default function ShowcaseReviewHistoryDialog({ row, onClose }: Props) {
  return (
    <DialogPrimitive.Root open={Boolean(row)} onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-slate-950/35" />
        <DialogPrimitive.Content className="contract-light fixed inset-y-0 right-0 z-50 flex w-full max-w-lg flex-col overflow-hidden border-l border-slate-200 bg-[#F6F8FB] text-slate-700 shadow-2xl focus:outline-none sm:w-[min(32rem,calc(100vw-2rem))]"
          aria-describedby="showcase-review-history-description">
          <header className="flex items-start justify-between gap-4 border-b border-slate-200 bg-white px-5 py-4">
            <div className="min-w-0">
              <DialogPrimitive.Title className="inline-flex items-center gap-2 text-base font-semibold text-slate-900">
                <History size={15} />审核记录
              </DialogPrimitive.Title>
              <DialogPrimitive.Description id="showcase-review-history-description" className="mt-1 truncate text-xs text-slate-500">
                {row ? `${row.contract.contractNo} · ${row.reviewName}` : "合同审核记录"}
              </DialogPrimitive.Description>
            </div>
            <DialogPrimitive.Close className="inline-flex size-9 items-center justify-center rounded border border-slate-200 bg-white text-slate-600 hover:bg-slate-50"
              aria-label="关闭审核记录"><X size={16} /></DialogPrimitive.Close>
          </header>
          <ol className="min-h-0 flex-1 space-y-3 overflow-y-auto p-5" aria-label="审核记录时间线">
            {row && (
              <li className="rounded-lg border border-slate-200 bg-white p-4">
                <div className="flex items-start gap-3">
                  <span className="inline-flex size-8 shrink-0 items-center justify-center rounded-full border border-emerald-200 bg-emerald-50 text-emerald-700"><CheckCircle2 size={15} /></span>
                  <div><h3 className="text-[13px] font-medium text-slate-900">{reviewRuleTitle("SOURCE_PREPARATION")}</h3>
                    <p className="mt-1 text-xs text-slate-600">核对完成</p>
                    <p className="mt-2 text-xs text-slate-500">核对完成时间：{formatReviewFactTime(row.createdAt)}</p></div>
                </div>
              </li>
            )}
            {row?.reviewResult.findings.map((finding) => {
              const risk = finding.riskLevel !== "GREEN";
              return <li key={finding.id} className="rounded-lg border border-slate-200 bg-white p-4">
                <div className="flex items-start gap-3">
                  <span className={`inline-flex size-8 shrink-0 items-center justify-center rounded-full border ${risk ? "border-amber-200 bg-amber-50 text-amber-800" : "border-emerald-200 bg-emerald-50 text-emerald-700"}`}>
                    {risk ? <CircleAlert size={15} /> : <CheckCircle2 size={15} />}
                  </span>
                  <div className="min-w-0"><h3 className="text-[13px] font-medium text-slate-900">{reviewRuleTitle(finding.ruleCode)}</h3>
                    <p className="mt-1 text-xs text-slate-700">{reviewFindingDetailPresentation(finding)}</p>
                    <p className={`mt-2 text-xs ${risk ? "text-amber-800" : "text-emerald-700"}`}>{risk ? "存在差异，建议复核" : "核对一致"}</p>
                    <p className="mt-1 text-xs text-slate-500">核对完成时间：{formatReviewFactTime(finding.createdAt)}</p></div>
                </div>
              </li>;
            })}
          </ol>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
