package com.fuquan.audit.procurement.autocase;
import java.util.*;
/** Explicit Mock protocol fixture, not a semantic review or production fallback. */
public final class CaseReviewFixtures {
    private CaseReviewFixtures() {}
    public static CaseReviewAiPort.Result result(CaseReviewAiPort.Request input) {
        var codes=input.module().equals("AGENT_PRECHECK")?List.of("IDENTITY","BUDGET_VS_AWARD","QUANTITY","DATE_AND_PLAN")
                :List.of("TECHNICAL_REQUIREMENTS","SCORING_METHOD","PAYMENT_CLARIFICATION");
        var ids=input.evidence().isEmpty()?List.<String>of():List.of(input.evidence().get(0).id());
        return new CaseReviewAiPort.Result("MOCK_CASE_REVIEW",input.version(),true,List.of(),
                codes.stream().map(c->new CaseReviewAiPort.Observation(c,"Mock协议测试：仅验证执行与引用契约，不代表语义审查通过",ids)).toList());
    }
}
