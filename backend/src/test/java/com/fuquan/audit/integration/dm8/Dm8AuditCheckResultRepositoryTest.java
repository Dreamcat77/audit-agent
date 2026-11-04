package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dm8AuditCheckResultRepositoryTest {

    @Mock
    private AuditCheckResultMapper mapper;

    @Test
    void appendAllExposesInsertOnlyResultPersistence() {
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        Dm8AuditCheckResultRepository repository = new Dm8AuditCheckResultRepository(mapper);
        AuditCheckResult result = result();

        repository.appendAll(List.of(result));

        ArgumentCaptor<AuditCheckResultPersistenceRow> captor =
                ArgumentCaptor.forClass(AuditCheckResultPersistenceRow.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().checkStatus()).isEqualTo("FAIL");
        assertThat(captor.getValue().riskLevel()).isEqualTo("RED");
        assertThat(captor.getValue().blockSign()).isEqualTo(1);
    }

    @Test
    void findMapsStrongStatusAndRiskTypes() {
        AuditCheckResult result = result();
        when(mapper.findByTaskId(result.taskId().toString())).thenReturn(List.of(
                new AuditCheckResultPersistenceRow(
                        result.id().toString(), result.taskId().toString(), result.ruleCode(), null,
                        "FAIL", "RED", 1, "100", "99", "mismatch", 1,
                        LocalDateTime.parse("2026-08-17T00:00:00"))));
        Dm8AuditCheckResultRepository repository = new Dm8AuditCheckResultRepository(mapper);

        assertThat(repository.findByTaskId(result.taskId())).singleElement().satisfies(mapped -> {
            assertThat(mapped.checkStatus()).isEqualTo(CheckStatus.FAIL);
            assertThat(mapped.riskLevel()).isEqualTo(RiskLevel.RED);
            assertThat(mapped.blockSign()).isTrue();
        });
    }

    @Test
    void findsResultByItsSemanticId() {
        AuditCheckResult result = result();
        when(mapper.findById(result.id().toString())).thenReturn(new AuditCheckResultPersistenceRow(
                result.id().toString(), result.taskId().toString(), result.ruleCode(), null,
                "FAIL", "RED", 1, "100", "99", "mismatch", 1,
                LocalDateTime.parse("2026-08-17T00:00:00")));
        Dm8AuditCheckResultRepository repository = new Dm8AuditCheckResultRepository(mapper);

        assertThat(repository.findById(result.id())).hasValueSatisfying(found ->
                assertThat(found.id()).isEqualTo(result.id()));
    }

    private AuditCheckResult result() {
        return new AuditCheckResult(
                UUID.randomUUID(), UUID.randomUUID(), "F02_LINE_SUM_MATCH", null,
                CheckStatus.FAIL, RiskLevel.RED, true, "100", "99", "mismatch", 1,
                Instant.parse("2026-08-17T00:00:00Z"));
    }
}
