import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { ApiError } from "../../api/client.ts";
import { httpReviewRunDataSource } from "./data/httpReviewRunDataSource.ts";
import type { ReviewRunDataSource } from "./data/reviewRunDataSource.ts";
import { httpReviewStepRetryDataSource } from "./data/httpReviewStepRetryDataSource.ts";
import type { HttpReviewStepRetryDataSource } from "./data/httpReviewStepRetryDataSource.ts";
import {
  browserReviewRunOperationPersistence,
  clearActiveReviewRun,
  clearReviewRunRestartMarker,
  clearReviewRunCreateOperation,
  loadActiveReviewRun,
  loadReviewRunRestartMarker,
  loadReviewRunCreateOperation,
  loadOrCreateReviewRunCreateOperation,
  loadOrCreateReviewStepOperation,
  saveActiveReviewRun,
  saveReviewRunRestartMarker,
  type ReviewRunOperationPersistence,
  type ReviewRunCreateOperation,
} from "./reviewRunOperation.ts";
import {
  REVIEW_RUN_RESTART_REQUIRED_MESSAGE,
  canStartNewReviewRun,
  presentReviewRun,
  type DurableReviewPresentation,
} from "./reviewRunPresentation.ts";
import { assertReviewRunProjection } from "./reviewRunValidation.ts";
import { useDurableReviewRunPolling } from "./useDurableReviewRunPolling.ts";
import type { ReviewRunProjectionKind } from "./reviewRunValidation.ts";
import type { ReviewRunDto, ReviewStepDto, ReviewStepType } from "./reviewRunTypes.ts";
import {
  clearReviewStepRetryOperation,
  loadOrCreateReviewStepRetryOperation,
  retryableCurrentAttempt,
} from "./reviewStepRetry.ts";

const EXECUTABLE_TYPES = new Set<ReviewStepType>([
  "F02_DETERMINISTIC", "SEMANTIC_AI", "BREACH", "TERM",
]);
const OWNERSHIP_ERROR = "无法验证本次审核记录的归属";

export function canonicalReviewMaterialIds(fileIds: readonly string[]): readonly string[] {
  const normalized = [...new Set(fileIds.map((fileId) => fileId.trim()).filter(Boolean))].sort();
  if (normalized.length === 0) throw new Error("请至少选择一份审核材料");
  return normalized;
}

function assertOwnedRun(
  run: ReviewRunDto,
  actorId: string,
  projectId: string,
  contractId: string,
  expectedRunId?: string,
  projectionKind: ReviewRunProjectionKind = "REOPEN",
): ReviewRunDto {
  if (run.projectId !== projectId || run.contractId !== contractId || run.requestedBy !== actorId
      || expectedRunId !== undefined && run.runId !== expectedRunId) {
    throw new Error(OWNERSHIP_ERROR);
  }
  assertReviewRunProjection(run, projectionKind);
  return run;
}

type ProvenRunPublisher = (run: ReviewRunDto) => void;
export interface ReviewRunExecutionAssessment {
  restartRequired: boolean;
}
type ExecutionAssessmentPublisher = (assessment: ReviewRunExecutionAssessment) => void;

type ReviewRunCreateConflictClassification =
  | "OUTCOME_UNCERTAIN"
  | "READINESS_REJECTED"
  | "INTEGRITY_CONFLICT";

type ReviewRunCreateReconciliation =
  | { kind: "NONE" }
  | { kind: "PROVEN"; run: ReviewRunDto }
  | { kind: "READINESS_REJECTED"; commandChanged: boolean; error: ApiError };

function reviewRunCreateConflictClassification(
  cause: unknown,
): ReviewRunCreateConflictClassification | null {
  if (!(cause instanceof ApiError) || !cause.data || typeof cause.data !== "object"
      || Array.isArray(cause.data)) return null;
  const data = cause.data as Record<string, unknown>;
  if (Object.keys(data).length !== 1 || typeof data.classification !== "string") return null;
  switch (data.classification) {
    case "OUTCOME_UNCERTAIN":
    case "READINESS_REJECTED":
    case "INTEGRITY_CONFLICT":
      return data.classification;
    default:
      return null;
  }
}

