import { useMemo, useState } from "react";
import type { SpreadsheetHeaderMapping, SpreadsheetHeaderPreview } from "../../api";

export interface HeaderMappingSelection {
  headerRowNumber: number;
  mappings: SpreadsheetHeaderMapping[];
}

interface Section {
  key: "quote" | "blacklist";
  title: string;
  preview: SpreadsheetHeaderPreview;
  fields: Record<string, string>;
  requiredFields: string[];
}

interface Props {
  open: boolean;
  sections: Section[];
  onCancel: () => void;
  onConfirm: (selections: Record<Section["key"], HeaderMappingSelection>) => void;
}

function initialSelection(section: Section): HeaderMappingSelection {
  const rowNumber = section.preview.suggestedHeaderRowNumber
    ?? section.preview.rows.find((row) => row.cells.some((cell) => cell.trim()))?.rowNumber
    ?? section.preview.firstRowNumber;
  const row = section.preview.rows.find((item) => item.rowNumber === rowNumber);
  const suggested = new Map(section.preview.mappings.map((mapping) => [mapping.sourceColumnIndex, mapping]));
  return {
    headerRowNumber: rowNumber,
    mappings: (row?.cells ?? []).map((sourceHeader, sourceColumnIndex) => {
      const mapping = suggested.get(sourceColumnIndex);
      return {
        sourceHeader,
        sourceColumnIndex,
        standardField: mapping?.standardField ?? null,
        confidence: mapping?.confidence ?? null,
      };
    }).filter((mapping) => mapping.sourceHeader.trim()),
  };
}

export function SpreadsheetHeaderMappingDialog({ open, sections, onCancel, onConfirm }: Props) {
  const defaults = useMemo(
    () => Object.fromEntries(sections.map((section) => [section.key, initialSelection(section)])) as Record<Section["key"], HeaderMappingSelection>,
    [sections],
  );
  const [selections, setSelections] = useState(defaults);
  const [error, setError] = useState<string | null>(null);

  if (!open) return null;

  const updateRow = (key: Section["key"], rowNumber: number) => {
    const section = sections.find((item) => item.key === key);
    if (!section) return;
    const row = section.preview.rows.find((item) => item.rowNumber === rowNumber);
    const previous = selections[key];
    const previousByColumn = new Map(previous.mappings.map((mapping) => [mapping.sourceColumnIndex, mapping]));
    setSelections((current) => ({
      ...current,
      [key]: {
        headerRowNumber: rowNumber,
        mappings: (row?.cells ?? []).map((sourceHeader, sourceColumnIndex) => {
          const old = previousByColumn.get(sourceColumnIndex);
          return {
            sourceHeader,
            sourceColumnIndex,
            standardField: old?.standardField ?? null,
            confidence: old?.confidence ?? null,
          };
        }).filter((mapping) => mapping.sourceHeader.trim()),
      },
    }));
  };

  const updateMapping = (key: Section["key"], column: number, standardField: string) => {
    setSelections((current) => ({
      ...current,
      [key]: {
        ...current[key],
        mappings: current[key].mappings.map((mapping) => mapping.sourceColumnIndex === column
          ? { ...mapping, standardField: standardField || null }
          : mapping),
      },
    }));
  };

  const confirm = () => {
    const missing = sections.find((section) => {
      const selected = selections[section.key].mappings.map((mapping) => mapping.standardField);
      return section.requiredFields.some((field) => !selected.includes(field));
    });
    if (missing) {
      setError(`${missing.title} 仍缺少必填字段，请完成映射。`);
      return;
    }
    setError(null);
    onConfirm(selections);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/40 p-4">
      <div className="max-h-[90vh] w-full max-w-4xl overflow-y-auto rounded-lg border border-slate-200 bg-white shadow-xl">
        <div className="border-b border-slate-200 px-5 py-4">
          <h2 className="text-base font-semibold text-slate-900">确认 Excel 表头映射</h2>
          <p className="mt-1 text-xs text-slate-500">AI 仅提供识别建议，确认后才会解析并写入分析结果。</p>
        </div>
        <div className="space-y-5 px-5 py-4">
          {sections.map((section) => {
            const selection = selections[section.key];
            return (
              <section key={section.key} className="rounded border border-slate-200 p-3">
                <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <h3 className="text-sm font-medium text-slate-900">{section.title}</h3>
                    <p className="mt-1 text-[11px] text-slate-500">
                      {section.preview.aiAvailable ? `AI 已识别${section.preview.aiModel ? `（${section.preview.aiModel}）` : ""}，请确认。` : "AI 不可用，请手工选择表头行和字段。"}
                    </p>
                  </div>
                  <label className="flex items-center gap-2 text-xs text-slate-600">
                    表头行
                    <select
                      value={selection.headerRowNumber}
                      onChange={(event) => updateRow(section.key, Number(event.target.value))}
                      className="rounded border border-slate-300 bg-white px-2 py-1.5 text-xs text-slate-900"
                    >
                      {section.preview.rows.map((row) => <option key={row.rowNumber} value={row.rowNumber}>第 {row.rowNumber} 行</option>)}
                    </select>
                  </label>
                </div>
                <div className="overflow-x-auto rounded border border-slate-200">
                  <table className="w-full text-xs">
                    <thead className="bg-slate-50">
                      <tr><th className="px-3 py-2 text-left font-medium text-slate-600">文件表头</th><th className="px-3 py-2 text-left font-medium text-slate-600">标准字段</th><th className="px-3 py-2 text-left font-medium text-slate-600">置信度</th></tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {selection.mappings.map((mapping) => (
                        <tr key={`${section.key}-${mapping.sourceColumnIndex}`}>
                          <td className="px-3 py-2 font-medium text-slate-900">{mapping.sourceHeader}</td>
                          <td className="px-3 py-2">
                            <select
                              value={mapping.standardField ?? ""}
                              onChange={(event) => updateMapping(section.key, mapping.sourceColumnIndex, event.target.value)}
                              className="w-full min-w-48 rounded border border-slate-300 bg-white px-2 py-1.5 text-xs text-slate-900"
                            >
                              <option value="">不映射</option>
                              {Object.entries(section.fields).map(([field, label]) => <option key={field} value={field}>{label}</option>)}
                            </select>
                          </td>
                          <td className="px-3 py-2 font-mono text-slate-500">{mapping.confidence == null ? "手工" : `${Math.round(mapping.confidence * 100)}%`}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            );
          })}
          {error && <div className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">{error}</div>}
        </div>
        <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button type="button" onClick={onCancel} className="rounded border border-slate-300 px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50">取消</button>
          <button type="button" onClick={confirm} className="rounded bg-blue-600 px-3 py-1.5 text-xs text-white hover:bg-blue-700">确认并开始分析</button>
        </div>
      </div>
    </div>
  );
}
