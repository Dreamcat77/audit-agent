package com.fuquan.audit.budget.application.ai;

import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort.BudgetSemanticAnalysisItem;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort.BudgetSemanticAnalysisRequest;
import com.fuquan.audit.budget.application.port.BudgetReviewItemRepository;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import java.util.Map;

@Service
public class BudgetSemanticAnalysisUseCase {

    private final BudgetReviewItemRepository items;
    private final BudgetSemanticAnalysisPort analysis;

    public BudgetSemanticAnalysisUseCase(BudgetReviewItemRepository items,
                                         BudgetSemanticAnalysisPort analysis) {
        this.items = items;
        this.analysis = analysis;
    }

    public BudgetSemanticAnalysisResult analyze(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return analyzeItems(taskId, PipelineDiagnostics.run("BUDGET", "DB_ITEMS_READ", taskId, Map.of(), () -> items.findByTaskId(taskId)));
    }

    /**
     * Analyzes only the supplied, already-persisted F01 items.
     */
    public BudgetSemanticAnalysisResult analyzeItems(
            UUID sourceId, List<BudgetReviewItem> sourceItems) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(sourceItems, "sourceItems must not be null");
        List<BudgetSemanticAnalysisItem> semanticItems = new ArrayList<>();
        for (BudgetReviewItem item : sourceItems) {
            if (!sourceId.equals(item.taskId())) {
                throw new BusinessException(BusinessErrorCode.CONFLICT,
                        "budget item ownership is inconsistent");
            }
            if (item.subjectName() == null || item.subjectName().isBlank()) {
                continue;
            }
            semanticItems.add(new BudgetSemanticAnalysisItem(
                    item.id(), item.subjectName(), currentCategory(item)));
        }
        if (semanticItems.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "budget task has no descriptions for semantic analysis");
        }
        return PipelineDiagnostics.run("BUDGET", "AI_ANALYSIS", sourceId,
                Map.of("itemCount", semanticItems.size(), "inputChars", semanticItems.stream().mapToLong(i -> i.description().length()).sum()),
                () -> analysis.analyzeBudgetSemantics(new BudgetSemanticAnalysisRequest(sourceId, semanticItems)));
    }

    public BudgetSemanticAnalysisResult analyzeItem(
            UUID sourceId, BudgetSemanticAnalysisItem item) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(item, "item must not be null");
        BudgetSemanticAnalysisResult result = PipelineDiagnostics.run("BUDGET", "AI_ROW_ANALYSIS", sourceId,
                Map.of("itemCount", 1, "inputChars", item.description().length()),
                () -> analysis.analyzeBudgetSemantics(new BudgetSemanticAnalysisRequest(sourceId, List.of(item))));
        return result;
    }

    private static String currentCategory(BudgetReviewItem item) {
        if (item.approvalSubjectName() != null && !item.approvalSubjectName().isBlank()) {
            return item.approvalSubjectName();
        }
        return item.categorySuggestion();
    }
}
