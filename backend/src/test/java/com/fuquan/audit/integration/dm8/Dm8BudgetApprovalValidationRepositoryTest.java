package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.budget.domain.BudgetApprovalValidation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dm8BudgetApprovalValidationRepositoryTest {

    @Mock private BudgetApprovalValidationMapper mapper;

    @Test
    void persistsCanonicalStepSnapshotsAndSmallintFlags() {
        when(mapper.insert(any())).thenReturn(1);
        Dm8BudgetApprovalValidationRepository repository =
                new Dm8BudgetApprovalValidationRepository(mapper);
        BudgetApprovalValidation validation = new BudgetApprovalValidation(
                UUID.randomUUID(),
                "PM_DEMO_20260828",
                UUID.randomUUID(),
                UUID.randomUUID(),
                2,
                new BigDecimal("50000.00"),
                "MANUAL_TEST",
                List.of("REQUEST_APPROVAL", "GENERAL_BRANCH_REVIEW"),
                "FINANCE_REVIEW",
                List.of("REQUEST_APPROVAL", "FINANCE_REVIEW", "GENERAL_BRANCH_REVIEW"),
                List.of(),
                true,
                true,
                true,
                "审批程序符合当前规则集",
                "00000000-0000-0000-0000-000000000001",
                Instant.parse("2026-08-28T10:00:00Z"));

        repository.create(validation);

        ArgumentCaptor<BudgetApprovalValidationPersistenceRow> captor =
                ArgumentCaptor.forClass(BudgetApprovalValidationPersistenceRow.class);
        verify(mapper).insert(captor.capture());
        BudgetApprovalValidationPersistenceRow row = captor.getValue();
        assertThat(row.requiredProcedureSteps())
                .isEqualTo("REQUEST_APPROVAL|GENERAL_BRANCH_REVIEW");
        assertThat(row.actualSteps())
                .isEqualTo("REQUEST_APPROVAL|FINANCE_REVIEW|GENERAL_BRANCH_REVIEW");
        assertThat(row.missingProcedureSteps()).isEmpty();
        assertThat(row.procedureOrderValid()).isEqualTo(1);
        assertThat(row.authorityMatched()).isEqualTo(1);
        assertThat(row.passed()).isEqualTo(1);
        assertThat(row.actualSource()).isEqualTo("MANUAL_TEST");
    }
}
