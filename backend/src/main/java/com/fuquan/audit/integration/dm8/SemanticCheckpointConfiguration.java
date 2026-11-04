package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.reviewrun.semantic.*;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.core.env.Profiles;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit opt-in wiring for the two-table semantic executor. */
@Configuration(proxyBeanMethods = false)
@Profile({"local-dev", "dev29", "customer"})
@ConditionalOnProperty(name = "audit.contract.semantic-checkpoints.enabled", havingValue = "true", matchIfMissing = false)
public class SemanticCheckpointConfiguration {
    @Bean
    SemanticCheckpointSchemaPreflight semanticCheckpointSchemaPreflight(JdbcTemplate jdbc) {
        var preflight = new SemanticCheckpointSchemaPreflight(jdbc);
        preflight.verify();
        return preflight;
    }
    @Bean
    SemanticCheckpointCoordinator semanticCheckpointCoordinator(SemanticCheckpointSchemaPreflight verifiedSchema,
            SemanticCheckpointRepository repository, ContractReviewRunRepository runs,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            ContractModelCallAuditRepository calls, Clock clock) {
        return new SemanticCheckpointCoordinator(repository, runs, steps, attempts, calls, clock);
    }
    @Bean
    DurableSemanticExecution durableSemanticExecution(SemanticCheckpointCoordinator checkpoints,
            SemanticWorkPort work, ObjectMapper json, Clock clock,
            org.springframework.core.env.Environment environment,
            @org.springframework.beans.factory.annotation.Value("${audit.contract.semantic-checkpoints.local-boundary-proof:}") String proof,
            @org.springframework.beans.factory.annotation.Value("${AUDIT_CONTRACT_SEMANTIC_OBSERVE_WINDOW_PATH:}") String windowPath) {
        SemanticCheckpointBoundary boundary=SemanticCheckpointBoundary.NONE;
        if(!proof.isBlank()) {
            if(!environment.acceptsProfiles(Profiles.of("local-dev"))
                    || environment.acceptsProfiles(Profiles.of("dev29","customer")))
                throw new IllegalArgumentException("semantic acceptance boundary is local-dev only");
            boundary=new com.fuquan.audit.integration.file.LocalSemanticCheckpointBoundary(
                    java.nio.file.Path.of(proof),json,clock,java.time.Duration.ofSeconds(90));
        }
        SemanticObserveWindowPort window = null;
        if (!windowPath.isBlank()) {
            if (!environment.acceptsProfiles(Profiles.of("local-dev"))
                    || environment.acceptsProfiles(Profiles.of("dev29", "customer")))
                throw new IllegalArgumentException("semantic observe window is local-dev only");
            window = new com.fuquan.audit.integration.file.LocalSemanticObserveWindow(java.nio.file.Path.of(windowPath), json);
        }
        return new DurableSemanticExecution(checkpoints, work, json, clock, boundary, window);
    }
}
