package com.fuquan.audit.integration.ai.fake;
import com.fuquan.audit.contract.application.analysis.ContractAnalysisAiPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile({"local","local-dev & !local-ai"})
public class UnavailableContractAnalysisAdapter implements ContractAnalysisAiPort {
    public Result analyze(Request request){throw new IllegalStateException("CONTRACT_ANALYSIS_MODEL_NOT_CONFIGURED");}
}
