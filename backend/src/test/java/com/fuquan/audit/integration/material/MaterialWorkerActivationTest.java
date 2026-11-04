package com.fuquan.audit.integration.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fuquan.audit.budget.application.BudgetMaterialService;
import com.fuquan.audit.budget.application.BudgetMaterialWorker;
import com.fuquan.audit.budget.application.port.BudgetMaterialJobRepository;
import com.fuquan.audit.common.material.application.MaterialIntakeService;
import com.fuquan.audit.common.material.application.MaterialIntakeWorker;
import com.fuquan.audit.common.material.application.port.MaterialIntakeRepository;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MaterialWorkerActivationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(MaterialIntakeRepository.class, () -> mock(MaterialIntakeRepository.class))
            .withBean(MaterialIntakeService.class, () -> mock(MaterialIntakeService.class))
            .withBean(BudgetMaterialJobRepository.class, () -> mock(BudgetMaterialJobRepository.class))
            .withBean(BudgetMaterialService.class, () -> mock(BudgetMaterialService.class))
            .withBean(UserAccountRoleQueryPort.class, () -> mock(UserAccountRoleQueryPort.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(MaterialIntakeWorker.class, BudgetMaterialWorker.class);

    @Test
    void schemaDependentWorkersAreDisabledByDefault() {
        context.run(result -> {
            assertThat(result).doesNotHaveBean(MaterialIntakeWorker.class);
            assertThat(result).doesNotHaveBean(BudgetMaterialWorker.class);
        });
    }

    @Test
    void eachWorkerRequiresItsOwnExplicitEnableFlag() {
        context.withPropertyValues("audit.procurement.material.worker-enabled=true")
                .run(result -> {
                    assertThat(result).hasSingleBean(MaterialIntakeWorker.class);
                    assertThat(result).doesNotHaveBean(BudgetMaterialWorker.class);
                });

        context.withPropertyValues("audit.budget.material.worker-enabled=true")
                .run(result -> {
                    assertThat(result).doesNotHaveBean(MaterialIntakeWorker.class);
                    assertThat(result).hasSingleBean(BudgetMaterialWorker.class);
                });
    }
}
