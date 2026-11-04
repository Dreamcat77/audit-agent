package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.fuquan.audit.contract.application.project.ContractCandidateSupportTestSupport.*;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.infra.memory.project.InMemoryContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.integration.dm8.ContractCandidateConfirmationPersistenceRow;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ContractCandidateConfirmationServiceTest {
    @Test
    void discoversSupportThenLocksCanonicalFileUnionBeforeProjectAndCandidate() {
        var f = new SupportFixture(List.of(B, A));
        f.support.calls.clear();
        doAnswer(call -> {
            f.support.calls.add("support-read");
            var rows = new ArrayList<>((List<ContractCandidateSupportAuthority>) call.callRealMethod());
            java.util.Collections.reverse(rows);
            return rows;
        }).when(f.support.supports).findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
        doAnswer(call -> {
            f.support.calls.add("validate-support");
            return call.callRealMethod();
        }).when(f.support.supportAuthority).validateCurrent(any());

        f.confirm("lock-order", 1);

        assertThat(f.support.calls).containsSubsequence("discover", "support-read", "file:" + A,
                "file:" + PRIMARY, "file:" + B, "project", "candidate", "support-read",
                "primary-authority", "validate-support", "claim");
        assertThat(f.support.calls.stream().filter(call -> call.startsWith("file:")))
                .containsExactly("file:" + A, "file:" + PRIMARY, "file:" + B);
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "identity"})
    void supportAuthorityWriterCannotCommitAfterValidationUntilConfirmationCommits(String decision) throws Exception {
        var f = new SupportFixture();
        var validated = new CountDownLatch(1);
        var releaseConfirmation = new CountDownLatch(1);
        var writerAttemptingFile = new CountDownLatch(1);
        var writerCommitted = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { events.add("confirmation-commit"); }
            });
            validated.countDown();
            await(releaseConfirmation);
            return result;
        }).when(f.support.supportAuthority).validateCurrent(any());
        var writerThread = new AtomicReference<Thread>();
        doAnswer(call -> {
            if (Thread.currentThread() == writerThread.get()) writerAttemptingFile.countDown();
            return call.callRealMethod();
        }).when(f.support.files).findByProjectIdAndIdForUpdate(PROJECT, A);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var confirmation = pool.submit(() -> f.confirm("validation-window", 1));
            assertThat(validated.await(5, TimeUnit.SECONDS)).isTrue();
            var writer = pool.submit(() -> {
                writerThread.set(Thread.currentThread());
                new TransactionTemplate(new BoundaryTransactions()).executeWithoutResult(ignored -> {
                    // Both decision writers share this File fence; identity also locks Project.
                    f.support.files.findByProjectIdAndIdForUpdate(PROJECT, A).orElseThrow();
                    f.support.projects.findByIdForUpdate(PROJECT).orElseThrow();
                    events.add("writer-acquired");
                    var source = f.support.sources.get(1);
                    if (decision.equals("role")) source.roleId = UUID.randomUUID();
                    else source.identityId = UUID.randomUUID();
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() {
                            events.add("writer-commit");
                            writerCommitted.countDown();
                        }
                    });
                });
            });
            assertThat(writerAttemptingFile.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(writerCommitted.await(200, TimeUnit.MILLISECONDS))
                    .as("support writer must wait while confirmation is paused after validation").isFalse();
            releaseConfirmation.countDown();
            assertThat(confirmation.get(5, TimeUnit.SECONDS).confirmation().resultCandidateVersionNo()).isEqualTo(2);
            writer.get(5, TimeUnit.SECONDS);
            assertThat(events).containsExactly("confirmation-commit", "writer-acquired", "writer-commit");
        } finally {
            releaseConfirmation.countDown();
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "primary", "version", "promoted", "support-version", "support-fingerprint",
            "support-id", "support-file", "support-tuple", "support-missing"})
    void changeBetweenDiscoveryAndLocksRejectsBeforeAuthorityOrWrites(String change) {
        var f = new SupportFixture();
        clearInvocations(f.support.primaryAuthority, f.support.supportAuthority, f.support.idempotency);
        doAnswer(call -> {
            ContractCandidate current = ((Optional<ContractCandidate>) call.callRealMethod()).orElseThrow();
            if (change.equals("missing")) return Optional.empty();
            ContractCandidate changed = spy(current);
            switch (change) {
                case "primary" -> doReturn(B).when(changed).sourceContractFileId();
                case "version" -> doReturn(2L).when(changed).versionNo();
                case "promoted" -> doReturn(CandidatePromotionState.PROMOTED).when(changed).promotionState();
                case "support-version" -> doReturn(2L).when(changed).supportAuthorityVersionNo();
                case "support-fingerprint" -> doReturn("b".repeat(64)).when(changed).supportAuthorityFingerprint();
                case "support-missing" -> f.support.supports.deleteCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
                default -> {
                    var row = f.support.rows().get(0);
                    var replaced = new ContractCandidateSupportAuthority(
                            change.equals("support-id") || change.equals("support-file")
                                    ? UUID.randomUUID() : row.supportAuthorityId(),
                            row.projectId(), row.candidateId(), change.equals("support-file") ? B : row.sourceFileId(),
                            row.fileSha256(), row.parseResultId(), row.parseVersion(), row.projectVersionNo(),
                            row.effectiveRole(), change.equals("support-tuple") ? UUID.randomUUID() : row.roleDecisionId(),
                            row.identityResolution(), row.identityDecisionId(), row.sourceFactFingerprint(),
                            row.authorityVersion(), row.createdBy(), row.createdAt());
                    f.support.supports.replaceCurrentCohort(PROJECT, CANDIDATE, List.of(replaced));
                }
            }
            return Optional.of(changed);
        }).when(f.support.candidates).findByProjectIdAndIdForUpdate(PROJECT, CANDIDATE);

        assertThatThrownBy(() -> f.confirm("discovery-race", 1)).isInstanceOf(BusinessException.class);

        verify(f.support.primaryAuthority, never()).snapshot(any());
        verify(f.support.supportAuthority, never()).validateCurrent(any());
        verify(f.support.idempotency, never()).claim(any());
        assertThat(f.confirmations.findLatestByProjectAndCandidate(PROJECT, CANDIDATE)).isEmpty();
        assertThat(f.audits).isEmpty();
    }

    @Test
    void completedReplayDoesNotDiscoverOrLockCurrentCandidateFilesProjectOrSupport() {
        var f = new SupportFixture();
        var first = f.confirm("replay-without-locks", 1);
        clearInvocations(f.support.candidates, f.support.files, f.support.projects, f.support.supports,
                f.support.primaryAuthority, f.support.supportAuthority);

        var replay = f.confirm("replay-without-locks", 1);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.confirmation()).isEqualTo(first.confirmation());
        verifyNoInteractions(f.support.candidates, f.support.files, f.support.projects, f.support.supports,
                f.support.primaryAuthority, f.support.supportAuthority);
    }

    @Test
    void sameKeyWinnerBecomingVisibleWhileWaitingForLocksReplaysBeforeVersionRecheck() {
        var f = new SupportFixture();
        var first = f.confirm("winner-during-locks", 1);
        var winner = f.support.idempotency.findByScopeAndKey(
                ContractCandidateConfirmationTransactionCoordinator.SCOPE, "winner-during-locks");
        doReturn(Optional.empty(), winner).when(f.support.idempotency).findByScopeAndKey(
                ContractCandidateConfirmationTransactionCoordinator.SCOPE, "winner-during-locks");
        clearInvocations(f.support.primaryAuthority, f.support.supportAuthority, f.support.idempotency);

        var replay = f.confirm("winner-during-locks", 1);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.confirmation()).isEqualTo(first.confirmation());
        assertThat(f.support.current().versionNo()).isEqualTo(2);
        assertThat(f.audits).hasSize(1);
        verify(f.support.primaryAuthority, never()).snapshot(any());
        verify(f.support.supportAuthority, never()).validateCurrent(any());
        verify(f.support.idempotency, never()).claim(any());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("confirmation lock orchestration timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    @Test
    void firstConfirmationAcceptsExactSupportAndPreservesBusinessPayloadAndFingerprintContracts() throws Exception {
        var f = new SupportFixture();
        ContractCandidate before = f.support.current();
        var rows = f.support.rows();
        clearInvocations(f.support.primaryAuthority, f.support.supportAuthority, f.support.idempotency);
        var result = f.confirm("support-confirm", 1);
        var confirmation = result.confirmation();

        var ordered = inOrder(f.support.primaryAuthority, f.support.supportAuthority, f.support.idempotency);
        ordered.verify(f.support.primaryAuthority).snapshot(before);
        ordered.verify(f.support.supportAuthority).validateCurrent(before);
        ordered.verify(f.support.idempotency).claim(any());

        assertThat(result.replayed()).isFalse();
        assertThat(confirmation.baseCandidateVersionNo()).isEqualTo(1);
        assertThat(confirmation.resultCandidateVersionNo()).isEqualTo(2);
        assertThat(f.support.current().supportAuthorityVersionNo()).isEqualTo(before.supportAuthorityVersionNo());
        assertThat(f.support.current().supportAuthorityFingerprint()).isEqualTo(before.supportAuthorityFingerprint());
        assertThat(f.support.rows()).containsExactlyElementsOf(rows);
        assertThat(confirmation.sourceFileId()).isEqualTo(PRIMARY);
        assertThat(confirmation.sourceParseResultId()).isEqualTo(before.sourceParseResultId());
        assertThat(confirmation.sourceRoleDecisionId()).isEqualTo(before.sourceRoleDecisionId());
        assertThat(confirmation.sourceIdentityDecisionId()).isEqualTo(before.sourceIdentityDecisionId());
        assertThat(confirmation.payloadSchemaVersion()).isEqualTo(2);
        assertThat(confirmation.canonicalPayloadJson()).isEqualTo("{\"contractNo\":\"HUMAN\","
                + "\"contractName\":\"Human Contract\",\"contractType\":\"GOODS\",\"partyA\":\"Buyer\","
                + "\"partyB\":\"Supplier\",\"partyBCreditCode\":null,\"contractAmount\":\"100\","
                + "\"amountInWords\":null,\"lineItems\":[],\"awardNotice\":null}");
        String expectedPayloadFingerprint = hashParts("contract-candidate-confirmation-payload-v2",
                PROJECT.toString(), CANDIDATE.toString(), PRIMARY.toString(), before.sourceParseResultId().toString(),
                before.sourceRoleDecisionId().toString(), before.sourceIdentityDecisionId().toString(),
                "1", "2", confirmation.canonicalPayloadJson());
        assertThat(confirmation.payloadFingerprint()).isEqualTo(expectedPayloadFingerprint);
        assertThat(f.support.idempotency.findByScopeAndKey(ContractCandidateConfirmationTransactionCoordinator.SCOPE,
                "support-confirm").orElseThrow().requestFingerprint()).isEqualTo(hashParts(
                        "contract-candidate-confirm-command-v1", ACTOR.userId(), PROJECT.toString(),
                        CANDIDATE.toString(), "1", PRIMARY.toString(), before.sourceParseResultId().toString(),
                        before.sourceRoleDecisionId().toString(), before.sourceIdentityDecisionId().toString(),
                        expectedPayloadFingerprint));
        assertThat(f.confirmations.findLatestByProjectAndCandidate(PROJECT, CANDIDATE)).contains(confirmation);
        assertThat(f.audits).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(SupportDrift.class)
    void firstConfirmationRejectsStaleSupportBeforeClaimOrAnyConfirmationWrite(SupportDrift drift) {
        var f = new SupportFixture();
        f.drift(drift);
        ContractCandidate before = f.support.current();
        var rows = f.support.rows();
        f.support.primaryAuthority.snapshot(before).requirePromotable();
        clearInvocations(f.support.idempotency, f.support.candidates);

        assertThatThrownBy(() -> f.confirm("stale-support", 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));

        assertThat(f.support.current()).isEqualTo(before);
        assertThat(f.support.rows()).containsExactlyElementsOf(rows);
        assertThat(f.confirmations.findLatestByProjectAndCandidate(PROJECT, CANDIDATE)).isEmpty();
        assertThat(f.support.idempotency.findByScopeAndKey(ContractCandidateConfirmationTransactionCoordinator.SCOPE,
                "stale-support")).isEmpty();
        assertThat(f.audits).isEmpty();
        verify(f.support.idempotency, never()).claim(any());
        verify(f.support.candidates, never()).confirmDraftIfVersionMatches(any(), any(), anyLong(), any());
    }

    @ParameterizedTest
    @EnumSource(SupportDrift.class)
    void completedConfirmationReplaysImmutableTupleWithoutCurrentSupportValidation(SupportDrift drift) {
        var f = new SupportFixture();
        var first = f.confirm("replay-support", 1);
        f.drift(drift);
        // Prove today's support is invalid before observing only the replay's calls.
        assertThatThrownBy(() -> f.support.supportAuthority.validateCurrent(f.support.current()))
                .isInstanceOf(BusinessException.class);
        clearInvocations(f.support.primaryAuthority, f.support.supportAuthority, f.support.idempotency);

        var replay = f.confirm("replay-support", 1);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.confirmation()).isEqualTo(first.confirmation());
        assertThat(replay.payload()).isEqualTo(first.payload());
        assertThat(f.support.current().versionNo()).isEqualTo(2);
        assertThat(f.audits).hasSize(1);
        verify(f.support.primaryAuthority, never()).snapshot(any());
        verify(f.support.supportAuthority, never()).validateCurrent(any());
        verify(f.support.idempotency, never()).claim(any());
        verify(f.support.idempotency, never()).markSucceeded(any(), any(), any(), any());
    }

    @Test
    void primaryAuthorityRejectsBeforeSupportValidation() {
        var f = new SupportFixture();
        f.support.sources.get(0).roleId = UUID.randomUUID();
        clearInvocations(f.support.supportAuthority);
        assertThatThrownBy(() -> f.confirm("primary-stale", 1)).isInstanceOf(BusinessException.class);
        verify(f.support.supportAuthority, never()).validateCurrent(any());
        assertThat(f.confirmations.findLatestByProjectAndCandidate(PROJECT, CANDIDATE)).isEmpty();
    }

    @Test
    void confirmationCommandPayloadAndDurableRowsHaveOnlyExistingFields() {
        assertThat(recordFields(ConfirmContractCandidateCommand.class)).containsExactly(
                "projectId", "candidateId", "expectedVersion", "payload", "idempotencyKey", "metadata");
        assertThat(recordFields(ConfirmedContractPayload.class)).containsExactly("contractNo", "contractName",
                "contractType", "partyA", "partyB", "partyBCreditCode", "contractAmount", "amountInWords",
                "lineItems", "awardNotice");
        assertThat(recordFields(ConfirmedContractPayload.ConfirmedAwardNotice.class)).containsExactly("noticeNo",
                "projectName", "winningBidder", "winningBidderCreditCode", "winningAmount", "winningDate", "sourceRef");
        assertThat(recordFields(ConfirmedContractPayload.ConfirmedLineItem.class)).containsExactly("itemKey",
                "itemName", "basis", "quantity", "unitPrice", "declaredAmount", "sortNo");
        for (Class<?> row : List.of(ContractCandidateConfirmation.class, ContractCandidateConfirmationPersistenceRow.class)) {
            assertThat(recordFields(row)).containsExactly("confirmationId", "projectId", "candidateId",
                    "baseCandidateVersionNo", "resultCandidateVersionNo", "sourceFileId", "sourceParseResultId",
                    "sourceRoleDecisionId", "sourceIdentityDecisionId", "payloadSchemaVersion", "payloadFingerprint",
                    "canonicalPayloadJson", "confirmedBy", "confirmedAt", "supersedesConfirmationId", "createdAt");
        }
    }

    private static List<String> recordFields(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
    }

    private static String hashParts(String... parts) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private enum SupportDrift {
        PARSE, ROLE, IDENTITY, PROJECT_VERSION, ELIGIBLE_FACTS, MISSING_COHORT, WRONG_COHORT_VERSION,
        SOURCE_FINGERPRINT, COHORT_FINGERPRINT
    }

    private static final class SupportFixture {
        final ContractCandidateSupportTestSupport.Fixture support = new ContractCandidateSupportTestSupport.Fixture();
        final InMemoryContractCandidateConfirmationRepository confirmations =
                new InMemoryContractCandidateConfirmationRepository();
        final List<OperationAuditEvent> audits = new ArrayList<>();
        final ContractCandidateConfirmationTransactionCoordinator coordinator;

        SupportFixture() {
            this(List.of(A));
        }

        SupportFixture(List<UUID> ids) {
            support.replace("attach-support", 0, ids);
            coordinator = new ContractCandidateConfirmationTransactionCoordinator(support.projects, support.candidates,
                    support.files, support.primaryAuthority, support.supports, confirmations,
                    new ContractCandidateConfirmationPayloadCanonicalizer(), support.idempotency, audits::add,
                    Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC), new FlowDebugLogger(new ObservabilityProperties()));
        }

        ContractCandidateConfirmationResult confirm(String key, long version) {
            var payload = new ConfirmedContractPayload("HUMAN", "Human Contract", "GOODS", "Buyer", "Supplier",
                    null, new BigDecimal("100.00"), null, List.of(), null);
            return new TransactionTemplate(support.transactions).execute(ignored -> coordinator.confirm(
                    new ConfirmContractCandidateCommand(PROJECT, CANDIDATE, version, payload, key, META), ACTOR));
        }

        void drift(SupportDrift drift) {
            new TransactionTemplate(support.transactions).executeWithoutResult(ignored -> applyDrift(drift));
        }

        private void applyDrift(SupportDrift drift) {
            var source = support.sources.get(1);
            switch (drift) {
                case PARSE -> source.parseId = UUID.randomUUID();
                case ROLE -> source.roleId = UUID.randomUUID();
                case IDENTITY -> source.identityId = UUID.randomUUID();
                case PROJECT_VERSION -> {
                    Project project = support.projects.findById(PROJECT).orElseThrow();
                    support.projects.compareAndSet(project, project.confirmMetadata(1, project.name(),
                            project.projectNo(), project.category(), NOW.plusSeconds(3)));
                    // Keep the primary fixture's human identity current; only support retains project version 1.
                    support.sources.get(0).projectVersion = 2;
                }
                case ELIGIBLE_FACTS -> {
                    var fact = source.facts.get(0);
                    source.facts.set(0, new ExtractionResult(fact.id(), fact.projectId(), fact.sourceFileId(),
                            fact.parseResultId(), fact.candidateId(), fact.extractionKey(), fact.extractionVersion(),
                            fact.factType(), fact.groupKey(), "101", "101", new BigDecimal("101"), fact.sourceText(),
                            fact.pageNumber(), fact.paragraphIndex(), fact.sourceLocator(), fact.createdAt()));
                }
                case MISSING_COHORT -> support.supports.deleteCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
                case WRONG_COHORT_VERSION, SOURCE_FINGERPRINT -> {
                    var row = support.rows().get(0);
                    var changed = new ContractCandidateSupportAuthority(row.supportAuthorityId(), row.projectId(),
                            row.candidateId(), row.sourceFileId(), row.fileSha256(), row.parseResultId(), row.parseVersion(),
                            row.projectVersionNo(), row.effectiveRole(), row.roleDecisionId(), row.identityResolution(),
                            row.identityDecisionId(), drift == SupportDrift.SOURCE_FINGERPRINT ? "b".repeat(64)
                                    : row.sourceFactFingerprint(),
                            drift == SupportDrift.WRONG_COHORT_VERSION ? 2 : row.authorityVersion(), row.createdBy(), row.createdAt());
                    support.supports.deleteCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
                    support.supports.insertAll(List.of(changed));
                }
                case COHORT_FINGERPRINT -> {
                    var candidate = spy(support.current());
                    doReturn("b".repeat(64)).when(candidate).supportAuthorityFingerprint();
                    doReturn(Optional.of(candidate)).when(support.candidates).findByProjectIdAndId(PROJECT, CANDIDATE);
                    doReturn(Optional.of(candidate)).when(support.candidates).findByProjectIdAndIdForUpdate(PROJECT, CANDIDATE);
                }
            }
        }
    }

    @Test
    void confirmsDurableSnapshotAndBumpsOnlyCandidateVersion() {
        var f = new Fixture();
        var result = f.confirm("K1", 0, PromotionTestSupport.payload("HUMAN-C-1"));
        assertThat(result.replayed()).isFalse();
        assertThat(result.confirmation().baseCandidateVersionNo()).isZero();
        assertThat(result.confirmation().resultCandidateVersionNo()).isEqualTo(1);
        assertThat(f.candidate.get().versionNo()).isEqualTo(1);
        assertThat(f.candidate.get().contractNo()).isEqualTo("MACHINE-C-1");
        assertThat(f.confirmations.findByProjectAndCandidateAndId(f.project.id(), f.candidate.get().id(),
                result.confirmation().confirmationId())).contains(result.confirmation());
        assertThat(f.audits).hasSize(1).first().extracting(OperationAuditEvent::eventType)
                .isEqualTo("PROJECT_CONTRACT_CANDIDATE_CONFIRMED");
    }

    @Test
    void sameKeyReplaysAfterAuthorityDriftWithoutSecondVersionOrAudit() {
        var f = new Fixture();
        var command = f.command("K1", 0, PromotionTestSupport.payload("HUMAN-C-1"));
        var first = f.coordinator.confirm(command, f.actor);
        f.authority.set(f.staleRole());
        var replay = f.coordinator.confirm(command, f.actor);
        assertThat(replay.confirmation().confirmationId()).isEqualTo(first.confirmation().confirmationId());
        assertThat(replay.replayed()).isTrue();
        assertThat(f.candidate.get().versionNo()).isEqualTo(1);
        assertThat(f.audits).hasSize(1);
        assertThatThrownBy(() -> f.coordinator.confirm(f.command("K1", 0,
                PromotionTestSupport.payload("DIFFERENT")), f.actor))
                .isInstanceOf(BusinessException.class).hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> f.coordinator.confirm(f.command("K1", 1,
                PromotionTestSupport.payload("HUMAN-C-1")), f.actor))
                .isInstanceOf(BusinessException.class).hasMessageContaining("fingerprint");
    }

    @Test
    void correctedConfirmationAppendsSupersessionAndRejectsOldExpectedVersion() {
        var f = new Fixture();
        var c1 = f.confirm("K1", 0, PromotionTestSupport.payload("HUMAN-C-1")).confirmation();
        var c2 = f.confirm("K2", 1, PromotionTestSupport.payload("HUMAN-C-2")).confirmation();
        assertThat(c2.supersedesConfirmationId()).isEqualTo(c1.confirmationId());
        assertThat(f.candidate.get().versionNo()).isEqualTo(2);
        assertThat(f.confirmations.findLatestByProjectAndCandidate(f.project.id(), f.candidate.get().id()))
                .contains(c2);
        assertThatThrownBy(() -> f.confirm("K3", 1, PromotionTestSupport.payload("HUMAN-C-3")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void staleRoleIdentityOrPromotedCandidateCannotBeConfirmed() {
        var role = new Fixture(); role.authority.set(role.staleRole());
        assertThatThrownBy(() -> role.confirm("KR", 0, PromotionTestSupport.payload("C")))
                .isInstanceOf(BusinessException.class);
        var identity = new Fixture(); identity.authority.set(identity.staleIdentity());
        assertThatThrownBy(() -> identity.confirm("KI", 0, PromotionTestSupport.payload("C")))
                .isInstanceOf(BusinessException.class);
        var promoted = new Fixture();
        promoted.candidate.set(promoted.candidate.get().promote(UUID.randomUUID(), "actor", Instant.now()));
        assertThatThrownBy(() -> promoted.confirm("KP", 1, PromotionTestSupport.payload("C")))
                .isInstanceOf(BusinessException.class);
        assertThat(promoted.confirmations.findLatestByProjectAndCandidate(promoted.project.id(),
                promoted.candidate.get().id())).isEmpty();
    }

    @Test
    void concurrentDistinctKeysAtOneExpectedVersionYieldOneConfirmationAndOneConflict() throws Exception {
        var f = new Fixture();
        var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> raced(f, start, "race-a", "C-A"));
            Future<Object> second = executor.submit(() -> raced(f, start, "race-b", "C-B"));
            start.countDown();
            List<Object> results = List.of(first.get(), second.get());
            assertThat(results.stream().filter(value -> value instanceof ContractCandidateConfirmationResult))
                    .hasSize(1);
            assertThat(results.stream().filter(value -> value instanceof BusinessException)).hasSize(1);
            assertThat(f.candidate.get().versionNo()).isEqualTo(1);
            assertThat(f.idempotency.size()).isEqualTo(1);
            assertThat(f.audits).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyAndPayloadReplayOneConfirmationWithoutSecondAudit() throws Exception {
        var f = new Fixture();
        var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> raced(f, start, "same-race", "C-1"));
            Future<Object> second = executor.submit(() -> raced(f, start, "same-race", "C-1"));
            start.countDown();
            List<Object> results = List.of(first.get(), second.get());
            assertThat(results.stream().filter(value -> value instanceof ContractCandidateConfirmationResult))
                    .hasSize(2);
            var ids = results.stream().map(value -> ((ContractCandidateConfirmationResult) value)
                    .confirmation().confirmationId()).distinct().toList();
            assertThat(ids).hasSize(1);
            assertThat(f.candidate.get().versionNo()).isEqualTo(1);
            assertThat(f.idempotency.size()).isEqualTo(1);
            assertThat(f.audits).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Object raced(Fixture f, CountDownLatch start, String key, String no) throws Exception {
        start.await();
        try { return f.confirm(key, 0, PromotionTestSupport.payload(no)); }
        catch (BusinessException conflict) { return conflict; }
    }

    private static final class Fixture {
        final Instant now = Instant.parse("2026-09-15T01:00:00Z");
        final Project project = Project.blank(UUID.randomUUID(), "actor", now);
        final UUID fileId = UUID.randomUUID(), parseId = UUID.randomUUID(), roleId = UUID.randomUUID(),
                identityId = UUID.randomUUID();
        final AtomicReference<ContractCandidate> candidate = new AtomicReference<>(
                ContractCandidate.draftWithAuthority(UUID.randomUUID(), project.id(), fileId,
                        parseId, roleId, identityId, ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                        "a".repeat(64), "MACHINE-C-1", "Machine Contract", "GOODS", "Buyer", "Supplier",
                        null, null, null, null, null, null, List.of(), now));
        final CurrentUser actor = new CurrentUser("actor", "actor.name", "Actor", Set.of("AUDITOR"));
        final InMemoryContractCandidateConfirmationRepository confirmations =
                new InMemoryContractCandidateConfirmationRepository();
        final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        final List<OperationAuditEvent> audits = new ArrayList<>();
        final AtomicReference<CandidateSourceAuthority> authority = new AtomicReference<>(new CandidateSourceAuthority(
                fileId, "c".repeat(64), parseId, 1, DocumentRole.CONTRACT_BODY,
                EffectiveDocumentRoleSource.HUMAN_CONFIRMATION, roleId,
                EffectiveProjectIdentityStatus.SAME_PROJECT, identityId, "a".repeat(64), true, null));
        final ContractCandidateConfirmationTransactionCoordinator coordinator;

        Fixture() {
            ContractCandidateRepository candidates = mock(ContractCandidateRepository.class);
            when(candidates.findByProjectIdAndId(project.id(), candidate.get().id()))
                    .thenAnswer(call -> Optional.of(candidate.get()));
            when(candidates.findByProjectIdAndIdForUpdate(project.id(), candidate.get().id()))
                    .thenAnswer(call -> Optional.of(candidate.get()));
            when(candidates.confirmDraftIfVersionMatches(any(), any(), anyLong(), any()))
                    .thenAnswer(call -> {
                        long expected = call.getArgument(2);
                        ContractCandidate current = candidate.get();
                        if (current.versionNo() != expected || current.promotionState() != CandidatePromotionState.DRAFT)
                            throw new BusinessException(com.fuquan.audit.common.exception.BusinessErrorCode.CONFLICT);
                        ContractCandidate changed = current.confirmed(call.getArgument(3));
                        candidate.set(changed);
                        return changed;
                    });
            ProjectRepository projects = mock(ProjectRepository.class);
            when(projects.findByIdForUpdate(project.id())).thenReturn(Optional.of(project));
            ProjectFileRepository files = mock(ProjectFileRepository.class);
            when(files.findByProjectIdAndIdForUpdate(project.id(), fileId))
                    .thenReturn(Optional.of(mock(ProjectFile.class)));
            CandidateSourceAuthorityValidator validator = mock(CandidateSourceAuthorityValidator.class);
            when(validator.snapshotWithCurrentSupport(any())).thenAnswer(call -> authority.get());
            coordinator = new ContractCandidateConfirmationTransactionCoordinator(projects, candidates, files,
                    validator,
                    mock(ContractCandidateSupportAuthorityRepository.class), confirmations,
                    new ContractCandidateConfirmationPayloadCanonicalizer(),
                    idempotency, audits::add, Clock.fixed(now, ZoneOffset.UTC),
                    new FlowDebugLogger(new ObservabilityProperties()));
        }

        ConfirmContractCandidateCommand command(String key, long version, ConfirmedContractPayload payload) {
            return new ConfirmContractCandidateCommand(project.id(), candidate.get().id(), version, payload,
                    key, PromotionTestSupport.META);
        }
        ContractCandidateConfirmationResult confirm(String key, long version, ConfirmedContractPayload payload) {
            return coordinator.confirm(command(key, version, payload), actor);
        }
        CandidateSourceAuthority staleRole() { return new CandidateSourceAuthority(fileId, "c".repeat(64),
                parseId, 1, DocumentRole.OTHER, EffectiveDocumentRoleSource.HUMAN_CONFIRMATION,
                UUID.randomUUID(), EffectiveProjectIdentityStatus.SAME_PROJECT, identityId,
                "a".repeat(64), false, "candidate source role not authoritative"); }
        CandidateSourceAuthority staleIdentity() { return new CandidateSourceAuthority(fileId, "c".repeat(64),
                parseId, 1, DocumentRole.CONTRACT_BODY, EffectiveDocumentRoleSource.HUMAN_CONFIRMATION,
                roleId, EffectiveProjectIdentityStatus.CONFLICT, UUID.randomUUID(), "a".repeat(64),
                false, "candidate source project identity not authoritative"); }
    }
}
