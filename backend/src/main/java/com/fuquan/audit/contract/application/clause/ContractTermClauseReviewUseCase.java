package com.fuquan.audit.contract.application.clause;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractTermClauseReviewUseCase {
    private final ContractClauseMaterialLoader source;
    private final ContractTermClauseAnalyzer analyzer;

    public ContractTermClauseReviewUseCase(ContractClauseMaterialLoader source, ContractTermClauseAnalyzer analyzer) {
        this.source = source;
        this.analyzer = analyzer;
    }

    @Transactional(readOnly = true)
    public ContractTermClauseReview review(UUID projectId, UUID contractId, List<UUID> selectedFileIds) {
        return analyzer.analyze(source.load(projectId, contractId, selectedFileIds).materials());
    }
}
