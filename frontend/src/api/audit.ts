/**
 * 审计系统 API 服务
 * 对应后端 8 个功能模块
 */

import { apiUrl, request } from './client';
import { createOperationKey } from './operationKey';
import type {
  BudgetCheckResponse,
  BudgetReviewResponse,
  BudgetMaterialBatchView,
  BudgetMaterialBatchHistoryPage,
  BudgetReviewTaskResponse,
  BudgetReviewItemResponse,
  BudgetValidationBatchResponse,
  BudgetValidationRecordPage,
  BudgetConfirmationProgress,
  BudgetPendingConfirmationResponse,
  BudgetConfirmLog,
  BlacklistCheckRequest,
  BlacklistCheckResponse,
  BlacklistBatchCheckRequest,
  BlacklistBatchCheckResponse,
  BlacklistAlertItem,
  BlacklistEntry,
  BlacklistEntryRequest,
  BlacklistProcessRequest,
  BlacklistPage,
  BlacklistRule,
  BlacklistRuleRequest,
  ProcurementQuoteCheckResponse,
  SpreadsheetHeaderPreview,
  SpreadsheetHeaderMapping,
  ProcurementProjectPage,
  ProcurementSourceProject,
  ProcurementTenderDocument,
  ProcurementTenderFinding,
  ProcurementTenderScanResponse,
  ProcurementReviewMaterialResponse,
  ProcurementMaterialBatchResponse,
  ProcurementReviewWorkflow,
  ProcurementCollusionDocument,
  ProcurementCollusionReview,
  ProcurementAgentReview,
  ProcurementFieldConfirmation,
  ProcurementCriticalRiskConfirmation,
  ProcurementReviewReport,
  CreateProcurementProjectRequest,
  UpdateProcurementProjectRequest,
  CreateProcurementProjectResponse,
  ProcurementApproval,
  ProcurementApprovalOperation,
  ProcurementApprovalTask,
  ProcurementNotification,
  OperationAuditRecord,
  SplitRiskCheckRequest,
  SplitRiskCheckResponse,
  ContractStructureCheckRequest,
  ContractStructureCheckResponse,
  ContractPeriodCheckRequest,
  ContractPeriodCheckResponse,
  CollusionCheckRequest,
  CollusionCheckResponse,
  AttachmentCheckRequest,
  AttachmentCheckResponse,
  ClauseRiskCheckRequest,
  ClauseRiskCheckResponse,
} from './types';

// ==================== F01 预算审批校验 ====================

/**
 * 单个预算审批校验
 */
export async function checkBudget(procurementId: string): Promise<BudgetCheckResponse> {
  return request<BudgetCheckResponse>('/api/v1/budget/check', {
    method: 'POST',
    body: { procurementId },
  });
}

/**
 * 批量预算审批校验（逐条调用）
 */
export async function batchCheckBudget(procurementIds: string[]): Promise<BudgetCheckResponse[]> {
  return Promise.all(procurementIds.map(id => checkBudget(id)));
}

export async function uploadBudgetMaterial(
  file: File,
  budgetYear?: string,
  departmentCode?: string
): Promise<BudgetReviewTaskResponse> {
  const body = new FormData();
  body.append('file', file);
  if (budgetYear) body.append('budgetYear', budgetYear);
  if (departmentCode) body.append('departmentCode', departmentCode);
  return request<BudgetReviewTaskResponse>('/api/v1/budget/materials', {
    method: 'POST',
    body,
  });
}

export async function uploadAndValidateBudget(
  file: File,
  budgetYear?: string,
  departmentCode?: string
): Promise<BudgetValidationBatchResponse> {
  const body = new FormData();
  body.append('file', file);
  if (budgetYear) body.append('budgetYear', budgetYear);
  if (departmentCode) body.append('departmentCode', departmentCode);
  return request<BudgetValidationBatchResponse>('/api/v1/budget/upload-and-validate', {
    method: 'POST',
    body,
  });
}

