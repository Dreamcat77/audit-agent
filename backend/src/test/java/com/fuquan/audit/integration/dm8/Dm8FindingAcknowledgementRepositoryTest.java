package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.review.domain.FindingAcknowledgement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class Dm8FindingAcknowledgementRepositoryTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID RESULT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID ACKNOWLEDGEMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-08-25T08:15:30.123Z");

    @Mock
    private FindingAcknowledgementMapper mapper;

    private Dm8FindingAcknowledgementRepository repository;

    @BeforeEach
    void setUp() {
        repository = new Dm8FindingAcknowledgementRepository(mapper);
    }

    @Test
    void createInsertsEveryImmutableFactFieldExactlyOnce() {
        FindingAcknowledgement acknowledgement = acknowledgement(
                ACKNOWLEDGEMENT_ID, RESULT_ID, "actor-as-string", ACKNOWLEDGED_AT, 42);
        when(mapper.insert(any())).thenReturn(1);

        repository.create(acknowledgement);

        ArgumentCaptor<FindingAcknowledgementPersistenceRow> captor =
                ArgumentCaptor.forClass(FindingAcknowledgementPersistenceRow.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new FindingAcknowledgementPersistenceRow(
                ACKNOWLEDGEMENT_ID.toString(),
                RESULT_ID.toString(),
                "actor-as-string",
                LocalDateTime.parse("2026-08-25T08:15:30.123"),
                "request-123",
                "trace-456",
                42));
    }

    @Test
    void findByCheckResultIdRoundTripsUuidActorTimestampAndRuleVersion() {
        FindingAcknowledgementPersistenceRow row = row(
                ACKNOWLEDGEMENT_ID, RESULT_ID, "actor-as-string", ACKNOWLEDGED_AT, 42);
        when(mapper.findByCheckResultId(RESULT_ID.toString())).thenReturn(row);

        assertThat(repository.findByCheckResultId(RESULT_ID))
                .contains(acknowledgement(
                        ACKNOWLEDGEMENT_ID, RESULT_ID, "actor-as-string", ACKNOWLEDGED_AT, 42));
    }

    @Test
    void findByCheckResultIdReturnsEmptyWhenNoDurableRowExists() {
        when(mapper.findByCheckResultId(RESULT_ID.toString())).thenReturn(null);

        assertThat(repository.findByCheckResultId(RESULT_ID)).isEmpty();
    }

    @Test
    void verifiedResultUniquenessCollisionMapsToConflictWithoutDatabaseDetail() {
        FindingAcknowledgement acknowledgement = acknowledgement(
                ACKNOWLEDGEMENT_ID, RESULT_ID, "actor-as-string", ACKNOWLEDGED_AT, 42);
        DuplicateKeyException duplicate = new DuplicateKeyException("synthetic raw database detail");
        when(mapper.insert(any())).thenThrow(duplicate);
        when(mapper.findByCheckResultId(RESULT_ID.toString())).thenReturn(row(
                UUID.fromString("00000000-0000-0000-0000-0000000000dd"),
                RESULT_ID,
                "first-actor",
                ACKNOWLEDGED_AT.minusSeconds(1),
                41));

        assertThatThrownBy(() -> repository.create(acknowledgement))
                .isInstanceOfSatisfying(BusinessException.class, conflict -> {
                    assertThat(conflict.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT);
                    assertThat(conflict.getMessage()).isEqualTo("finding acknowledgement already exists");
                    assertThat(conflict.getMessage()).doesNotContain("synthetic raw database detail");
                });
        verify(mapper).findByCheckResultId(RESULT_ID.toString());
    }

    @Test
    void unknownDuplicateKeyIsRethrownWhenCandidateResultHasNoAcknowledgement() {
        FindingAcknowledgement acknowledgement = acknowledgement(
                ACKNOWLEDGEMENT_ID, RESULT_ID, "actor-as-string", ACKNOWLEDGED_AT, 42);
        DuplicateKeyException duplicate = new DuplicateKeyException("synthetic unknown duplicate");
        when(mapper.insert(any())).thenThrow(duplicate);
        when(mapper.findByCheckResultId(RESULT_ID.toString())).thenReturn(null);

        assertThatThrownBy(() -> repository.create(acknowledgement)).isSameAs(duplicate);
        verify(mapper).findByCheckResultId(RESULT_ID.toString());
    }

    @Test
    void failedConflictVerificationPreservesTheOriginalUnknownDuplicate() {
        FindingAcknowledgement acknowledgement = acknowledgement(
                ACKNOWLEDGEMENT_ID, RESULT_ID, "actor-as-string", ACKNOWLEDGED_AT, 42);
        DuplicateKeyException duplicate = new DuplicateKeyException("synthetic unknown duplicate");
        when(mapper.insert(any())).thenThrow(duplicate);
        when(mapper.findByCheckResultId(RESULT_ID.toString()))
                .thenThrow(new DataRetrievalFailureException("synthetic verification failure"));

        assertThatThrownBy(() -> repository.create(acknowledgement)).isSameAs(duplicate);
        verify(mapper).findByCheckResultId(RESULT_ID.toString());
    }

    @Test
    void findByTaskIdMapsSemanticIdsAndOrdersByTimestampThenAcknowledgementId() {
        Instant sameTime = Instant.parse("2026-08-25T08:15:30Z");
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID laterId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID firstResult = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID secondResult = UUID.fromString("20000000-0000-0000-0000-000000000002");
        UUID thirdResult = UUID.fromString("30000000-0000-0000-0000-000000000003");
        when(mapper.findByTaskId(TASK_ID.toString())).thenReturn(List.of(
                row(laterId, thirdResult, "later-actor", sameTime.plusSeconds(1), 3),
                row(higherId, secondResult, "higher-actor", sameTime, 2),
                row(lowerId, firstResult, "lower-actor", sameTime, 1)));

        assertThat(repository.findByTaskId(TASK_ID)).containsExactly(
                acknowledgement(lowerId, firstResult, "lower-actor", sameTime, 1),
                acknowledgement(higherId, secondResult, "higher-actor", sameTime, 2),
                acknowledgement(laterId, thirdResult, "later-actor", sameTime.plusSeconds(1), 3));
    }

    private FindingAcknowledgement acknowledgement(
            UUID id, UUID resultId, String actor, Instant acknowledgedAt, long ruleVersion) {
        return new FindingAcknowledgement(
                id, resultId, actor, acknowledgedAt, "request-123", "trace-456", ruleVersion);
    }

    private FindingAcknowledgementPersistenceRow row(
            UUID id, UUID resultId, String actor, Instant acknowledgedAt, long ruleVersion) {
        return new FindingAcknowledgementPersistenceRow(
                id.toString(),
                resultId.toString(),
                actor,
                LocalDateTime.ofInstant(acknowledgedAt, java.time.ZoneOffset.UTC),
                "request-123",
                "trace-456",
                ruleVersion);
    }
}
