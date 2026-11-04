import { useCallback, useEffect, useRef, useState } from "react";
import { userFacingError } from "../../api/userFacingError";
import {
  contractDataSource,
  type ContractDataSource,
} from "./data/contractDataSource";
import {
  createF02ReviewOperation,
  sessionStorageF02ReviewOperationPersistence,
  type F02ReviewOperation,
  type F02ReviewOperationPersistence,
} from "./reviewOperation";
import {
  classifyF02ReviewConflict,
  type F02ReviewConflict,
} from "./reviewConflict";
import {
  acknowledgeFindingAndReloadProgress,
  createFindingAcknowledgementOperationState,
  createSessionStorageFindingAcknowledgementOperationPersistence,
  deriveProgressiveReplayState,
  type FindingAcknowledgementOperationState,
  type ProgressiveReplayState,
} from "./reviewProgressState";
import type { Contract, ReviewProgress, ReviewResult } from "./types";

interface ContractReviewWorkspaceState {
  contract: Contract | null;
  result: ReviewResult | null;
  progress: ReviewProgress | null;
  progressiveReplayState: ProgressiveReplayState | null;
  contractLoading: boolean;
  latestResultLoading: boolean;
  progressLoading: boolean;
  reviewLoading: boolean;
  acknowledgementLoadingResultId: string | null;
  contractError: string | null;
  latestResultError: string | null;
  progressError: string | null;
  reviewError: string | null;
  acknowledgementError: string | null;
  operationStoreError: string | null;
  revealedFindingCount: number;
  reviewSupported: boolean;
  canRetryReview: boolean;
  reviewConflict: F02ReviewConflict;
  restartRequired: boolean;
  startReview: () => void;
  retryReview: () => void;
  startNewReview: () => void;
  acknowledgeFinding: (checkResultId: string) => void;
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}

function errorMessage(error: unknown, fallback: string): string {
  return userFacingError(error, fallback);
}

