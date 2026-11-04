import type {ProjectFileJobs} from "../data/projectDataSource";
import type {ProjectFile} from "../types";

export function ProjectFileProcessingPanel({jobs,files,error}: {
  jobs:ProjectFileJobs|null;files:readonly ProjectFile[];error:string|null;
}) {
  if(!jobs&&!error)return null;
  const names=new Map(files.map(file=>[file.id,file.originalFilename]));
  return <section aria-label="材料后台处理进度" className="mt-4 rounded-lg border border-slate-200 bg-white p-4">
    <h2 className="text-sm font-semibold">材料处理进度</h2>
    {error&&<p role="alert" className="mt-2 text-xs text-amber-800">{error}</p>}
    {jobs&&<>
      <p className="mt-2 text-xs text-slate-600">已接收 {jobs.total} 个任务 · 已完成 {jobs.completed} · 未完成 {jobs.failed} · 暂未取得结果 {jobs.unknown}</p>
      {!jobs.workerEnabled&&jobs.items.some(job=>job.status==="QUEUED")&&<p className="mt-2 text-xs text-amber-800">后台处理尚未启用，文件已保存并排队，等待恢复处理。</p>}
      <ul className="mt-3 space-y-2 text-xs">
        {jobs.items.map(job=>{
          const file=files.find(file=>file.id===job.fileId);
          const image=file?.mediaType.startsWith("image/");
          const completedLabel=file?.parseStatus==="UNSUPPORTED"?"格式暂不支持，已保留处理结果"
            :file?.category==="PACKAGE"?"资料包展开完成，子文件分别处理":"材料处理完成";
          const label=job.status==="QUEUED"?"已接收，排队解析":job.status==="PROCESSING"?(image?"OCR处理中":"解析 / OCR处理中")
            :job.status==="SUCCEEDED"?completedLabel:job.status==="FAILED"?"处理未完成，已保留原因":"本次处理暂未取得结果";
          return <li key={job.jobId} className="flex flex-wrap items-center justify-between gap-2 rounded bg-slate-50 p-2">
            <span>{names.get(job.fileId)??"已保存材料"}：{label}</span>
          </li>;
        })}
      </ul>
      <p className="mt-2 text-xs text-slate-500">系统会自动判断材料并整理结果，用户无需逐份核对或填写。</p>
    </>}
  </section>;
}
