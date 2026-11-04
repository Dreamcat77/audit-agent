import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";

import { userFacingError } from "../../api/userFacingError";
import type { ContractTextMaterialReference } from "./contractTextAnalysisPresentation";
import type { ContractDataSource } from "./data/contractDataSource";
import type { ContractTextAnalysis } from "./types";

const MATERIALS_CHANGED_NOTICE = "材料已变更，等待重新分析";

interface ContractSemanticAnalysisOptions {
  contractId: string;
  projectId: string | null;
  selectedMaterials: readonly ContractTextMaterialReference[];
  dataSource: Pick<ContractDataSource, "analyzeContractText">;
}

interface RequestIdentity {
  contractId: string;
  projectId: string | null;
  selectionKey: string;
}

interface StoredResult extends RequestIdentity {
  result: ContractTextAnalysis;
  materials: readonly ContractTextMaterialReference[];
}

export interface ContractSemanticAnalysisState {
  result: ContractTextAnalysis | null;
  resultMaterials: readonly ContractTextMaterialReference[];
  loading: boolean;
  error: string | null;
  notice: string | null;
  analyze: () => Promise<void>;
}

export function contractSemanticSelectionKey(fileIds: readonly string[]): string {
  return fileIds.map((fileId) => `${fileId.length}:${fileId}`).join("|");
}

function sameIdentity(left: RequestIdentity, right: RequestIdentity): boolean {
  return left.contractId === right.contractId
    && left.projectId === right.projectId
    && left.selectionKey === right.selectionKey;
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}

export function useContractSemanticAnalysis({
  contractId,
  projectId,
  selectedMaterials,
  dataSource,
}: ContractSemanticAnalysisOptions): ContractSemanticAnalysisState {
  const selectedFileIds = selectedMaterials.map((material) => material.id);
  const selectionKey = contractSemanticSelectionKey(selectedFileIds);
  const currentIdentity: RequestIdentity = { contractId, projectId, selectionKey };
  const currentIdentityRef = useRef(currentIdentity);
  currentIdentityRef.current = currentIdentity;

  const [storedResult, setStoredResult] = useState<StoredResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

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
    const previous = previousIdentityRef.current;
    if (sameIdentity(previous, currentIdentity)) return;

    generationRef.current += 1;
    pendingRef.current = false;
    controllerRef.current?.abort();
    controllerRef.current = null;
    setStoredResult(null);
    setLoading(false);
    setError(null);
    setNotice(
      previous.contractId === contractId
        && previous.projectId === projectId
        && previous.selectionKey !== selectionKey
        ? MATERIALS_CHANGED_NOTICE
        : null,
    );
    previousIdentityRef.current = currentIdentity;
  }, [contractId, projectId, selectionKey]);

  const analyze = useCallback(async () => {
    if (pendingRef.current || !projectId || selectedFileIds.length === 0) return;

    pendingRef.current = true;
    const generation = generationRef.current + 1;
    generationRef.current = generation;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const requestIdentity: RequestIdentity = { contractId, projectId, selectionKey };
    const requestFileIds = [...selectedFileIds];
    const requestMaterials = selectedMaterials.map((material) => ({ ...material }));

    setStoredResult(null);
    setLoading(true);
    setError(null);
    setNotice(null);

    const current = () => mountedRef.current
      && !controller.signal.aborted
      && generationRef.current === generation
      && sameIdentity(currentIdentityRef.current, requestIdentity);

    try {
      const result = await dataSource.analyzeContractText(
        projectId,
        requestFileIds,
        { signal: controller.signal },
      );
      if (!current()) return;
      setStoredResult({
        ...requestIdentity,
        result,
        materials: requestMaterials,
      });
    } catch (cause) {
      if (isAbortError(cause) || !current()) return;
      setError(userFacingError(
        cause,
        "跨材料语义分析暂未完成；未生成或替代任何精确校验结论。",
      ));
    } finally {
      if (!current()) return;
      pendingRef.current = false;
      if (controllerRef.current === controller) controllerRef.current = null;
      setLoading(false);
    }
  }, [contractId, dataSource, projectId, selectedFileIds, selectedMaterials, selectionKey]);

  const resultIsCurrent = storedResult !== null
    && sameIdentity(storedResult, currentIdentity);
  return {
    result: resultIsCurrent ? storedResult.result : null,
    resultMaterials: resultIsCurrent ? storedResult.materials : [],
    loading,
    error,
    notice,
    analyze,
  };
}
