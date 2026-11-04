package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Real Spring scheduling only: no application boot, HTTP, DB, model or file processing. */
class ContractReviewWorkerSchedulingIsolationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingOnly { }

    static final class Signals {
        final CountDownLatch semanticEntered = new CountDownLatch(1);
        final CountDownLatch releaseSemantic = new CountDownLatch(1);
        final CountDownLatch semanticExited = new CountDownLatch(1);
        final CountDownLatch fileDuringSemantic = new CountDownLatch(1);
        final CountDownLatch fileAfterRelease = new CountDownLatch(1);
        final AtomicReference<String> semanticThread = new AtomicReference<>();
        final AtomicReference<String> fileThread = new AtomicReference<>();
    }

    static final class FileReadProbe {
        private final Signals signals;
        FileReadProbe(Signals signals) { this.signals = signals; }
        @Scheduled(fixedDelay = 5)
        public void readOnlyProbe() {
            // A pre-semantic tick is not evidence of scheduler availability
            // during the long scan. Neither path reads a real file or service.
            if (signals.semanticEntered.getCount() != 0) return;
            signals.fileThread.set(Thread.currentThread().getName());
            if (signals.releaseSemantic.getCount() != 0) signals.fileDuringSemantic.countDown();
            else signals.fileAfterRelease.countDown();
        }
    }

    @Test
    void fileScheduledProbeMustRunAfterSemanticEnteredWhileProductionScannerIsWaiting() throws Exception {
        var signals = new Signals();
        var first = new AtomicBoolean(true);
        var worker = mock(ContractReviewWorker.class);
        doAnswer(invocation -> {
            if (!first.compareAndSet(true, false)) return null;
            signals.semanticThread.set(Thread.currentThread().getName());
            signals.semanticEntered.countDown();
            try {
                if (!signals.releaseSemantic.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("offline semantic fixture release deadline exceeded");
            } finally { signals.semanticExited.countDown(); }
            return null;
        }).when(worker).scanOnce(4);

        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("offline-scheduling", Map.of(
                "audit.contract.review-worker.scan-delay-ms", "5")));
        context.register(SchedulingOnly.class);
        context.registerBean("productionReviewScanner", ContractReviewWorkerConfiguration.WorkerScanner.class,
                () -> new ContractReviewWorkerConfiguration.WorkerScanner(worker, 4));
        context.registerBean(FileReadProbe.class, () -> new FileReadProbe(signals));
        try {
            context.refresh();
            assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
            assertThat(signals.semanticEntered.await(2, TimeUnit.SECONDS)).isTrue();
            // This bounded await tests progress, not a guessed sleep window:
            // semantic cannot finish until the finally block releases its latch.
            assertThat(signals.fileDuringSemantic.await(1, TimeUnit.SECONDS))
                    .as("file @Scheduled probe must make progress while production review scanner waits")
                    .isTrue();
        } finally {
            signals.releaseSemantic.countDown();
            try {
                assertThat(signals.semanticExited.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(signals.fileAfterRelease.await(2, TimeUnit.SECONDS))
                        .as("positive control: registered file probe runs after semantic releases")
                        .isTrue();
                System.out.println("OFFLINE_SCHEDULER_PROOF reviewThread=" + signals.semanticThread.get()
                        + " fileThread=" + signals.fileThread.get()
                        + " fileDuringSemantic=" + (signals.fileDuringSemantic.getCount() == 0)
                        + " fileAfterRelease=" + (signals.fileAfterRelease.getCount() == 0)
                        + " externalCalls=0");
            } finally { context.close(); }
        }
    }
}
