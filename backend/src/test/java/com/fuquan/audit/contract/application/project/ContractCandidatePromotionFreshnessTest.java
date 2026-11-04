package com.fuquan.audit.contract.application.project;

import static com.fuquan.audit.contract.application.project.ContractCandidateSupportTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ContractCandidatePromotionFreshnessTest {
    @Test
    void sessionCacheRetainsAbsentReadsAcrossUnrelatedForUpdateUntilTheSessionEnds() {
        var cache = new PromotionSessionCacheTestSupport();
        var database = new AtomicReference<java.util.Optional<String>>(java.util.Optional.empty());
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                new PromotionSessionCacheTestSupport.Transactions());
        transactions.execute(ignored -> {
            try {
                assertThat(cache.read("idempotency", database::get)).isEqualTo(java.util.Optional.empty());
                database.set(java.util.Optional.of("committed-winner"));
                cache.read("file-for-update", () -> "locked");
                assertThat(cache.read("idempotency", database::get)).isEqualTo(java.util.Optional.empty());
                return null;
            } catch (Throwable failure) { throw new AssertionError(failure); }
        });
        transactions.execute(ignored -> {
            try {
                assertThat(cache.read("idempotency", database::get)).isEqualTo(java.util.Optional.of("committed-winner"));
                return null;
            } catch (Throwable failure) { throw new AssertionError(failure); }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void committedWinnerIsVisibleAfterFileWaitDespiteSessionCachedAbsentPreflight(boolean ambientTransaction) throws Exception {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(A));
        var command = f.command(f.confirm(), "cached-absence-winner");
        cacheReads(f);
        var manager = new PromotionSessionCacheTestSupport.Transactions();
        var service = f.promotionService(f.newPromotionCoordinator(), manager);
        var pause = pauseBeforeFirstFile(f);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var loser = pool.submit(() -> {
                pause.thread.set(Thread.currentThread());
                if (!ambientTransaction) return attempt(() -> service.promote(command));
                try { return new org.springframework.transaction.support.TransactionTemplate(manager).execute(ignored -> {
                    f.idempotency.findByScopeAndKey(ContractCandidatePromotionTransactionCoordinator.SCOPE, command.idempotencyKey());
                    f.current(); // An ambient caller must not donate its stale session to the write transaction.
                    try { return attempt(() -> service.promote(command)); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }); } catch (RuntimeException failure) { return failure; }
            });
            assertThat(pause.entered.await(5, TimeUnit.SECONDS)).isTrue();
            // Independent coordinator/session, as with another service instance, writes the actual durable local result.
            var winnerService = f.promotionService(f.newPromotionCoordinator(), new PromotionSessionCacheTestSupport.Transactions());
            var winner = winnerService.promote(command);
            pause.release.countDown();
            assertThat(loser.get(5, TimeUnit.SECONDS)).isEqualTo(winner);
            assertThat(f.audits.stream().filter(a -> a.eventType().equals("PROJECT_CONTRACT_CANDIDATE_PROMOTED"))).hasSize(1);
            assertThat(f.contracts.findById(winner.contract().id())).contains(winner.contract());
        } finally { pause.release.countDown(); pool.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cohort-missing", "cohort-tuple"})
    void lockedCohortRecheckCannotReuseTheDiscoverySessionsCachedRows(String drift) throws Exception {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(A));
        var command = f.command(f.confirm(), "cached-cohort-" + drift);
        cacheReads(f);
        var pause = pauseBeforeFirstFile(f);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var promotion = pool.submit(() -> { pause.thread.set(Thread.currentThread()); return attempt(() -> f.service.promote(command)); });
            assertThat(pause.entered.await(5, TimeUnit.SECONDS)).isTrue();
            // Storage corruption/repair between sessions without a version bump must still fail closed.
            f.drift(drift.equals("cohort-missing") ? "missing" : "fingerprint");
            pause.release.countDown();
            assertThat(promotion.get(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
            assertThat(f.associations.findByCandidateId(CANDIDATE)).isEmpty();
            assertThat(f.current().promotionState()).isEqualTo(CandidatePromotionState.DRAFT);
        } finally { pause.release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void replacementFirstWithoutSpringSynchronizationCannotDeleteAPromotedCohort() throws Exception {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(A));
        var command = f.command(f.confirm(), "no-sync-replacement-first");
        var validated = new CountDownLatch(1);
        var releaseReplacement = new CountDownLatch(1);
        var attemptedPromotion = new CountDownLatch(1);
        var promotionDone = new CountDownLatch(1);
        var replacementThread = new AtomicReference<Thread>();
        var promotionThread = new AtomicReference<Thread>();
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (Thread.currentThread() == replacementThread.get()) {
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                validated.countDown(); await(releaseReplacement);
            }
            return result;
        }).when(f.supportAuthority).resolveRequested(any(), any());
        doAnswer(call -> {
            if (Thread.currentThread() == promotionThread.get()) attemptedPromotion.countDown();
            return call.callRealMethod();
        }).when(f.files).withFileAuthorityFence(any(), any(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var replacement = pool.submit(() -> {
                replacementThread.set(Thread.currentThread());
                return attempt(() -> f.replacement.replace(new ReplaceContractCandidateSupportAuthorityCommand(
                        PROJECT, CANDIDATE, command.expectedVersion(), List.of(B), "no-sync-replace", META), ACTOR));
            });
            assertThat(validated.await(5, TimeUnit.SECONDS)).isTrue();
            var promotion = pool.submit(() -> {
                promotionThread.set(Thread.currentThread());
                try { return attempt(() -> f.service.promote(command)); }
                finally { promotionDone.countDown(); }
            });
            assertThat(attemptedPromotion.await(5, TimeUnit.SECONDS)).isTrue();
            boolean promotedBeforeReplacementReleased = promotionDone.await(200, TimeUnit.MILLISECONDS);
            releaseReplacement.countDown();
            Object replaced = replacement.get(5, TimeUnit.SECONDS);
            Object promoted = promotion.get(5, TimeUnit.SECONDS);
            var rows = f.supports.findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
            // On the broken implementation: Promotion succeeds, replacement deletes A then its CAS rejects PROMOTED.
            assertThat(rows).as("committed Candidate metadata must retain its complete support cohort").hasSize(1);
            assertThat(promotedBeforeReplacementReleased).isFalse();
            assertThat(replaced).isInstanceOf(ContractCandidateSupportReplacementResult.class);
            assertThat(promoted).isInstanceOf(BusinessException.class);
            assertThat(rows.get(0).sourceFileId()).isEqualTo(B);
            var finalPromotion = f.promote(f.confirm());
            assertThat(f.supportAuthority.validateCurrent(finalPromotion.candidate()).supports()).hasSize(1);
            assertThat(f.contracts.findById(finalPromotion.contract().id())).contains(finalPromotion.contract());
        } finally { releaseReplacement.countDown(); pool.shutdownNow(); }
    }

    private static void cacheReads(CandidateSupportPromotionTestSupport f) {
        var cache = new PromotionSessionCacheTestSupport();
        doAnswer(call -> cache.read("idempotency-" + call.getArgument(0) + "-" + call.getArgument(1), call::callRealMethod))
                .when(f.idempotency).findByScopeAndKey(any(), any());
        doAnswer(call -> cache.read("candidate", call::callRealMethod))
                .when(f.candidates).findByProjectIdAndId(PROJECT, CANDIDATE);
        // The DM8 FOR UPDATE statement is distinct from the ordinary SELECT (unlike the local port default).
        doAnswer(call -> cache.read("candidate-for-update", () ->
                f.memory.contractCandidateRepository().findByProjectIdAndId(PROJECT, CANDIDATE)))
                .when(f.candidates).findByProjectIdAndIdForUpdate(PROJECT, CANDIDATE);
        doAnswer(call -> cache.read("support-cohort", call::callRealMethod))
                .when(f.supports).findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
    }

    @Test
    void replacementWithoutSynchronizationWaitsForPromotionAndRejectsBeforeDeletingItsCohort() throws Exception {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(A));
        var command = f.command(f.confirm(), "no-sync-promotion-first");
        var before = f.supports.findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
        var validated = new CountDownLatch(1);
        var releasePromotion = new CountDownLatch(1);
        var replacementAttempted = new CountDownLatch(1);
        var replacementDone = new CountDownLatch(1);
        var replacementThread = new AtomicReference<Thread>();
        doAnswer(call -> {
            var result = call.callRealMethod(); validated.countDown(); await(releasePromotion); return result;
        }).when(f.supportAuthority).validateCurrent(any());
        doAnswer(call -> {
            if (Thread.currentThread() == replacementThread.get()) replacementAttempted.countDown();
            return call.callRealMethod();
        }).when(f.files).withFileAuthorityFence(any(), any(), any());
        clearInvocations(f.supports);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var promotion = pool.submit(() -> f.service.promote(command));
            assertThat(validated.await(5, TimeUnit.SECONDS)).isTrue();
            var replacement = pool.submit(() -> {
                replacementThread.set(Thread.currentThread());
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                try { return attempt(() -> f.replacement.replace(new ReplaceContractCandidateSupportAuthorityCommand(
                        PROJECT, CANDIDATE, command.expectedVersion(), List.of(B), "waiting-replace", META), ACTOR)); }
                finally { replacementDone.countDown(); }
            });
            assertThat(replacementAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(replacementDone.await(200, TimeUnit.MILLISECONDS)).isFalse();
            releasePromotion.countDown();
            var promoted = promotion.get(5, TimeUnit.SECONDS);
            assertThat(replacement.get(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(
                    ContractCandidateSupportReplacementException.class, failure -> {
                        assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT);
                        assertThat(failure.classification()).isEqualTo(
                                ContractCandidateSupportReplacementException.Classification.REQUEST_REJECTED);
                    });
            verify(f.supports, never()).deleteCurrentByProjectIdAndCandidateId(any(), any());
            assertThat(f.supports.findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE)).isEqualTo(before);
            assertThat(f.current()).isEqualTo(promoted.candidate());
        } finally { releasePromotion.countDown(); pool.shutdownNow(); }
    }

    private record Pause(AtomicReference<Thread> thread, CountDownLatch entered, CountDownLatch release) { }
    private static Pause pauseBeforeFirstFile(CandidateSupportPromotionTestSupport f) {
        var pause = new Pause(new AtomicReference<>(), new CountDownLatch(1), new CountDownLatch(1));
        var once = new AtomicBoolean();
        doAnswer(call -> {
            if (Thread.currentThread() == pause.thread.get() && once.compareAndSet(false, true)) {
                pause.entered.countDown(); await(pause.release);
            }
            return call.callRealMethod();
        }).when(f.files).withFileAuthorityFence(any(), any(), any());
        return pause;
    }
    private static Object attempt(Callable<?> operation) throws Exception {
        try { return operation.call(); } catch (BusinessException rejected) { return rejected; }
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
