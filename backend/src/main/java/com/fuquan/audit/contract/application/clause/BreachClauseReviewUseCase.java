package com.fuquan.audit.contract.application.clause;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BreachClauseReviewUseCase {
    private final ContractClauseMaterialLoader source;
    private final BreachClauseAnalyzer analyzer;

    public BreachClauseReviewUseCase(ContractClauseMaterialLoader source, BreachClauseAnalyzer analyzer) {
        this.source = source;
        this.analyzer = analyzer;
    }

    @Transactional(readOnly = true)
    public BreachClauseReview review(UUID projectId, UUID contractId, List<UUID> selectedFileIds) {
        ContractClauseMaterialLoader.Source loaded = source.load(projectId, contractId, selectedFileIds);
        return analyzer.analyze(loaded.contract().contractAmount().amount(), loaded.materials());
    }
}
