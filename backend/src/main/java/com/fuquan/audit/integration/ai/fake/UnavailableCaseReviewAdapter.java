package com.fuquan.audit.integration.ai.fake;
import com.fuquan.audit.procurement.autocase.CaseReviewAiPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
@Component @Profile({"local","local-dev & !local-ai"})
public class UnavailableCaseReviewAdapter implements CaseReviewAiPort {
    public Result analyze(Request request){throw new IllegalStateException("CASE_REVIEW_MODEL_NOT_CONFIGURED");}
}
