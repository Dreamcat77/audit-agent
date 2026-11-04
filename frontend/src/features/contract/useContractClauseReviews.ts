import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { userFacingError } from "../../api/userFacingError";
import type { ContractDataSource } from "./data/contractDataSource";
import type { BreachClauseReview, ContractTermClauseReview } from "./types";

interface ClauseReviewIdentity {
  projectId: string | null;
  contractId: string;
  selectionKey: string;
}

function sameIdentity(left: ClauseReviewIdentity, right: ClauseReviewIdentity): boolean {
  return left.projectId === right.projectId && left.contractId === right.contractId
    && left.selectionKey === right.selectionKey;
}

function isAbortError(cause: unknown): boolean {
  return cause instanceof Error && cause.name === "AbortError";
}

export function useContractClauseReviews(
  projectId: string | null,
  contractId: string,
  fileIds: readonly string[],
  dataSource: ContractDataSource,
) {
  const selectionKey = fileIds.join("\u0000");
  const currentIdentity: ClauseReviewIdentity = { projectId, contractId, selectionKey };
  const currentIdentityRef = useRef(currentIdentity);
  currentIdentityRef.current = currentIdentity;
  const [breachResult, setBreachResult] = useState<BreachClauseReview | null>(null);
  const [termResult, setTermResult] = useState<ContractTermClauseReview | null>(null);
  const [breachLoading, setBreachLoading] = useState(false);
  const [termLoading, setTermLoading] = useState(false);
  const [breachError, setBreachError] = useState<string | null>(null);
  const [termError, setTermError] = useState<string | null>(null);

  const mountedRef = useRef(false);
  const pendingRef = useRef(false);
  const generationRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);
  const previousIdentityRef = useRef(currentIdentity);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      generationRef.current += 1;
      pendingRef.current = false;
      controllerRef.current?.abort();
      controllerRef.current = null;
    };
  }, []);

  useLayoutEffect(() => {
    if (sameIdentity(previousIdentityRef.current, currentIdentity)) return;
    generationRef.current += 1;
    pendingRef.current = false;
    controllerRef.current?.abort();
    controllerRef.current = null;
    setBreachResult(null); setTermResult(null);
    setBreachError(null); setTermError(null);
    setBreachLoading(false); setTermLoading(false);
    previousIdentityRef.current = currentIdentity;
  }, [contractId, projectId, selectionKey]);

  const analyze = useCallback(async () => {
    if (pendingRef.current || !projectId || fileIds.length === 0
      || !dataSource.reviewBreachClauses || !dataSource.reviewTermClauses) return;
    pendingRef.current = true;
    const generation = generationRef.current + 1;
    generationRef.current = generation;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const requestIdentity: ClauseReviewIdentity = { projectId, contractId, selectionKey };
    const requestFileIds = [...fileIds];

    setBreachResult(null); setTermResult(null);
    setBreachLoading(true); setTermLoading(true); setBreachError(null); setTermError(null);

    const current = () => mountedRef.current
      && !controller.signal.aborted
      && generationRef.current === generation
      && sameIdentity(currentIdentityRef.current, requestIdentity);
    const breach = dataSource.reviewBreachClauses(projectId, contractId, requestFileIds, { signal: controller.signal })
      .then((result) => { if (current()) setBreachResult(result); })
      .catch((cause) => {
        if (!isAbortError(cause) && current()) {
          setBreachError(userFacingError(cause, "违约责任条款审查暂未完成。"));
        }
      })
      .finally(() => { if (current()) setBreachLoading(false); });
    const term = dataSource.reviewTermClauses(projectId, contractId, requestFileIds, { signal: controller.signal })
      .then((result) => { if (current()) setTermResult(result); })
      .catch((cause) => {
        if (!isAbortError(cause) && current()) {
          setTermError(userFacingError(cause, "合同期限与终止条件审查暂未完成。"));
        }
      })
      .finally(() => { if (current()) setTermLoading(false); });
    await Promise.allSettled([breach, term]);
    if (!current()) return;
    pendingRef.current = false;
    if (controllerRef.current === controller) controllerRef.current = null;
  }, [contractId, dataSource, fileIds, projectId, selectionKey]);

  return { breachResult, termResult, breachLoading, termLoading, breachError, termError, analyze };
}
