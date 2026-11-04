import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../../api/client";
import { userFacingError } from "../../api/userFacingError";
import {
  contractReviewSessionDataSource,
  type ContractReviewSessionDataSource,
} from "./data/contractReviewSessionDataSource";
import {
  clearSessionOperation,
  isAmbiguousSessionOperation,
  loadOrCreateSessionOperation,
} from "./sessionOperation";
import {
  afterUploadFailure,
  reuseOrCreateUploadAttempt,
  type PendingUploadAttempt,
} from "./uploadAttempt";
import type {
  ContractReviewSession,
  SessionDocumentList,
  SessionReviewDocument,
} from "./sessionTypes";
import type { ReviewDocumentKind } from "./workspaceTypes";

const EMPTY_DOCUMENTS: SessionDocumentList = { currentMaterials: [], history: [] };

export function useContractReviewSessionWorkspace(
  sessionId: string,
  actorId: string,
  dataSource: ContractReviewSessionDataSource = contractReviewSessionDataSource,
) {
  const [session, setSession] = useState<ContractReviewSession | null>(null);
  const [documents, setDocuments] = useState<SessionDocumentList>(EMPTY_DOCUMENTS);
  const [loading, setLoading] = useState(true);
  const [action, setAction] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const mounted = useRef(false);
  const runId = useRef(0);
  const actionPending = useRef(false);
  const uploadAttempts = useRef(new Map<ReviewDocumentKind, PendingUploadAttempt>());
  const loadController = useRef<AbortController | null>(null);
  const actionController = useRef<AbortController | null>(null);

  const refresh = useCallback(async () => {
    const currentRun = ++runId.current;
    loadController.current?.abort();
    const controller = new AbortController();
    loadController.current = controller;
    setLoading(true);
    try {
      const [nextSession, nextDocuments] = await Promise.all([
        dataSource.getSession(sessionId, { signal: controller.signal }),
        dataSource.getDocuments(sessionId, true, { signal: controller.signal }),
      ]);
      if (!mounted.current || currentRun !== runId.current) return;
      setSession(nextSession);
      setDocuments(nextDocuments);
      setError(null);
    } catch (cause) {
      if (cause instanceof Error && cause.name === "AbortError") return;
      if (mounted.current && currentRun === runId.current) {
        setError(userFacingError(cause, "内审事项暂时无法读取，请稍后重试。"));
      }
    } finally {
      if (mounted.current && currentRun === runId.current) setLoading(false);
    }
  }, [dataSource, sessionId]);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    return () => {
      mounted.current = false;
      loadController.current?.abort();
      actionController.current?.abort();
    };
  }, [refresh]);

  const runAction = useCallback(async <T,>(
    actionName: string,
    operationAction: string,
    execute: (idempotencyKey: string, signal: AbortSignal) => Promise<T>,
  ): Promise<T | null> => {
    if (!actorId || actionPending.current) return null;
    actionPending.current = true;
    const idempotencyKey = loadOrCreateSessionOperation(actorId, sessionId, operationAction);
    actionController.current?.abort();
    const controller = new AbortController();
    actionController.current = controller;
    setAction(actionName);
    setError(null);
    try {
      const result = await execute(idempotencyKey, controller.signal);
      clearSessionOperation(actorId, sessionId, operationAction);
      return result;
    } catch (cause) {
      if (cause instanceof Error && cause.name === "AbortError") return null;
      if (!isAmbiguousSessionOperation(cause)) {
        clearSessionOperation(actorId, sessionId, operationAction);
      }
      if (cause instanceof ApiError && cause.status === 409) {
        await refresh();
        setError("材料状态已经变化，请重新确认");
      } else {
        setError(userFacingError(cause, `${actionName}暂时失败，请稍后重试。`));
      }
      return null;
    } finally {
      actionPending.current = false;
      if (mounted.current) setAction(null);
    }
  }, [actorId, refresh, sessionId]);

  const upload = useCallback(async (kind: ReviewDocumentKind, file: File) => {
    if (!session || !actorId || actionPending.current) return false;
    const attempt = reuseOrCreateUploadAttempt(
      uploadAttempts.current.get(kind),
      sessionId,
      kind,
      file,
      session.versionNo,
    );
    uploadAttempts.current.set(kind, attempt);
    actionPending.current = true;
    actionController.current?.abort();
    const controller = new AbortController();
    actionController.current = controller;
    setAction("上传材料");
    setError(null);
    try {
      const result = await dataSource.uploadDocument(sessionId, {
        documentType: kind,
        expectedVersion: attempt.startingExpectedVersion,
        file: attempt.fileRef,
        idempotencyKey: attempt.idempotencyKey,
        signal: controller.signal,
      });
      if (uploadAttempts.current.get(kind) === attempt) uploadAttempts.current.delete(kind);
      setSession(result.session);
      await refresh();
      return true;
    } catch (cause) {
      const nextAttempt = afterUploadFailure(attempt, cause);
      if (uploadAttempts.current.get(kind) === attempt) uploadAttempts.current.set(kind, nextAttempt);
      if (cause instanceof ApiError && cause.status === 409) {
        await refresh();
        setError(nextAttempt.state === "ACTIVE"
          ? "上传仍在处理中，请使用同一文件重试"
          : "材料状态已经变化，请重新发起上传");
      } else if (!(cause instanceof Error && cause.name === "AbortError")) {
        setError(userFacingError(cause, "材料暂时无法上传，请稍后重试。"));
      }
      return false;
    } finally {
      actionPending.current = false;
      if (mounted.current) setAction(null);
    }
  }, [actorId, dataSource, refresh, session, sessionId]);

  const remove = useCallback(async (document: SessionReviewDocument) => {
    if (!session) return false;
    const result = await runAction("移除材料", `remove:${document.documentId}:${session.versionNo}`,
      (idempotencyKey, signal) => dataSource.removeDocument(sessionId, document.documentId, {
        expectedVersion: session.versionNo,
        idempotencyKey,
        signal,
      }));
    if (!result) return false;
    setSession(result);
    await refresh();
    return true;
  }, [dataSource, refresh, runAction, session, sessionId]);

  const confirm = useCallback(async () => {
    if (!session) return false;
    const result = await runAction("确认材料", `confirm:${session.versionNo}`,
      (idempotencyKey, signal) => dataSource.confirmMaterials(sessionId, {
        expectedVersion: session.versionNo,
        idempotencyKey,
        signal,
      }));
    if (!result) return false;
    setSession(result);
    await refresh();
    return true;
  }, [dataSource, refresh, runAction, session, sessionId]);

  const recover = useCallback(async (document: SessionReviewDocument) => {
    if (actionPending.current) return false;
    actionPending.current = true;
    const controller = new AbortController();
    actionController.current?.abort();
    actionController.current = controller;
    setAction("恢复上传");
    setError(null);
    try {
      await dataSource.recoverDocument(sessionId, document.documentId, { signal: controller.signal });
      await refresh();
      return true;
    } catch (cause) {
      if (cause instanceof Error && cause.name === "AbortError") return false;
      if (cause instanceof ApiError && cause.status === 409) await refresh();
      setError(userFacingError(cause, "上传状态暂时无法恢复，请稍后重试。"));
      return false;
    } finally {
      actionPending.current = false;
      if (mounted.current) setAction(null);
    }
  }, [dataSource, refresh, sessionId]);

  return {
    session,
    documents,
    loading,
    action,
    error,
    refresh,
    upload,
    remove,
    confirm,
    recover,
    contentUrl: dataSource.contentUrl.bind(dataSource),
  };
}
