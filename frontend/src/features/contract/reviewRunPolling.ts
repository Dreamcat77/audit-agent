export interface PollableReviewRun {
  status: string;
  steps: readonly { status: string }[];
}

export interface ReviewRunPollingOptions<T extends PollableReviewRun> {
  load: (signal: AbortSignal) => Promise<T>;
  onValue: (value: T) => void;
  onError: (error: unknown) => void;
  visible: () => boolean;
  subscribeVisibility: (listener: () => void) => () => void;
  schedule?: (callback: () => void, delay: number) => () => void;
  maxPolls?: number;
}

/** GET-only observer. Server state is authoritative, including after transport uncertainty. */
export function startReviewRunPolling<T extends PollableReviewRun>(options: ReviewRunPollingOptions<T>) {
  // Queue delay and lease recovery can outlive one worker timeout. The default
  // GET-only observer therefore remains active while visible until the exact
  // durable run is terminal or its owning component disposes it.
  const maxPolls = options.maxPolls;
  if (maxPolls !== undefined && (!Number.isInteger(maxPolls) || maxPolls < 1 || maxPolls > 100))
    throw new Error("invalid polling limit");
  const schedule = options.schedule ?? ((callback, delay) => {
    const timer = setTimeout(callback, delay);
    return () => clearTimeout(timer);
  });
  let disposed = false;
  let generation = 0;
  let polls = 0;
  let terminal = false;
  let active: AbortController | undefined;
  let cancelTimer: (() => void) | undefined;
  function cancel() {
    generation++;
    active?.abort(); active = undefined;
    cancelTimer?.(); cancelTimer = undefined;
  }
  async function poll() {
    if (disposed || terminal || !options.visible() || (maxPolls !== undefined && polls >= maxPolls)) return;
    const token = generation;
    const controller = new AbortController();
    active = controller;
    polls++;
    const current = () => !disposed && token === generation && !controller.signal.aborted;
    try {
      const value = await options.load(controller.signal);
      if (!current()) return;
      // Existing aggregate UNKNOWN can coexist with a running sibling. Do not strand its output.
      terminal = value.status !== "PENDING" && value.status !== "RUNNING"
        && !value.steps.some(step => step.status === "RUNNING");
      options.onValue(value);
    } catch (error) {
      if (!current()) return;
      options.onError(error);
    } finally {
      if (current()) {
        active = undefined;
        if (!terminal && options.visible() && (maxPolls === undefined || polls < maxPolls))
          cancelTimer = schedule(() => { cancelTimer = undefined; void poll(); }, Math.min(15000, 1000 * 2 ** (polls - 1)));
      }
    }
  }
  const unsubscribe = options.subscribeVisibility(() => {
    cancel();
    if (options.visible()) void poll();
  });
  void poll();
  return {
    reload() { if (disposed) return; cancel(); polls = 0; terminal = false; void poll(); },
    dispose() { if (disposed) return; disposed = true; cancel(); unsubscribe(); },
  };
}
