package com.fuquan.audit.procurement.autocase;
import com.fuquan.audit.AuditApplication;
import com.fuquan.audit.integration.ai.fastapi.FastApiCaseReviewAdapter;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestClient;

/** Isolated DM8 acceptance only. Real and Mock modes are deliberately separate. */
public class AutomaticCasePhase3Harness {
    public static void main(String[] args) {
        if(!"ISOLATED_AUTOCASE2".equals(System.getenv("AUDIT_CASE_TEST_DB_CONFIRM")))throw new IllegalStateException("isolated database required");
        new SpringApplicationBuilder(AuditApplication.class,AutomaticCaseDm8Harness.AcceptanceConfiguration.class,Reviews.class).profiles("local")
                .properties(Map.of("server.address","127.0.0.1","server.port","19131","audit.procurement.auto-case.enabled","true",
                        "audit.procurement.auto-case.scan-delay-ms","500","logging.level.com.fuquan.audit.common.observability.FlowDebugLogger","INFO")).run(args);
    }
    @TestConfiguration public static class Reviews {
        @Bean @Primary CaseReviewAiPort acceptanceReviewAi() {
            if("actual".equals(System.getenv("AUDIT_CASE_TEST_MODEL_MODE")))return new FastApiCaseReviewAdapter(RestClient.builder().baseUrl(System.getenv("AUDIT_CASE_REAL_AI_URL")).build());
            return input->{
                String gate=System.getenv("AUDIT_CASE_TEST_REVIEW_GATE");
                if(gate!=null&&input.module().equals("AGENT_PRECHECK"))try {
                    while(!Files.exists(Path.of(gate)))Thread.sleep(100);
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return CaseReviewFixtures.result(input);
            };
        }
    }
}