export async function listBudgetValidationRecords(params?: {
  batchId?: string;
  budgetYear?: string;
  departmentCode?: string;
  fileName?: string;
  createdFrom?: string;
  createdTo?: string;
  page?: number;
  size?: number;
}): Promise<BudgetValidationRecordPage> {
  return request<BudgetValidationRecordPage>('/api/v1/budget/validation-records', {
    method: 'GET',
    params,
  });
}

export async function getBudgetValidationRecord(
  batchId: string
): Promise<BudgetValidationBatchResponse> {
  return request<BudgetValidationBatchResponse>(`/api/v1/budget/validation-records/${batchId}`, {
    method: 'GET',
  });
}

export async function reviewBudgetMaterial(taskId: string): Promise<BudgetReviewResponse> {
  return request<BudgetReviewResponse>(`/api/v1/budget/reviews/${taskId}`, {
    method: 'POST',
  });
}

export async function getBudgetReview(taskId: string): Promise<BudgetReviewResponse> {
  return request<BudgetReviewResponse>(`/api/v1/budget/reviews/${taskId}`, {
    method: 'GET',
  });
}

export async function getBudgetMaterialBatch(batchId: string): Promise<BudgetMaterialBatchView> {
  return request<BudgetMaterialBatchView>(`/api/v1/budget/material-uploads/batches/${encodeURIComponent(batchId)}`);
}

export async function listBudgetMaterialBatches(params?: {
  fileName?: string;
  createdFrom?: string;
  createdTo?: string;
  page?: number;
  size?: number;
}): Promise<BudgetMaterialBatchHistoryPage> {
  return request<BudgetMaterialBatchHistoryPage>('/api/v1/budget/material-uploads/batches', {
    method: 'GET',
    params,
  });
}

export async function getBudgetReviewExceptions(taskId: string): Promise<BudgetReviewItemResponse[]> {
  return request<BudgetReviewItemResponse[]>(`/api/v1/budget/reviews/${taskId}/exceptions`, {
    method: 'GET',
  });
}

export async function getBudgetReviewItem(taskId: string, itemId: string): Promise<BudgetReviewItemResponse> {
  return request<BudgetReviewItemResponse>(`/api/v1/budget/reviews/${taskId}/items/${itemId}`, {
    method: 'GET',
  });
}

export async function confirmBudgetRow(data: {
  batchId: string;
  rowNumber: number;
  remark?: string;
}): Promise<BudgetConfirmationProgress> {
  return request<BudgetConfirmationProgress>('/api/v1/budget/validation/confirm-row', {
    method: 'POST',
    body: data,
  });
}

export async function getBudgetPendingConfirmation(
  batchId: string,
): Promise<BudgetPendingConfirmationResponse> {
  return request<BudgetPendingConfirmationResponse>(
    `/api/v1/budget/validation/pending-confirm?batchId=${encodeURIComponent(batchId)}`,
    { method: 'GET' },
  );
}

export async function getBudgetConfirmLogs(batchId: string): Promise<BudgetConfirmLog[]> {
  return request<BudgetConfirmLog[]>(
    `/api/v1/budget/validation/confirm-logs?batchId=${encodeURIComponent(batchId)}`,
    { method: 'GET' },
  );
}

// ==================== F03 供应商黑名单报警 ====================

/** 供应商黑名单命中检查 */
export async function checkBlacklist(data: BlacklistCheckRequest): Promise<BlacklistCheckResponse> {
  return request<BlacklistCheckResponse>('/api/v1/blacklist/check', {
    method: 'POST',
    body: data,
  });
}

/** 批量检查询比价材料中的供应商黑名单命中情况 */
export async function checkBlacklistBatch(
  data: BlacklistBatchCheckRequest,
): Promise<BlacklistBatchCheckResponse> {
  return request<BlacklistBatchCheckResponse>('/api/v1/blacklist/check-batch', {
    method: 'POST',
    body: data,
  });
}

