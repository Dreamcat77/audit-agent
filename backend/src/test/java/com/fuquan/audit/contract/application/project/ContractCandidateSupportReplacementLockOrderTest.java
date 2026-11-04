package com.fuquan.audit.contract.application.project;

import static com.fuquan.audit.contract.application.project.ContractCandidateSupportTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;

class ContractCandidateSupportReplacementLockOrderTest {
    @Test
    void locksCanonicalUuidTextUnionBeforeProjectAndCandidateThenResolvesAuthority() {
        Fixture f = new Fixture();
        doAnswer(call -> { f.calls.add("fence:" + call.getArgument(1)); return call.callRealMethod(); })
                .when(f.files).withFileAuthorityFence(any(), any(), any());
        f.replace("ordered", 0, List.of(B, A, B));
        assertThat(f.calls).containsSubsequence("discover", "fence:" + A, "fence:" + PRIMARY, "fence:" + B,
                "file:" + A, "file:" + PRIMARY,
                "file:" + B, "project", "candidate", "primary-authority", "support-authority", "claim");
        assertThat(f.calls.stream().filter(call -> call.startsWith("fence:"))).containsExactly(
                "fence:" + A, "fence:" + PRIMARY, "fence:" + B);
        assertThat(f.calls.stream().filter(call -> call.startsWith("file:"))).containsExactly(
                "file:" + A, "file:" + PRIMARY, "file:" + B);
        assertThat(f.calls.indexOf("project")).isGreaterThan(f.calls.indexOf("file:" + B));
    }

    @Test
    void unionDeduplicatesThePrimaryEvenWhenInvalidlySelectedAsSupport() {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> f.replace("self", 0, List.of(PRIMARY, A, PRIMARY)))
                .isInstanceOf(BusinessException.class);
        assertThat(f.calls.stream().filter(call -> call.startsWith("file:")))
                .containsExactly("file:" + A, "file:" + PRIMARY);
        verify(f.idempotency, never()).claim(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "primary", "version", "promoted"})
    void lockedCandidateRecheckRejectsBeforeAnyAuthorityResolutionOrClaim(String mutation) {
        Fixture f = new Fixture();
        ContractCandidate before = f.current();
        ContractCandidate changed = switch (mutation) {
            case "missing" -> null;
            case "primary" -> ContractCandidate.draftWithAuthority(CANDIDATE, PROJECT, UUID.randomUUID(),
                    before.sourceParseResultId(), before.sourceRoleDecisionId(), before.sourceIdentityDecisionId(),
                    ContractCandidateAuthoritySource.HUMAN_CONFIRMATION, before.sourceFactFingerprint(),
                    null, null, null, null, null, null, null, null, null, null, null, List.of(), NOW);
            case "version" -> before.confirmed(NOW);
            case "promoted" -> before.promote(UUID.randomUUID(), ACTOR.userId(), NOW);
            default -> throw new AssertionError(mutation);
        };
        doReturn(Optional.ofNullable(changed)).when(f.candidates).findByProjectIdAndIdForUpdate(PROJECT, CANDIDATE);
        assertThatThrownBy(() -> f.replace("recheck", 0, List.of(A))).isInstanceOf(BusinessException.class);
        verify(f.primaryAuthority, never()).snapshot(any());
        verify(f.supportAuthority, never()).resolveRequested(any(), any());
        verify(f.idempotency, never()).claim(any());
        assertThat(f.rows()).isEmpty();
        assertThat(f.current()).isEqualTo(before);
    }

    @Test
    void missingRequestedFileStopsBeforeProjectLockAndAnyWrite() {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> f.replace("missing-file", 0, List.of(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class).hasMessage("candidate authority file not found");
        verify(f.projects, never()).findByIdForUpdate(any());
        verify(f.candidates, never()).findByProjectIdAndIdForUpdate(any(), any());
        verify(f.idempotency, never()).claim(any());
    }

    @Test
    void replacementWaitsForSupportFileWithoutTakingProjectFromConcurrentAuthorityConfirmation() throws Exception {
        Fixture f = new Fixture();
        var fileHeld = new CountDownLatch(1);
        var replacementWaiting = new CountDownLatch(1);
        var releaseConfirmation = new CountDownLatch(1);
        var replacementThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        doAnswer(call -> {
            if (Thread.currentThread() == replacementThread.get()) replacementWaiting.countDown();
            return call.callRealMethod();
        }).when(f.files).withFileAuthorityFence(org.mockito.ArgumentMatchers.eq(PROJECT),
                org.mockito.ArgumentMatchers.eq(A), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var confirmation = pool.submit(() -> new TransactionTemplate(new BoundaryTransactions()).execute(status -> {
                f.files.withFileAuthorityFence(PROJECT, A, () -> {
                    fileHeld.countDown();
                    await(replacementWaiting);
                    // While replacement is blocked at File, confirmation can request Project.
                    assertThat(f.calls).doesNotContain("project");
                    f.projects.findByIdForUpdate(PROJECT).orElseThrow();
                    await(releaseConfirmation);
                    return null;
                });
                return null;
            }));
            assertThat(fileHeld.await(5, TimeUnit.SECONDS)).isTrue();
            var replacement = pool.submit(() -> {
                replacementThread.set(Thread.currentThread());
                return f.replace("file-first", 0, List.of(A));
            });
            assertThat(replacementWaiting.await(5, TimeUnit.SECONDS)).isTrue();
            releaseConfirmation.countDown();
            confirmation.get(5, TimeUnit.SECONDS);
            assertThat(replacement.get(5, TimeUnit.SECONDS).candidateVersion()).isEqualTo(1);
            assertThat(f.rows()).hasSize(1);
        } finally { releaseConfirmation.countDown(); pool.shutdownNow(); }
    }

    @Test
    void oppositeRequestOrdersAcquireIdenticalSortedFiles() {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        first.replace("first", 0, List.of(A, B));
        second.replace("second", 0, List.of(B, A));
        assertThat(first.calls.stream().filter(call -> call.startsWith("file:"))).containsExactly(
                "file:" + A, "file:" + PRIMARY, "file:" + B);
        assertThat(second.calls.stream().filter(call -> call.startsWith("file:"))).containsExactly(
                "file:" + A, "file:" + PRIMARY, "file:" + B);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("lock orchestration timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
