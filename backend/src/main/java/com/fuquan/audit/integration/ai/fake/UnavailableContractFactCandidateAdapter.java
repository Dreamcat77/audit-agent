package com.fuquan.audit.integration.ai.fake;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "local-dev & !local-ai"})
public class UnavailableContractFactCandidateAdapter implements ContractFactCandidatePort {
    private static final Provenance PROVENANCE = new Provenance("DISABLED", "not-configured",
            "not-configured", "contract-fact-prompt-v2", "contract-fact-candidates-v2");

    @Override public Provenance configuredProvenance() { return PROVENANCE; }

    @Override public Result extract(Request request) {
        throw new IllegalStateException("contract fact extraction provider is unavailable");
    }
}
