import { BudgetCheckPage } from "../../app/BudgetCheckPage";

/**
 * Keep the main application shell stable while the F01-owned budget UI
 * is provided by the f01 implementation.
 */
export function BudgetAuditWorkspace({
  onOpenProcurement,
}: {
  onOpenProcurement?: (projectId: string) => void;
}) {
  return <BudgetCheckPage onOpenProcurement={onOpenProcurement} />;
}
