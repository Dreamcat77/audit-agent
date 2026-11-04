import { request } from '../../../api/client';
import type { ProcurementMaterialResult } from '../../../api/types';
import { RESUMABLE_THRESHOLD,uploadMaterialResumable } from './resumableMaterialUpload';

const path = '/api/v1/procurement/materials';
export function uploadProcurementMaterial(file: File, key: string) {
  if(file.size>=RESUMABLE_THRESHOLD)return uploadMaterialResumable<ProcurementMaterialResult>('procurement',file,key);
  const body = new FormData(); body.append('file', file);
  return request<ProcurementMaterialResult>(path, { method: 'POST', body, headers: { 'Idempotency-Key': key } });
}
export function getProcurementMaterialOperation(key: string) {
  return request<ProcurementMaterialResult | null>(`${path}/operations/${encodeURIComponent(key)}`);
}
export function listProcurementMaterials() { return request<ProcurementMaterialResult[]>(path); }
export function getProcurementMaterial(id: string) {
  return request<ProcurementMaterialResult>(`${path}/${encodeURIComponent(id)}`);
}
export function retryProcurementMaterial(id: string) {
  return request<ProcurementMaterialResult>(`${path}/${encodeURIComponent(id)}/retry`, { method: 'POST' });
}
