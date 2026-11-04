import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { userFacingError } from "../../api/userFacingError";
import type {
  ContractAuditTaskRow,
  ContractAuditTaskStats,
  DirectF02AuditTaskRow,
  ShowcaseAuditTaskRow,
} from "./auditTaskViewModel";
import { contractAuditTaskDataSource } from "./data/contractAuditTaskDataSource";
import type {
  ContractAuditTaskRiskFilter,
  ContractAuditTaskStatusFilter,
  ContractAuditTaskSummary,
} from "./data/contractAuditTaskDataSource";
import { resolveContractApiId } from "./data/contractSourceMap";
import { contractMockCases } from "./mock/contractMock";
import {
  showcaseCaseOrder,
  showcaseProjectPresentations,
} from "./mock/showcaseCaseCatalog";
import { contractDataSourceMode } from "./data/contractDataSource";
import { shouldIncludeShowcaseRows } from "./auditTaskPresentation";
import { directF02AuditTaskRow } from "./contractAuditTaskPresentation";

const PAGE_SIZE = 20;

export interface ContractAuditTaskFilters {
  keyword: string;
  createdFrom: string;
  createdTo: string;
  risk: ContractAuditTaskRiskFilter | "";
  status: ContractAuditTaskStatusFilter | "";
}

const EMPTY_FILTERS: ContractAuditTaskFilters = {
  keyword: "", createdFrom: "", createdTo: "", risk: "", status: "",
};
function showcaseRows(): ShowcaseAuditTaskRow[] {
  return showcaseCaseOrder.map((displayId) => {
    const showcase = contractMockCases[displayId];
    const presentation = showcaseProjectPresentations[displayId];
    return {
      rowId: `showcase:${displayId}`,
      source: "SHOWCASE",
      taskId: showcase.reviewResult.taskId,
      initiatedBy: "合同内审人员",
      contractDisplayId: displayId,
      contractApiId: resolveContractApiId(displayId),
      contract: showcase.contract,
      reviewName: `${showcase.contract.contractName}审核`,
      projectId: null,
      projectName: presentation.projectName,
      projectNo: presentation.projectNo,
      overallRisk: showcase.reviewResult.overallRisk,
      reviewResult: showcase.reviewResult,
      createdAt: presentation.createdAt,
      updatedAt: presentation.updatedAt,
      isShowcase: true,
    };
  });
}

export interface ContractAuditTaskListState {
  rows: ContractAuditTaskRow[];
  dynamicLoading: boolean;
  dynamicError: string | null;
  stats: ContractAuditTaskStats;
  page: number;
  totalPages: number;
  realTotal: number;
  displayTotal: number;
  previousPage: () => void;
  nextPage: () => void;
  refresh: () => void;
  filters: ContractAuditTaskFilters;
  setFilters: (filters: ContractAuditTaskFilters) => void;
  resetFilters: () => void;
}

export function useContractAuditTaskList(): ContractAuditTaskListState {
  const [realRows, setRealRows] = useState<DirectF02AuditTaskRow[]>([]);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(0);
  const [realTotal, setRealTotal] = useState(0);
  const [dynamicLoading, setDynamicLoading] = useState(true);
  const [dynamicError, setDynamicError] = useState<string | null>(null);
  const [summary, setSummary] = useState<ContractAuditTaskSummary | null>(null);
  const [filters, setFiltersState] = useState<ContractAuditTaskFilters>(EMPTY_FILTERS);
  const [nonce, setNonce] = useState(0);
  const runIdRef = useRef(0);

  useEffect(() => {
    const runId = ++runIdRef.current;
    const controller = new AbortController();
    setDynamicLoading(true);
    setDynamicError(null);
    const createdFrom = filters.createdFrom
      ? new Date(`${filters.createdFrom}T00:00:00`).toISOString() : undefined;
    const createdTo = filters.createdTo
      ? new Date(`${filters.createdTo}T23:59:59.999`).toISOString() : undefined;
    void Promise.all([contractAuditTaskDataSource.list({
      page, size: PAGE_SIZE, signal: controller.signal,
      includeHistory: true,
      keyword: filters.keyword || undefined,
      createdFrom,
      createdTo,
      risk: filters.risk || undefined,
      status: filters.status || undefined,
    }), contractAuditTaskDataSource.summary({ signal: controller.signal, includeHistory: true })]).then(([result, nextSummary]) => {
      if (controller.signal.aborted || runId !== runIdRef.current) return;
      if (result.items.length === 0 && page > 1) {
        setPage((value) => Math.max(1, value - 1));
        return;
      }
      setRealRows(result.items.map(directF02AuditTaskRow));
      setSummary(nextSummary);
      setRealTotal(result.total);
      setTotalPages(result.totalPages);
    }).catch((cause: unknown) => {
      if (cause instanceof Error && cause.name === "AbortError") return;
      if (runId !== runIdRef.current) return;
      setRealRows([]);
      setRealTotal(0);
      setTotalPages(0);
      setSummary(null);
      setDynamicError(userFacingError(cause, "内审任务暂时无法读取，请稍后重试。"));
    }).finally(() => {
      if (!controller.signal.aborted && runId === runIdRef.current) setDynamicLoading(false);
    });
    return () => controller.abort();
  }, [filters, nonce, page]);

  const presets = useMemo(
    () => shouldIncludeShowcaseRows(contractDataSourceMode) ? showcaseRows() : [],
    [],
  );
  const displayTotal = realTotal + presets.length;
  const rows = useMemo<ContractAuditTaskRow[]>(
    () => page === 1 ? [...presets, ...realRows] : realRows,
    [page, presets, realRows],
  );
  const stats = useMemo<ContractAuditTaskStats>(() => {
    const presetCompleted = presets.filter((row) =>
      row.reviewResult.findings.every((finding) => finding.riskLevel === "GREEN"),
    ).length;
    const presetRisks = presets.filter(
      (row) => row.reviewResult.findings.some((finding) => finding.riskLevel !== "GREEN"),
    ).length;
    if (dynamicLoading || dynamicError || !summary) {
      return { tasks: null, pending: null, completed: presetCompleted, risks: presetRisks };
    }
    return {
      tasks: summary.total + presets.length,
      pending: summary.pendingOrProcessing + presets.length - presetCompleted,
      completed: summary.completed + presetCompleted,
      risks: summary.needsReview + presetRisks,
    };
  }, [dynamicError, dynamicLoading, presets, summary]);

  const refresh = useCallback(() => setNonce((value) => value + 1), []);
  const setFilters = useCallback((next: ContractAuditTaskFilters) => {
    setPage(1);
    setFiltersState(next);
  }, []);
  const resetFilters = useCallback(() => setFilters(EMPTY_FILTERS), [setFilters]);
  return {
    rows,
    dynamicLoading,
    dynamicError,
    stats,
    page,
    totalPages,
    realTotal,
    displayTotal,
    previousPage: () => setPage((value) => Math.max(1, value - 1)),
    nextPage: () => setPage((value) => Math.min(Math.max(totalPages, 1), value + 1)),
    refresh,
    filters,
    setFilters,
    resetFilters,
  };
}
