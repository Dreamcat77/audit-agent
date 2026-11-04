import { useSyncExternalStore } from 'react';
import { analyzeProcurementIntake } from '../../../api/audit';
import { userFacingError } from '../../../api/userFacingError';
import { ProcurementIntakeReviewOperation } from './procurementIntakeReviewOperation';

const operations = new Map<string, ProcurementIntakeReviewOperation>();
export function intakeReviewOperation(actorId: string, projectId: string) {
  const key = JSON.stringify([actorId, projectId]);
  let operation = operations.get(key);
  if (!operation) {
    operation = new ProcurementIntakeReviewOperation(() => analyzeProcurementIntake(projectId),
      cause => userFacingError(cause, '采购需求已保存，本次分析结果暂时无法确认；请先查看已保存材料和结果，无需重新上传。'));
    operations.set(key, operation);
  }
  return operation;
}
export function useIntakeReview(actorId: string, projectId: string) {
  const operation = intakeReviewOperation(actorId, projectId);
  const snapshot = useSyncExternalStore(operation.subscribe, operation.snapshot, operation.snapshot);
  return { operation, snapshot };
}