function sameCreateOperation(
  left: ReviewRunCreateOperation | null,
  right: ReviewRunCreateOperation,
): boolean {
  return left !== null
    && left.idempotencyKey === right.idempotencyKey
    && left.expectedContractVersion === right.expectedContractVersion
    && JSON.stringify(left.materialFileIds) === JSON.stringify(right.materialFileIds);
}

export class ProjectContractReviewRunCoordinator {
  private readonly actorId: string;
  private readonly projectId: string;
  private readonly contractId: string;
  private readonly dataSource: ReviewRunDataSource;
  private readonly persistence: ReviewRunOperationPersistence;

  constructor(
    actorId: string,
    projectId: string,
    contractId: string,
    dataSource: ReviewRunDataSource,
    persistence: ReviewRunOperationPersistence,
  ) {
    this.actorId = actorId;
    this.projectId = projectId;
    this.contractId = contractId;
    this.dataSource = dataSource;
    this.persistence = persistence;
  }

  /** Historical task navigation never follows or replaces the active pointer. */
  async reopenExact(runId: string, signal?: AbortSignal): Promise<ReviewRunDto> {
    return assertOwnedRun(await this.dataSource.getReviewRun(
      this.projectId, this.contractId, runId, { signal },
    ), this.actorId, this.projectId, this.contractId, runId);
  }

