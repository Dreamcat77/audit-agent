import { useCallback, useEffect, useRef, useState } from "react";
import type { ReviewRunDataSource } from "./data/reviewRunDataSource.ts";
import type { ReviewRunDto } from "./reviewRunTypes.ts";
import { startReviewRunPolling } from "./reviewRunPolling.ts";

export interface DurableReviewRunPointer {
  actorId: string;
  projectId: string;
  contractId: string;
  runId: string;
}
type ReadSource = Pick<ReviewRunDataSource, "getReviewRun">;

export function scopedReviewRunLoader(pointer: DurableReviewRunPointer, source: ReadSource) {
  return async (signal: AbortSignal): Promise<ReviewRunDto> => {
    const value = await source.getReviewRun(pointer.projectId, pointer.contractId, pointer.runId, { signal });
    if (value.projectId !== pointer.projectId || value.contractId !== pointer.contractId || value.runId !== pointer.runId)
      throw new Error("review run scope mismatch");
    return value;
  };
}

/** Caller restores its durable run pointer; this hook never creates, executes or retries work. */
export function useDurableReviewRunPolling(pointer: DurableReviewRunPointer | null, source: ReadSource) {
  const identity = pointer ? JSON.stringify([pointer.actorId, pointer.projectId, pointer.contractId, pointer.runId]) : null;
  const [state, setState] = useState<{ identity: string | null; run: ReviewRunDto | null; error: string | null }>({ identity, run: null, error: null });
  const poller = useRef<ReturnType<typeof startReviewRunPolling<ReviewRunDto>> | null>(null);
  useEffect(() => {
    setState({ identity, run: null, error: null });
    if (!pointer) return;
    const active = startReviewRunPolling({
      load: scopedReviewRunLoader(pointer, source),
      onValue: run => setState({ identity, run, error: null }),
      onError: () => setState(previous => ({ ...previous, error: "暂时无法刷新审查状态，请稍后刷新。" })),
      visible: () => typeof document === "undefined" || document.visibilityState !== "hidden",
      subscribeVisibility: listener => {
        if (typeof document === "undefined") return () => {};
        document.addEventListener("visibilitychange", listener);
        return () => document.removeEventListener("visibilitychange", listener);
      },
    });
    poller.current = active;
    return () => { active.dispose(); if (poller.current === active) poller.current = null; };
    // Identity intentionally scopes actor changes too; pointer object identity is not the operation identity.
  }, [identity, source]);
  const reload = useCallback(() => poller.current?.reload(), []);
  // Never render the prior actor/project state during the render before effect cleanup.
  return { run: state.identity === identity ? state.run : null,
    error: state.identity === identity ? state.error : null, reload };
}
