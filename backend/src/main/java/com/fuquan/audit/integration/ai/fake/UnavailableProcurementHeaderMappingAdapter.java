package com.fuquan.audit.integration.ai.fake;

import com.fuquan.audit.procurement.application.port.ProcurementHeaderMappingAiPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "local-dev & !local-ai"})
public class UnavailableProcurementHeaderMappingAdapter implements ProcurementHeaderMappingAiPort {
    @Override
    public Result analyze(Request request) {
        return Result.unavailable();
    }
}