/** 上传询比价 Excel 并批量检查供应商黑名单 */
export async function uploadBlacklistBatch(
  file: File,
  batchId: string,
): Promise<BlacklistBatchCheckResponse> {
  const body = new FormData();
  body.append('file', file);
  body.append('batchId', batchId);
  return request<BlacklistBatchCheckResponse>('/api/v1/blacklist/check-batch-upload', {
    method: 'POST',
    body,
  });
}

export async function previewBlacklistBatchHeaders(file: File): Promise<SpreadsheetHeaderPreview> {
  const body = new FormData();
  body.append('file', file);
  return request<SpreadsheetHeaderPreview>('/api/v1/blacklist/check-batch/headers/preview', {
    method: 'POST', body,
  });
}

export async function uploadBlacklistBatchConfirmed(
  file: File,
  batchId: string,
  headerRowNumber: number,
  mappings: SpreadsheetHeaderMapping[],
): Promise<BlacklistBatchCheckResponse> {
  const body = new FormData();
  body.append('file', file);
  body.append('batchId', batchId);
  body.append('headerRowNumber', String(headerRowNumber));
  body.append('mappings', JSON.stringify(mappings));
  return request<BlacklistBatchCheckResponse>('/api/v1/blacklist/check-batch-upload-confirmed', {
    method: 'POST', body,
  });
}

/** 查询报警记录列表 */
export async function listBlacklistAlerts(status?: string): Promise<BlacklistAlertItem[]> {
  const params = status ? { status } : undefined;
  return request<BlacklistAlertItem[]>('/api/v1/blacklist/alerts', {
    method: 'GET',
    params,
  });
}

export async function queryBlacklistAlerts(params: {
  batchId?: string;
  alertLevel?: string;
  status?: string;
  supplierName?: string;
  page?: number;
  size?: number;
}): Promise<BlacklistPage<BlacklistAlertItem>> {
  return request<BlacklistPage<BlacklistAlertItem>>('/api/v1/blacklist/alerts', {
    method: 'POST',
    body: { page: 1, size: 20, ...params },
  });
}

/** 查询单条报警详情 */
export async function getBlacklistAlert(id: string): Promise<BlacklistAlertItem> {
  return request<BlacklistAlertItem>(`/api/v1/blacklist/alerts/${id}`, {
    method: 'GET',
  });
}

/** 处理报警（标记已处理/已忽略） */
export async function processBlacklistAlert(id: string, data: BlacklistProcessRequest): Promise<BlacklistAlertItem> {
  return request<BlacklistAlertItem>(`/api/v1/blacklist/alerts/${id}/process`, {
    method: 'PUT',
    body: data,
  });
}

/** 新增黑名单条目 */
export async function addBlacklistEntry(data: BlacklistEntryRequest): Promise<BlacklistEntry> {
  return request<BlacklistEntry>('/api/v1/blacklist/entries', {
    method: 'POST',
    body: data,
  });
}

export async function updateBlacklistEntry(id: string, data: BlacklistEntryRequest): Promise<BlacklistEntry> {
  return request<BlacklistEntry>(`/api/v1/blacklist/entries/${id}`, {
    method: 'PUT',
    body: data,
  });
}

/** 人工移除黑名单条目（停用并记录操作原因） */
export async function removeBlacklistEntry(id: string, reason: string): Promise<BlacklistEntry> {
  return request<BlacklistEntry>(`/api/v1/blacklist/entries/${id}/remove`, {
    method: 'POST',
    body: { reason },
  });
}

export async function deleteBlacklistEntry(id: string): Promise<void> {
  await request<null>(`/api/v1/blacklist/entries/${id}`, {
    method: 'DELETE',
  });
}

/** 查询黑名单条目列表 */
export async function listBlacklistEntries(): Promise<BlacklistEntry[]> {
  return request<BlacklistEntry[]>('/api/v1/blacklist/entries', {
    method: 'GET',
  });
}

