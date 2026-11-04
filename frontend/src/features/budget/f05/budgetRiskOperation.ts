import { createOperationKey } from '../../../api/operationKey.ts';
const prefix='budget-material-risk:pending:';
export function riskOperation(keys:string[],storage:Storage=window.sessionStorage):{id:string;storageKey:string} {
  const storageKey=prefix+encodeURIComponent([...keys].sort().join(','));
  const old=storage.getItem(storageKey);if(old)return {id:old,storageKey};
  const id=createOperationKey();storage.setItem(storageKey,id);return {id,storageKey};
}