  async restore(
    signal?: AbortSignal,
    onExecutionAssessed?: ExecutionAssessmentPublisher,
  ): Promise<ReviewRunDto | null> {
    const pointer = loadActiveReviewRun(
      this.persistence.storage, this.actorId, this.projectId, this.contractId,
    );
    if (!pointer) {
      return this.discover(signal, onExecutionAssessed);
    }
    try {
      const run = await this.dataSource.getReviewRun(
        this.projectId, this.contractId, pointer.runId, { signal },
      );
      const durable = assertOwnedRun(
        run, this.actorId, this.projectId, this.contractId, pointer.runId,
      );
      onExecutionAssessed?.(this.assessStoredRestartMarker(durable));
      return durable;
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) {
        clearActiveReviewRun(
          this.persistence.storage, this.actorId, this.projectId, this.contractId, pointer.runId,
        );
        clearReviewRunRestartMarker(
          this.persistence.storage, this.actorId, this.projectId, this.contractId, pointer.runId,
        );
        return this.discover(signal, onExecutionAssessed);
      }
      if (cause instanceof Error && cause.message === OWNERSHIP_ERROR) {
        clearActiveReviewRun(
          this.persistence.storage, this.actorId, this.projectId, this.contractId, pointer.runId,
        );
        this.clearStoredRestartMarker();
      }
      throw cause;
    }
  }

  private async discover(
    signal?: AbortSignal,
    onExecutionAssessed?: ExecutionAssessmentPublisher,
  ): Promise<ReviewRunDto | null> {
    const discovered = await this.dataSource.getLatestReviewRun(
      this.projectId, this.contractId, { signal },
    );
    if (signal?.aborted) throw new DOMException("审核恢复已取消", "AbortError");
    // Discovery starts without a pointer; a new pointer belongs to newer navigation.
    if (loadActiveReviewRun(
      this.persistence.storage, this.actorId, this.projectId, this.contractId,
    )) {
      throw new Error("当前审核记录已发生变化，请刷新后重试");
    }
    if (!discovered) {
      this.clearStoredRestartMarker();
      onExecutionAssessed?.({ restartRequired: false });
      return null;
    }
    const durable = assertOwnedRun(
      discovered, this.actorId, this.projectId, this.contractId, undefined, "REOPEN",
    );
    saveActiveReviewRun(
      this.persistence.storage, this.actorId, this.projectId, this.contractId, durable.runId,
    );
    onExecutionAssessed?.(this.assessStoredRestartMarker(durable));
    return durable;
  }

  async createAndExecute(
    expectedContractVersion: number,
    fileIds: readonly string[],
    signal?: AbortSignal,
    onRunProven?: ProvenRunPublisher,
    onExecutionAssessed?: ExecutionAssessmentPublisher,
  ): Promise<ReviewRunDto> {
    const materialFileIds = canonicalReviewMaterialIds(fileIds);
    const operation = loadOrCreateReviewRunCreateOperation(
      this.persistence,
      this.actorId,
      this.projectId,
      this.contractId,
      expectedContractVersion,
      materialFileIds,
    );
    const created = await this.createAndProve({
      ...operation, expectedContractVersion, materialFileIds,
    }, signal);
    onRunProven?.(created);
    return this.executeProvenCreate(created, signal, onExecutionAssessed);
  }

  async reconcileCreate(
    currentExpectedContractVersion: number,
    currentFileIds: readonly string[],
    signal?: AbortSignal,
  ): Promise<ReviewRunCreateReconciliation> {
    const retained = loadReviewRunCreateOperation(
      this.persistence, this.actorId, this.projectId, this.contractId,
    );
    if (!retained) return { kind: "NONE" };
    const pointer = loadActiveReviewRun(
      this.persistence.storage, this.actorId, this.projectId, this.contractId,
    );
    try {
      return { kind: "PROVEN", run: await this.createAndProve(retained, signal, true) };
    } catch (cause) {
      if (reviewRunCreateConflictClassification(cause) !== "READINESS_REJECTED") throw cause;
      if (signal?.aborted) throw new DOMException("审核创建已取消", "AbortError");
      const currentPointer = loadActiveReviewRun(
        this.persistence.storage, this.actorId, this.projectId, this.contractId,
      );
      if (currentPointer?.runId !== pointer?.runId) {
        throw new Error("当前审核记录已发生变化，请刷新后重试");
      }
      const currentOperation = loadReviewRunCreateOperation(
        this.persistence, this.actorId, this.projectId, this.contractId,
      );
      if (!sameCreateOperation(currentOperation, retained)) {
        throw new Error("当前审核操作已发生变化，请刷新后重试");
      }
      const currentMaterialFileIds = canonicalReviewMaterialIds(currentFileIds);
      const commandChanged = retained.expectedContractVersion !== currentExpectedContractVersion
        || JSON.stringify(retained.materialFileIds) !== JSON.stringify(currentMaterialFileIds);
      clearReviewRunCreateOperation(
        this.persistence, this.actorId, this.projectId, this.contractId, retained.idempotencyKey,
      );
      return { kind: "READINESS_REJECTED", commandChanged, error: cause };
    }
  }

  private async createAndProve(
    operation: ReviewRunCreateOperation,
    signal?: AbortSignal,
    allowExistingPointer = false,
  ): Promise<ReviewRunDto> {
    if (signal?.aborted) throw new DOMException("审核创建已取消", "AbortError");
    const pointer = loadActiveReviewRun(
      this.persistence.storage, this.actorId, this.projectId, this.contractId,
    );
    if (pointer && !allowExistingPointer) {
      throw new Error("当前审核记录已发生变化，请刷新后重试");
    }
    const created = assertOwnedRun(await this.dataSource.createReviewRun(
      this.projectId,
      this.contractId,
      operation.expectedContractVersion,
      operation.materialFileIds,
      operation.idempotencyKey,
      { signal },
    ), this.actorId, this.projectId, this.contractId, undefined, "CREATE");
    if (signal?.aborted) throw new DOMException("审核创建已取消", "AbortError");
    const currentPointer = loadActiveReviewRun(
      this.persistence.storage, this.actorId, this.projectId, this.contractId,
    );
    const currentOperation = loadReviewRunCreateOperation(
      this.persistence, this.actorId, this.projectId, this.contractId,
    );
    if (pointer && created.runId !== pointer.runId
        || currentPointer?.runId !== pointer?.runId
        || currentOperation?.idempotencyKey !== operation.idempotencyKey
        || currentOperation.expectedContractVersion !== operation.expectedContractVersion
        || JSON.stringify(currentOperation.materialFileIds) !== JSON.stringify(operation.materialFileIds)) {
      throw new Error("当前审核操作已发生变化，请刷新后重试");
    }
    saveActiveReviewRun(
      this.persistence.storage, this.actorId, this.projectId, this.contractId, created.runId,
    );
    clearReviewRunCreateOperation(
      this.persistence, this.actorId, this.projectId, this.contractId, operation.idempotencyKey,
    );
    return created;
  }

  async executeProvenCreate(
    created: ReviewRunDto,
    signal?: AbortSignal,
    onExecutionAssessed?: ExecutionAssessmentPublisher,
  ): Promise<ReviewRunDto> {
    if (created.executionMode === "SERVER_WORKER") {
      onExecutionAssessed?.({ restartRequired: false });
      return created;
    }
    return this.executePending(created, signal, onExecutionAssessed);
  }

  async startOrResume(
    expectedContractVersion: number,
    fileIds: readonly string[],
    signal?: AbortSignal,
    onRunProven?: ProvenRunPublisher,
    onExecutionAssessed?: ExecutionAssessmentPublisher,
  ): Promise<ReviewRunDto> {
    const reconciliation = await this.reconcileCreate(
      expectedContractVersion, fileIds, signal,
    );
    if (reconciliation.kind === "PROVEN") {
      onRunProven?.(reconciliation.run);
      return this.executeProvenCreate(
        reconciliation.run, signal, onExecutionAssessed,
      );
    }
    if (reconciliation.kind === "READINESS_REJECTED") {
      if (!reconciliation.commandChanged) throw reconciliation.error;
      const pointer = loadActiveReviewRun(
        this.persistence.storage, this.actorId, this.projectId, this.contractId,
      );
      if (!pointer) {
        return this.createAndExecute(
          expectedContractVersion, fileIds, signal, onRunProven, onExecutionAssessed,
        );
      }
    }
    let restoredAssessment: ReviewRunExecutionAssessment = { restartRequired: false };
    const existing = await this.restore(signal, (assessment) => {
      restoredAssessment = assessment;
    });
    if (existing) {
      onRunProven?.(existing);
      if (restoredAssessment.restartRequired) {
        onExecutionAssessed?.(restoredAssessment);
        return existing;
      }
      return this.continuePending(existing, signal, onExecutionAssessed);
    }
    return this.createAndExecute(
      expectedContractVersion, fileIds, signal, onRunProven, onExecutionAssessed,
    );
  }

  async continuePending(
    run: ReviewRunDto,
    signal?: AbortSignal,
    onExecutionAssessed?: ExecutionAssessmentPublisher,
  ): Promise<ReviewRunDto> {
    assertOwnedRun(
      run,
      this.actorId,
      this.projectId,
      this.contractId,
      undefined,
      run.outputs.length === 0 ? "CREATE" : "REOPEN",
    );
    if (run.status !== "PENDING" && run.status !== "RUNNING") {
      onExecutionAssessed?.(this.assessStoredRestartMarker(run));
      return run;
    }
    if (run.executionMode === "SERVER_WORKER") {
      onExecutionAssessed?.(this.assessStoredRestartMarker(run));
      return run;
    }
    return this.executePending(run, signal, onExecutionAssessed);
  }

  clear(runId: string): void {
    clearActiveReviewRun(
      this.persistence.storage, this.actorId, this.projectId, this.contractId, runId,
    );
    clearReviewRunRestartMarker(
      this.persistence.storage, this.actorId, this.projectId, this.contractId, runId,
    );
  }

  private async executePending(
    run: ReviewRunDto,
    signal?: AbortSignal,
    onExecutionAssessed?: ExecutionAssessmentPublisher,
  ): Promise<ReviewRunDto> {
    const pending = run.steps.filter((step): step is ReviewStepDto =>
      EXECUTABLE_TYPES.has(step.stepType) && step.status === "PENDING");
    const settlements = await Promise.allSettled(pending.map(async (step) => {
      const operation = loadOrCreateReviewStepOperation(
        this.persistence,
        this.actorId,
        this.projectId,
        this.contractId,
        run.runId,
        step.stepId,
      );
      await this.dataSource.executeReviewStep(
        this.projectId,
        this.contractId,
        run.runId,
        step.stepId,
        operation.idempotencyKey,
        { signal },
      );
    }));
    const durable = assertOwnedRun(await this.dataSource.getReviewRun(
      this.projectId, this.contractId, run.runId, { signal },
    ), this.actorId, this.projectId, this.contractId, run.runId);
    const blockedF02 = (durable.status === "PENDING" || durable.status === "RUNNING")
      ? pending.find((step, index) => {
        const settlement = settlements[index];
        if (step.stepType !== "F02_DETERMINISTIC" || settlement?.status !== "rejected"
            || !(settlement.reason instanceof ApiError) || settlement.reason.status !== 409) {
          return false;
        }
        return durable.steps.some((durableStep) =>
          durableStep.stepId === step.stepId
          && durableStep.stepType === "F02_DETERMINISTIC"
          && durableStep.status === "PENDING");
      })
      : undefined;
    if (blockedF02) {
      saveReviewRunRestartMarker(
        this.persistence.storage,
        this.actorId,
        this.projectId,
        this.contractId,
        durable.runId,
        blockedF02.stepId,
      );
    }
    onExecutionAssessed?.(this.assessStoredRestartMarker(durable));
    return durable;
  }

  private assessStoredRestartMarker(run: ReviewRunDto): ReviewRunExecutionAssessment {
    const marker = loadReviewRunRestartMarker(
      this.persistence.storage, this.actorId, this.projectId, this.contractId,
    );
    if (!marker) return { restartRequired: false };
    const f02 = run.steps.find((step) => step.stepType === "F02_DETERMINISTIC") ?? null;
    const restartRequired = f02 !== null
      && marker.runId === run.runId
      && marker.stepId === f02.stepId
      && (run.status === "PENDING" || run.status === "RUNNING")
      && f02.status === "PENDING";
    if (!restartRequired) {
      clearReviewRunRestartMarker(
        this.persistence.storage,
        this.actorId,
        this.projectId,
        this.contractId,
        marker.runId,
      );
    }
    return { restartRequired };
  }

  private clearStoredRestartMarker(): void {
    const marker = loadReviewRunRestartMarker(
      this.persistence.storage, this.actorId, this.projectId, this.contractId,
    );
    if (!marker) return;
    clearReviewRunRestartMarker(
      this.persistence.storage, this.actorId, this.projectId, this.contractId, marker.runId,
    );
  }
}

