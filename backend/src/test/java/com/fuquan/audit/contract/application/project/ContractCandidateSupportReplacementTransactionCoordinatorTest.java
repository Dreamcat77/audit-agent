package com.fuquan.audit.contract.application.project;

import static com.fuquan.audit.contract.application.project.ContractCandidateSupportTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.security.domain.CurrentUser;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class ContractCandidateSupportReplacementTransactionCoordinatorTest {
    @ParameterizedTest
    @CsvSource({"false,A,98,EXPLICIT_SUPPORT_AUTHORITY", "true,B,99,EXPLICIT_SUPPORT_AUTHORITY",
            "true,EMPTY,100,PRIMARY_ONLY_COMPAT", "false,EMPTY,100,PRIMARY_ONLY_COMPAT"})
    void replacesTheWholeSetAndPrimaryLinesWhileAdvancingIndependentVersionsOnce(
            boolean initiallyAssigned, String requested, String amount, CandidateAuthorityMode mode) {
        Fixture f = new Fixture();
        if (initiallyAssigned) f.replace("initial", 0, List.of(A));
        var before = f.candidates.confirmDraftIfVersionMatches(PROJECT, CANDIDATE,
                f.current().versionNo(), NOW);
        long oldConfirmationResultVersion = before.versionNo();
        var beforeLines = before.lineItems();
        List<UUID> ids = requested.equals("EMPTY") ? List.of() : List.of(requested.equals("A") ? A : B);
        f.calls.clear();

        var result = f.replace("replacement", before.versionNo(), ids);
        var updated = f.current();

        assertThat(result.candidateId()).isEqualTo(CANDIDATE);
        assertThat(result.candidateVersion()).isEqualTo(before.versionNo() + 1);
        assertThat(updated.versionNo()).isGreaterThan(oldConfirmationResultVersion);
        assertThat(result.supportAuthorityVersion()).isEqualTo(initiallyAssigned ? 2 : 1);
        assertThat(updated.supportAuthorityVersionNo()).isEqualTo(result.supportAuthorityVersion());
        assertThat(result.authorityMode()).isEqualTo(mode);
        assertThat(result.replayed()).isFalse();
        assertThat(updated).usingRecursiveComparison().comparingOnlyFields("id", "projectId",
                "sourceContractFileId", "sourceParseResultId", "sourceRoleDecisionId", "sourceIdentityDecisionId",
                "sourceAuthoritySource", "sourceFactFingerprint", "createdAt", "promotionState")
                .isEqualTo(before);
        assertThat(updated.updatedAt()).isAfter(before.updatedAt());
        assertThat(updated.awardAmount()).isEqualByComparingTo(amount);
        assertThat(updated.contractNo()).isEqualTo("PRIMARY");
        assertThat(updated.lineItems()).hasSize(1);
        assertThat(updated.lineItems().get(0).itemName()).isEqualTo("Primary line");
        assertThat(updated.lineItems().get(0).id()).isNotEqualTo(beforeLines.get(0).id());
        assertThat(result.supportAssociations()).containsExactlyElementsOf(f.rows());
        assertThat(f.rows()).extracting(ContractCandidateSupportAuthority::sourceFileId).containsExactlyElementsOf(ids);
        assertThat(f.rows()).allSatisfy(row -> {
            assertThat(row.authorityVersion()).isEqualTo(result.supportAuthorityVersion());
            assertThat(row.createdBy()).isEqualTo(ACTOR.userId());
        });
        assertThat(f.supportAuthority.validateCurrent(updated).authorityMode()).isEqualTo(mode);
        assertThat(f.calls).containsSubsequence("claim", "delete", "cas", "audit", "success");
        if (!ids.isEmpty()) assertThat(f.calls).containsSubsequence("cas", "insert", "audit");
        assertThat(f.idempotency.findByScopeAndKey("CONTRACT_CANDIDATE_SUPPORT_REPLACE", "replacement"))
                .hasValueSatisfying(record -> {
                    assertThat(record.status()).isEqualTo(IdempotencyStatus.SUCCEEDED);
                    assertThat(record.resultResourceType()).isEqualTo("CONTRACT_CANDIDATE");
                    assertThat(record.resultResourceId()).isEqualTo(CANDIDATE.toString());
                });
        verify(f.extractions, never()).bindCandidateIfUnbound(any(), any(), any(), any(), any());
        verify(f.extractions, never()).findByProjectId(any());
        assertThat(f.transactions.commits).isEqualTo(initiallyAssigned ? 2 : 1);
    }

    @Test
    void canonicalizesSelectionAndReplaysWithoutAnotherCohortOrAudit() {
        Fixture f = new Fixture();
        var selected = new ArrayList<>(List.of(B, A, B));
        var command = f.command("same", 0, selected);
        selected.clear();
        var first = f.service.replace(command);
        var repeated = f.replace("same", 0, List.of(A, B));
        assertThat(first.supportAssociations()).extracting(ContractCandidateSupportAuthority::sourceFileId)
                .containsExactly(A, B);
        assertThat(repeated).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(first);
        assertThat(repeated.replayed()).isTrue();
        assertThat(f.current().versionNo()).isEqualTo(1);
        assertThat(f.current().awardAmount()).isNull();
        assertThat(f.audits).hasSize(1);
        assertThat(f.idempotency.size()).isEqualTo(1);
    }

    @Test
    void commandAcceptsOnlyStableUserInputsAndAuditAndResultContainNoBusinessEvidence() throws Exception {
        assertThat(Arrays.stream(ReplaceContractCandidateSupportAuthorityCommand.class.getRecordComponents())
                .map(RecordComponent::getName)).containsExactly("projectId", "candidateId", "expectedCandidateVersion",
                        "supportFileIds", "idempotencyKey", "metadata");
        Fixture f = new Fixture();
        var result = f.replace("safe", 0, List.of(A));
        var audit = f.audits.get(0);
        assertThat(audit.eventType()).isEqualTo("PROJECT_CANDIDATE_SUPPORT_REPLACED");
        assertThat(audit.taskId()).isNull();
        assertThat(audit.userId()).isEqualTo(ACTOR.userId());
        var detail = new ObjectMapper().readTree(audit.detail());
        assertThat(detail.get("projectId").asText()).isEqualTo(PROJECT.toString());
        assertThat(detail.get("candidateId").asText()).isEqualTo(CANDIDATE.toString());
        assertThat(detail.get("oldMode").asText()).isEqualTo("PRIMARY_ONLY_COMPAT");
        assertThat(detail.get("newMode").asText()).isEqualTo("EXPLICIT_SUPPORT_AUTHORITY");
        assertThat(detail.get("oldSupportCount").asInt()).isZero();
        assertThat(detail.get("newSupportCount").asInt()).isEqualTo(1);
        assertThat(detail.get("supportFileIds").get(0).asText()).isEqualTo(A.toString());
        assertThat(detail.get("candidateVersion").asLong()).isEqualTo(1);
        assertThat(detail.get("supportAuthorityVersion").asLong()).isEqualTo(1);
        assertThat(detail.get("supportAuthorityFingerprint").asText()).matches("[0-9a-f]{64}");
        assertThat(audit.toString() + result).doesNotContain(PRIVATE_TEXT, PRIVATE_NAME,
                "synthetic-private-excerpt", "synthetic-private-supplier", "synthetic-private-credit", "Primary line");
    }

    @Test
    void stableCommandFingerprintFencesEveryIdentityDimensionAndIgnoresTransportMetadataAndKey() {
        Fixture f = new Fixture();
        var command = f.command("original", 0, List.of(A, B));
        String fingerprint = ContractCandidateSupportReplacementTransactionCoordinator.commandFingerprint(ACTOR, command);
        assertThat(fingerprint).matches("[0-9a-f]{64}");
        var equivalent = new ReplaceContractCandidateSupportAuthorityCommand(PROJECT, CANDIDATE, 0,
                List.of(B, A, A), "different-key", new ProjectRequestMetadata("another", "different", null));
        assertThat(ContractCandidateSupportReplacementTransactionCoordinator.commandFingerprint(ACTOR, equivalent))
                .isEqualTo(fingerprint);
        for (var changed : List.of(
                new ReplaceContractCandidateSupportAuthorityCommand(UUID.randomUUID(), CANDIDATE, 0, List.of(A, B), "original", META),
                new ReplaceContractCandidateSupportAuthorityCommand(PROJECT, UUID.randomUUID(), 0, List.of(A, B), "original", META),
                f.command("original", 1, List.of(A, B)), f.command("original", 0, List.of(A)))) {
            assertThat(ContractCandidateSupportReplacementTransactionCoordinator.commandFingerprint(ACTOR, changed))
                    .isNotEqualTo(fingerprint);
        }
        assertThat(ContractCandidateSupportReplacementTransactionCoordinator.commandFingerprint(
                new CurrentUser("another-actor", ACTOR.username(), ACTOR.displayName(), ACTOR.roles()), command))
                .isNotEqualTo(fingerprint);
    }

    @Test
    void actualDurableCommandFingerprintDoesNotIncludeMutableResolvedAuthority() {
        Fixture first = new Fixture();
        first.replace("stable", 0, List.of(A));
        Fixture second = new Fixture();
        var support = second.sources.get(1);
        support.hash = "b".repeat(64);
        support.facts.set(0, support.fact("AWARD_AMOUNT", "97", new BigDecimal("97"), null));
        second.files.update(support.file());
        var project = second.projects.findById(PROJECT).orElseThrow();
        second.projects.compareAndSet(project, project.confirmMetadata(1, "Synthetic project", "SYN-2", null, NOW));
        second.sources.forEach(source -> source.projectVersion = 2);
        second.replace("stable", 0, List.of(A));
        var record1 = first.idempotency.findByScopeAndKey("CONTRACT_CANDIDATE_SUPPORT_REPLACE", "stable").orElseThrow();
        var record2 = second.idempotency.findByScopeAndKey("CONTRACT_CANDIDATE_SUPPORT_REPLACE", "stable").orElseThrow();
        assertThat(record2.requestFingerprint()).isEqualTo(record1.requestFingerprint());
        assertThat(second.current().supportAuthorityFingerprint()).isNotEqualTo(first.current().supportAuthorityFingerprint());
        assertThat(second.rows().get(0).parseResultId()).isNotEqualTo(first.rows().get(0).parseResultId());
        assertThat(second.rows().get(0).roleDecisionId()).isNotEqualTo(first.rows().get(0).roleDecisionId());
        assertThat(second.rows().get(0).identityDecisionId()).isNotEqualTo(first.rows().get(0).identityDecisionId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "cas", "insert", "audit", "success"})
    void eachPostWriteFailurePropagatesAndRequestsTransactionAndUncommittedClaimRollback(String stage) {
        Fixture f = new Fixture();
        f.failureAfter = stage;
        assertThatThrownBy(() -> f.replace("failure", 0, List.of(A)))
                .isInstanceOfSatisfying(ContractCandidateSupportReplacementException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(BusinessErrorCode.INTERNAL_ERROR);
                    assertThat(error.classification()).isEqualTo(
                            ContractCandidateSupportReplacementException.Classification.OUTCOME_UNCERTAIN);
                    assertThat(error.getCause()).isInstanceOf(IllegalStateException.class)
                            .hasMessage("injected " + stage);
                });
        assertThat(f.calls).contains(stage);
        verify(f.idempotency).rollbackUncommittedClaim(any());
        assertThat(f.transactions.begins).isEqualTo(1);
        assertThat(f.transactions.commits).isZero();
        assertThat(f.transactions.rollbacks).isEqualTo(1);
        // The local port only removes IN_PROGRESS; actual state rollback is a Task 19 DM8 assertion.
        if (!stage.equals("success")) assertThat(f.idempotency.size()).isZero();
    }

    @Test
    void authenticationAndSafeKeyValidationRejectBeforeOpeningATransaction() {
        Fixture f = new Fixture();
        var anonymous = new ContractCandidateSupportReplacementService(Optional::empty, f.coordinator);
        assertThatThrownBy(() -> anonymous.replace(f.command("safe", 0, List.of(A))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(BusinessErrorCode.UNAUTHENTICATED));
        for (String invalid : List.of("", "has space", "bad/character", "a".repeat(129))) {
            assertThatThrownBy(() -> f.replace(invalid, 0, List.of(A)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(BusinessErrorCode.INVALID_ARGUMENT));
        }
        assertThat(f.transactions.begins).isZero();
    }

    @Test
    void maximumCanonicalSupportSelectionSucceedsWithoutTruncationDespiteDuplicateRequestEntries() {
        Fixture f = new Fixture(39);
        List<UUID> ids = f.sources.stream().skip(1).map(source -> source.id)
                .sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        var request = new ArrayList<>(ids);
        request.addAll(ids);
        java.util.Collections.reverse(request);

        var result = f.replace("maximum", 0, request);

        assertThat(result.supportAssociations()).extracting(ContractCandidateSupportAuthority::sourceFileId)
                .containsExactlyElementsOf(ids);
        assertThat(f.rows()).hasSize(39);
        assertThat(f.audits).hasSize(1);
        assertThat(f.audits.get(0).detail().length()).isLessThanOrEqualTo(2000);
        assertThat(f.transactions.commits).isEqualTo(1);
    }

    @Test
    void oneOverCanonicalLimitRejectsBeforeAnyCoordinatorInteraction() {
        var coordinator = mock(ContractCandidateSupportReplacementTransactionCoordinator.class);
        var service = new ContractCandidateSupportReplacementService(() -> Optional.of(ACTOR), coordinator);
        List<UUID> ids = java.util.stream.IntStream.range(0, 40).mapToObj(index -> new UUID(0L, index)).toList();
        var command = new ReplaceContractCandidateSupportAuthorityCommand(PROJECT, CANDIDATE, 0, ids, "too-many", META);

        assertThatThrownBy(() -> service.replace(command)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.errorCode()).isEqualTo(BusinessErrorCode.INVALID_ARGUMENT);
            assertThat(error.getMessage()).isEqualTo("supportFileIds must contain at most 39 distinct file IDs");
        });
        verifyNoInteractions(coordinator);
    }

    @Test
    void everyAcceptedAuditRetainsAllIdsAndFitsDetailAtMaximumVersionsAndOldCount() throws Exception {
        var updated = new ContractCandidate(CANDIDATE, PROJECT, PRIMARY, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, List.of(),
                CandidatePromotionState.DRAFT, Long.MAX_VALUE, null, null, null, null, NOW, NOW,
                Long.MAX_VALUE, "f".repeat(64));
        for (int count = 0; count <= 39; count++) {
            List<ContractCandidateSupportAuthority> rows = java.util.stream.IntStream.range(0, count)
                    .mapToObj(index -> new ContractCandidateSupportAuthority(UUID.randomUUID(), PROJECT, CANDIDATE,
                            new UUID(0L, index), "f".repeat(64), UUID.randomUUID(), Integer.MAX_VALUE, Long.MAX_VALUE,
                            DocumentRole.AWARD_NOTICE, UUID.randomUUID(), ProjectIdentityResolution.CONFIRM_SAME_PROJECT,
                            UUID.randomUUID(), "f".repeat(64), Long.MAX_VALUE, ACTOR.userId(), NOW)).toList();
            List<UUID> ids = rows.stream().map(ContractCandidateSupportAuthority::sourceFileId).toList();
            var command = new ReplaceContractCandidateSupportAuthorityCommand(PROJECT, CANDIDATE, Long.MAX_VALUE - 1,
                    ids, "audit-limit", META);

            OperationAuditEvent audit = ReflectionTestUtils.invokeMethod(
                    ContractCandidateSupportReplacementTransactionCoordinator.class, "successAudit",
                    command, ACTOR, updated, Integer.MAX_VALUE, rows, NOW);

            assertThat(audit).isNotNull();
            assertThat(audit.detail().length()).as("audit length for %s supports", count).isLessThanOrEqualTo(2000);
            var detail = new ObjectMapper().readTree(audit.detail());
            assertThat(detail.get("supportFileIds")).hasSize(count);
            for (int index = 0; index < count; index++) {
                assertThat(detail.get("supportFileIds").get(index).asText()).isEqualTo(ids.get(index).toString());
            }
            assertThat(detail.get("candidateVersion").asLong()).isEqualTo(Long.MAX_VALUE);
            assertThat(detail.get("supportAuthorityVersion").asLong()).isEqualTo(Long.MAX_VALUE);
            assertThat(detail.get("oldSupportCount").asInt()).isEqualTo(Integer.MAX_VALUE);
            assertThat(detail.get("newSupportCount").asInt()).isEqualTo(count);
            assertThat(detail.get("supportAuthorityFingerprint").asText()).isEqualTo("f".repeat(64));
        }
    }

    @Test
    void simultaneousDifferentSetsAtOneVersionProduceOneWinnerAndOneCompleteCohort() throws Exception {
        Fixture f = new Fixture();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> race(f, start, "one", List.of(A)));
            var two = pool.submit(() -> race(f, start, "two", List.of(B)));
            start.countDown();
            var results = List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(ContractCandidateSupportReplacementResult.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(BusinessException.class::isInstance)).hasSize(1);
            assertThat(f.current().versionNo()).isEqualTo(1);
            assertThat(f.rows()).hasSize(1);
            assertThat(f.rows().get(0).authorityVersion()).isEqualTo(1);
            assertThat(f.current().awardAmount()).isEqualByComparingTo(f.rows().get(0).sourceFileId().equals(A) ? "98" : "99");
            assertThat(f.audits).hasSize(1);
            assertThat(f.idempotency.size()).isEqualTo(1);
            assertThat(f.transactions.commits).isEqualTo(1);
            f.supportAuthority.validateCurrent(f.current());
        } finally { pool.shutdownNow(); }
    }

    private static Object race(Fixture f, CountDownLatch start, String key, List<UUID> ids) throws Exception {
        start.await();
        try { return f.replace(key, 0, ids); }
        catch (BusinessException failure) { return failure; }
    }
}
