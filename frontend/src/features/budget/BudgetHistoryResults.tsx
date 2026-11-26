import { BudgetResultHistory } from './BudgetResultHistory';
import { BudgetFileResults } from './BudgetFileResults';
import type { BudgetMaterialBatchHistoryRecord } from '../../api';
import {useLayoutEffect,useRef} from 'react';

export function BudgetHistoryResults({ entry, onEntryChange }: {
  entry: BudgetMaterialBatchHistoryRecord | null;
  onEntryChange: (entry: BudgetMaterialBatchHistoryRecord | null) => void;
}) {
  const detail=useRef<HTMLDivElement>(null);
  useLayoutEffect(()=>{if(entry)detail.current?.scrollIntoView({block:'start'});},[entry]);
  return <>
    <div hidden={entry !== null}>
      <BudgetResultHistory onOpen={onEntryChange} />
    </div>
    {entry !== null && <div ref={detail} className="budget-history-detail">
      <BudgetFileResults key={entry.batchId ?? entry.taskId} batchId={entry.batchId ?? undefined} taskId={entry.taskId ?? undefined}
        initialName={entry.projectName??undefined} initialFileCount={entry.fileCount}/>
    </div>}
  </>;
}
