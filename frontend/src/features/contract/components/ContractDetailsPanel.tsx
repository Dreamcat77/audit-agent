import { ChevronDown, LocateFixed } from "lucide-react";
import { createContractDetailsAmountView } from "../contractDetailsPresentation";
import type { Contract } from "../types";
import type {
  EvidenceAnchor,
  StructuredFactKey,
  StructuredFactVisibility,
} from "../workspaceTypes";

interface StructuredFactsPanelProps {
  contract: Contract;
  visibility: StructuredFactVisibility;
  projectName?: string | null;
  projectNo?: string | null;
  initiatedBy?: string | null;
  createdAt?: string | null;
  evidenceByFact?: Partial<Record<StructuredFactKey, EvidenceAnchor>>;
  onEvidenceFocus?: (anchor: EvidenceAnchor) => void;
}

function FactRow({
  label,
  value,
  evidence,
  onEvidenceFocus,
}: {
  label: string;
  value: string;
  evidence?: EvidenceAnchor;
  onEvidenceFocus?: (anchor: EvidenceAnchor) => void;
}) {
  const content = (
    <>
      <span className="text-xs text-slate-500">{label}</span>
      <span className="break-all text-[13px] text-slate-900">{value}</span>
      {evidence && onEvidenceFocus ? <LocateFixed size={14} className="text-blue-700 opacity-40 transition-opacity group-hover:opacity-100" /> : <span />}
    </>
  );
  if (evidence && onEvidenceFocus) {
    return (
      <button type="button" onClick={() => onEvidenceFocus({ ...evidence, label })} className="group grid w-full grid-cols-[6.5rem_minmax(0,1fr)_auto] items-start gap-2 border-b border-slate-100 py-2 text-left last:border-b-0 hover:bg-blue-50/60" title="定位原文" aria-label={`${label}：定位原文`}>
        {content}
      </button>
    );
  }
  return <div className="grid grid-cols-[6.5rem_minmax(0,1fr)_auto] items-start gap-2 border-b border-slate-100 py-2 last:border-b-0">{content}</div>;
}

function FactGroup({ title, children, open = false }: { title: string; children: React.ReactNode; open?: boolean }) {
  return (
    <details open={open} className="group border-b border-slate-100 last:border-b-0">
      <summary className="flex cursor-pointer list-none items-center justify-between px-4 py-3 text-[13px] font-medium text-slate-800 hover:bg-slate-50">
        {title}
        <ChevronDown size={14} className="text-slate-500 transition-transform group-open:rotate-180" />
      </summary>
      <div className="px-4 pb-3">{children}</div>
    </details>
  );
}

export default function StructuredFactsPanel({
  contract,
  visibility,
  projectName = null,
  projectNo = null,
  initiatedBy = null,
  createdAt = null,
  evidenceByFact = {},
  onEvidenceFocus,
}: StructuredFactsPanelProps) {
  const amounts = createContractDetailsAmountView(contract);
  const lineAmountsById = new Map(amounts.lineItems.map((item) => [item.id, item]));
  return (
    <details className="group overflow-hidden rounded-lg border border-slate-200 bg-white">
      <summary className="flex cursor-pointer list-none items-center justify-between px-4 py-3">
        <div><h3 className="text-[15px] font-semibold text-slate-900">结构化信息</h3><p className="mt-1 text-xs text-slate-600">系统已录入的审核事实</p></div>
        <ChevronDown size={16} className="text-slate-500 transition-transform group-open:rotate-180" />
      </summary>
      <div className="border-t border-slate-200">

      {(projectName || projectNo) && (
        <FactGroup title="项目信息" open>
          <FactRow label="项目名称" value={projectName ?? "—"} />
          <FactRow label="项目编号" value={projectNo ?? "—"} />
        </FactGroup>
      )}

      {visibility.contractIdentity && (
        <FactGroup title="合同基本信息" open>
          <FactRow label="合同编号" value={contract.contractNo} evidence={evidenceByFact.contractNo} onEvidenceFocus={onEvidenceFocus} />
          <FactRow label="合同名称" value={contract.contractName} evidence={evidenceByFact.contractName} onEvidenceFocus={onEvidenceFocus} />
          {visibility.parties && (
            <>
              <FactRow label="甲方" value={contract.partyA} evidence={evidenceByFact.partyA} onEvidenceFocus={onEvidenceFocus} />
              <FactRow label="供应商名称" value={contract.partyB} evidence={evidenceByFact.partyB} onEvidenceFocus={onEvidenceFocus} />
              <FactRow label="统一社会信用代码" value={contract.partyBCreditCode ?? "—"} evidence={evidenceByFact.partyBCreditCode} onEvidenceFocus={onEvidenceFocus} />
            </>
          )}
        </FactGroup>
      )}

      {visibility.amount && (
        <FactGroup title="合同金额" open>
          <FactRow label="数字金额" value={amounts.contractAmount} evidence={evidenceByFact.contractAmount} onEvidenceFocus={onEvidenceFocus} />
          <FactRow label="金额大写" value={amounts.amountInWords} evidence={evidenceByFact.amountInWords} onEvidenceFocus={onEvidenceFocus} />
        </FactGroup>
      )}

      {visibility.lineItems && (
        <FactGroup title={`合同明细（${contract.lineItems.length} 项）`}>
          <div className="flex justify-end mb-1">
            {evidenceByFact.lineItems && onEvidenceFocus && (
              <button type="button" onClick={() => onEvidenceFocus({ ...evidenceByFact.lineItems!, label: "合同明细" })} className="inline-flex items-center text-blue-700 opacity-50 hover:opacity-100" title="定位原文" aria-label="合同明细：定位原文">
                <LocateFixed size={14} />
              </button>
            )}
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-slate-200 text-slate-500">
                  {['明细项', '数量', '单价', '申报金额'].map((header) => <th key={header} className="py-1.5 pr-3 text-left font-medium whitespace-nowrap">{header}</th>)}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {contract.lineItems.map((item) => (
                  <tr key={item.id}>
                    <td className="py-2 pr-3 text-slate-900">{item.itemName}</td>
                    <td className="py-2 pr-3 tabular-nums">{item.quantity ?? "—"}</td>
                    <td className="whitespace-nowrap py-2 pr-3 tabular-nums">{lineAmountsById.get(item.id)?.unitPrice ?? "—"}</td>
                    <td className="whitespace-nowrap py-2 tabular-nums text-emerald-700">{lineAmountsById.get(item.id)?.declaredAmount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </FactGroup>
      )}

      {visibility.awardNotice && (
        <FactGroup title="中标通知书比对来源">
          {contract.awardNotice ? (
            <>
              <FactRow label="中标供应商" value={contract.awardNotice.winningBidder} evidence={evidenceByFact.awardSupplier} onEvidenceFocus={onEvidenceFocus} />
              <FactRow label="中标金额" value={amounts.winningAmount ?? "暂无可靠金额"} evidence={evidenceByFact.awardAmount} onEvidenceFocus={onEvidenceFocus} />
            </>
          ) : (
            <p className="py-2 text-xs text-amber-700">当前合同没有结构化中标通知书信息。</p>
          )}
        </FactGroup>
      )}

      {(initiatedBy || createdAt) && (
        <FactGroup title="内审任务信息">
          <FactRow label="发起人" value={initiatedBy ?? "—"} />
          <FactRow label="创建时间" value={createdAt ? new Date(createdAt).toLocaleString("zh-CN") : "—"} />
        </FactGroup>
      )}
      </div>
    </details>
  );
}
