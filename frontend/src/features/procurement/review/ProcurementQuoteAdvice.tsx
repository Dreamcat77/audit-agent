import type { ProcurementQuoteCheckResponse } from '../../../api/types';

const money = (value: number) => `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export function ProcurementQuoteAdvice({ result }: { result: ProcurementQuoteCheckResponse }) {
  const detail = result.comparison;
  if (!detail) return null;
  return <section className="space-y-3 rounded-lg border border-blue-200 bg-blue-50/40 p-4 text-xs text-slate-700">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div><h4 className="font-semibold text-slate-900">三方比价建议</h4>
        <p className="mt-1">{detail.recommendedAmount == null ? '待补充可比条件或有效报价后推荐' : `可比最低总价：${money(detail.recommendedAmount)}`}</p>
        {detail.recommendedSuppliers.length > 0 && <p className="mt-1 font-medium text-blue-800">{detail.recommendedSuppliers.join('、')}{detail.recommendedSuppliers.length > 1 ? '（并列）' : ''}</p>}
      </div>
      <div className="text-right"><p>比价上限 {money(detail.effectiveCeiling)}</p>
        {detail.savingAmount != null && <p className="mt-1">预算结余 {money(detail.savingAmount)}</p>}</div>
    </div>
    <p className="leading-relaxed">价格建议须在资质、规格响应及费用服务口径确认后采用。报价趋同等风险仍需处理。</p>
    {detail.conditions.map((condition, index) => <p key={index} className="leading-relaxed">{condition}</p>)}
    {detail.notes.map((note, index) => <p key={index} className="text-slate-600">{note}</p>)}
    {detail.items.length > 0 && <details>
      <summary className="cursor-pointer font-medium text-blue-800">查看 {detail.items.length} 个品目的报价与最低价</summary>
      <div className="mt-3 overflow-x-auto rounded border border-slate-200 bg-white">
        <table className="w-full text-xs"><thead className="bg-slate-50 text-left"><tr>
          <th className="px-3 py-2">品目 / 规格</th><th className="px-3 py-2">数量</th>
          {result.quotes.map(quote => <th key={quote.supplierName} className="whitespace-nowrap px-3 py-2 text-right">{quote.supplierName}</th>)}
          <th className="px-3 py-2 text-right">单项最低总价</th>
        </tr></thead><tbody className="divide-y divide-slate-100">{detail.items.map(item => <tr key={item.itemCode}>
          <td className="min-w-48 px-3 py-2"><p className="font-medium">{item.itemName}</p><p className="mt-1 text-slate-500">{item.specification}</p></td>
          <td className="whitespace-nowrap px-3 py-2">{item.quantity} {item.unit}</td>
          {result.quotes.map(quote => { const offer = item.offers.find(o => o.supplierName === quote.supplierName); return <td key={quote.supplierName} className="whitespace-nowrap px-3 py-2 text-right">
            {offer ? <><p>{money(offer.totalPrice)}</p><p className="mt-1 text-slate-500">单价 {money(offer.unitPrice)}</p></> : '未报价'}</td>; })}
          <td className="whitespace-nowrap px-3 py-2 text-right"><p>{money(item.lowestAmount)}</p><p className="mt-1 text-slate-500">{item.lowestSuppliers.join('、')}</p></td>
        </tr>)}</tbody></table>
      </div>
      <p className="mt-2 text-slate-500">单项最低价用于核对明细；供应商建议按完整清单总价比较，不拼接不同供应商报价。</p>
    </details>}
  </section>;
}
