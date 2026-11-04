package com.fuquan.audit.budget.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort.BudgetSemanticAnalysisRequest;
import com.fuquan.audit.budget.application.port.BudgetReviewItemRepository;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BudgetSemanticAnalysisUseCaseTest {

    @Test
    void sendsDescriptionsAndCategoriesWithoutAnyMonetaryFields() {
        UUID taskId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        BudgetReviewItemRepository items = mock(BudgetReviewItemRepository.class);
        when(items.findByTaskId(taskId)).thenReturn(List.of(new BudgetReviewItem(
                itemId, taskId, 1, "彩色打印设备", new BigDecimal("999.99"),
                "办公设备", new BigDecimal("888.88"), "MATCHED", "EXACT", 0,
                "DIFFERENT", new BigDecimal("111.11"), null, null,
                Instant.parse("2026-08-31T00:00:00Z"))));
        RecordingPort port = new RecordingPort();

        BudgetSemanticAnalysisResult result = new BudgetSemanticAnalysisUseCase(items, port)
                .analyze(taskId);

        assertThat(result.available()).isTrue();
        assertThat(port.request.sourceId()).isEqualTo(taskId);
        assertThat(port.request.items()).singleElement().satisfies(item -> {
            assertThat(item.itemId()).isEqualTo(itemId);
            assertThat(item.description()).isEqualTo("彩色打印设备");
            assertThat(item.currentCategory()).isEqualTo("办公设备");
            assertThat(Arrays.stream(item.getClass().getRecordComponents())
                    .map(component -> component.getName().toLowerCase()))
                    .noneMatch(name -> name.contains("amount") || name.contains("price")
                            || name.contains("quantity") || name.contains("difference"));
        });
    }

    private static final class RecordingPort implements BudgetSemanticAnalysisPort {
        private BudgetSemanticAnalysisRequest request;

        @Override
        public BudgetSemanticAnalysisResult analyzeBudgetSemantics(BudgetSemanticAnalysisRequest request) {
            this.request = request;
            return new BudgetSemanticAnalysisResult(true, List.of(), "qwen-test", 5L);
        }
    }
}
