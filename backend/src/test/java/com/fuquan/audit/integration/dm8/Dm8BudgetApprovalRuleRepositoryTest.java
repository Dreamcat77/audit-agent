package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dm8BudgetApprovalRuleRepositoryTest {

    @Mock private BudgetApprovalRuleMapper mapper;

    @Test
    void loadsInactiveRulesWhenResolvingAPersistedRuleSetSnapshot() {
        BudgetApprovalRulePersistenceRow inactive = new BudgetApprovalRulePersistenceRow(
                "62000000-0000-0000-0000-000000000001",
                "RULE_SET_OLD",
                "PROCEDURE_REQUIREMENT",
                "CUMULATIVE_MIN",
                new BigDecimal("10000.00"),
                null,
                1,
                0,
                "REQUEST_APPROVAL",
                "请示报批",
                10,
                "INACTIVE",
                1L,
                LocalDateTime.parse("2026-08-28T10:00:00"),
                LocalDateTime.parse("2026-08-28T10:00:00"));
        when(mapper.findByRuleSetCode("RULE_SET_OLD")).thenReturn(List.of(inactive));
        Dm8BudgetApprovalRuleRepository repository = new Dm8BudgetApprovalRuleRepository(mapper);

        var restored = repository.findByRuleSetCode("RULE_SET_OLD");

        assertThat(restored).singleElement().satisfies(rule -> {
            assertThat(rule.status()).isEqualTo("INACTIVE");
            assertThat(rule.step().displayName()).isEqualTo("请示报批");
        });
        verify(mapper).findByRuleSetCode("RULE_SET_OLD");
    }
}
