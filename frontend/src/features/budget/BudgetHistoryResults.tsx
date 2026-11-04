import { BudgetResultHistory } from './BudgetResultHistory';
import { BudgetFileResults } from './BudgetFileResults';
import type { BudgetMaterialBatchHistoryRecord } from '../../api';

export function BudgetHistoryResults({ entry, onEntryChange }: {
  entry: BudgetMaterialBatchHistoryRecord | null;
  onEntryChange: (entry: BudgetMaterialBatchHistoryRecord | null) => void;
}) {
  return <>
    <div hidden={entry !== null}>
      <BudgetResultHistory onOpen={onEntryChange} />
    </div>
    {entry !== null && <div className="budget-history-detail">
      <BudgetFileResults key={entry.batchId ?? entry.taskId} batchId={entry.batchId ?? undefined} taskId={entry.taskId ?? undefined} />
    </div>}
  </>;
}
