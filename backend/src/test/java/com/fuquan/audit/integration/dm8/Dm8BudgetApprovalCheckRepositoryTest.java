package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fuquan.audit.budget.domain.BudgetCheckResult;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dm8BudgetApprovalCheckRepositoryTest {

    @Mock
    private BudgetApprovalCheckMapper mapper;

    @Test
    void mapsAllF01ResultsIncludingBooleanPassStateBeforeBatchInsert() {
        BudgetCheckResult passed = BudgetCheckResult.pass(
                "00000000-0000-0000-0000-000000000101",
                "SUBJECT_CODE",
                "M1001",
                "M1001",
                "M1001",
                "校验通过");
        BudgetCheckResult failed = BudgetCheckResult.fail(
                "00000000-0000-0000-0000-000000000101",
                "BUDGET_AMOUNT",
                null,
                "150000.00",
                null,
                "实际金额为空");

        Dm8BudgetApprovalCheckRepository repository = new Dm8BudgetApprovalCheckRepository(
                mapper,
                new FlowDebugLogger(new ObservabilityProperties()));

        repository.saveAll(List.of(passed, failed));

        ArgumentCaptor<List<BudgetApprovalCheckPersistenceRow>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(mapper).insertBatch(captor.capture());
        List<BudgetApprovalCheckPersistenceRow> rows = captor.getValue();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getPassed()).isTrue();
        assertThat(rows.get(1).getPassed()).isFalse();
        assertThat(rows.get(1).getExpectedValue()).isEqualTo("150000.00");
        assertThat(rows.get(1).getActualValue()).isNull();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getId()).isNotBlank();
            assertThat(row.getProcurementId())
                    .isEqualTo("00000000-0000-0000-0000-000000000101");
            assertThat(row.getCheckType()).isNotBlank();
            assertThat(row.getErrorDetail()).isNotBlank();
            assertThat(row.getCheckTime()).isNotNull();
        });
    }
}
