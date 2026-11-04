package com.fuquan.audit.integration.ai.fake;

import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewAiPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "local-dev & !local-ai"})
public class UnavailableProcurementAgentReviewAdapter implements ProcurementAgentReviewAiPort {
    @Override
    public Result analyze(Request request) {
        return Result.unavailable();
    }
}
