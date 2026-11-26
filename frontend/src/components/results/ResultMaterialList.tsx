import {apiUrl} from '../../api/client';

export function ResultMaterialList({ names,originalUrls,onOpenOriginal }: { names: readonly string[];originalUrls?:readonly (string|null|undefined)[];onOpenOriginal?:(index:number)=>void }) {
  if (!names.length) return null;
  return <section className="rounded-xl border border-slate-200 bg-white px-5 py-4">
    <h3 className="text-sm font-semibold text-slate-800">本批材料（{names.length} 份）</h3>
    <ul className="mt-2 divide-y divide-slate-100 text-sm text-slate-700">
      {names.map((name, index) => <li key={index} className="flex items-start justify-between gap-4 py-2 [overflow-wrap:anywhere]">
        <span className="min-w-0">{name}</span>{originalUrls?.[index]&&(onOpenOriginal?
          <button type="button" onClick={()=>onOpenOriginal(index)} aria-label={`打开原文件：${name}`} className="result-source-action shrink-0">打开原文件</button>:
          <a href={apiUrl(originalUrls[index]!)} target="_blank" rel="noreferrer" aria-label={`查看原文件：${name}`} className="result-source-action shrink-0">原文件</a>)}
      </li>)}
    </ul>
  </section>;
}