export interface ProjectContractReviewRunState {
  run: ReviewRunDto | null;
  presentation: DurableReviewPresentation | null;
  restoring: boolean;
  executing: boolean;
  observing: boolean;
  retryingStepId: string | null;
  recovered: boolean;
  restartRequired: boolean;
  error: string | null;
  start: () => Promise<void>;
  continuePending: () => Promise<void>;
  startNew: () => void;
  reload: () => Promise<void>;
  retryFailedStep: (stepId: string) => Promise<void>;
}

interface UseProjectContractReviewRunOptions {
  enabled: boolean;
  actorId: string;
  projectId: string | null;
  contractId: string;
  expectedContractVersion: number | null;
  selectedFileIds: readonly string[];
  historicalRunId?: string | null;
  dataSource?: ReviewRunDataSource;
  persistence?: ReviewRunOperationPersistence;
  retryDataSource?: Pick<HttpReviewStepRetryDataSource, "retry">;
}

function isAbortError(cause: unknown): boolean {
  return cause instanceof Error && cause.name === "AbortError";
}

type ReviewRunOperationKind = "RESTORE" | "CREATE" | "CONTINUE";

export function reviewRunCustomerError(cause: unknown, operation: ReviewRunOperationKind): string {
  if (cause instanceof ApiError) {
    if (cause.code === "NETWORK_ERROR") return operation === "CREATE"
      ? "正在确认本次审核是否已成功创建，请勿重复提交。"
      : "网络连接异常，本次操作身份已保留，请稍后继续。";
    if (operation === "CREATE") {
      const classification = reviewRunCreateConflictClassification(cause);
      if (classification === "OUTCOME_UNCERTAIN") {
        return "正在确认本次审核是否已成功创建，请勿重复提交。";
      }
      if (classification === "READINESS_REJECTED" || classification === "INTEGRITY_CONFLICT") {
        return "当前合同或审核材料已发生变化，请返回项目页面确认最新内容后重新发起审核。";
      }
    }
    if (cause.status === 409) {
      return operation === "CREATE"
        ? "当前合同或审核材料已发生变化，请返回项目页面确认最新内容后重新发起审核。"
        : "审核规则已更新，请重新发起本次审核。";
    }
    if (cause.status >= 500) return "审核服务暂不可用，请稍后继续。";
  }
  if (operation === "RESTORE") return "暂时无法读取上次审核记录";
  return "本次审核暂未完成，请稍后继续";
}

