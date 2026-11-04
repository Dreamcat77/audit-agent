package com.fuquan.audit.integration.ai.fake;

import com.fuquan.audit.procurement.application.port.ProcurementTenderReviewAiPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "local-dev & !local-ai"})
public class UnavailableProcurementTenderReviewAdapter implements ProcurementTenderReviewAiPort {

    @Override
    public Result analyze(Request request) {
        throw new UnavailableException(
                new IllegalStateException("local fake AI does not implement tender review"));
    }
}
