package com.fuquan.audit.integration.ai.fake;

import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort.BudgetSemanticAnalysisRequest;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisResult;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisRequest;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "local-dev & !local-ai"})
public class FakeAiReviewAdapter
        implements ContractTextAnalysisPort, BudgetSemanticAnalysisPort {

    @Override
    public ContractTextAnalysisResult analyzeContractText(ContractTextAnalysisRequest request) {
        return ContractTextAnalysisResult.unavailable();
    }

    @Override
    public BudgetSemanticAnalysisResult analyzeBudgetSemantics(BudgetSemanticAnalysisRequest request) {
        return BudgetSemanticAnalysisResult.unavailable();
    }

}