export async function queryBlacklistEntries(params: {
  keyword?: string;
  blacklistType?: string;
  active?: boolean;
  page?: number;
  size?: number;
}): Promise<BlacklistPage<BlacklistEntry>> {
  return request<BlacklistPage<BlacklistEntry>>('/api/v1/blacklist/entries/query', {
    method: 'POST',
    body: { page: 1, size: 20, ...params },
  });
}

export async function importBlacklistEntries(file: File): Promise<{ imported: number; failed: number; errors: string[] }> {
  const body = new FormData();
  body.append('file', file);
  return request<{ imported: number; failed: number; errors: string[] }>('/api/v1/blacklist/entries/import', {
    method: 'POST',
    body,
  });
}

export async function listBlacklistRules(): Promise<BlacklistRule[]> {
  return request<BlacklistRule[]>('/api/v1/blacklist/rules', { method: 'GET' });
}

export async function addBlacklistRule(data: BlacklistRuleRequest): Promise<BlacklistRule> {
  return request<BlacklistRule>('/api/v1/blacklist/rules', { method: 'POST', body: data });
}

export async function updateBlacklistRule(id: string, data: BlacklistRuleRequest): Promise<BlacklistRule> {
  return request<BlacklistRule>(`/api/v1/blacklist/rules/${id}`, { method: 'PUT', body: data });
}

export async function deleteBlacklistRule(id: string): Promise<void> {
  await request<null>(`/api/v1/blacklist/rules/${id}`, { method: 'DELETE' });
}

// ==================== F06 询比价异常报警 ====================

/** 上传询比价 Excel 并分析预算超支、报价离散度和可疑供应商。 */
export async function uploadProcurementQuoteCheck(
  file: File,
  batchId: string,
): Promise<ProcurementQuoteCheckResponse> {
  const body = new FormData();
  body.append('file', file);
  body.append('batchId', batchId);
  return request<ProcurementQuoteCheckResponse>('/api/v1/procurement/quote-checks/upload', {
    method: 'POST',
    body,
  });
}

export async function previewProcurementQuoteHeaders(file: File): Promise<SpreadsheetHeaderPreview> {
  const body = new FormData();
  body.append('file', file);
  return request<SpreadsheetHeaderPreview>('/api/v1/procurement/quote-checks/headers/preview', {
    method: 'POST', body,
  });
}

export async function uploadProcurementQuoteCheckConfirmed(
  file: File,
  batchId: string,
  headerRowNumber: number,
  mappings: SpreadsheetHeaderMapping[],
): Promise<ProcurementQuoteCheckResponse> {
  const body = new FormData();
  body.append('file', file);
  body.append('batchId', batchId);
  body.append('headerRowNumber', String(headerRowNumber));
  body.append('mappings', JSON.stringify(mappings));
  return request<ProcurementQuoteCheckResponse>('/api/v1/procurement/quote-checks/upload-confirmed', {
    method: 'POST', body,
  });
}

/** 查询指定批次最近一次询比价分析结果。 */
export async function getProcurementQuoteCheck(
  batchId: string,
): Promise<ProcurementQuoteCheckResponse> {
  return request<ProcurementQuoteCheckResponse>(
    `/api/v1/procurement/quote-checks/${encodeURIComponent(batchId)}`,
    { method: 'GET' },
  );
}

// ==================== F10 招标文件合规审查 ====================

export async function uploadProcurementTenderReview(
  projectId: string,
  file: File,
  refresh = false,
): Promise<ProcurementTenderDocument> {
  const body = new FormData();
  body.append('file', file);
  return request<ProcurementTenderDocument>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/tender-reviews`,
    { method: 'POST', params: { refresh: String(refresh) }, body },
  );
}

export async function getLatestProcurementTenderReview(
  projectId: string,
): Promise<ProcurementTenderDocument | null> {
  return request<ProcurementTenderDocument | null>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/tender-reviews/latest`,
    { method: 'GET' },
  );
}

