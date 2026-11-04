package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;

class ContractReviewWorkerScannerTest {
    @Test void scheduledTicksReturnWithoutWaitingAndBusyTicksNeverStartAnotherScan() throws Exception {
        var worker = mock(ContractReviewWorker.class); var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1); var exited = new CountDownLatch(1); var calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet(); entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { exited.countDown(); }
            return null;
        }).when(worker).scanOnce(4);
        var scanner = new ContractReviewWorkerConfiguration.WorkerScanner(worker, 4);
        var ticks = Executors.newFixedThreadPool(4);
        try {
            var first = ticks.submit(scanner::scan);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatCode(() -> first.get(1, TimeUnit.SECONDS)).doesNotThrowAnyException();
            var concurrent = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) concurrent.add(ticks.submit(scanner::scan));
            for (var tick : concurrent) tick.get(1, TimeUnit.SECONDS);
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown(); assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
            scanner.close();
            ticks.shutdownNow();
        }
    }

    @Test void failureReleasesAdmissionAndNextTickRediscoversWithoutClosingWorker() throws Exception {
        var worker = mock(ContractReviewWorker.class); var calls = new AtomicInteger();
        var firstExited = new CountDownLatch(1); var secondEntered = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("SYNTHETIC_PRIVATE_MESSAGE");
            secondEntered.countDown(); return null;
        }).when(worker).scanOnce(4);
        var pool = new ThreadPoolExecutor[1];
        try (var scanner = new ContractReviewWorkerConfiguration.WorkerScanner(worker, 4, queue ->
                pool[0] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue) {
                    @Override protected void afterExecute(Runnable task, Throwable error) { firstExited.countDown(); }
                })) {
            scanner.scan(); assertThat(firstExited.await(2, TimeUnit.SECONDS)).isTrue();
            scanner.scan(); assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
        }
        verify(worker, times(2)).scanOnce(4); verify(worker, never()).close();
    }

    @Test void rejectionDoesNotCallWorkerAndReleasesAdmissionForALaterTick() {
        var worker = mock(ContractReviewWorker.class); var submissions = new AtomicInteger();
        try (var scanner = new ContractReviewWorkerConfiguration.WorkerScanner(worker, 4, queue ->
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue) {
                    @Override public void execute(Runnable task) {
                        submissions.incrementAndGet(); throw new RejectedExecutionException("SYNTHETIC_PRIVATE_MESSAGE");
                    }
                })) {
            scanner.scan(); scanner.scan();
            assertThat(submissions).hasValue(2);
        }
        verifyNoInteractions(worker);
    }

    @Test void closeAfterDequeueBeforeRunDoesNotStartWorkerOrCloseTheWorker() throws Exception {
        var worker = mock(ContractReviewWorker.class); var beforeRun = new CountDownLatch(1);
        var allowRun = new CountDownLatch(1); var afterRun = new CountDownLatch(1);
        try (var scanner = new ContractReviewWorkerConfiguration.WorkerScanner(worker, 4, queue ->
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue) {
                    @Override protected void beforeExecute(Thread thread, Runnable task) {
                        beforeRun.countDown(); awaitIgnoringInterrupt(allowRun);
                    }
                    @Override protected void afterExecute(Runnable task, Throwable failure) { afterRun.countDown(); }
                })) {
            try {
                scanner.scan(); assertThat(beforeRun.await(2, TimeUnit.SECONDS)).isTrue();
                scanner.close(); allowRun.countDown();
                assertThat(afterRun.await(2, TimeUnit.SECONDS)).isTrue();
                scanner.scan(); verifyNoInteractions(worker);
            } finally { allowRun.countDown(); }
        }
    }

    @Test void completedScanAllowsOneBoundedHandoffAndCloseCancelsIt() throws Exception {
        var worker = mock(ContractReviewWorker.class); var firstReturned = new CountDownLatch(1);
        var afterExecute = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = new ThreadPoolExecutor[1];
        doAnswer(invocation -> { firstReturned.countDown(); return null; }).when(worker).scanOnce(4);
        try (var scanner = new ContractReviewWorkerConfiguration.WorkerScanner(worker, 4, queue ->
                pool[0] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue) {
                    @Override protected void afterExecute(Runnable task, Throwable failure) {
                        afterExecute.countDown(); awaitIgnoringInterrupt(release);
                    }
                })) {
            try {
                scanner.scan(); assertThat(afterExecute.await(2, TimeUnit.SECONDS)).isTrue();
                scanner.scan(); scanner.scan();
                assertThat(pool[0].getQueue()).hasSize(1);
                scanner.close(); assertThat(pool[0].getQueue()).isEmpty();
                scanner.scan(); verify(worker, times(1)).scanOnce(4); verify(worker, never()).close();
            } finally { release.countDown(); }
        }
    }

    @Test void fatalScanFailureLogsOnlyItsClassAndRemainsExceptionalWithoutHoldingAdmission() throws Exception {
        var worker = mock(ContractReviewWorker.class); var calls = new AtomicInteger();
        var firstExited = new CountDownLatch(1); var secondEntered = new CountDownLatch(1);
        var firstTask = new AtomicReference<Future<?>>();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1)
                throw new AssertionError("PRIVATE_ERROR_CANARY", new IllegalStateException("PRIVATE_CAUSE_CANARY"));
            secondEntered.countDown(); return null;
        }).when(worker).scanOnce(4);
        var logger = (Logger) LoggerFactory.getLogger(ContractReviewWorkerConfiguration.WorkerScanner.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try (var scanner = new ContractReviewWorkerConfiguration.WorkerScanner(worker, 4, queue ->
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue) {
                    @Override protected void afterExecute(Runnable task, Throwable failure) {
                        if (firstTask.compareAndSet(null, (Future<?>) task)) firstExited.countDown();
                    }
                })) {
            scanner.scan(); assertThat(firstExited.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(appender.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("failureType=AssertionError"));
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE_ERROR_CANARY", "PRIVATE_CAUSE_CANARY");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThatThrownBy(() -> firstTask.get().get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AssertionError.class);
            scanner.scan(); assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
            verify(worker, times(2)).scanOnce(4); verify(worker, never()).close();
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (true) {
            try { latch.await(); return; }
            catch (InterruptedException ignored) { /* Native-like fixture only. */ }
        }
    }
}
