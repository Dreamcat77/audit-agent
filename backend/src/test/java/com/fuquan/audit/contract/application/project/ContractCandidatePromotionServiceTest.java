package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractCandidatePromotionServiceTest {

    @Test
    void promotionLocksCanonicalCompleteFileUnionBeforeProjectCandidateAndValidation() {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(ContractCandidateSupportTestSupport.B, ContractCandidateSupportTestSupport.A));
        var confirmed = f.confirm();
        var calls = new java.util.ArrayList<String>();
        Mockito.doAnswer(call -> {
            calls.add("fence:" + call.getArgument(1)); return call.callRealMethod();
        }).when(f.files).withFileAuthorityFence(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doAnswer(call -> {
            calls.add("file:" + call.getArgument(1)); return call.callRealMethod();
        }).when(f.files).findByProjectIdAndIdForUpdate(Mockito.any(), Mockito.any());
        Mockito.doAnswer(call -> { calls.add("project"); return call.callRealMethod(); })
                .when(f.projects).findByIdForUpdate(Mockito.any());
        Mockito.doAnswer(call -> { calls.add("candidate"); return call.callRealMethod(); })
                .when(f.candidates).findByProjectIdAndIdForUpdate(Mockito.any(), Mockito.any());
        // Repository order is deliberately opposite to the required canonical file order.
        Mockito.doAnswer(call -> {
            var rows = new java.util.ArrayList<>((List<com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority>)
                    call.callRealMethod()); java.util.Collections.reverse(rows); return rows;
        }).when(f.supports).findCurrentByProjectIdAndCandidateId(Mockito.any(), Mockito.any());

        f.promote(confirmed);

        assertThat(calls.stream().filter(value -> value.startsWith("fence:"))).containsExactly(
                "fence:" + ContractCandidateSupportTestSupport.A, "fence:" + ContractCandidateSupportTestSupport.PRIMARY,
                "fence:" + ContractCandidateSupportTestSupport.B);
        assertThat(calls.stream().filter(value -> !value.startsWith("fence:"))).containsExactly(
                "file:" + ContractCandidateSupportTestSupport.A, "file:" + ContractCandidateSupportTestSupport.PRIMARY,
                "file:" + ContractCandidateSupportTestSupport.B, "project", "candidate");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"primary", "version", "promoted", "support-version",
            "support-fingerprint", "cohort-row", "cohort-file", "cohort-tuple", "cohort-missing"})
    void changedDiscoverySnapshotFailsBeforeValidationClaimOrContractWrites(String drift) {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(ContractCandidateSupportTestSupport.A));
        var confirmed = f.confirm();
        var command = f.command(confirmed, "discovery-drift");
        Mockito.clearInvocations(f.sourceAuthority, f.idempotency, f.creation);
        Mockito.doAnswer(call -> {
            var candidate = Mockito.spy(((Optional<com.fuquan.audit.contract.domain.project.ContractCandidate>)
                    call.callRealMethod()).orElseThrow());
            switch (drift) {
                case "primary" -> Mockito.doReturn(ContractCandidateSupportTestSupport.B).when(candidate).sourceContractFileId();
                case "version" -> Mockito.doReturn(3L).when(candidate).versionNo();
                case "promoted" -> Mockito.doReturn(CandidatePromotionState.PROMOTED).when(candidate).promotionState();
                case "support-version" -> Mockito.doReturn(2L).when(candidate).supportAuthorityVersionNo();
                case "support-fingerprint" -> Mockito.doReturn("b".repeat(64)).when(candidate).supportAuthorityFingerprint();
                case "cohort-missing" -> f.supports.deleteCurrentByProjectIdAndCandidateId(candidate.projectId(), candidate.id());
                default -> {
                    var row = f.supports.findCurrentByProjectIdAndCandidateId(candidate.projectId(), candidate.id()).get(0);
                    f.supports.replaceCurrentCohort(candidate.projectId(), candidate.id(), List.of(
                            new com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority(UUID.randomUUID(),
                                    row.projectId(), row.candidateId(), drift.equals("cohort-file")
                                            ? ContractCandidateSupportTestSupport.B : row.sourceFileId(), row.fileSha256(),
                                    row.parseResultId(), row.parseVersion(), row.projectVersionNo(), row.effectiveRole(),
                                    drift.equals("cohort-tuple") ? UUID.randomUUID() : row.roleDecisionId(),
                                    row.identityResolution(), row.identityDecisionId(), row.sourceFactFingerprint(),
                                    row.authorityVersion(), row.createdBy(), row.createdAt())));
                }
            }
            return Optional.of(candidate);
        }).when(f.candidates).findByProjectIdAndIdForUpdate(Mockito.any(), Mockito.any());

        assertThatThrownBy(() -> f.service.promote(command)).isInstanceOf(BusinessException.class);
        Mockito.verifyNoInteractions(f.sourceAuthority, f.creation);
        Mockito.verify(f.idempotency, Mockito.never()).claim(Mockito.any());
        assertThat(f.associations.findByCandidateId(ContractCandidateSupportTestSupport.CANDIDATE)).isEmpty();
    }

    @Test
    void successfulReplayBypassesCurrentSupportDiscoveryLocksAndValidationButReopensDurableResult() {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(ContractCandidateSupportTestSupport.A));
        var command = f.command(f.confirm(), "proven-replay");
        var first = f.service.promote(command);
        f.drift("missing");
        Mockito.clearInvocations(f.files, f.projects, f.supports, f.sourceAuthority, f.candidates, f.creation, f.contracts);

        var replay = f.service.promote(command);

        assertThat(replay).isEqualTo(first);
        Mockito.verifyNoInteractions(f.files, f.projects, f.supports, f.sourceAuthority, f.creation);
        Mockito.verify(f.candidates, Mockito.never()).findByProjectIdAndIdForUpdate(Mockito.any(), Mockito.any());
        Mockito.verify(f.contracts).findById(first.contract().id());
        Mockito.doReturn(Optional.empty()).when(f.contracts).findById(first.contract().id());
        assertThatThrownBy(() -> f.service.promote(command)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("durable state is missing");
    }

    @Test
    void successfulSameKeyWinnerDuringFileWaitReplaysBeforeFreshVersionOrAuthorityChecks() {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(ContractCandidateSupportTestSupport.A));
        var command = f.command(f.confirm(), "winner-during-locks");
        var first = f.service.promote(command);
        var winner = f.idempotency.findByScopeAndKey(ContractCandidatePromotionTransactionCoordinator.SCOPE, command.idempotencyKey());
        Mockito.doReturn(Optional.empty(), winner).when(f.idempotency).findByScopeAndKey(
                ContractCandidatePromotionTransactionCoordinator.SCOPE, command.idempotencyKey());
        Mockito.clearInvocations(f.sourceAuthority, f.creation);

        assertThat(f.service.promote(command)).isEqualTo(first);
        Mockito.verifyNoInteractions(f.sourceAuthority, f.creation);
    }

    @Test
    void supportReplacementWinningAfterDiscoveryRejectsPromotionWithoutRestoringTheOldCohort() throws Exception {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(ContractCandidateSupportTestSupport.A));
        var command = f.command(f.confirm(), "cohort-replacement-race");
        var discovered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var promotionThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var paused = new java.util.concurrent.atomic.AtomicBoolean();
        Mockito.doAnswer(call -> {
            if (Thread.currentThread() == promotionThread.get() && paused.compareAndSet(false, true)) {
                discovered.countDown(); await(release);
            }
            return call.callRealMethod();
        }).when(f.files).withFileAuthorityFence(Mockito.any(), Mockito.any(), Mockito.any());
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var promotion = pool.submit(() -> {
                promotionThread.set(Thread.currentThread());
                try { return (Object) f.service.promote(command); }
                catch (BusinessException failure) { return failure; }
            });
            assertThat(discovered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            f.replace(List.of());
            var afterReplacement = f.current();
            release.countDown();
            assertThat(promotion.get(5, java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(BusinessException.class);
            assertThat(f.current()).isEqualTo(afterReplacement);
            assertThat(f.supports.findCurrentByProjectIdAndCandidateId(afterReplacement.projectId(), afterReplacement.id())).isEmpty();
            assertThat(f.associations.findByCandidateId(afterReplacement.id())).isEmpty();
            Mockito.verify(f.creation, Mockito.never()).insertCompleteSnapshot(Mockito.any());
        } finally {
            release.countDown(); pool.shutdownNow();
            assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void localCommandFencesProtectSupportWhenNoTransactionSynchronizationExists() throws Exception {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(ContractCandidateSupportTestSupport.A));
        var command = f.command(f.confirm(), "local-without-transaction-manager");
        var service = new ContractCandidatePromotionService(f.promotion,
                () -> Optional.of(ContractCandidateSupportTestSupport.ACTOR), f.flow);
        var validated = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var attempted = new java.util.concurrent.CountDownLatch(1);
        var committed = new java.util.concurrent.CountDownLatch(1);
        var writerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        Mockito.doAnswer(call -> {
            var result = call.callRealMethod();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            validated.countDown(); await(release); return result;
        }).when(f.supportAuthority).validateCurrent(Mockito.any());
        Mockito.doAnswer(call -> {
            if (Thread.currentThread() == writerThread.get()) attempted.countDown();
            return call.callRealMethod();
        }).when(f.files).findByProjectIdAndIdForUpdate(ContractCandidateSupportTestSupport.PROJECT,
                ContractCandidateSupportTestSupport.A);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var promotion = pool.submit(() -> service.promote(command));
            assertThat(validated.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var writer = pool.submit(() -> {
                writerThread.set(Thread.currentThread()); f.changeRole(); committed.countDown();
            });
            assertThat(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(committed.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            assertThat(promotion.get(5, java.util.concurrent.TimeUnit.SECONDS).candidate().promotionState())
                    .isEqualTo(CandidatePromotionState.PROMOTED);
            writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown(); pool.shutdownNow();
            assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"role", "identity", "parse", "missing", "fingerprint", "cohort"})
    void invalidAssignedSupportRejectsPromotionBeforeContractAssociationOrCandidateWrites(String drift) {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(ContractCandidateSupportTestSupport.A));
        var confirmation = f.confirm();
        f.drift(drift);
        var before = f.current();
        long auditCount = f.audits.size();

        assertThatThrownBy(() -> f.promote(confirmation)).isInstanceOf(BusinessException.class);

        assertThat(f.current()).isEqualTo(before);
        assertThat(f.associations.findByCandidateId(before.id())).isEmpty();
        assertThat(f.audits).hasSize((int) auditCount);
        Mockito.verify(f.creation, Mockito.never()).insertCompleteSnapshot(Mockito.any());
    }

    @Test
    void currentExplicitSupportUsesUnchangedConfirmedContractMappingAndCannotBeReplacedAfterPromotion() {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(ContractCandidateSupportTestSupport.A));
        var confirmation = f.confirm();
        var rows = f.supports.findCurrentByProjectIdAndCandidateId(
                ContractCandidateSupportTestSupport.PROJECT, ContractCandidateSupportTestSupport.CANDIDATE);
        var result = f.promote(confirmation);

        assertThat(result.contract().contractNo()).isEqualTo("SUPPORT-PROMOTION");
        assertThat(result.contract().awardNotice().noticeNo()).isEqualTo("AWARD-1");
        assertThat(result.contract().awardNotice().winningAmount().amount()).isEqualByComparingTo("100.00");
        assertThat(result.contract().lineItems()).singleElement().satisfies(line ->
                assertThat(line.itemKey()).isEqualTo("ITEM-1"));
        assertThat(result.candidate().versionNo()).isEqualTo(3);
        assertThat(result.candidate().supportAuthorityVersionNo()).isEqualTo(1);
        assertThatThrownBy(() -> f.replace(List.of())).isInstanceOf(BusinessException.class);
        assertThat(f.contracts.findById(result.contract().id())).contains(result.contract());
        assertThat(f.current()).isEqualTo(result.candidate());
        assertThat(f.supports.findCurrentByProjectIdAndCandidateId(
                ContractCandidateSupportTestSupport.PROJECT, ContractCandidateSupportTestSupport.CANDIDATE))
                .isEqualTo(rows);
    }

    @Test
    void replacementAfterConfirmationRejectsOldResultEvenWithCurrentExpectedVersion() {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(ContractCandidateSupportTestSupport.A));
        var old = f.confirm();
        f.replace(List.of());

        assertThatThrownBy(() -> f.promote(old)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("confirmation is stale");
        assertThat(f.current().promotionState()).isEqualTo(CandidatePromotionState.DRAFT);
        assertThat(f.associations.findByCandidateId(f.current().id())).isEmpty();
        Mockito.verify(f.creation, Mockito.never()).insertCompleteSnapshot(Mockito.any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"role", "identity"})
    void promotionMustHoldSupportAuthorityThroughCommitEvenWithSharedValidation(String decision) throws Exception {
        var f = new CandidateSupportPromotionTestSupport();
        f.replace(List.of(ContractCandidateSupportTestSupport.A));
        var confirmed = f.confirm();
        var validated = new java.util.concurrent.CountDownLatch(1);
        var releasePromotion = new java.util.concurrent.CountDownLatch(1);
        var writerAttempted = new java.util.concurrent.CountDownLatch(1);
        var writerCommitted = new java.util.concurrent.CountDownLatch(1);
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var writerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        // Pause the real shared support validation, retaining every production caller and writer.
        Mockito.doAnswer(call -> {
            var result = call.callRealMethod();
            events.add("support-validated");
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { events.add("promotion-commit"); }
                    });
            validated.countDown();
            await(releasePromotion);
            return result;
        }).when(f.supportAuthority).validateCurrent(Mockito.any());
        Mockito.doAnswer(call -> {
            if (Thread.currentThread() == writerThread.get()) writerAttempted.countDown();
            return call.callRealMethod();
        }).when(f.files).findByProjectIdAndIdForUpdate(
                ContractCandidateSupportTestSupport.PROJECT, ContractCandidateSupportTestSupport.A);
        Mockito.doAnswer(call -> {
            events.add("contract-write");
            return call.callRealMethod();
        }).when(f.creation).insertCompleteSnapshot(Mockito.any());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var promoted = pool.submit(() -> f.promote(confirmed));
            assertThat(validated.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var writer = pool.submit(() -> {
                writerThread.set(Thread.currentThread());
                if (decision.equals("role")) f.changeRole(); else f.changeIdentity();
                events.add("writer-commit");
                writerCommitted.countDown();
            });
            assertThat(writerAttempted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            boolean writerWonBeforePromotionWasReleased = writerCommitted.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
            releasePromotion.countDown();
            var result = promoted.get(5, java.util.concurrent.TimeUnit.SECONDS);
            writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.candidate().promotionState()).isEqualTo(CandidatePromotionState.PROMOTED);
            assertThat(f.contracts.findById(result.contract().id())).contains(result.contract());
            assertThat(f.associations.findByCandidateId(result.candidate().id())).contains(result.association());
            assertThatThrownBy(() -> f.supportAuthority.validateCurrent(f.current())).isInstanceOf(BusinessException.class);
            assertThat(writerWonBeforePromotionWasReleased)
                    .as("%s writer committed after support validation but before persisted Contract/association/PROMOTED; events=%s",
                            decision, events).isFalse();
            assertThat(events).containsSubsequence("support-validated", "contract-write", "promotion-commit", "writer-commit");
        } finally {
            releasePromotion.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("promotion preflight timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void confirmedPayloadRejectsDuplicateLineBusinessKeysBeforeAdapterSpecificPersistence() {
        ConfirmedContractPayload original = PromotionTestSupport.payload("DUPLICATE-LINE");
        assertThatThrownBy(() -> new ConfirmedContractPayload(original.contractNo(), original.contractName(),
                original.contractType(), original.partyA(), original.partyB(), original.partyBCreditCode(),
                original.contractAmount(), original.amountInWords(),
                List.of(original.lineItems().get(0), original.lineItems().get(0)), original.awardNotice()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
    }

    @Test
    void successUsesOnlyServerOwnedFormalIdentitiesAndCommitsDurableLifecycleFacts() {
        PromotionTestSupport support = new PromotionTestSupport();

        ContractCandidatePromotionResult result = support.service.promote(support.command("promotion-key"));
        Contract contract = result.contract();

        assertThat(contract.id()).isNotNull();
        assertThat(contract.status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(contract.versionNo()).isZero();
        assertThat(contract.createdAt()).isEqualTo(PromotionTestSupport.NOW);
        assertThat(contract.lineItems()).allSatisfy(line -> {
            assertThat(line.id()).isNotNull();
            assertThat(line.contractId()).isEqualTo(contract.id());
        });
        assertThat(contract.awardNotice().id()).isNotNull();
        assertThat(contract.awardNotice().contractId()).isEqualTo(contract.id());
        assertThat(result.association().projectId()).isEqualTo(support.project.id());
        assertThat(result.association().contractId()).isEqualTo(contract.id());
        assertThat(result.association().candidateId()).isEqualTo(support.candidate.get().id());
        assertThat(result.candidate().promotionState()).isEqualTo(CandidatePromotionState.PROMOTED);
        assertThat(result.candidate().promotedContractId()).isEqualTo(contract.id());
        assertThat(result.candidate().versionNo()).isEqualTo(2);
        assertThat(result.candidate().promotedConfirmationId()).isEqualTo(support.confirmationId);
        assertThat(support.audits).singleElement().satisfies(audit -> {
            assertThat(audit.eventType()).isEqualTo("PROJECT_CONTRACT_CANDIDATE_PROMOTED");
            assertThat(audit.resourceType()).isEqualTo("CONTRACT_CANDIDATE");
            assertThat(audit.resourceId()).isEqualTo(result.candidate().id().toString());
            assertThat(audit.taskId()).isNull();
            assertThat(audit.detail()).doesNotContain("Confirmed Contract", "promotion-key", "Supplier");
        });
    }

    @Test
    void sameKeyReplayReturnsTheSameContractAndChangedPayloadConflicts() {
        PromotionTestSupport support = new PromotionTestSupport();
        ContractCandidatePromotionResult first = support.service.promote(support.command("same-key"));
        ContractCandidatePromotionResult replay = support.service.promote(support.command("same-key"));

        assertThat(replay.contract().id()).isEqualTo(first.contract().id());
        assertThat(support.audits).hasSize(1);
        assertThatThrownBy(() -> support.service.promote(support.command("same-key", 1,
                PromotionTestSupport.payload("CHANGED"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("fingerprint");
        assertThat(support.formal.get().id()).isEqualTo(first.contract().id());
    }

    @Test
    void staleVersionAndProjectCandidateMismatchFailBeforeCreatingAContract() {
        PromotionTestSupport stale = new PromotionTestSupport();
        assertThatThrownBy(() -> stale.service.promote(stale.command("stale-key", 2,
                PromotionTestSupport.payload("C-1"))))
                .isInstanceOf(BusinessException.class);
        assertThat(stale.formal).hasValue(null);

        PromotionTestSupport mismatch = new PromotionTestSupport();
        PromoteContractCandidateCommand command = new PromoteContractCandidateCommand(UUID.randomUUID(),
                mismatch.candidate.get().id(), 1, mismatch.confirmationId, "mismatch-key",
                PromotionTestSupport.META);
        assertThatThrownBy(() -> mismatch.service.promote(command))
                .isInstanceOf(BusinessException.class);
        assertThat(mismatch.formal).hasValue(null);
    }

    @Test
    void sourceAuthorityIsRequiredForNewPromotionButNotAlreadySuccessfulReplay() {
        PromotionTestSupport invalid = new PromotionTestSupport();
        invalid.changeSourceAuthority(DocumentRole.OTHER,
                EffectiveDocumentRoleSource.HUMAN_CONFIRMATION, UUID.randomUUID());
        assertThatThrownBy(() -> invalid.service.promote(invalid.command("invalid-authority")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("source role");
        assertThat(invalid.formal).hasValue(null);

        PromotionTestSupport changed = new PromotionTestSupport();
        changed.service.promote(changed.command("authority-key"));
        changed.changeSourceAuthority(DocumentRole.OTHER,
                EffectiveDocumentRoleSource.HUMAN_CONFIRMATION, UUID.randomUUID());
        assertThat(changed.service.promote(changed.command("authority-key")).contract().id())
                .isEqualTo(changed.formal.get().id());
    }

    @Test
    void samePromotionKeyReplaysAfterCurrentProjectIdentityDecisionChanges() {
        PromotionTestSupport support = new PromotionTestSupport();
        PromoteContractCandidateCommand command = support.command("identity-authority-key");
        support.service.promote(command);
        support.changeCurrentIdentityDecision(UUID.randomUUID());
        assertThat(support.service.promote(command).contract().id())
                .isEqualTo(support.formal.get().id());
    }


    @Test
    void successfulPromotionReplaysAfterProjectMetadataVersionDriftWithoutRecheckingReadiness() {
        PromotionTestSupport support = new PromotionTestSupport();
        PromoteContractCandidateCommand command = support.command("project-version-key");
        ContractCandidatePromotionResult first = support.service.promote(command);
        CandidateSourceAuthority old = support.sourceAuthority.get();
        support.sourceAuthority.set(new CandidateSourceAuthority(old.sourceFileId(), old.fileSha256(),
                old.parseResultId(), old.projectVersionNo() + 1, old.effectiveRole(),
                old.effectiveRoleSource(), old.currentRoleDecisionId(), old.effectiveIdentityStatus(),
                old.currentIdentityDecisionId(), old.sourceFactFingerprint(), false,
                "candidate source project identity is stale"));
        assertThat(support.service.promote(command).contract().id()).isEqualTo(first.contract().id());
        assertThat(support.audits).hasSize(1);
    }

    @Test
    void authenticatedServiceDelegatesWithoutPreAcquiringThePrimaryFile() {
        PromotionTestSupport support = new PromotionTestSupport();
        ContractCandidatePromotionTransactionCoordinator coordinator =
                Mockito.mock(ContractCandidatePromotionTransactionCoordinator.class);
        ContractCandidatePromotionService service = new ContractCandidatePromotionService(coordinator,
                () -> Optional.of(support.actor),
                Mockito.mock(FlowDebugLogger.class));
        var command = support.command("legacy-boundary");
        var prepared = Mockito.mock(ContractCandidatePromotionTransactionCoordinator.FreshPromotion.class);
        Mockito.when(coordinator.prepare(command, support.actor)).thenReturn(prepared);

        service.promote(command);

        Mockito.verify(coordinator).prepare(command, support.actor);
        Mockito.verify(coordinator).promote(command, support.actor, prepared);
    }

    @Test
    void sourceAuthorityDriftInsideFileFenceIsRejectedByPromotionTransaction() {
        var f = new CandidateSupportPromotionTestSupport();
        var confirmed = f.confirm();
        Mockito.doAnswer(call -> {
            var old = f.roles.findLatestByFileId(ContractCandidateSupportTestSupport.PRIMARY).orElseThrow();
            f.roles.insertIfAbsent(new com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision(
                    UUID.randomUUID(), old.projectId(), old.fileId(), old.fileSha256(), old.parseResultId(),
                    old.parseVersion(), old.assessmentId(), DocumentRole.OTHER, old.decisionStatus(),
                    old.decidedBy(), old.decidedAt().plusSeconds(1), old.decisionId(), "synthetic drift", 2));
            return call.callRealMethod();
        }).when(f.files).withFileAuthorityFence(Mockito.any(), Mockito.any(), Mockito.any());
        assertThatThrownBy(() -> f.promote(confirmed))
                .isInstanceOf(BusinessException.class).hasMessageContaining("source role");
        assertThat(f.associations.findByCandidateId(confirmed.candidateId())).isEmpty();
        Mockito.verify(f.creation, Mockito.never()).insertCompleteSnapshot(Mockito.any());
    }
}
