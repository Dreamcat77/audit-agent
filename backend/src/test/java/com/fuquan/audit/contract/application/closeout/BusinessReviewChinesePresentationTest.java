package com.fuquan.audit.contract.application.closeout;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BusinessReviewChinesePresentationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void fixedEnglishRuleReasonsAndSupplierComparisonValuesBecomeChineseWithoutChangingFacts() {
        String[][] cases = {
            {"F02_AMOUNT_WORDS_MATCH", "PASS", "Amount in words matches numeric contract amount", "合同金额大小写一致"},
            {"F02_AMOUNT_WORDS_MATCH", "FAIL", "Amount in words does not match numeric contract amount", "合同金额大小写不一致"},
            {"F02_AMOUNT_WORDS_MATCH", "NOT_EVALUATED", "Amount in words is missing or invalid", "合同大写金额缺失或无法识别"},
            {"F02_LINE_ITEM_ARITHMETIC", "PASS", "Line quantity multiplied by unit price matches declared amount", "数量乘以单价与分项金额一致"},
            {"F02_LINE_ITEM_ARITHMETIC", "FAIL", "Line declared amount differs from quantity multiplied by unit price", "数量乘以单价与分项金额不一致"},
            {"F02_LINE_ITEM_ARITHMETIC", "NOT_EVALUATED", "Line item has declared amount only; quantity and unit price are unavailable", "仅取得分项金额，数量和单价尚不可用"},
            {"F02_LINE_ITEM_ARITHMETIC", "NOT_EVALUATED", "Contract has no line item basis", "尚未取得合同明细核对依据"},
            {"F02_LINE_SUM_MATCH", "PASS", "Line item sum matches contract amount", "分项合计与合同金额一致"},
            {"F02_LINE_SUM_MATCH", "FAIL", "Line item sum differs from contract amount", "分项合计与合同金额不一致"},
            {"F02_LINE_SUM_MATCH", "NOT_EVALUATED", "Contract has no line totals to sum", "尚未取得可汇总的分项金额"},
            {"F02_AWARD_AMOUNT_MATCH", "PASS", "Contract amount matches award amount", "合同金额与中标金额一致"},
            {"F02_AWARD_AMOUNT_MATCH", "FAIL", "Contract amount differs from award amount", "合同金额与中标金额不一致"},
            {"F02_AWARD_AMOUNT_MATCH", "NOT_EVALUATED", "Award notice amount basis is missing", "尚未取得中标金额核对依据"},
            {"F02_AWARD_SUPPLIER_MATCH", "PASS", "Contract supplier matches award supplier by normalized supplier name", "合同乙方与中标供应商的规范化名称一致"},
            {"F02_AWARD_SUPPLIER_MATCH", "FAIL", "Contract supplier differs from award supplier by credit code", "合同乙方与中标供应商的统一社会信用代码不一致"},
            {"F02_AWARD_SUPPLIER_MATCH", "NOT_EVALUATED", "Award notice supplier basis is missing", "尚未取得中标供应商核对依据"},
            {"F02_AWARD_SUPPLIER_MATCH", "NOT_EVALUATED", "Supplier identity basis is incomplete", "供应商身份核对依据不完整"}
        };
        for (String[] c : cases) {
            var facts = amountFacts(c[0], c[1], c[2]);
            var check = (ObjectNode) facts.path("findings").get(0).path("checks").get(0);
            check.put("actualValue", c[0].equals("F02_AWARD_SUPPLIER_MATCH") ? "DIFFERENT" : "1234567890123.45000001");
            check.put("expectedValue", c[0].equals("F02_AWARD_SUPPLIER_MATCH") ? "MATCH" : "1234567890123.45");
            String rawBefore = facts.toString();
            var item = BusinessReviewProjection.fromFacts(rawBefore).categories().get(0).items().get(0);
            assertEquals(c[3], item.summary(), c[2]);
            assertEquals(c[0].equals("F02_AWARD_SUPPLIER_MATCH") ? "不一致" : "1234567890123.45000001", item.actualValue());
            assertEquals(c[0].equals("F02_AWARD_SUPPLIER_MATCH") ? "一致" : "1234567890123.45", item.expectedValue());
            assertEquals(rawBefore, facts.toString());
        }
    }

    @Test void chineseRuleDetailAndEvidenceAreNotRewrittenAsGenericPassText() {
        var facts = amountFacts("F02_LINE_ITEM_ARITHMETIC", "FAIL", "项目甲数量2与单价3的乘积为6，申报金额为7");
        var item = BusinessReviewProjection.fromFacts(facts.toString()).categories().get(0).items().get(0);
        assertEquals("项目甲数量2与单价3的乘积为6，申报金额为7", item.summary());
    }

    @Test void uncertainCalculationBasisIsExplainedInChineseAndExactDecimalIsRetained() {
        var projection = BusinessReviewProjection.fromFacts("""
                {"runId":"r","runStatus":"COMPLETED","materials":[],"findings":[
                {"stepType":"BREACH","status":"COMPLETED","checks":[],"evidence":[],
                 "findings":[{"findingId":"f","ordinal":1,"findingType":"BREACH_COMPARISON",
                 "category":"BREACH","statusCode":"INSUFFICIENT","source":"DETERMINISTIC","summary":"计算基数待核实"}],
                 "typedComparisons":[{"findingId":"f","result":"NOT_EVALUATED","values":[
                 {"sideKey":"CONTRACT_BODY:c","state":"KNOWN","responsibleParty":"BOTH",
                  "calculationBasis":"OTHER_OR_UNCLEAR","rate":0.0000000000000000000001,"rateUnit":"PERCENT"}]}]}]}
                """);
        String fact = projection.categories().get(2).items().get(0).typedFacts().get(0);
        assertTrue(fact.contains("双方") && fact.contains("其他或尚不明确"), fact);
        assertTrue(fact.contains("0.0000000000000000000001%"), fact);
        assertFalse(fact.contains("OTHER_OR_UNCLEAR"));
        assertEquals("INSUFFICIENT", projection.categories().get(2).judgement());
    }

    @Test void conciseOpinionRetainsDistinctRisksSharingOneBusinessTitleAndDoesNotTruncateNegation() {
        var projection = BusinessReviewProjection.fromFacts("""
                {"runId":"r","runStatus":"COMPLETED","materials":[],"findings":[
                {"stepType":"SEMANTIC_AI","status":"COMPLETED","checks":[],"evidence":[],"findings":[
                {"findingId":"a","ordinal":1,"category":"BREACH_LIABILITY","attentionCode":"REVIEW",
                 "source":"MODEL_ASSISTED","summary":"乙方逾期交付每周2%，合同未约定累计上限。"},
                {"findingId":"b","ordinal":2,"category":"BREACH_LIABILITY","attentionCode":"REVIEW",
                 "source":"MODEL_ASSISTED","summary":"甲方逾期付款责任与采购文件不一致。"}]},
                {"stepType":"BREACH","status":"COMPLETED","checks":[],"evidence":[],"findings":[]}]}
                """);
        String concise = projection.categories().get(2).conciseOpinion();
        assertTrue(concise.contains("每周2%") && concise.contains("未约定累计上限"), concise);
        assertTrue(concise.contains("甲方逾期付款") && concise.contains("不一致"), concise);
        assertFalse(concise.contains("另有1项"), concise);
        assertTrue(concise.chars().filter(c -> c == '。').count() <= 3, concise);
        assertEquals(2, projection.categories().get(2).items().size());
    }

    @Test void longQuotedEvidenceRetainsDenialRateAndConditionInFrozenHtml() {
        var facts = amountFacts("F02_LINE_ITEM_ARITHMETIC", "FAIL", "数量乘以单价与分项金额不一致");
        String excerpt = "前置上下文".repeat(40) + "未经甲方书面同意不得转包；每周2%，累计不得超过10%；延期超过4周方可解除。";
        var step = (ObjectNode) facts.path("findings").get(0);
        String carrier = com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper
                .findingId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")).toString();
        step.putArray("evidence").addObject().put("evidenceId", "e").put("findingId", carrier)
                .put("boundedExcerpt", excerpt).put("fileId", "file").put("locatorAvailable", true).put("validationStatus", "VALIDATED");
        var snapshot = JSON.createObjectNode().put("schemaVersion", 3);
        snapshot.set("businessProjection", JSON.valueToTree(BusinessReviewProjection.fromFacts(facts.toString())));
        String html = new String(new DeterministicHtmlReportRenderer().render(snapshot.toString()).bytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains(excerpt));
    }

    private static ObjectNode amountFacts(String rule, String status, String detail) {
        var facts = JSON.createObjectNode().put("runId", "r").put("runStatus", "COMPLETED");
        facts.putArray("materials");
        var step = facts.putArray("findings").addObject().put("stepType", "F02_DETERMINISTIC").put("status", "COMPLETED");
        step.putArray("findings"); step.putArray("evidence");
        step.putArray("checks").addObject().put("id", "00000000-0000-0000-0000-000000000001")
                .put("ruleCode", rule).put("checkStatus", status).put("detail", detail);
        return facts;
    }
}
