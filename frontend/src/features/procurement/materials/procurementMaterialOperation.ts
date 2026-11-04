import { droppedFilePath } from './procurementFolderDrop.ts';

export interface MaterialOperation { key: string; name: string; size: number; lastModified: number; relativePath?: string; context?: {budgetYear:string;departmentCode:string;batchId?:string} }
export function materialOperationStorageKey(actor: string, namespace='procurement') { return `${namespace}-material-operation:${encodeURIComponent(actor)}`; }
export function readMaterialOperation(storage: Pick<Storage, 'getItem'>, actor: string): MaterialOperation | null {
  return readMaterialOperations(storage, actor)[0] ?? null;
}
export function readMaterialOperations(storage: Pick<Storage, 'getItem'>, actor: string, namespace='procurement'): MaterialOperation[] {
  const raw = storage.getItem(materialOperationStorageKey(actor,namespace));
  if (!raw) return [];
  const parsed = JSON.parse(raw);
  const values: MaterialOperation[] = Array.isArray(parsed) ? parsed : [parsed];
  for (const value of values) {
  if (!value) throw new Error('上传记录暂时无法读取，请联系管理员核对');
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.key) || typeof value.name !== 'string'
    || !Number.isSafeInteger(value.size) || value.size < 0 || !Number.isFinite(value.lastModified)
    || (value.relativePath !== undefined && (typeof value.relativePath !== 'string' || value.relativePath.length > 4096))
    || (value.context !== undefined && (!value.context || typeof value.context.budgetYear!=='string' || typeof value.context.departmentCode!=='string'
      || value.context.batchId!==undefined && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.context.batchId))))
    throw new Error('上传记录暂时无法读取，请联系管理员核对');
  }
  if (new Set(values.map(value => value.key)).size !== values.length) throw new Error('上传操作记录重复，请核对');
  return values;
}
export function writeMaterialOperations(storage: Pick<Storage, 'setItem' | 'removeItem'>, actor: string, operations: MaterialOperation[], namespace='procurement') {
  if (operations.length) storage.setItem(materialOperationStorageKey(actor,namespace), JSON.stringify(operations));
  else storage.removeItem(materialOperationStorageKey(actor,namespace));
}
export function sameMaterialFile(operation: MaterialOperation, file: Pick<File, 'name' | 'size' | 'lastModified'>) {
  return operation.name === file.name && operation.size === file.size && operation.lastModified === file.lastModified
    && normalizedMaterialPath(operation.relativePath ?? '') === normalizedMaterialPath(droppedFilePath(file as File));
}
export function normalizedMaterialPath(value: string) { return value.replace(/\\/g,'/').replace(/^\/+/, ''); }
