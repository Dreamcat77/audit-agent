package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Opt-in only. Integration must route synchronous compatibility through the same lease boundary. */
@Configuration(proxyBeanMethods = false)
@Profile({"local-dev", "dev29", "customer"})
@ConditionalOnProperty(name = "audit.contract.review-worker.enabled", havingValue = "true")
@EnableScheduling
public class ContractReviewWorkerConfiguration {
    @Bean
    ReviewStepRetryService reviewStepRetryService(ContractReviewWorkerRepository workers,
            ContractReviewRunRepository runs, ContractReviewStepRepository steps,
            ContractReviewStepAttemptRepository attempts, IdempotencyPort idempotency, OperationAuditPort audit,
            com.fuquan.audit.security.application.AuthContext auth, Clock clock) {
        return new ReviewStepRetryService(workers, runs, steps, attempts, idempotency, audit, auth, clock,
                new ReviewStepRetryPolicy());
    }
    @Bean
    ReviewWorkerTransactionCoordinator reviewWorkerTransactions(ContractReviewWorkerRepository workers,
            ContractReviewRunRepository runs, ContractReviewStepRepository steps,
            ContractReviewStepAttemptRepository attempts, IdempotencyPort idempotency, OperationAuditPort audit,
            ContractReviewExecutionPolicyResolver policies, FrozenReviewInputLoader inputs,
            ReviewStepCompleteTransactionCoordinator completes, ReviewStepOutputReadService outputs,
            ReviewRunStatusAggregator aggregator, Clock clock, FrozenF02ResultMapper f02Mapper,
            ContractModelCallAuditRepository modelCalls,
            com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointRepository semanticCheckpoints,
            @Value("${audit.contract.review-worker.lease:PT1M}") Duration lease) {
        return new ReviewWorkerTransactionCoordinator(workers, runs, steps, attempts, idempotency, audit,
                policies, inputs, completes, outputs, aggregator, clock, lease, f02Mapper, modelCalls,semanticCheckpoints);
    }

    @Bean(destroyMethod = "close")
    ContractReviewWorker contractReviewWorker(ContractReviewWorkerRepository repository,
            ReviewWorkerTransactionCoordinator transactions, FrozenReviewInputLoader inputs,
            List<ReviewStepExecutor> executors, Clock clock,
            @Value("${audit.contract.review-worker.lease:PT1M}") Duration lease,
            @Value("${audit.contract.review-worker.heartbeat:PT10S}") Duration heartbeat,
            @Value("${audit.contract.review-worker.timeout:PT20M}") Duration timeout) {
        if (heartbeat.multipliedBy(3).compareTo(lease) >= 0 || timeout.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("worker heartbeat must leave lease margin and timeout must be bounded");
        return new ContractReviewWorker(repository, transactions, inputs, executors, clock,
                "worker-" + UUID.randomUUID(), heartbeat, timeout);
    }

    @Bean(destroyMethod = "close")
    WorkerScanner contractReviewWorkerScanner(ContractReviewWorker worker,
            @Value("${audit.contract.review-worker.batch-size:4}") int batchSize) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("invalid worker batch size");
        return new WorkerScanner(worker, batchSize);
    }

    public static final class WorkerScanner implements AutoCloseable {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkerScanner.class);
        private final ContractReviewWorker worker;
        private final int batchSize;
        private final ThreadPoolExecutor scans;
        private final AtomicReference<Object> inFlight = new AtomicReference<>();
        private volatile boolean closed;

        WorkerScanner(ContractReviewWorker worker, int batchSize) {
            this(worker, batchSize, queue -> new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    queue, runnable -> {
                        var thread = new Thread(runnable, "contract-review-scan");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy()));
        }

        WorkerScanner(ContractReviewWorker worker, int batchSize,
                java.util.function.Function<BlockingQueue<Runnable>, ThreadPoolExecutor> factory) {
            if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("invalid worker batch size");
            this.worker = Objects.requireNonNull(worker);
            this.batchSize = batchSize;
            var handoff = new ArrayBlockingQueue<Runnable>(1);
            this.scans = Objects.requireNonNull(factory.apply(handoff));
            if (scans.getQueue() != handoff || scans.getCorePoolSize() != 1 || scans.getMaximumPoolSize() != 1
                    || !(scans.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy))
                throw new IllegalArgumentException("scanner requires its single bounded handoff");
        }

        @Scheduled(fixedDelayString = "${audit.contract.review-worker.scan-delay-ms:5000}")
        public void scan() {
            Object token = new Object();
            if (closed || !inFlight.compareAndSet(null, token)) return;
            var task = new ScanTask(token);
            try {
                if (closed) { task.cancelQueued(); return; }
                scans.execute(task);
            } catch (RuntimeException rejected) {
                task.cancelQueued();
                logFailure(rejected);
            }
        }

        private void logFailure(Throwable failure) {
            log.warn("Contract review scan deferred failureType={}", failure.getClass().getSimpleName());
        }

        private final class ScanTask extends FutureTask<Void> {
            private final Object token;
            ScanTask(Object token) {
                super(() -> {
                    try {
                        if (!closed) worker.scanOnce(batchSize);
                    } catch (RuntimeException failure) {
                        logFailure(failure);
                    } catch (Error fatal) {
                        logFailure(fatal);
                        throw fatal;
                    } finally { inFlight.compareAndSet(token, null); }
                    return null;
                });
                this.token = token;
            }
            @Override public void run() {
                try { super.run(); }
                finally { inFlight.compareAndSet(token, null); }
            }
            void cancelQueued() {
                cancel(false);
                inFlight.compareAndSet(token, null);
            }
        }

        @Override public void close() {
            closed = true;
            for (Runnable abandoned : scans.shutdownNow()) {
                if (abandoned instanceof WorkerScanner.ScanTask task) task.cancelQueued();
            }
            // Only interrupts the scan waiter; existing Worker lease/cancel
            // rules govern already admitted computation. This is not global quiescence.
        }
    }
}