export function useContractReviewWorkspace(
  contractId: string,
  actorId: string,
  dataSource: ContractDataSource = contractDataSource,
  operationPersistence: F02ReviewOperationPersistence = sessionStorageF02ReviewOperationPersistence,
  enabled = true,
): ContractReviewWorkspaceState {
  const [contract, setContract] = useState<Contract | null>(null);
  const [result, setResult] = useState<ReviewResult | null>(null);
  const [progress, setProgress] = useState<ReviewProgress | null>(null);
  const [contractLoading, setContractLoading] = useState(false);
  const [latestResultLoading, setLatestResultLoading] = useState(false);
  const [progressLoading, setProgressLoading] = useState(false);
  const [reviewLoading, setReviewLoading] = useState(false);
  const [acknowledgementLoadingResultId, setAcknowledgementLoadingResultId] = useState<string | null>(null);
  const [contractError, setContractError] = useState<string | null>(null);
  const [latestResultError, setLatestResultError] = useState<string | null>(null);
  const [progressError, setProgressError] = useState<string | null>(null);
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [acknowledgementError, setAcknowledgementError] = useState<string | null>(null);
  const [operationStoreError, setOperationStoreError] = useState<string | null>(null);
  const [canRetryReview, setCanRetryReview] = useState(false);
  const [reviewConflict, setReviewConflict] = useState<F02ReviewConflict>("OTHER");
  const [restartRequired, setRestartRequired] = useState(false);

  const mountedRef = useRef(false);
  const pendingReviewRef = useRef(false);
  const runIdRef = useRef(0);
  const contractControllerRef = useRef<AbortController | null>(null);
  const reviewControllerRef = useRef<AbortController | null>(null);
  const acknowledgementControllerRef = useRef<AbortController | null>(null);
  const activeTaskIdRef = useRef<string | null>(null);
  const reviewOperationRef = useRef<F02ReviewOperation | null>(null);
  const acknowledgementOperationStateRef = useRef<FindingAcknowledgementOperationState | null>(null);
  const reviewSupported = enabled && dataSource.supportsReview(contractId);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      pendingReviewRef.current = false;
      contractControllerRef.current?.abort();
      reviewControllerRef.current?.abort();
      acknowledgementControllerRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    const runId = ++runIdRef.current;
    contractControllerRef.current?.abort();
    reviewControllerRef.current?.abort();
    acknowledgementControllerRef.current?.abort();
    pendingReviewRef.current = false;
    reviewOperationRef.current = null;
    acknowledgementOperationStateRef.current = null;
    activeTaskIdRef.current = null;
    setCanRetryReview(false);
    setReviewConflict("OTHER");
    setRestartRequired(false);
    setContract(null);
    setResult(null);
    setProgress(null);
    setContractError(null);
    setLatestResultError(null);
    setProgressError(null);
    setReviewError(null);
    setAcknowledgementError(null);
    setOperationStoreError(null);
    setReviewLoading(false);
    setProgressLoading(false);
    setAcknowledgementLoadingResultId(null);

    if (!reviewSupported) {
      setContractLoading(false);
      setLatestResultLoading(false);
      return;
    }

    if (!actorId.trim()) {
      setOperationStoreError("无法确定当前用户，不能恢复或发起审核");
    } else {
      try {
        const restoredOperation = operationPersistence.load(actorId, contractId);
        reviewOperationRef.current = restoredOperation;
        setCanRetryReview(Boolean(restoredOperation));
        acknowledgementOperationStateRef.current = createFindingAcknowledgementOperationState({
          persistence: createSessionStorageFindingAcknowledgementOperationPersistence(actorId, contractId),
        });
      } catch (error) {
        setOperationStoreError(errorMessage(error, "无法读取待确认审核操作"));
      }
    }

    const controller = new AbortController();
    contractControllerRef.current = controller;
    setContractLoading(true);
    setLatestResultLoading(true);

    void dataSource
      .getContract(contractId, { signal: controller.signal })
      .then((loadedContract) => {
        if (!mountedRef.current || runId !== runIdRef.current) return;
        setContract(loadedContract);
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || !mountedRef.current || runId !== runIdRef.current) return;
        setContractError(errorMessage(error, "合同数据读取失败"));
      })
      .finally(() => {
        if (!mountedRef.current || runId !== runIdRef.current) return;
        setContractLoading(false);
      });

    void dataSource
      .getLatestReviewResult(contractId, { signal: controller.signal })
      .then((latestResult) => {
        if (!mountedRef.current || runId !== runIdRef.current) return;
        activeTaskIdRef.current = latestResult?.taskId ?? null;
        setResult(latestResult);
        if (!latestResult) {
          setProgress(null);
          return;
        }
        setProgressLoading(true);
        setProgressError(null);
        void dataSource
          .getReviewProgress(contractId, latestResult.taskId, { signal: controller.signal })
          .then((loadedProgress) => {
            if (!mountedRef.current || runId !== runIdRef.current) return;
            acknowledgementOperationStateRef.current?.reconcile(loadedProgress);
            setProgress(loadedProgress);
          })
          .catch((error: unknown) => {
            if (isAbortError(error) || !mountedRef.current || runId !== runIdRef.current) return;
            setProgressError(errorMessage(error, "审核进度读取失败"));
          })
          .finally(() => {
            if (!mountedRef.current || runId !== runIdRef.current) return;
            setProgressLoading(false);
          });
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || !mountedRef.current || runId !== runIdRef.current) return;
        setLatestResultError(errorMessage(error, "最新审核结果读取失败"));
      })
      .finally(() => {
        if (!mountedRef.current || runId !== runIdRef.current) return;
        setLatestResultLoading(false);
      });

    return () => controller.abort();
  }, [actorId, contractId, dataSource, operationPersistence, reviewSupported]);

  const executeReview = useCallback(
    (newOperation: boolean) => {
      if (
        pendingReviewRef.current
        || !actorId.trim()
        || !reviewSupported
        || !contract
        || latestResultLoading
      ) return;

      let operation = reviewOperationRef.current;
      try {
        if (newOperation && operation) {
          throw new Error("当前合同仍有未确认的审核操作，请先继续或重试该操作");
        }
        if (!operation) {
          operation = createF02ReviewOperation();
          operationPersistence.save(actorId, contractId, operation);
        }
      } catch (error) {
        setOperationStoreError(errorMessage(error, "无法保留本次审核状态"));
        return;
      }

      reviewOperationRef.current = operation;
      setCanRetryReview(true);
      setOperationStoreError(null);
      pendingReviewRef.current = true;
      const runId = ++runIdRef.current;
      reviewControllerRef.current?.abort();
      acknowledgementControllerRef.current?.abort();
      acknowledgementControllerRef.current = null;
      activeTaskIdRef.current = null;
      const controller = new AbortController();
      reviewControllerRef.current = controller;
      setReviewLoading(true);
      setReviewError(null);
      setReviewConflict("OTHER");
      setRestartRequired(false);
      setResult(null);
      setProgress(null);
      setProgressError(null);
      setAcknowledgementLoadingResultId(null);
      setAcknowledgementError(null);

      void dataSource
        .reviewContract(contractId, {
          idempotencyKey: operation.idempotencyKey,
          signal: controller.signal,
        })
        .then((reviewResult) => {
          if (!mountedRef.current || runId !== runIdRef.current) return;
          activeTaskIdRef.current = reviewResult.taskId;
          setResult(reviewResult);
          setLatestResultError(null);
          try {
            operationPersistence.clear(actorId, contractId, operation.idempotencyKey);
            setOperationStoreError(null);
          } catch (error) {
            setOperationStoreError(errorMessage(error, "审核已返回，但当前状态仍需继续确认"));
          }
          reviewOperationRef.current = null;
          setCanRetryReview(false);
          setReviewConflict("OTHER");
          setRestartRequired(false);
          setProgressLoading(true);
          void dataSource
            .getReviewProgress(contractId, reviewResult.taskId, { signal: controller.signal })
            .then((loadedProgress) => {
              if (!mountedRef.current || runId !== runIdRef.current) return;
              acknowledgementOperationStateRef.current?.reconcile(loadedProgress);
              setProgress(loadedProgress);
            })
            .catch((error: unknown) => {
              if (isAbortError(error) || !mountedRef.current || runId !== runIdRef.current) return;
              setProgressError(errorMessage(error, "审核已完成，但进度事实读取失败"));
            })
            .finally(() => {
              if (!mountedRef.current || runId !== runIdRef.current) return;
              setProgressLoading(false);
            });
        })
        .catch((error: unknown) => {
          if (isAbortError(error) || !mountedRef.current || runId !== runIdRef.current) return;

          const conflict = classifyF02ReviewConflict(error);
          setReviewConflict(conflict);
          if (conflict === "IN_PROGRESS") {
            setReviewError("该审核操作仍在处理中，请稍后继续确认");
            return;
          }
          if (conflict === "TERMINAL_FAILED" || conflict === "TERMINAL_FINGERPRINT") {
            try {
              operationPersistence.clear(actorId, contractId, operation.idempotencyKey);
              setOperationStoreError(null);
            } catch (storageError) {
              setOperationStoreError(errorMessage(storageError, "上一轮审核状态无法完成收口"));
            }
            reviewOperationRef.current = null;
            setCanRetryReview(false);
            setRestartRequired(true);
            setReviewError(
              conflict === "TERMINAL_FAILED"
                ? "上一次审核执行失败，可以重新发起审核"
                : "合同或规则版本已变化，需要重新发起审核",
            );
            return;
          }
          setReviewError(errorMessage(error, "智能审核请求失败"));
        })
        .finally(() => {
          if (!mountedRef.current || runId !== runIdRef.current) return;
          pendingReviewRef.current = false;
          setReviewLoading(false);
        });
    },
    [
      actorId,
      contract,
      contractId,
      dataSource,
      latestResultLoading,
      operationPersistence,
      reviewSupported,
    ],
  );

  const startReview = useCallback(() => executeReview(false), [executeReview]);
  const retryReview = useCallback(() => executeReview(false), [executeReview]);
  const startNewReview = useCallback(() => executeReview(true), [executeReview]);

  const acknowledgeFinding = useCallback((checkResultId: string) => {
    if (
      acknowledgementLoadingResultId
      || !result
      || activeTaskIdRef.current !== result.taskId
      || !acknowledgementOperationStateRef.current
    ) return;

    const controller = new AbortController();
    const runId = runIdRef.current;
    const taskId = result.taskId;
    acknowledgementControllerRef.current?.abort();
    acknowledgementControllerRef.current = controller;
    setAcknowledgementLoadingResultId(checkResultId);
    setAcknowledgementError(null);
    const getReviewProgress = dataSource.getReviewProgress.bind(dataSource);
    const acknowledgeFindingRequest = dataSource.acknowledgeFinding.bind(dataSource);
    void acknowledgeFindingAndReloadProgress({
      dataSource: {
        getReviewProgress,
        acknowledgeFinding: acknowledgeFindingRequest,
      },
      contractId,
      taskId,
      checkResultId,
      operationState: acknowledgementOperationStateRef.current,
      signal: controller.signal,
    })
      .then((loadedProgress) => {
        if (
          !mountedRef.current
          || controller.signal.aborted
          || runId !== runIdRef.current
          || taskId !== activeTaskIdRef.current
        ) return;
        setProgress(loadedProgress);
        setProgressError(null);
      })
      .catch((error: unknown) => {
        if (
          isAbortError(error)
          || !mountedRef.current
          || controller.signal.aborted
          || runId !== runIdRef.current
          || taskId !== activeTaskIdRef.current
        ) return;
        setAcknowledgementError(errorMessage(error, "风险知悉请求失败"));
      })
      .finally(() => {
        if (
          !mountedRef.current
          || controller.signal.aborted
          || runId !== runIdRef.current
          || taskId !== activeTaskIdRef.current
        ) return;
        if (acknowledgementControllerRef.current === controller) {
          acknowledgementControllerRef.current = null;
        }
        setAcknowledgementLoadingResultId(null);
      });
  }, [acknowledgementLoadingResultId, contractId, dataSource, result]);

  const progressiveReplayState = progress ? deriveProgressiveReplayState(progress) : null;
  const revealedFindingCount = progressiveReplayState
    ? progressiveReplayState.orderedItems
        .slice(0, progressiveReplayState.complete
          ? progressiveReplayState.orderedItems.length
          : progressiveReplayState.completedPrefixCount + 1)
        .filter((item) => item.type === "FINDING").length
    : 0;

  return {
    contract,
    result,
    progress,
    progressiveReplayState,
    contractLoading,
    latestResultLoading,
    progressLoading,
    reviewLoading,
    acknowledgementLoadingResultId,
    contractError,
    latestResultError,
    progressError,
    reviewError,
    acknowledgementError,
    operationStoreError,
    revealedFindingCount,
    reviewSupported,
    canRetryReview,
    reviewConflict,
    restartRequired,
    startReview,
    retryReview,
    startNewReview,
    acknowledgeFinding,
  };
}