export async function scanProcurementTenderReview(
  projectId: string,
  reviewId: string,
): Promise<ProcurementTenderScanResponse> {
  return request<ProcurementTenderScanResponse>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/tender-reviews/${encodeURIComponent(reviewId)}/scan`,
    { method: 'POST' },
  );
}

export async function listProcurementTenderFindings(
  projectId: string,
  reviewId: string,
): Promise<ProcurementTenderFinding[]> {
  return request<ProcurementTenderFinding[]>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/tender-reviews/${encodeURIComponent(reviewId)}/findings`,
    { method: 'GET' },
  );
}

// ==================== 采购项目查询和规则预审 ====================

export async function uploadProcurementReviewMaterial(projectId: string, file: File): Promise<ProcurementReviewMaterialResponse> {
  const form = new FormData();
  form.append('file', file);
  return request<ProcurementReviewMaterialResponse>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-materials/upload`,
    { method: 'POST', body: form },
  );
}

export async function uploadProcurementMaterialBatch(projectId: string, files: File[]): Promise<ProcurementMaterialBatchResponse> {
  const form = new FormData();
  files.forEach(file => form.append('files', file));
  return request<ProcurementMaterialBatchResponse>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-materials/batch`, { method: 'POST', body: form });
}
export async function getProcurementMaterialBatch(projectId: string): Promise<ProcurementMaterialBatchResponse> {
  return request<ProcurementMaterialBatchResponse>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-materials/current`);
}
export async function stageProcurementMaterials(projectId: string, files: File[]): Promise<ProcurementMaterialBatchResponse> {
  if(files.some(file=>file.size>=8*1024*1024)) {
    const {uploadMaterialResumable}=await import('../features/procurement/materials/resumableMaterialUpload');
    for(const file of files)await uploadMaterialResumable<ProcurementMaterialBatchResponse>('project',file,undefined,{projectId});
    return getProcurementMaterialBatch(projectId);
  }
  const form = new FormData();
  files.forEach(file => form.append('files', file));
  return request<ProcurementMaterialBatchResponse>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-materials/uploads`, { method: 'POST', body: form });
}
export async function analyzeProcurementMaterials(projectId: string, sourceIds: string[]): Promise<ProcurementMaterialBatchResponse> {
  return request<ProcurementMaterialBatchResponse>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-materials/analyze`, { method: 'POST', body: { sourceIds } });
}
export function analyzeProcurementIntake(projectId: string) {
  return request<ProcurementMaterialBatchResponse>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-materials/from-intake/analyze`, { method: 'POST' });
}
export async function confirmProcurementMaterialBidder(projectId: string, sourceId: string, bidderName: string): Promise<ProcurementMaterialBatchResponse> {
  return request<ProcurementMaterialBatchResponse>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-materials/${encodeURIComponent(sourceId)}/bidder`, { method: 'POST', body: { bidderName } });
}

/** 查询采购项目列表。后端分页从 1 开始。 */
export async function listProcurementProjects(params?: {
  page?: number;
  size?: number;
  status?: string;
  keyword?: string;
}): Promise<ProcurementProjectPage> {
  return request<ProcurementProjectPage>('/api/v1/procurement/projects', {
    method: 'GET',
    params: {
      page: String(params?.page ?? 1),
      size: String(params?.size ?? 10),
      ...(params?.status ? { status: params.status } : {}),
      ...(params?.keyword ? { keyword: params.keyword } : {}),
    },
  });
}

/** 查询指定采购项目的规则预审结果。 */
export async function getProcurementAgentReview(projectId: string): Promise<ProcurementAgentReview | null> {
  return request<ProcurementAgentReview>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/agent-review`,
    { method: 'GET' },
  );
}

