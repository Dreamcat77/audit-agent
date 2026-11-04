package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewWorkerRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded in-flight computation only; all pending work and ownership live in DM8. */
public final class ContractReviewWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ContractReviewWorker.class);
    private final ContractReviewWorkerRepository repository;
    private final ReviewWorkerTransactionCoordinator transactions;
    private final FrozenReviewInputLoader inputs;
    private final Map<ContractReviewStepType, ReviewStepExecutor> executors;
    private final Clock clock;
    private final String workerId;
    private final Duration heartbeat;
    private final Duration timeout;
    private final ThreadPoolExecutor computation;
    private final ReentrantLock scan = new ReentrantLock();
    private final AtomicReference<Object> inFlight = new AtomicReference<>();
    private volatile boolean closed;

    public ContractReviewWorker(ContractReviewWorkerRepository repository,
            ReviewWorkerTransactionCoordinator transactions, FrozenReviewInputLoader inputs,
            List<ReviewStepExecutor> executors, Clock clock, String workerId,
            Duration heartbeat, Duration timeout) {
        this(repository, transactions, inputs, executors, clock, workerId, heartbeat, timeout,
                queue -> new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue, runnable -> {
                    Thread thread = new Thread(runnable, "contract-review-computation");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()));
    }

    ContractReviewWorker(ContractReviewWorkerRepository repository,
            ReviewWorkerTransactionCoordinator transactions, FrozenReviewInputLoader inputs,
            List<ReviewStepExecutor> executors, Clock clock, String workerId,
            Duration heartbeat, Duration timeout,
            java.util.function.Function<BlockingQueue<Runnable>, ThreadPoolExecutor> computationFactory) {
        if (heartbeat.isNegative() || heartbeat.isZero() || timeout.compareTo(heartbeat) <= 0)
            throw new IllegalArgumentException("invalid worker timing");
        if (workerId == null || !workerId.matches("[A-Za-z0-9._:-]{1,100}"))
            throw new IllegalArgumentException("invalid worker identity");
        var registry = new EnumMap<ContractReviewStepType, ReviewStepExecutor>(ContractReviewStepType.class);
        for (var executor : executors) {
            if (executor.stepType() == ContractReviewStepType.MATERIAL_VALIDATION
                    || registry.putIfAbsent(executor.stepType(), executor) != null)
                throw new IllegalArgumentException("duplicate or invalid worker executor");
        }
        this.repository = repository; this.transactions = transactions; this.inputs = inputs;
        this.executors = Map.copyOf(registry); this.clock = clock; this.workerId = workerId;
        this.heartbeat = heartbeat; this.timeout = timeout;
        // One bounded handoff covers the gap between Future completion and the
        // worker thread returning to dequeue. It is not a pending-work queue.
        var handoff = new ArrayBlockingQueue<Runnable>(1);
        this.computation = Objects.requireNonNull(computationFactory.apply(handoff));
        if (computation.getQueue() != handoff || computation.getCorePoolSize() != 1
                || computation.getMaximumPoolSize() != 1
                || !(computation.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy))
            throw new IllegalArgumentException("worker computation must use its single bounded handoff");
    }

    public void scanOnce(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("worker batch limit must be 1..100");
        if (!scan.tryLock()) return;
        try {
            // Cancellation is not computation exit. Neither claim nor recover
            // may acquire a durable lease while this instance is still busy.
            if (closed || inFlight.get() != null) return;
            for (var candidate : repository.findExpiredLeases(limit, clock.instant())) {
                if (!admit(candidate, true)) return;
            }
            for (var candidate : repository.findClaimCandidates(limit, clock.instant())) {
                if (!admit(candidate, false)) return;
            }
        } finally {
            scan.unlock();
        }
    }

    private boolean admit(ContractReviewWorkerRepository.Candidate candidate, boolean recover) {
        Object token = new Object();
        if (closed || !inFlight.compareAndSet(null, token)) return false;
        boolean executionOwnsToken = false;
        try {
            if (closed) return false;
            var started = recover ? transactions.recover(candidate, workerId)
                    : Optional.of(transactions.claim(candidate, workerId));
            if (started.isPresent()) {
                executionOwnsToken = true;
                execute(started.get(), token);
            }
        } catch (RuntimeException uncertain) {
            logSafe(uncertain);
        } finally {
            if (!executionOwnsToken) inFlight.compareAndSet(token, null);
        }
        return !closed && inFlight.get() == null;
    }

    private void execute(ReviewStepStartTransactionCoordinator.StartedExecution started, Object token) {
        if (closed) {
            inFlight.compareAndSet(token, null);
            return; // Retain the committed lease for recovery, never invent failure.
        }
        var executor = executors.get(started.step().stepType());
        if (executor == null) {
            try { transactions.close(started, ContractReviewStepStatus.FAILED, "WORKER_EXECUTOR_UNAVAILABLE"); }
            finally { inFlight.compareAndSet(token, null); }
            return;
        }
        var observed = new AtomicReference<>(started);
        long deadline = System.nanoTime() + timeout.toNanos();
        var future = new ComputationTask(token, () -> {
            try {
                if (closed) throw new CancellationException("worker closed before computation start");
                var reopened = inputs.load(started.run().projectId(), started.run().contractId(), started.run().runId());
                if (!reopened.run().runId().equals(started.run().runId())
                        || !reopened.run().projectId().equals(started.run().projectId())
                        || !reopened.run().contractId().equals(started.run().contractId())
                        || !reopened.run().requestedBy().equals(started.run().requestedBy())
                        || !reopened.run().inputSnapshotIdentity().equals(started.run().inputSnapshotIdentity()))
                    throw ReviewWorkerLeasePolicy.conflict();
                // A sibling may advance mutable aggregate status/version after claim. The verified frozen
                // input identity is unchanged; keep the executor's input and claim context on one snapshot.
                var input = new FrozenReviewInput(started.run(), reopened.frozenContract(), reopened.materials());
                var context = new ReviewExecutionContext(started.run(), started.step(), started.attempt(),
                        started.actor(), started.command().metadata(), started.f02Configurations(), fingerprint -> {
                            if (closed) throw new CancellationException("worker closed before external request");
                            var previous = observed.get();
                            var requested = transactions.markRequested(previous, fingerprint);
                            // The durable CAS wins before this local token cache is refreshed.
                            observed.set(requested);
                        });
                return executor.execute(input, context);
            } finally {
                // The actual callable has exited, including after ignored
                // cancellation. A late old finally must not clear a new owner.
                inFlight.compareAndSet(token, null);
            }
        });
        try {
            computation.execute(future);
        } catch (RuntimeException rejected) {
            future.cancelQueued(); // Rejection proves this task was not dispatched.
            throw rejected;
        }
        try {
            StepComputationResult result;
            while (true) {
                if (closed) return;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    cancel(future);
                    // Cancellation only requests interruption; computation may still publish inner F02 facts.
                    // Stop renewing and leave RUNNING at its last durable lease for fenced recovery.
                    return;
                }
                try {
                    result = future.get(Math.min(heartbeat.toNanos(), remaining), TimeUnit.NANOSECONDS);
                    break;
                } catch (TimeoutException awaiting) {
                    if (closed) return;
                    var previous = observed.get();
                    observed.set(transactions.heartbeat(previous));
                }
            }
            // Persistence uncertainty must not be caught and rewritten as a computation failure.
            if (!closed && !future.isCancelled()) transactions.complete(observed.get(), result);
        } catch (ExecutionException failed) {
            if (closed) return; // Shutdown is not authority to publish a late outcome.
            Throwable cause = failed.getCause();
            var status = cause instanceof ReviewStepProvenFailureException
                    ? ContractReviewStepStatus.FAILED : ContractReviewStepStatus.UNKNOWN;
            String reason = cause instanceof ReviewStepProvenFailureException proven
                    ? proven.safeReasonCode()
                    : cause instanceof ReviewStepOutcomeUnknownException unknown
                    ? unknown.safeReasonCode()
                    : "WORKER_COMPUTATION_OUTCOME_UNPROVEN";
            transactions.close(observed.get(), status, reason,
                    cause instanceof ReviewStepProvenFailureException proven ? proven.modelCalls()
                            : cause instanceof ReviewStepOutcomeUnknownException unknown ? unknown.modelCalls() : List.of());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // Keep durable lease for scanner; interruption is not evidence of failure.
        } finally {
            cancel(future);
        }
    }

    private void cancel(ComputationTask future) {
        if (!future.isDone()) future.cancel(true);
        // Only a successfully removed queue entry is proved not running.
        if (computation.remove(future)) future.cancelQueued();
    }

    private final class ComputationTask extends FutureTask<StepComputationResult> {
        private final Object token;
        ComputationTask(Object token, Callable<StepComputationResult> callable) {
            super(callable);
            this.token = token;
        }
        @Override public void run() {
            try { super.run(); }
            finally {
                // Also covers cancellation after dequeue but before callable start.
                inFlight.compareAndSet(token, null);
            }
        }
        void cancelQueued() {
            cancel(false);
            inFlight.compareAndSet(token, null);
        }
    }

    private void logSafe(RuntimeException failure) {
        log.warn("Contract review worker deferred operation workerId={} failureType={}",
                workerId, failure.getClass().getSimpleName());
    }
    @Override public void close() {
        closed = true;
        for (Runnable abandoned : computation.shutdownNow()) {
            if (abandoned instanceof ContractReviewWorker.ComputationTask task) task.cancelQueued();
        }
    }
}
