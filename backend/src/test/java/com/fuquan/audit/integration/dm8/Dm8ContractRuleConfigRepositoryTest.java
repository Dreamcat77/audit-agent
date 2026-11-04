package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fuquan.audit.review.domain.RiskLevel;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dm8ContractRuleConfigRepositoryTest {

    @Mock
    private ContractRuleConfigMapper mapper;

    @Test
    void mapsEnabledRuleMetadataWithoutDynamicExpressions() {
        when(mapper.findEnabledF02()).thenReturn(List.of(new ContractRuleConfigPersistenceRow(
                "F02_AWARD_AMOUNT_MATCH", "Award amount", 1, "RED", 1, 3,
                "Deterministic equality rule", LocalDateTime.parse("2026-08-17T00:00:00"))));
        Dm8ContractRuleConfigRepository repository = new Dm8ContractRuleConfigRepository(mapper);

        assertThat(repository.findEnabledForF02()).singleElement().satisfies(configuration -> {
            assertThat(configuration.enabled()).isTrue();
            assertThat(configuration.failLevel()).isEqualTo(RiskLevel.RED);
            assertThat(configuration.blockSign()).isTrue();
            assertThat(configuration.versionNo()).isEqualTo(3);
        });
    }
}
