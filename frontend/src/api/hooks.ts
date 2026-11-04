/**
 * 审计系统 API 自定义 Hooks
 * 用于在 React 组件中方便地调用后端 API
 */

import { useState, useEffect, useCallback } from 'react';
import {
  checkBudget,
  batchCheckBudget,
  checkBlacklist,
  checkSplitRisk,
  checkContractStructure,
  checkContractPeriod,
  checkCollusion,
  checkAttachment,
  checkClauseRisk,
  healthCheck,
} from './audit';
import type {
  BudgetCheckResponse,
  BlacklistCheckResponse,
  SplitRiskCheckResponse,
  ContractStructureCheckResponse,
  ContractPeriodCheckResponse,
  CollusionCheckResponse,
  AttachmentCheckResponse,
  ClauseRiskCheckResponse,
} from './types';

/**
 * 通用 API Hook 返回类型
 */
interface UseApiResult<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  execute: (...args: any[]) => Promise<void>;
  reset: () => void;
}

/**
 * 通用 API Hook 工厂
 */
function useApi<T>(apiFn: (...args: any[]) => Promise<T>): UseApiResult<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const execute = useCallback(async (...args: any[]) => {
    setLoading(true);
    setError(null);
    try {
      const result = await apiFn(...args);
      setData(result);
    } catch (err: any) {
      setError(err.message || '请求失败');
      console.error('API Error:', err);
    } finally {
      setLoading(false);
    }
  }, [apiFn]);

  const reset = useCallback(() => {
    setData(null);
    setError(null);
    setLoading(false);
  }, []);

  return { data, loading, error, execute, reset };
}

// ==================== F01 预算审批 ====================

export function useBudgetCheck() {
  const api = useApi<BudgetCheckResponse>(checkBudget);

  useEffect(() => {
    // 可以在这里设置默认检查，或留空由组件触发
    // api.execute('PR-2024-0183');
  }, []);

  return api;
}

// ==================== F03 黑名单检查 ====================

export function useBlacklistCheck(supplierName: string, autoCheck = false) {
  const api = useApi<BlacklistCheckResponse>(() =>
    checkBlacklist({ supplierName })
  );

  useEffect(() => {
    if (autoCheck && supplierName) {
      api.execute();
    }
  }, [supplierName, autoCheck]);

  return api;
}

// ==================== F05 拆分风险检查 ====================

export function useSplitRiskCheck() {
  return useApi<SplitRiskCheckResponse>(checkSplitRisk);
}

// ==================== F07 合同结构对标 ====================

export function useContractStructureCheck() {
  return useApi<ContractStructureCheckResponse>(checkContractStructure);
}

// ==================== F09 合同期限审查 ====================

export function useContractPeriodCheck() {
  return useApi<ContractPeriodCheckResponse>(checkContractPeriod);
}

// ==================== F11 围串标识别 ====================

export function useCollusionCheck() {
  return useApi<CollusionCheckResponse>(checkCollusion);
}

// ==================== F13 附件审查 ====================

export function useAttachmentCheck() {
  return useApi<AttachmentCheckResponse>(checkAttachment);
}

// ==================== F15 条款风险提示 ====================

export function useClauseRiskCheck() {
  return useApi<ClauseRiskCheckResponse>(checkClauseRisk);
}

// ==================== 健康检查 ====================

export function useHealthCheck() {
  const [status, setStatus] = useState<'up' | 'down' | 'checking'>('checking');

  useEffect(() => {
    const check = async () => {
      try {
        await healthCheck();
        setStatus('up');
      } catch {
        setStatus('down');
      }
    };

    check();
    const interval = setInterval(check, 30000); // 每30秒检查一次
    return () => clearInterval(interval);
  }, []);

  return status;
}
