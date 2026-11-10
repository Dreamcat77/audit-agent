/** Processing progress is independent of the material coverage or review findings. */
export function historyStatus(status:string):{label:string;tone:'complete'|'processing'|'neutral'}{
  if(['OPEN','WAITING_UPLOAD','UPLOADING','UPLOADED','QUEUED','RUNNING','PROCESSING','PARSED','PARSING','ANALYZING','PENDING'].includes(status))
    return {label:'正在分析',tone:'processing'};
  if(['COMPLETED','REVIEWED','PARTIAL','DATA_INSUFFICIENT','WAITING_CONFIRMATION'].includes(status))
    return {label:'已完成',tone:'complete'};
  if(['FAILED','UNKNOWN','CANCELLED'].includes(status))return {label:'已结束',tone:'neutral'};
  return {label:'状态待核实',tone:'neutral'};
}