// ==================== 采购审查八步向导 ====================
export async function startProcurementReviewWorkflow(projectId: string): Promise<ProcurementReviewWorkflow> {
  return request<ProcurementReviewWorkflow>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-workflow`, { method: 'POST' });
}
export async function getProcurementReviewWorkflow(projectId: string): Promise<ProcurementReviewWorkflow> {
  return request<ProcurementReviewWorkflow>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-workflow`, { method: 'GET' });
}
export async function runProcurementReviewBlacklistCheck(projectId: string): Promise<BlacklistBatchCheckResponse> {
  return request<BlacklistBatchCheckResponse>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-workflow/blacklist-check`, { method: 'POST' });
}
export async function uploadProcurementCollusionDocument(projectId: string, data: {
  file: File; bidderName: string; ipAddress?: string; handlerName?: string;
}): Promise<ProcurementCollusionDocument> {
  const body = new FormData(); body.append('file', data.file); body.append('bidderName', data.bidderName);
  if (data.ipAddress?.trim()) body.append('ipAddress', data.ipAddress.trim());
  if (data.handlerName?.trim()) body.append('handlerName', data.handlerName.trim());
  return request<ProcurementCollusionDocument>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/collusion-reviews/documents`, { method: 'POST', body });
}
export async function scoreProcurementCollusion(projectId: string): Promise<ProcurementCollusionReview> {
  return request<ProcurementCollusionReview>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/collusion-reviews/score`, { method: 'POST' });
}
export async function getProcurementCollusionReview(projectId: string): Promise<ProcurementCollusionReview> {
  return request<ProcurementCollusionReview>(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/collusion-reviews/latest`, { method: 'GET' });
}

/** 执行一次新的预审并覆盖项目已有的最新快照。 */
export async function refreshProcurementAgentReview(projectId: string): Promise<ProcurementAgentReview> {
  return request<ProcurementAgentReview>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/agent-review`,
    { method: 'POST', params: { refresh: 'true' } },
  );
}

export async function listProcurementFieldConfirmations(
  projectId: string,
): Promise<ProcurementFieldConfirmation[]> {
  return request<ProcurementFieldConfirmation[]>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/agent-review/confirmations`,
    { method: 'GET' },
  );
}

export async function confirmProcurementField(
  projectId: string,
  fieldCode: string,
  confirmationNote?: string,
): Promise<ProcurementFieldConfirmation> {
  return request<ProcurementFieldConfirmation>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/agent-review/confirmations`,
    { method: 'POST', body: { fieldCode, confirmationNote: confirmationNote || null } },
  );
}

/** 生成采购项目统一审查报告，汇总预审、F06 和 F03 已有结果。 */
export async function generateProcurementReviewReport(projectId: string): Promise<ProcurementReviewReport> {
  return request<ProcurementReviewReport>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-report`,
    { method: 'POST' },
  );
}

/** 查询采购项目最近一次统一审查报告。 */
export async function getProcurementReviewReport(projectId: string): Promise<ProcurementReviewReport> {
  return request<ProcurementReviewReport>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-report`,
    { method: 'GET' },
  );
}

/** 查询已完成资料确认和持久化解析、可用于新建采购需求的项目。 */
export async function listProcurementSourceProjects(keyword?: string): Promise<ProcurementSourceProject[]> {
  return request<ProcurementSourceProject[]>('/api/v1/procurement/projects/source-projects', {
    method: 'GET',
    params: {
      limit: '100',
      ...(keyword?.trim() ? { keyword: keyword.trim() } : {}),
    },
  });
}

export async function getProcurementCriticalRiskConfirmation(
  projectId: string,
  reportId: string,
): Promise<ProcurementCriticalRiskConfirmation | null> {
  return request<ProcurementCriticalRiskConfirmation | null>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-report/${encodeURIComponent(reportId)}/critical-risk-confirmation`,
    { method: 'GET' },
  );
}

