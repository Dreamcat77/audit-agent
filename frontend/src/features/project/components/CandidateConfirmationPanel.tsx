import { useEffect, useState } from "react";
import { addAmountOnlyLineItem, addBlankAwardNotice, confirmedPayloadFromCandidate,
  confirmedPayloadFromConfirmation, removeConfirmedLineItem, submitConfirmedContract,
  setConfirmedLineItemBasis, type CandidateConfirmation, type ConfirmedContractPayload,
  type PromotionValidationError } from "../candidateConfirmation";
import type { ContractCandidate } from "../types";
import { contractTypePresentation } from "../../contract/contractDetailsPresentation";
import { ChineseDateInput } from "../../../app/components/ui/chinese-date-input";

export function CandidateConfirmationPanel({ candidate, savedConfirmation, loading, error,
  canConfirm, canPromote, onConfirm, onPromote }:
  { candidate: ContractCandidate; loading: boolean; error: string | null;
    savedConfirmation: CandidateConfirmation | null;
    canConfirm: boolean; canPromote: boolean;
    onConfirm: (payload: ConfirmedContractPayload) => Promise<string | null>;
    onPromote: () => Promise<string | null> }) {
  const [payload, setPayload] = useState(() => confirmedPayloadFromCandidate(candidate));
  const [validationErrors, setValidationErrors] = useState<PromotionValidationError[]>([]);
  useEffect(() => {
    setPayload(savedConfirmation?.resultCandidateVersion === candidate.versionNo
      ? confirmedPayloadFromConfirmation(savedConfirmation)
      : confirmedPayloadFromCandidate(candidate));
    setValidationErrors([]);
  }, [candidate.id, candidate.versionNo, savedConfirmation?.confirmationId]);
  const field = (key: keyof ConfirmedContractPayload, value: string | number | null) =>
    setPayload((current) => ({ ...current, [key]: value }));
  const inputClass = "mt-1 w-full rounded border border-slate-200 px-2 py-1.5 text-sm";
  return <section className="mt-6 rounded-lg border border-blue-200 bg-blue-50/40 p-5">
    <h2 className="font-semibold text-slate-900">正式合同确认</h2>
    <p className="mt-1 text-xs text-slate-600">上方为机器提取事实；以下是本次正式确认值。编辑不会改写已保存的机器提取记录。</p>
    <div className="mt-4 grid gap-3 md:grid-cols-2">
      {([
        ["contractNo", "合同编号"], ["contractName", "合同名称"],
        ["partyA", "甲方"], ["partyB", "乙方"], ["partyBCreditCode", "乙方信用代码"],
        ["amountInWords", "金额大写"],
      ] as Array<[keyof ConfirmedContractPayload, string]>).map(([key, label]) => <label key={key} className="text-xs text-slate-600">{label}
        <input className={inputClass} value={(payload[key] as string | null) ?? ""}
          onChange={(event) => field(key, event.target.value || null)}/></label>)}
      <label className="text-xs text-slate-600">合同类型
        <select className={inputClass} value={payload.contractType ?? ""}
          onChange={(event) => field("contractType", event.target.value || null)}>
          {!payload.contractType && <option value="">请选择合同类型</option>}
          {payload.contractType && !["GOODS", "SERVICE", "CONSTRUCTION", "LEASE", "FRAMEWORK"]
            .includes(payload.contractType) && <option value={payload.contractType}>{contractTypePresentation(payload.contractType)}</option>}
          <option value="GOODS">采购合同</option><option value="SERVICE">服务合同</option>
          <option value="CONSTRUCTION">工程合同</option><option value="LEASE">租赁合同</option>
          <option value="FRAMEWORK">框架协议</option>
        </select>
      </label>
      <label className="text-xs text-slate-600">合同金额（¥）
        <input className={inputClass} type="number" step="0.01" value={payload.contractAmount ?? ""}
          onChange={(event) => field("contractAmount", event.target.value === "" ? null : Number(event.target.value))}/></label>
    </div>
    <div className="mt-4 flex items-center justify-between gap-3">
      <h3 className="text-sm font-medium text-slate-800">合同金额分项</h3>
      <button type="button" onClick={() => setPayload((current) => addAmountOnlyLineItem(current))}
        className="rounded border border-blue-300 bg-white px-3 py-1.5 text-xs text-blue-700">新增仅金额分项</button>
    </div>
    {payload.lineItems.length === 0 && <p className="mt-2 text-xs text-slate-500">原始提取未形成金额分项，可按原件人工补录。</p>}
    {payload.lineItems.map((line, index) => <div key={`${line.itemKey}-${index}`} className="mt-3 grid gap-2 rounded bg-white p-3 md:grid-cols-6">
      <label className="text-xs text-slate-500">金额依据
        <select className={inputClass} value={line.basis ?? "QUANTITY_PRICE"}
          onChange={(event) => setPayload((current) => ({ ...current,
            lineItems: current.lineItems.map((item, itemIndex) => itemIndex !== index ? item
              : setConfirmedLineItemBasis(item, event.target.value as "QUANTITY_PRICE" | "AMOUNT_ONLY")),
          }))}>
          <option value="QUANTITY_PRICE">数量 × 单价</option>
          <option value="AMOUNT_ONLY">仅申报金额</option>
        </select>
      </label>
      {(["itemKey", "itemName", "quantity", "unitPrice", "declaredAmount"] as const).map((key) => <label key={key} className="text-xs text-slate-500">{{
        itemKey: "项目编号", itemName: "项目名称", quantity: "数量", unitPrice: "单价（¥）", declaredAmount: "申报金额（¥）",
      }[key]}
        <input className={inputClass} type={["quantity", "unitPrice", "declaredAmount"].includes(key) ? "number" : "text"}
          disabled={line.basis === "AMOUNT_ONLY" && (key === "quantity" || key === "unitPrice")}
          value={line[key] ?? ""} onChange={(event) => setPayload((current) => ({ ...current,
            lineItems: current.lineItems.map((item, itemIndex) => itemIndex !== index ? item : {
              ...item, [key]: ["quantity", "unitPrice", "declaredAmount"].includes(key)
                ? event.target.value === "" ? null : Number(event.target.value) : event.target.value,
            }),
          }))}/></label>)}
      <button type="button" onClick={() => setPayload((current) => removeConfirmedLineItem(current, index))}
        className="md:col-span-6 justify-self-start text-xs text-red-700">移除此分项</button>
    </div>)}
    {!payload.awardNotice && <div className="mt-4 rounded bg-white p-3">
      <p className="text-xs text-slate-500">原始提取未形成中标事实时，可依据已核验的中标材料人工补录。</p>
      <button type="button" onClick={() => setPayload((current) => addBlankAwardNotice(current))}
        className="mt-2 rounded border border-blue-300 px-3 py-1.5 text-xs text-blue-700">补录中标信息</button>
    </div>}
    {payload.awardNotice && <div className="mt-3 grid gap-3 rounded bg-white p-3 md:grid-cols-3">
      {([[
        "noticeNo", "中标通知书编号"], ["projectName", "中标项目名称"], ["winningBidder", "中标供应商"],
        ["winningBidderCreditCode", "中标供应商信用代码"], ["winningDate", "中标日期"],
      ] as const).map(([key, label]) => <label key={key} className="text-xs text-slate-500">{label}
          {key === "winningDate"
            ? <ChineseDateInput className={inputClass} value={payload.awardNotice?.winningDate ?? ""}
                onChange={(value) => setPayload((current) => ({ ...current,
                  awardNotice: current.awardNotice ? { ...current.awardNotice, winningDate: value || null } : null,
                }))}/>
            : <input className={inputClass} type="text" value={payload.awardNotice?.[key] ?? ""}
                onChange={(event) => setPayload((current) => ({ ...current,
                  awardNotice: current.awardNotice ? { ...current.awardNotice, [key]: event.target.value || null } : null,
                }))}/>}</label>)}
      <label className="text-xs text-slate-500">中标金额（¥）<input className={inputClass} type="number" step="0.01"
        value={payload.awardNotice.winningAmount ?? ""} onChange={(event) => setPayload((current) => ({ ...current,
          awardNotice: current.awardNotice ? { ...current.awardNotice,
            winningAmount: event.target.value === "" ? null : Number(event.target.value) } : null,
        }))}/></label>
      <button type="button" onClick={() => setPayload((current) => ({ ...current, awardNotice: null }))}
        className="md:col-span-3 justify-self-start text-xs text-red-700">清除中标信息</button>
    </div>}
    {validationErrors.length > 0 && <div role="alert" className="mt-3 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
      <p className="font-medium">请完善正式合同必填项：</p>
      <ul className="mt-1 list-disc space-y-1 pl-5">{validationErrors.map((validation) =>
        <li key={validation.field}>{validation.message}</li>)}</ul>
    </div>}
    {error && <p role="alert" className="mt-3 text-sm text-red-700">{error}</p>}
    {savedConfirmation && candidate.promotionState === "DRAFT" &&
      <p role="status" className="mt-3 text-sm text-emerald-800">人工确认已保存（版本 {savedConfirmation.resultCandidateVersion}）。
        可重试生成正式合同，重试不会新增确认。</p>}
    {canConfirm && <button type="button" disabled={loading || candidate.promotionState === "PROMOTED"}
      onClick={() => void submitConfirmedContract(payload, onConfirm)
        .then((submission) => setValidationErrors(submission.errors))}
      className="mt-4 rounded bg-blue-700 px-4 py-2 text-sm text-white disabled:opacity-50">
      {loading ? "正在确认合同内容…" : "确认合同内容"}
    </button>}
    {canPromote && savedConfirmation && candidate.promotionState === "DRAFT" && <button type="button"
      disabled={loading} onClick={() => void onPromote()}
      className="ml-3 mt-4 rounded border border-blue-700 bg-white px-4 py-2 text-sm text-blue-700 disabled:opacity-50">
      {loading ? "正在生成正式合同…" : "生成正式合同"}
    </button>}
  </section>;
}