export function useProjectContractReviewRun({
  enabled,
  actorId,
  projectId,
  contractId,
  expectedContractVersion,
  selectedFileIds,
  historicalRunId = null,
  dataSource = httpReviewRunDataSource,
  persistence,
  retryDataSource = httpReviewStepRetryDataSource,
}: UseProjectContractReviewRunOptions): ProjectContractReviewRunState {
  const operationPersistence = useMemo(
    () => persistence ?? browserReviewRunOperationPersistence(),
    [persistence],
  );
  const coordinator = useMemo(() => projectId && actorId.trim()
    ? new ProjectContractReviewRunCoordinator(
        actorId, projectId, contractId, dataSource, operationPersistence,
      )
    : null,
  [actorId, contractId, dataSource, operationPersistence, projectId]);
  const [run, setRun] = useState<ReviewRunDto | null>(null);
  const [restoring, setRestoring] = useState(false);
  const [executing, setExecuting] = useState(false);
  const [recovered, setRecovered] = useState(false);
  const [restartRequired, setRestartRequired] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retryingStepId, setRetryingStepId] = useState<string | null>(null);
  const generationRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);
  const pendingRef = useRef(false);
  const explicitNewIntentRef = useRef(false);
  const explicitNewBaselineRunIdRef = useRef<string | null>(null);

  useEffect(() => {
    explicitNewIntentRef.current = false;
    explicitNewBaselineRunIdRef.current = null;
  }, [actorId, projectId, contractId]);

  const pollingPointer = useMemo(() => run?.executionMode === "SERVER_WORKER"
      && (run.status === "PENDING" || run.status === "RUNNING") && projectId
    ? { actorId, projectId, contractId, runId: run.runId }
    : null,
  [actorId, contractId, projectId, run]);
  const workerPolling = useDurableReviewRunPolling(pollingPointer, dataSource);

  useEffect(() => {
    if (!workerPolling.run || !projectId) return;
    try {
      const durable = assertOwnedRun(
        workerPolling.run, actorId, projectId, contractId, pollingPointer?.runId,
      );
      setRun(durable);
      setError(workerPolling.error);
    } catch {
      setError(OWNERSHIP_ERROR);
    }
  }, [actorId, contractId, pollingPointer?.runId, projectId, workerPolling.error, workerPolling.run]);

  const load = useCallback(async () => {
    if (!enabled || !coordinator) return;
    const generation = ++generationRef.current;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setRestoring(true);
    setError(null);
    try {
      let assessment: ReviewRunExecutionAssessment = { restartRequired: false };
      const restored = historicalRunId
        ? await coordinator.reopenExact(historicalRunId, controller.signal)
        : await coordinator.restore(controller.signal, (nextAssessment) => {
            assessment = nextAssessment;
          });
      if (generation !== generationRef.current || controller.signal.aborted) return;
      setRun(restored);
      setRecovered(restored !== null);
      setRestartRequired(assessment.restartRequired);
      setError(assessment.restartRequired ? REVIEW_RUN_RESTART_REQUIRED_MESSAGE : null);
    } catch (cause) {
      if (isAbortError(cause) || generation !== generationRef.current) return;
      setError(reviewRunCustomerError(cause, "RESTORE"));
    } finally {
      if (generation === generationRef.current && !controller.signal.aborted) setRestoring(false);
    }
  }, [coordinator, enabled, historicalRunId]);

  useEffect(() => {
    setRun(null);
    setRecovered(false);
    setRestartRequired(false);
    setError(null);
    pendingRef.current = false;
    void load();
    return () => {
      generationRef.current += 1;
      controllerRef.current?.abort();
    };
  }, [load]);

  const execute = useCallback(async (
    action: (
      signal: AbortSignal,
      publish: ProvenRunPublisher,
      assess: ExecutionAssessmentPublisher,
    ) => Promise<ReviewRunDto>,
    operation: Exclude<ReviewRunOperationKind, "RESTORE">,
  ) => {
    if (pendingRef.current) return;
    pendingRef.current = true;
    const generation = ++generationRef.current;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setExecuting(true);
    setRestartRequired(false);
    setError(null);
    try {
      let assessment: ReviewRunExecutionAssessment = { restartRequired: false };
      const publish = (proven: ReviewRunDto) => {
        if (generation !== generationRef.current || controller.signal.aborted) return;
        setRun(proven);
        setRecovered(false);
      };
      const assess = (nextAssessment: ReviewRunExecutionAssessment) => {
        assessment = nextAssessment;
      };
      const next = await action(controller.signal, publish, assess);
      if (generation !== generationRef.current || controller.signal.aborted) return;
      setRun(next);
      setRecovered(false);
      setRestartRequired(assessment.restartRequired);
      setError(assessment.restartRequired ? REVIEW_RUN_RESTART_REQUIRED_MESSAGE : null);
    } catch (cause) {
      if (isAbortError(cause) || generation !== generationRef.current) return;
      setError(reviewRunCustomerError(cause, operation));
    } finally {
      if (generation === generationRef.current && !controller.signal.aborted) {
        pendingRef.current = false;
        setExecuting(false);
      }
    }
  }, []);

  const start = useCallback(async () => {
    if (historicalRunId || !coordinator || expectedContractVersion === null) return;
    await execute(async (signal, publish, assess) => {
      if (!explicitNewIntentRef.current) {
        return coordinator.startOrResume(
          expectedContractVersion, selectedFileIds, signal, publish, assess,
        );
      }
      const reconciliation = await coordinator.reconcileCreate(
        expectedContractVersion, selectedFileIds, signal,
      );
      if (signal.aborted) throw new DOMException("审核创建已取消", "AbortError");
      if (reconciliation.kind === "PROVEN") {
        const reconciled = reconciliation.run;
        if (reconciled.runId !== explicitNewBaselineRunIdRef.current) {
          explicitNewIntentRef.current = false;
          explicitNewBaselineRunIdRef.current = null;
          publish(reconciled);
          return coordinator.executeProvenCreate(reconciled, signal, assess);
        }
        coordinator.clear(reconciled.runId);
      }
      if (reconciliation.kind === "READINESS_REJECTED" && !reconciliation.commandChanged) {
        throw reconciliation.error;
      }
      return coordinator.createAndExecute(
        expectedContractVersion,
        selectedFileIds,
        signal,
        (proven) => {
          if (signal.aborted) return;
          explicitNewIntentRef.current = false;
          explicitNewBaselineRunIdRef.current = null;
          publish(proven);
        },
        assess,
      );
    }, "CREATE");
  }, [coordinator, execute, expectedContractVersion, historicalRunId, selectedFileIds]);

  const continuePending = useCallback(async () => {
    if (historicalRunId || !coordinator || !run || restartRequired) return;
    await execute((signal, _publish, assess) => coordinator.continuePending(
      run, signal, assess,
    ), "CONTINUE");
  }, [coordinator, execute, historicalRunId, restartRequired, run]);

  const startNew = useCallback(() => {
    if (historicalRunId || !coordinator || !run || pendingRef.current) return;
    if (!restartRequired && !canStartNewReviewRun(run.status)) return;
    coordinator.clear(run.runId);
    explicitNewIntentRef.current = true;
    explicitNewBaselineRunIdRef.current = run.runId;
    setRun(null);
    setRecovered(false);
    setRestartRequired(false);
    setError(null);
  }, [coordinator, historicalRunId, restartRequired, run]);

  const retryFailedStep = useCallback(async (stepId: string) => {
    if (historicalRunId || !coordinator || !projectId || !run || pendingRef.current) return;
    const retryable = retryableCurrentAttempt(run, stepId);
    if (!retryable) return;
    const operation = loadOrCreateReviewStepRetryOperation(
      operationPersistence, actorId, projectId, contractId, run.runId, stepId, retryable.command,
    );
    pendingRef.current = true;
    const generation = ++generationRef.current;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setRetryingStepId(stepId);
    setError(null);
    try {
      await retryDataSource.retry(projectId, contractId, run.runId, stepId,
        retryable.command, operation.idempotencyKey, { signal: controller.signal });
      clearReviewStepRetryOperation(
        operationPersistence, actorId, projectId, contractId, run.runId, stepId,
        operation.idempotencyKey,
      );
      const durable = await coordinator.restore(controller.signal);
      if (generation === generationRef.current && !controller.signal.aborted && durable) setRun(durable);
    } catch (cause) {
      if (!isAbortError(cause) && generation === generationRef.current) {
        setError("该审核环节暂未确认重新处理结果，请保持当前操作后重试。");
      }
    } finally {
      if (generation === generationRef.current && !controller.signal.aborted) {
        pendingRef.current = false;
        setRetryingStepId(null);
      }
    }
  }, [actorId, contractId, coordinator, historicalRunId, operationPersistence, projectId, retryDataSource, run]);

  return {
    run,
    presentation: run ? presentReviewRun(run, restartRequired) : null,
    restoring,
    executing,
    observing: pollingPointer !== null,
    retryingStepId,
    recovered,
    restartRequired,
    error,
    start,
    continuePending,
    startNew,
    reload: load,
    retryFailedStep,
  };
}