export async function confirmProcurementCriticalRisk(
  projectId: string,
  reportId: string,
  idempotencyKey: string,
  confirmationNote?: string,
): Promise<ProcurementCriticalRiskConfirmation> {
  return request<ProcurementCriticalRiskConfirmation>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/review-report/${encodeURIComponent(reportId)}/critical-risk-confirmation`,
    {
      method: 'POST',
      body: { idempotencyKey, confirmationNote: confirmationNote?.trim() || null },
    },
  );
}

/** 新建采购需求，不在此阶段提交供应商信息。 */
export async function createProcurementProject(
  data: CreateProcurementProjectRequest,
): Promise<CreateProcurementProjectResponse> {
  return request<CreateProcurementProjectResponse>('/api/v1/procurement/projects', {
    method: 'POST',
    body: data,
  });
}

/** 更新草稿采购项目详情。已进入审批流程的项目由后端拒绝修改。 */
export async function updateProcurementProject(
  projectId: string,
  data: UpdateProcurementProjectRequest,
): Promise<ProcurementProject> {
  return request<ProcurementProject>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}`,
    { method: 'PUT', body: data },
  );
}

/** 逻辑删除采购项目，后端会保留相关审查历史。 */
export async function deleteProcurementProject(projectId: string): Promise<void> {
  await request<null>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}`,
    { method: 'DELETE' },
  );
}

/** 导出单个采购需求 Excel。 */
export async function exportProcurementProject(
  projectId: string,
): Promise<{ blob: Blob; filename: string | null }> {
  const response = await fetch(
    apiUrl(`/api/v1/procurement/projects/${encodeURIComponent(projectId)}/export`),
    { method: 'GET', credentials: 'include' },
  );
  if (!response.ok) {
    let message = response.statusText || '采购需求导出失败';
    try {
      const payload = await response.json() as { message?: string };
      if (payload.message) message = payload.message;
    } catch {
      // The export endpoint may return an empty/non-JSON proxy error.
    }
    throw new Error(message);
  }
  const disposition = response.headers.get('Content-Disposition');
  const utf8Name = disposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const quotedName = disposition?.match(/filename="([^"]+)"/i)?.[1];
  let filename: string | null = quotedName ?? null;
  if (utf8Name) {
    try {
      filename = decodeURIComponent(utf8Name);
    } catch {
      filename = utf8Name;
    }
  }
  return { blob: await response.blob(), filename };
}

export async function startProcurementApproval(projectId: string, modificationNote?: string): Promise<ProcurementApproval> {
  return request<ProcurementApproval>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/approval/start`,
    { method: 'POST', ...(modificationNote?.trim() ? { body: { modificationNote: modificationNote.trim() } } : {}) },
  );
}

export async function getProcurementApproval(projectId: string): Promise<ProcurementApproval> {
  return request<ProcurementApproval>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/approval`,
    { method: 'GET' },
  );
}

export async function approveProcurementApproval(
  projectId: string,
  opinion?: string,
): Promise<ProcurementApproval> {
  return request<ProcurementApproval>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/approval/approve`,
    { method: 'POST', ...(opinion?.trim() ? { body: { opinion: opinion.trim() } } : {}) },
  );
}

export async function returnProcurementApproval(
  projectId: string,
  opinion: string,
): Promise<ProcurementApproval> {
  return request<ProcurementApproval>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/approval/return`,
    { method: 'POST', body: { opinion: opinion.trim() } },
  );
}

export async function addProcurementApprovalComment(
  projectId: string,
  opinion: string,
): Promise<ProcurementApproval> {
  return request<ProcurementApproval>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/approval/comments`,
    { method: 'POST', body: { opinion: opinion.trim() } },
  );
}

