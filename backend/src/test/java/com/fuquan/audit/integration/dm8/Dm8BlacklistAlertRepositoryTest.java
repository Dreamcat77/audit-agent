package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class Dm8BlacklistAlertRepositoryTest {

    @Test
    void identifiesCreationFromDurableIdentityInsteadOfMergeRowCount() {
        BlacklistAlertMapper mapper = mock(BlacklistAlertMapper.class);
        AtomicReference<BlacklistAlertPersistenceRow> durable = new AtomicReference<>();
        doAnswer(invocation -> {
            durable.set(invocation.getArgument(0));
            return -1;
        }).when(mapper).insertIfAbsent(any());
        when(mapper.findByBatchAndSupplier("B-1", "供应商甲"))
                .thenAnswer(invocation -> durable.get());
        Dm8BlacklistAlertRepository repository = new Dm8BlacklistAlertRepository(
                mapper, mock(FlowDebugLogger.class));
        BlacklistAlert candidate = alert(UUID.randomUUID(), "B-1", "供应商甲");

        var result = repository.saveIfAbsent(candidate);

        assertThat(result.created()).isTrue();
        assertThat(result.alert().id()).isEqualTo(candidate.id());
    }

    @Test
    void returnsExistingDurableAlertWhenAnotherRequestWonTheRace() {
        BlacklistAlertMapper mapper = mock(BlacklistAlertMapper.class);
        BlacklistAlert existing = alert(UUID.randomUUID(), "B-2", "供应商乙");
        when(mapper.insertIfAbsent(any())).thenThrow(new DuplicateKeyException("concurrent insert"));
        when(mapper.findByBatchAndSupplier("B-2", "供应商乙")).thenReturn(row(existing));
        Dm8BlacklistAlertRepository repository = new Dm8BlacklistAlertRepository(
                mapper, mock(FlowDebugLogger.class));

        var result = repository.saveIfAbsent(alert(UUID.randomUUID(), "B-2", "供应商乙"));

        assertThat(result.created()).isFalse();
        assertThat(result.alert().id()).isEqualTo(existing.id());
    }

    @Test
    void failsClosedWhenDurableOutcomeCannotBeRediscovered() {
        BlacklistAlertMapper mapper = mock(BlacklistAlertMapper.class);
        Dm8BlacklistAlertRepository repository = new Dm8BlacklistAlertRepository(
                mapper, mock(FlowDebugLogger.class));

        assertThatThrownBy(() -> repository.saveIfAbsent(
                alert(UUID.randomUUID(), "B-3", "供应商丙")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法确认");
    }

    private static BlacklistAlert alert(UUID id, String batchId, String supplierName) {
        return new BlacklistAlert(id, batchId, 1, null, supplierName, null, UUID.randomUUID(),
                supplierName, "本单位黑名单", "严重违规", "EXACT", 100, "CRITICAL",
                "PENDING", null, null, null, Instant.parse("2026-09-29T00:00:00Z"));
    }

    private static BlacklistAlertPersistenceRow row(BlacklistAlert alert) {
        BlacklistAlertPersistenceRow row = new BlacklistAlertPersistenceRow();
        row.setId(alert.id().toString());
        row.setBatchId(alert.batchId());
        row.setRowNumber(alert.rowNumber());
        row.setSupplierName(alert.supplierName());
        row.setBlacklistId(alert.blacklistId().toString());
        row.setMatchedName(alert.matchedName());
        row.setBlacklistType(alert.blacklistType());
        row.setBlacklistReason(alert.blacklistReason());
        row.setMatchType(alert.matchType());
        row.setMatchScore(BigDecimal.valueOf(alert.matchScore()));
        row.setAlertLevel(alert.alertLevel());
        row.setStatus(alert.status());
        row.setCreatedTime(LocalDateTime.ofInstant(alert.createdTime(), ZoneOffset.UTC));
        return row;
    }
}
