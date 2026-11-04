package com.fuquan.audit.contract.application.project;

import static com.fuquan.audit.contract.application.project.ContractCandidateSupportTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.security.domain.CurrentUser;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContractCandidateSupportReplacementReplayTest {
    private static final String SCOPE = "CONTRACT_CANDIDATE_SUPPORT_REPLACE";

    @ParameterizedTest
    @ValueSource(strings = {"UNCHANGED", "EMPTY", "ROLE", "IDENTITY", "PARSE", "PROJECT", "HASH", "FACTS", "PRIMARY"})
    void completedReplayUsesOnlyStoredAuthorityDespiteMutableAuthorityDrift(String drift) {
        Fixture f = new Fixture();
        List<UUID> ids = drift.equals("EMPTY") ? List.of() : List.of(B, A);
        var first = f.replace("original", 0, ids);
        var source = f.sources.get(1);
        switch (drift) {
            case "ROLE" -> source.roleId = UUID.randomUUID();
            case "IDENTITY" -> source.identityId = UUID.randomUUID();
            case "PARSE" -> source.parseId = UUID.randomUUID();
            case "PROJECT" -> {
                var p = f.projects.findById(PROJECT).orElseThrow();
                f.projects.compareAndSet(p, p.confirmMetadata(1, "Changed project", "SYN-2", null, NOW));
            }
            case "HASH" -> { source.hash = "b".repeat(64); f.files.update(source.file()); }
            case "FACTS" -> source.facts.set(0, source.fact("AWARD_AMOUNT", "97", new BigDecimal("97"), null));
            case "PRIMARY" -> f.sources.get(0).parseId = UUID.randomUUID();
            default -> { }
        }
        var before = f.current();
        var rows = f.rows();
        clearInvocations(f.primaryAuthority, f.supportAuthority, f.extractions, f.idempotency);
        f.calls.clear();

        var reordered = new java.util.ArrayList<>(ids);
        java.util.Collections.reverse(reordered);
        var replay = f.replace("original", 0, reordered);

        assertThat(replay).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(first);
        assertThat(replay.replayed()).isTrue();
        assertUnchanged(f, before, rows, 1, 1);
        verify(f.primaryAuthority, never()).snapshot(any());
        verify(f.supportAuthority, never()).resolveRequested(any(), anyList());
        verify(f.supportAuthority, never()).validateCurrent(any());
        verifyNoInteractions(f.extractions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REPLACED", "CONFIRMED", "PROMOTED"})
    void laterBusinessMutationSupersedesWithoutRestoringTheOldSet(String mutation) {
        Fixture f = new Fixture();
        f.replace("original", 0, List.of(A));
        switch (mutation) {
            case "REPLACED" -> f.replace("later", 1, List.of(B));
            case "CONFIRMED" -> f.candidates.confirmDraftIfVersionMatches(PROJECT, CANDIDATE, 1, NOW);
            case "PROMOTED" -> f.candidates.promoteIfVersionMatches(PROJECT, CANDIDATE, 1,
                    UUID.randomUUID(), ACTOR.userId(), NOW);
            default -> throw new AssertionError(mutation);
        }
        var before = f.current();
        var rows = f.rows();
        int count = f.idempotency.size();
        f.calls.clear();
        expect(() -> f.replace("original", 0, List.of(A)), BusinessErrorCode.CONFLICT, "RESULT_SUPERSEDED");
        assertUnchanged(f, before, rows, count, count);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTOR", "PROJECT", "CANDIDATE", "VERSION", "SUPPORT_IDS"})
    void stableIdentityMismatchWinsEvenWhenTheRequestedResourceDoesNotExist(String mismatch) {
        Fixture f = new Fixture();
        f.replace("original", 0, List.of(A));
        var command = f.command("original", 0, List.of(A));
        CurrentUser actor = ACTOR;
        switch (mismatch) {
            case "ACTOR" -> actor = new CurrentUser("other", ACTOR.username(), ACTOR.displayName(), ACTOR.roles());
            case "PROJECT" -> command = copy(command, Map.of("projectId", UUID.randomUUID()));
            case "CANDIDATE" -> command = copy(command, Map.of("candidateId", UUID.randomUUID()));
            case "VERSION" -> command = copy(command, Map.of("expectedCandidateVersion", 1L));
            case "SUPPORT_IDS" -> command = copy(command, Map.of("supportFileIds", List.of(B)));
            default -> throw new AssertionError(mismatch);
        }
        var changed = command;
        var requester = actor;
        var before = f.current();
        var rows = f.rows();
        f.calls.clear();
        expect(() -> f.coordinator.replace(changed, requester), BusinessErrorCode.CONFLICT, "INTEGRITY_CONFLICT");
        assertUnchanged(f, before, rows, 1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STALE_VERSION", "PROMOTED", "WRONG_ROLE", "STALE_ROLE", "STALE_IDENTITY", "INVALID_PARSE", "PRIMARY", "MISSING_FILE"})
    void deterministicFreshRejectionRequiresAnEmptySecondLookupAndPreservesItsBusinessCode(String invalid) {
        Fixture f = new Fixture();
        long version = 0;
        List<UUID> ids = List.of(A);
        switch (invalid) {
            case "STALE_VERSION" -> version = 1;
            case "PROMOTED" -> f.candidates.promoteIfVersionMatches(PROJECT, CANDIDATE, 0,
                    UUID.randomUUID(), ACTOR.userId(), NOW);
            case "WRONG_ROLE" -> ids = List.of(PRIMARY);
            case "STALE_ROLE" -> f.sources.get(1).hash = "b".repeat(64);
            case "STALE_IDENTITY" -> f.sources.get(1).projectVersion = 2;
            case "INVALID_PARSE" -> f.sources.get(1).parseId = UUID.randomUUID();
            case "PRIMARY" -> f.sources.get(0).roleId = UUID.randomUUID();
            case "MISSING_FILE" -> ids = List.of(UUID.randomUUID());
            default -> throw new AssertionError(invalid);
        }
        var command = f.command("rejected", version, ids);
        var before = f.current();
        f.calls.clear();
        expect(() -> f.service.replace(command), invalid.equals("MISSING_FILE")
                ? BusinessErrorCode.NOT_FOUND : BusinessErrorCode.CONFLICT, "REQUEST_REJECTED");
        verify(f.idempotency, times(2)).findByScopeAndKey(SCOPE, "rejected");
        assertUnchanged(f, before, List.of(), 0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "FAILED", "MISSING_RESULT", "LOWER_VERSION", "COHORT_MISSING", "COHORT_DUPLICATE",
            "COHORT_VERSION", "COHORT_OWNER", "COHORT_FACT_HASH", "COHORT_FILE_HASH", "CANDIDATE_OWNER", "METADATA_MISSING",
            "FINGERPRINT_MISSING", "FINGERPRINT_WRONG", "RESULT_ID", "RESULT_TYPE", "SCOPE", "KEY", "DB_READ", "DB_COHORT"})
    void uncertainAndCorruptDurableResultsAreDistinctAndNeverMutate(String state) {
        Fixture f = new Fixture();
        f.replace("original", 0, List.of(A));
        IdempotencyRecord record = record(f);
        ContractCandidate before = f.current();
        var rows = f.rows();
        String expected = corruptResult(f, state, record, before, rows);
        f.calls.clear();
        expect(() -> f.replace("original", 0, List.of(A)), state.startsWith("DB_")
                ? BusinessErrorCode.INTERNAL_ERROR : state.equals("MISSING_RESULT")
                        ? BusinessErrorCode.NOT_FOUND : BusinessErrorCode.CONFLICT, expected);
        assertThat(f.audits).hasSize(1);
        assertThat(f.idempotency.size()).isEqualTo(1);
        assertThat(f.calls).doesNotContain("claim", "delete", "cas", "insert", "audit", "success");
        verify(f.idempotency, never()).markFailed(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "SUPERSEDED", "IN_PROGRESS", "FAILED", "IDENTITY", "COHORT_MISSING", "COHORT_FACT_HASH",
            "METADATA_MISSING", "FINGERPRINT_MISSING", "RESULT_ID", "LOWER_VERSION", "MISSING_RESULT", "DB_LOOKUP"})
    void winnerDiscoveredAfterValidationFailureAlwaysOutranksRequestRejection(String state) {
        Fixture f = new Fixture();
        var original = f.replace("original", 0, List.of(A));
        var record = record(f);
        var candidate = f.current();
        var rows = f.rows();
        String classification = null;
        if (state.equals("SUPERSEDED")) {
            f.candidates.confirmDraftIfVersionMatches(PROJECT, CANDIDATE, 1, NOW);
            classification = "RESULT_SUPERSEDED";
        } else if (state.equals("IDENTITY")) {
            record = copy(record, Map.of("actorId", "other"));
            classification = "INTEGRITY_CONFLICT";
        } else if (!state.equals("SUCCEEDED") && !state.equals("DB_LOOKUP")) {
            classification = corruptResult(f, state, record, candidate, rows);
            record = f.idempotency.findByScopeAndKey(SCOPE, "original").orElseThrow();
        }
        // The original absent read raced with a committed winner; then fresh validation rejects.
        // Only the first attempted lock fails; reconciliation must use the durable winner.
        doAnswer(new org.mockito.stubbing.Answer<Object>() {
            boolean rejected;
            public Object answer(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
                if (!rejected) { rejected = true; throw new BusinessException(BusinessErrorCode.NOT_FOUND, "synthetic deterministic rejection"); }
                return invocation.callRealMethod();
            }
        }).when(f.files).findByProjectIdAndIdForUpdate(PROJECT, A);
        if (state.equals("DB_LOOKUP")) {
            doReturn(Optional.empty()).doThrow(new IllegalStateException("unavailable"))
                    .when(f.idempotency).findByScopeAndKey(SCOPE, "original");
            classification = "OUTCOME_UNCERTAIN";
        } else {
            doReturn(Optional.empty(), Optional.of(record)).when(f.idempotency).findByScopeAndKey(SCOPE, "original");
        }
        clearInvocations(f.idempotency);
        f.calls.clear();
        if (classification == null) {
            var replay = f.replace("original", 0, List.of(A));
            assertThat(replay).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(original);
            assertThat(replay.replayed()).isTrue();
        } else {
            expect(() -> f.replace("original", 0, List.of(A)), state.equals("DB_LOOKUP")
                    ? BusinessErrorCode.INTERNAL_ERROR : state.equals("MISSING_RESULT")
                            ? BusinessErrorCode.NOT_FOUND : BusinessErrorCode.CONFLICT, classification);
        }
        verify(f.idempotency, times(2)).findByScopeAndKey(SCOPE, "original");
        assertThat(f.calls).doesNotContain("claim", "delete", "cas", "insert", "audit", "success");
        assertThat(f.idempotency.size()).isEqualTo(1);
        assertThat(f.audits).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "SUPERSEDED", "IN_PROGRESS", "FAILED", "IDENTITY", "COHORT_FACT_HASH"})
    void concurrentClaimDiscoveryUsesTheSameBoundedReplayRules(String state) {
        Fixture f = new Fixture();
        var initialCandidate = f.current();
        var original = f.replace("original", 0, List.of(A));
        var winnerCandidate = f.current();
        var rows = f.rows();
        var record = record(f);
        String classification = null;
        if (state.equals("SUPERSEDED")) {
            winnerCandidate = winnerCandidate.confirmed(NOW);
            classification = "RESULT_SUPERSEDED";
        } else if (state.equals("IDENTITY")) {
            record = copy(record, Map.of("actorId", "other"));
            classification = "INTEGRITY_CONFLICT";
        } else if (!state.equals("SUCCEEDED")) {
            classification = corruptResult(f, state, record, winnerCandidate, rows);
            record = f.idempotency.findByScopeAndKey(SCOPE, "original").orElseThrow();
        }
        doReturn(Optional.empty()).when(f.idempotency).findByScopeAndKey(SCOPE, "original");
        doReturn(Optional.of(initialCandidate), Optional.of(winnerCandidate))
                .when(f.candidates).findByProjectIdAndIdForUpdate(PROJECT, CANDIDATE);
        doReturn(new IdempotencyClaim(false, record)).when(f.idempotency).claim(any());
        f.calls.clear();
        if (classification == null) {
            var replay = f.replace("original", 0, List.of(A));
            assertThat(replay).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(original);
            assertThat(replay.replayed()).isTrue();
        } else expect(() -> f.replace("original", 0, List.of(A)), BusinessErrorCode.CONFLICT, classification);
        assertThat(f.calls).doesNotContain("delete", "cas", "insert", "audit", "success");
        assertThat(f.idempotency.size()).isEqualTo(1);
        assertThat(f.audits).hasSize(1);
    }

    @Test
    void infrastructureBusinessFailureKeepsItsOriginalCodeAndIsNotARequestRejection() {
        Fixture f = new Fixture();
        doThrow(new BusinessException(BusinessErrorCode.EXTERNAL_SERVICE_ERROR, "unavailable"))
                .when(f.primaryAuthority).snapshot(any());
        expect(() -> f.replace("uncertain", 0, List.of(A)), BusinessErrorCode.EXTERNAL_SERVICE_ERROR, "OUTCOME_UNCERTAIN");
        assertThat(f.idempotency.size()).isZero();
        assertThat(f.audits).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "SUPERSEDED", "IN_PROGRESS", "FAILED", "IDENTITY"})
    void winnerAfterStaleVersionValidationIsReconciledUnderAlreadyHeldLocks(String state) {
        Fixture f = new Fixture();
        var original = f.replace("original", 0, List.of(A));
        var record = record(f);
        String classification = null;
        switch (state) {
            case "SUPERSEDED" -> {
                f.candidates.confirmDraftIfVersionMatches(PROJECT, CANDIDATE, 1, NOW);
                classification = "RESULT_SUPERSEDED";
            }
            case "IN_PROGRESS" -> {
                record = copy(record, Map.of("status", IdempotencyStatus.IN_PROGRESS));
                classification = "OUTCOME_UNCERTAIN";
            }
            case "FAILED" -> { record = copy(record, Map.of("status", IdempotencyStatus.FAILED)); classification = "INTEGRITY_CONFLICT"; }
            case "IDENTITY" -> { record = copy(record, Map.of("actorId", "other")); classification = "INTEGRITY_CONFLICT"; }
            default -> { }
        }
        doReturn(Optional.empty(), Optional.of(record)).when(f.idempotency).findByScopeAndKey(SCOPE, "original");
        clearInvocations(f.idempotency);
        f.calls.clear();
        if (classification == null) {
            var replay = f.replace("original", 0, List.of(A));
            assertThat(replay).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(original);
            assertThat(replay.replayed()).isTrue();
        } else expect(() -> f.replace("original", 0, List.of(A)), BusinessErrorCode.CONFLICT, classification);
        verify(f.idempotency, times(2)).findByScopeAndKey(SCOPE, "original");
        assertThat(f.calls).doesNotContain("claim", "delete", "cas", "insert", "audit", "success");
        assertThat(f.audits).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_COMPLETION", "ZERO_RECORD_VERSION", "COMPLETED_BEFORE_START", "WRONG_COHORT_ACTOR"})
    void impossibleSuccessfulRecordOrCohortCannotReplay(String impossible) {
        Fixture f = new Fixture();
        f.replace("original", 0, List.of(A));
        var record = record(f);
        Map<String, Object> changed = new HashMap<>();
        switch (impossible) {
            case "MISSING_COMPLETION" -> changed.put("completedAt", null);
            case "ZERO_RECORD_VERSION" -> changed.put("versionNo", 0L);
            case "COMPLETED_BEFORE_START" -> changed.put("completedAt", NOW.minusSeconds(1));
            case "WRONG_COHORT_ACTOR" -> doReturn(List.of(copy(f.rows().get(0), Map.of("createdBy", "other"))))
                    .when(f.supports).findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
            default -> throw new AssertionError(impossible);
        }
        doReturn(Optional.of(copy(record, changed))).when(f.idempotency).findByScopeAndKey(SCOPE, "original");
        f.calls.clear();
        expect(() -> f.replace("original", 0, List.of(A)), BusinessErrorCode.CONFLICT, "INTEGRITY_CONFLICT");
        assertThat(f.calls).doesNotContain("claim", "delete", "cas", "insert", "audit", "success");
        assertThat(f.audits).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "INFRASTRUCTURE"})
    void replayReadBusinessFailurePreservesOriginalBusinessErrorCode(String failure) {
        Fixture f = new Fixture();
        f.replace("original", 0, List.of(A));
        BusinessErrorCode code = failure.equals("MISSING") ? BusinessErrorCode.NOT_FOUND : BusinessErrorCode.EXTERNAL_SERVICE_ERROR;
        var cause = new BusinessException(code, "synthetic read failure");
        doThrow(cause).when(f.candidates).findByProjectIdAndIdForUpdate(PROJECT, CANDIDATE);
        expect(() -> f.replace("original", 0, List.of(A)), code, "OUTCOME_UNCERTAIN");
    }

    private static String corruptResult(Fixture f, String state, IdempotencyRecord record,
            ContractCandidate candidate, List<ContractCandidateSupportAuthority> rows) {
        String classification = "INTEGRITY_CONFLICT";
        switch (state) {
            case "IN_PROGRESS" -> { record = copy(record, Map.of("status", IdempotencyStatus.IN_PROGRESS)); classification = "OUTCOME_UNCERTAIN"; }
            case "FAILED" -> record = copy(record, Map.of("status", IdempotencyStatus.FAILED));
            case "MISSING_RESULT" -> {
                doReturn(Optional.empty()).when(f.candidates).findByProjectIdAndId(PROJECT, CANDIDATE);
                classification = "OUTCOME_UNCERTAIN";
            }
            case "LOWER_VERSION" -> { candidate = copy(candidate, Map.of("versionNo", 0L)); classification = "OUTCOME_UNCERTAIN"; }
            case "COHORT_MISSING" -> doReturn(List.of()).when(f.supports).findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
            case "COHORT_DUPLICATE" -> doReturn(List.of(rows.get(0), rows.get(0))).when(f.supports).findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
            case "COHORT_VERSION", "COHORT_OWNER", "COHORT_FACT_HASH", "COHORT_FILE_HASH" -> {
                String field = switch (state) { case "COHORT_VERSION" -> "authorityVersion"; case "COHORT_OWNER" -> "candidateId";
                    case "COHORT_FACT_HASH" -> "sourceFactFingerprint"; default -> "fileSha256"; };
                Object value = state.equals("COHORT_VERSION") ? 2L : state.equals("COHORT_OWNER") ? UUID.randomUUID() : "f".repeat(64);
                doReturn(List.of(copy(rows.get(0), Map.of(field, value))))
                        .when(f.supports).findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
            }
            case "CANDIDATE_OWNER" -> candidate = copy(candidate, Map.of("projectId", UUID.randomUUID()));
            case "METADATA_MISSING" -> {
                Map<String, Object> absent = new HashMap<>();
                absent.put("supportAuthorityVersionNo", null); absent.put("supportAuthorityFingerprint", null);
                candidate = copy(candidate, absent);
            }
            case "FINGERPRINT_MISSING" -> {
                Map<String, Object> absent = new HashMap<>(); absent.put("requestFingerprint", null);
                record = copy(record, absent);
            }
            case "FINGERPRINT_WRONG" -> record = copy(record, Map.of("requestFingerprint", "f".repeat(64)));
            case "RESULT_ID" -> record = copy(record, Map.of("resultResourceId", UUID.randomUUID().toString()));
            case "RESULT_TYPE" -> record = copy(record, Map.of("resultResourceType", "AUDIT_TASK"));
            case "SCOPE" -> record = copy(record, Map.of("scope", "OTHER"));
            case "KEY" -> record = copy(record, Map.of("idempotencyKey", "other"));
            case "DB_READ" -> {
                doThrow(new IllegalStateException("unavailable")).when(f.idempotency).findByScopeAndKey(SCOPE, "original");
                return "OUTCOME_UNCERTAIN";
            }
            case "DB_COHORT" -> {
                doThrow(new IllegalStateException("unavailable")).when(f.supports).findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
                classification = "OUTCOME_UNCERTAIN";
            }
            default -> throw new AssertionError(state);
        }
        doReturn(Optional.of(record)).when(f.idempotency).findByScopeAndKey(SCOPE, "original");
        doReturn(Optional.of(candidate)).when(f.candidates).findByProjectIdAndIdForUpdate(PROJECT, CANDIDATE);
        return classification;
    }

    private static IdempotencyRecord record(Fixture f) {
        return f.idempotency.findByScopeAndKey(SCOPE, "original").orElseThrow();
    }

    private static void assertUnchanged(Fixture f, ContractCandidate candidate,
            List<ContractCandidateSupportAuthority> rows, int auditCount, int recordCount) {
        assertThat(f.current()).isEqualTo(candidate);
        assertThat(f.rows()).isEqualTo(rows);
        assertThat(f.audits).hasSize(auditCount);
        assertThat(f.idempotency.size()).isEqualTo(recordCount);
        assertThat(f.calls).doesNotContain("claim", "delete", "cas", "insert", "audit", "success");
        verify(f.idempotency, never()).markFailed(any(), any());
    }

    private static void expect(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            BusinessErrorCode code, String classification) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.errorCode()).isEqualTo(code);
            assertThat(error.getClass().getSimpleName()).isEqualTo("ContractCandidateSupportReplacementException");
            assertThat(error).extracting("classification").asString().isEqualTo(classification);
        });
    }

    /** Fault injection changes one persisted record field while preserving the complete record shape. */
    @SuppressWarnings("unchecked")
    private static <T extends Record> T copy(T value, Map<String, Object> changed) {
        try {
            RecordComponent[] fields = value.getClass().getRecordComponents();
            Object[] args = new Object[fields.length];
            for (int index = 0; index < fields.length; index++) {
                args[index] = changed.containsKey(fields[index].getName()) ? changed.get(fields[index].getName())
                        : fields[index].getAccessor().invoke(value);
            }
            return (T) value.getClass().getDeclaredConstructor(Arrays.stream(fields).map(RecordComponent::getType)
                    .toArray(Class<?>[]::new)).newInstance(args);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