export async function getProcurementApprovalHistory(
  projectId: string,
): Promise<ProcurementApprovalOperation[]> {
  return request<ProcurementApprovalOperation[]>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/approval/history`,
    { method: 'GET' },
  );
}

export async function reassignProcurementApproval(
  projectId: string,
  nodeCode: string,
  targetUserId: string,
  opinion?: string,
): Promise<ProcurementApproval> {
  return request<ProcurementApproval>(
    `/api/v1/procurement/projects/${encodeURIComponent(projectId)}/approval/reassign`,
    {
      method: 'POST',
      body: {
        nodeCode,
        targetUserId: targetUserId.trim(),
        ...(opinion?.trim() ? { opinion: opinion.trim() } : {}),
      },
    },
  );
}

export async function listProcurementApprovalTasks(): Promise<ProcurementApprovalTask[]> {
  return request<ProcurementApprovalTask[]>('/api/v1/procurement/approval/tasks', { method: 'GET' });
}

export async function listNotifications(unreadOnly = false): Promise<ProcurementNotification[]> {
  return request<ProcurementNotification[]>('/api/v1/notifications', { method: 'GET', params: { unreadOnly: String(unreadOnly) } });
}
export async function getUnreadNotificationCount(): Promise<number> {
  return request<number>('/api/v1/notifications/unread-count', { method: 'GET' });
}
export async function markNotificationRead(id: string): Promise<void> {
  await request<null>(`/api/v1/notifications/${encodeURIComponent(id)}/read`, { method: 'POST' });
}

export async function listOperationAudits(filters: {
  userId?: string;
  action?: string;
  resourceType?: string;
  from?: string;
  to?: string;
} = {}): Promise<OperationAuditRecord[]> {
  return request<OperationAuditRecord[]>('/api/v1/audit/operations', {
    method: 'GET',
    params: Object.fromEntries(Object.entries(filters).filter(([, value]) => Boolean(value))),
  });
}

// ==================== F05 项目拆分风险 ====================

/**
 * 项目拆分风险检查
 */
export async function checkSplitRisk(
  data: SplitRiskCheckRequest,
  idempotencyKey: string = `budget-f05-api-${createOperationKey()}`,
): Promise<SplitRiskCheckResponse> {
  return request<SplitRiskCheckResponse>('/api/v1/budget/split-risk/check', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: data,
  });
}

export async function getSplitRiskAssessment(assessmentId: string): Promise<SplitRiskCheckResponse> {
  return request<SplitRiskCheckResponse>(
    `/api/v1/budget/split-risk/assessments/${encodeURIComponent(assessmentId)}`,
    { method: 'GET' },
  );
}

// ==================== F07 合同结构对标 ====================

/**
 * 合同结构对标检查
 */
export async function checkContractStructure(
  data: ContractStructureCheckRequest
): Promise<ContractStructureCheckResponse> {
  return request<ContractStructureCheckResponse>('/api/contract-structure/check', {
    method: 'POST',
    body: data,
  });
}

// ==================== F09 合同期限审查 ====================

/**
 * 合同期限审查
 */
export async function checkContractPeriod(
  data: ContractPeriodCheckRequest
): Promise<ContractPeriodCheckResponse> {
  return request<ContractPeriodCheckResponse>('/api/contract-period/check', {
    method: 'POST',
    body: data,
  });
}

// ==================== F11 围串标识别 ====================

/**
 * 围串标风险检查
 */
export async function checkCollusion(data: CollusionCheckRequest): Promise<CollusionCheckResponse> {
  return request<CollusionCheckResponse>('/api/collusion/check', {
    method: 'POST',
    body: data,
  });
}

// ==================== F13 附件审查 ====================

/**
 * 附件审查
 */
export async function checkAttachment(data: AttachmentCheckRequest): Promise<AttachmentCheckResponse> {
  return request<AttachmentCheckResponse>('/api/attachment/check', {
    method: 'POST',
    body: data,
  });
}

// ==================== F15 条款风险提示 ====================

/**
 * 条款风险检查
 */
export async function checkClauseRisk(data: ClauseRiskCheckRequest): Promise<ClauseRiskCheckResponse> {
  return request<ClauseRiskCheckResponse>('/api/clause-risk/check', {
    method: 'POST',
    body: data,
  });
}

// ==================== 健康检查 ====================

/**
 * 健康检查
 */
export async function healthCheck(): Promise<{ status: string; time: string }> {
  return request<{ status: string; time: string }>('/api/health', {
    method: 'GET',
  });
}
