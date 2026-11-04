import type { ProcurementMaterialBatchResponse } from '../../../api/types';

export type IntakeReviewSnapshot = {
  pending: boolean;
  result: ProcurementMaterialBatchResponse | null;
  error: string | null;
};

/** An explicit save starts one request. Subscriptions never start model calls. */
export class ProcurementIntakeReviewOperation {
  private state: IntakeReviewSnapshot = { pending: false, result: null, error: null };
  private listeners = new Set<() => void>();
  private running: Promise<void> | null = null;
  private execute: () => Promise<ProcurementMaterialBatchResponse>;
  private explain: (cause: unknown) => string;
  constructor(execute: () => Promise<ProcurementMaterialBatchResponse>, explain: (cause: unknown) => string) {
    this.execute = execute; this.explain = explain;
  }
  snapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private publish(state: IntakeReviewSnapshot) { this.state = state; this.listeners.forEach(listener => listener()); }
  reset = () => { if (!this.running) this.publish({ pending: false, result: null, error: null }); };
  start = (): Promise<void> => {
    if (this.running) return this.running;
    this.running = Promise.resolve().then(this.execute).then(
      result => this.publish({ pending: false, result, error: null }),
      cause => this.publish({ pending: false, result: null, error: this.explain(cause) }),
    ).finally(() => { this.running = null; });
    this.publish({ pending: true, result: null, error: null });
    return this.running;
  };
}
