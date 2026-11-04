package com.fuquan.audit.contract.application.closeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BusinessReviewOverallOpinionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void oneParagraphKeepsExactDeterministicDifferenceAndRiskThemesWithoutPromotingModelMoney() {
        var projection = CloseoutCanonical.tree(CloseoutCanonical.json(BusinessReviewProjection.fromFacts("""
            {"runId":"r","runStatus":"COMPLETED","materials":[],
             "monetaryObservation":{"protocol":"contract-money-observation-v1","mode":"OBSERVE",
               "warnings":[{"fieldPath":"hints[0].description","errorKind":"MONETARY_VALUE",
                 "category":"DELIVERY_TERMS","textSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "ruleSubtypes":["ABSOLUTE_VALUE"]}],"workWarnings":[]},
             "findings":[{"stepType":"F02_DETERMINISTIC","status":"COMPLETED","findings":[],"evidence":[],
                "checks":[{"id":"00000000-0000-0000-0000-000000000001","ruleCode":"F02_AWARD_AMOUNT_MATCH",
                  "checkStatus":"FAIL","actualValue":"69000.00","expectedValue":"67000.00","detail":"金额不一致"}]},
               {"stepType":"TERM","status":"COMPLETED","checks":[],"evidence":[],"findings":[]},
               {"stepType":"SEMANTIC_AI","status":"COMPLETED","checks":[],"evidence":[],"findings":[
                 {"findingId":"model-risk","ordinal":1,"category":"DELIVERY_TERMS","attentionCode":"REVIEW",
                  "source":"MODEL_ASSISTED","summary":"验收付款安排","recommendation":"验收前应支付99999元，需核对付款条件"}]}]}
            """)));
        String opinion = projection.path("overallOpinion").asText();
        assertThat(opinion).isNotBlank().doesNotContain("\n", "99999", "MODEL_ASSISTED", "MONETARY_VALUE")
                .contains("69,000", "67,000", "2,000", "验收", "付款", "核对");
        assertThat(projection.path("monetaryObservation").path("mode").asText()).isEqualTo("OBSERVE");
        assertThat(projection.path("categories").toString()).contains("99999", "model-risk");
    }

    @Test void versionFourHtmlFreezesTheSameParagraphModeAndWarningSubtypes() throws Exception {
        var snapshot = json.createObjectNode().put("schemaVersion", 3).put("contractName", "合成合同");
        var projection = snapshot.putObject("businessProjection").put("version", 1).put("opinionVersion", 4)
                .put("runStatus", "COMPLETED").put("scopeStatement", "冻结范围")
                .put("overallAdvice", "旧建议不应代替本段").put("overallOpinion", "冻结综合审核意见：需核对验收付款条件。");
        var observation = projection.putObject("monetaryObservation").put("protocol", "contract-money-observation-v1").put("mode", "OBSERVE");
        observation.putArray("workWarnings");
        observation.putArray("warnings").addObject().put("fieldPath", "hints[0].description")
                .put("errorKind", "MONETARY_VALUE").put("category", "DELIVERY_TERMS")
                .put("textSha256", "a".repeat(64)).putArray("ruleSubtypes").add("ABSOLUTE_VALUE");
        var categories = projection.putArray("categories");
        for (int i = 1; i <= 8; i++) {
            var category = categories.addObject().put("order", i).put("title", "合同条款").put("statusLabel", "需核对")
                    .put("summary", "冻结明细").put("conciseOpinion", "冻结简版");
            category.putArray("items"); category.putArray("uncoveredScopes");
        }
        String html = new String(org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                new DeterministicHtmlReportRenderer().render(snapshot.toString())).bytes(), StandardCharsets.UTF_8);
        assertThat(html).contains("意见版本：4", "冻结综合审核意见：需核对验收付款条件。", "联调参考意见", "ABSOLUTE_VALUE")
                .doesNotContain("旧建议不应代替本段");
        projection.remove("monetaryObservation");
        assertThatThrownBy(() -> new DeterministicHtmlReportRenderer().render(snapshot.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unverifiedArithmeticOperandsStayInDetailAndDoNotBecomeNumericFactsInTheOverallOpinion() {
        var projection = CloseoutCanonical.tree(CloseoutCanonical.json(BusinessReviewProjection.fromFacts("""
            {"runId":"r","runStatus":"COMPLETED","materials":[],
             "monetaryObservation":{"protocol":"contract-money-observation-v1","mode":"OBSERVE",
              "warnings":[{"fieldPath":"hints[0].description","errorKind":"MONETARY_CALCULATION","category":"DELIVERY_TERMS",
                "textSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ruleSubtypes":["ARITHMETIC"]}],"workWarnings":[]},
             "findings":[{"stepType":"TERM","status":"COMPLETED","checks":[],"evidence":[],"findings":[]},
              {"stepType":"SEMANTIC_AI","status":"COMPLETED","checks":[],"evidence":[],"findings":[
                {"findingId":"arithmetic-risk","ordinal":1,"category":"DELIVERY_TERMS","attentionCode":"REVIEW","source":"MODEL_ASSISTED",
                 "summary":"验收付款安排","recommendation":"支付金额按1000×30=30000计算，验收前付款，需核对适用条件"}]}]}
            """)));
        assertThat(projection.path("overallOpinion").asText()).doesNotContain("1000", "30000")
                .contains("验收", "付款", "核对");
        assertThat(projection.path("categories").toString()).contains("1000×30=30000");
    }

    @Test void overallOpinionLocalizesFixedRuleCodesWhilePreservingARealModelQuotedBrandName() {
        var projection = CloseoutCanonical.tree(CloseoutCanonical.json(BusinessReviewProjection.fromFacts("""
            {"runId":"r","runStatus":"COMPLETED","materials":[],"findings":[
              {"stepType":"BREACH","status":"COMPLETED","checks":[],"evidence":[],"findings":[
               {"findingId":"rule","ordinal":1,"category":"BREACH","statusCode":"NEEDS_REVIEW","source":"DETERMINISTIC",
                "summary":"责任匹配结果 MATCH，责任主体 OTHER_OR_UNCLEAR","recommendation":"比较 NOT_EVALUATED；内部原因 SEMANTIC_INPUT_INVALID"}]},
              {"stepType":"SEMANTIC_AI","status":"COMPLETED","checks":[],"evidence":[],"findings":[
               {"findingId":"brand","ordinal":1,"category":"SERVICE_WARRANTY","attentionCode":"REVIEW","source":"MODEL_ASSISTED",
                "summary":"MATCH牌设备的质保条件需核对","recommendation":"请核对质保响应范围"}]}]}
            """)));
        assertThat(projection.path("overallOpinion").asText()).contains("责任匹配结果 一致", "其他或尚不明确", "MATCH牌设备")
                .doesNotContain("OTHER_OR_UNCLEAR", "NOT_EVALUATED", "SEMANTIC_INPUT_INVALID");
    }

    @Test void anUnsupportedRuleCannotSupplyANewAuthoritativeMoneyDifference() {
        var projection = CloseoutCanonical.tree(CloseoutCanonical.json(BusinessReviewProjection.fromFacts("""
            {"runId":"r","runStatus":"COMPLETED","materials":[],"findings":[
              {"stepType":"F02_DETERMINISTIC","status":"COMPLETED","findings":[],"evidence":[],"checks":[
                {"id":"00000000-0000-0000-0000-000000000001","ruleCode":"UNSUPPORTED_RULE","checkStatus":"FAIL",
                 "actualValue":"999999","expectedValue":"0","detail":"不支持的规则结果"}]}]}
            """)));
        assertThat(projection.path("overallOpinion").asText()).doesNotContain("999,999")
                .contains("不支持的金额规则");
    }
}
