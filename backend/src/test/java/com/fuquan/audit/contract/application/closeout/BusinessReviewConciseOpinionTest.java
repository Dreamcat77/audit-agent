package com.fuquan.audit.contract.application.closeout;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class BusinessReviewConciseOpinionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void conciseAmountsExplainFortyEightLinesAndBothAmountsWithoutExpandingFiftyTwoNormalChecks() {
        var projection = project(amountFacts(null));
        assertEquals(1, projection.path("version").asInt());
        assertEquals(4, projection.path("opinionVersion").asInt());
        var amount = projection.path("categories").get(0);
        String concise = amount.path("conciseOpinion").asText();
        assertTrue(concise.contains("48项明细"), concise);
        assertTrue(concise.contains("48,000元"), concise);
        assertTrue(concise.contains("成交") && concise.contains("一致"), concise);
        assertFalse(concise.contains("逐行详细原文"));
        assertEquals(52, amount.path("items").size());
        assertEquals(193, amount.path("items").findValues("evidence").stream().mapToInt(JsonNode::size).sum());
        assertFalse(projection.path("overallAdvice").asText().isBlank());
    }

    @Test void numericPassDoesNotClaimCompleteSourcesAndConfirmedDifferencesRemainHumanFacts() {
        var facts = amountFacts("SOURCE_UNAVAILABLE:CONFIRMED_VALUE_DIFFERS_FROM_SOURCE");
        var amount = project(facts).path("categories").get(0);
        assertEquals("NORMAL", amount.path("items").get(0).path("status").asText(), "source gap must not rewrite arithmetic PASS");
        assertEquals("PARTIALLY_COVERED", amount.path("coverage").asText());
        assertEquals("INSUFFICIENT", amount.path("judgement").asText());
        assertTrue(amount.path("uncoveredScopes").toString().contains("原文"));
        String concise = amount.path("conciseOpinion").asText();
        assertTrue(concise.contains("人工确认") && concise.contains("原文"), concise);
        assertTrue(concise.contains("来源") && concise.contains("待核实"), concise);
        assertTrue(amount.path("items").get(0).path("recommendation").asText().contains("确认值"));
        assertEquals("PASS", facts.path("findings").get(0).path("checks").get(0).path("checkStatus").asText());
    }

    @Test void realAmountDifferenceAndMissingRuleStayDistinctFromSourceInsufficiency() {
        var facts = amountFacts("SOURCE_UNAVAILABLE:LOCATOR_UNAVAILABLE");
        var checks = (com.fasterxml.jackson.databind.node.ArrayNode) facts.path("findings").get(0).path("checks");
        ((ObjectNode) checks.get(49)).put("checkStatus", "FAIL").put("actualValue", "48000.00").put("expectedValue", "49000.00");
        ((ObjectNode) checks.get(51)).put("checkStatus", "NOT_EVALUATED").remove("actualValue");
        var amount = project(facts).path("categories").get(0);
        String concise = amount.path("conciseOpinion").asText();
        assertTrue(concise.contains("48,000") && concise.contains("49,000") && concise.contains("差异"), concise);
        assertTrue(concise.contains("合同金额大小写") && concise.contains("事实不足"), concise);
        assertTrue(concise.contains("来源") && concise.contains("待核实"), concise);
        assertEquals("ATTENTION", amount.path("judgement").asText());
    }

    @Test void opinionTwoHtmlUsesFrozenConciseWordingAndKeepsAllDetailEvidenceWithoutReadingRawFacts() {
        var projection = project(amountFacts(null));
        ((ObjectNode) projection).put("opinionVersion", 2).put("overallAdvice", "冻结综合建议：请核实主体资格。");
        for (var category : projection.path("categories"))
            ((ObjectNode) category).put("conciseOpinion", "冻结简版结论：" + category.path("title").asText());
        var snapshot = JSON.createObjectNode().put("schemaVersion", 3).put("contractName", "合成合同");
        snapshot.set("businessProjection", projection); snapshot.putArray("acknowledgements");
        var renderer = new DeterministicHtmlReportRenderer();
        String html = new String(renderer.render(snapshot.toString()).bytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains("冻结综合建议：请核实主体资格。"));
        assertTrue(html.contains("冻结简版结论：金额一致性"));
        assertTrue(html.contains("<details>"));
        assertTrue(html.contains("完整明细"));
        assertEquals(52, occurrences(html, "<article>"));
        assertEquals(193, occurrences(html, "冻结证据："));
        snapshot.putArray("findings").addObject().put("summary", "今天的新结果不得进入旧报告");
        assertEquals(html, new String(renderer.render(snapshot.toString()).bytes(), StandardCharsets.UTF_8));
    }

    @Test void legacyConstructorsAndJsonRemainReadableWithoutInventingOpinionTwo() throws Exception {
        var category = new BusinessReviewProjection.Category("QUANTITATIVE", 1, "金额一致性", "COVERED", "NORMAL",
                "已核对", 0, "旧摘要", List.of(), List.of());
        var legacy = new BusinessReviewProjection.Projection(1, "old-run", "COMPLETED", "旧范围", List.of(category));
        String json = JSON.writeValueAsString(legacy);
        assertFalse(json.contains("opinionVersion"));
        assertFalse(json.contains("conciseOpinion"));
        assertFalse(json.contains("overallAdvice"));
        assertEquals(legacy, JSON.readValue(json, BusinessReviewProjection.Projection.class));
    }

    @Test void legacySchemaThreeBytesRemainExactlyTheSame() {
        var snapshot = JSON.createObjectNode().put("schemaVersion", 3).put("contractName", "旧合同");
        var projection = snapshot.putObject("businessProjection").put("version", 1).put("runStatus", "COMPLETED").put("scopeStatement", "旧范围");
        var categories = projection.putArray("categories");
        for (int order = 1; order <= 8; order++) {
            var category = categories.addObject().put("order", order).put("title", "旧项").put("statusLabel", "旧状态").put("summary", "旧摘要");
            category.putArray("items"); category.putArray("uncoveredScopes");
        }
        String expected = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>合同审核报告</title></head><body><h1>合同审核报告</h1><h2>旧合同</h2><p>旧范围</p>"
                + "<section><h2>1. 旧项</h2><p>状态：旧状态</p><p>旧摘要</p></section>"
                + "<section><h2>2. 旧项</h2><p>状态：旧状态</p><p>旧摘要</p></section>"
                + "<section><h2>3. 旧项</h2><p>状态：旧状态</p><p>旧摘要</p></section>"
                + "<section><h2>4. 旧项</h2><p>状态：旧状态</p><p>旧摘要</p></section>"
                + "<section><h2>5. 旧项</h2><p>状态：旧状态</p><p>旧摘要</p></section>"
                + "<section><h2>6. 旧项</h2><p>状态：旧状态</p><p>旧摘要</p></section>"
                + "<section><h2>7. 旧项</h2><p>状态：旧状态</p><p>旧摘要</p></section>"
                + "<section><h2>8. 旧项</h2><p>状态：旧状态</p><p>旧摘要</p></section></body></html>\n";
        assertEquals(expected, new String(new DeterministicHtmlReportRenderer().render(snapshot.toString()).bytes(), StandardCharsets.UTF_8));
    }

    @Test void frozenOpinionTwoRetainsItsOriginalVersionHeadingAndEveryByte() {
        var snapshot = JSON.createObjectNode().put("schemaVersion", 3).put("contractName", "旧合同");
        var projection = snapshot.putObject("businessProjection").put("version", 1).put("opinionVersion", 2)
                .put("runStatus", "COMPLETED").put("scopeStatement", "旧范围").put("overallAdvice", "旧建议");
        var categories = projection.putArray("categories");
        for (int order = 1; order <= 8; order++) {
            var category = categories.addObject().put("order", order).put("title", "旧项")
                    .put("statusLabel", "旧状态").put("summary", "旧摘要").put("conciseOpinion", "旧简版");
            category.putArray("items"); category.putArray("uncoveredScopes");
        }
        String expected = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>合同审核报告</title></head><body><h1>合同审核报告</h1><h2>旧合同</h2><p>旧范围</p>"
                + "<p>意见版本：2</p><h2>综合建议</h2><p>旧建议</p>"
                + "<section><h2>1. 旧项</h2><p>状态：旧状态</p><p>旧简版</p><details><summary>完整明细</summary><p>旧摘要</p></details></section>"
                + "<section><h2>2. 旧项</h2><p>状态：旧状态</p><p>旧简版</p><details><summary>完整明细</summary><p>旧摘要</p></details></section>"
                + "<section><h2>3. 旧项</h2><p>状态：旧状态</p><p>旧简版</p><details><summary>完整明细</summary><p>旧摘要</p></details></section>"
                + "<section><h2>4. 旧项</h2><p>状态：旧状态</p><p>旧简版</p><details><summary>完整明细</summary><p>旧摘要</p></details></section>"
                + "<section><h2>5. 旧项</h2><p>状态：旧状态</p><p>旧简版</p><details><summary>完整明细</summary><p>旧摘要</p></details></section>"
                + "<section><h2>6. 旧项</h2><p>状态：旧状态</p><p>旧简版</p><details><summary>完整明细</summary><p>旧摘要</p></details></section>"
                + "<section><h2>7. 旧项</h2><p>状态：旧状态</p><p>旧简版</p><details><summary>完整明细</summary><p>旧摘要</p></details></section>"
                + "<section><h2>8. 旧项</h2><p>状态：旧状态</p><p>旧简版</p><details><summary>完整明细</summary><p>旧摘要</p></details></section></body></html>\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), new DeterministicHtmlReportRenderer().render(snapshot.toString()).bytes());
    }

    @Test void currentOpinionThreeReportUsesTheSameChineseProjectionAndExplicitNewVersion() {
        var facts = amountFacts(null);
        ((ObjectNode) facts.path("findings").get(0).path("checks").get(0))
                .put("detail", "Line quantity multiplied by unit price matches declared amount");
        var projection = project(facts);
        ((ObjectNode) projection).put("opinionVersion", 3);
        assertEquals(3, projection.path("opinionVersion").asInt());
        var snapshot = JSON.createObjectNode().put("schemaVersion", 3).put("contractName", "新合同");
        snapshot.set("businessProjection", projection);
        String html = new String(new DeterministicHtmlReportRenderer().render(snapshot.toString()).bytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains("意见版本：3"));
        assertTrue(html.contains("数量乘以单价与分项金额一致"));
        assertFalse(html.contains("Line quantity"));
        assertTrue(html.contains(projection.path("categories").get(0).path("conciseOpinion").asText()));
    }

    @Test void conciseRiskRetainsBothBusinessClassesAndConcretePersistedDifferences() {
        var projection = BusinessReviewProjection.fromFacts("""
                {"runId":"r","runStatus":"COMPLETED","materials":[],"findings":[
                {"stepType":"SEMANTIC_AI","status":"COMPLETED","checks":[],"evidence":[],"findings":[
                {"findingId":"warranty","ordinal":1,"category":"SERVICE_WARRANTY","attentionCode":"REVIEW",
                 "source":"MODEL_ASSISTED","summary":"合同质保12个月，采购文件要求36个月"},
                {"findingId":"scope","ordinal":2,"category":"SCOPE_AND_SUBCONTRACTING","attentionCode":"INSUFFICIENT",
                 "source":"MODEL_ASSISTED","summary":"分包责任缺少对照依据"}]}]}
                """);
        String concise = CloseoutCanonical.tree(CloseoutCanonical.json(projection)).path("categories").get(7).path("conciseOpinion").asText();
        assertTrue(concise.contains("服务与质保") && concise.contains("履约范围与分包"), concise);
        assertTrue(concise.contains("12个月") && concise.contains("36个月") && concise.contains("对照依据"), concise);
    }

    @Test void opinionTwoRejectsMissingConciseFieldsAndEscapesEverySuppliedSentence() {
        var projection = (ObjectNode) project(amountFacts(null));
        projection.put("opinionVersion", 2);
        projection.put("overallAdvice", "<script>advice</script>");
        ((ObjectNode) projection.path("categories").get(0)).put("conciseOpinion", "<script>opinion</script>");
        var snapshot = JSON.createObjectNode().put("schemaVersion", 3);
        snapshot.set("businessProjection", projection);
        var renderer = new DeterministicHtmlReportRenderer();
        String html = new String(renderer.render(snapshot.toString()).bytes(), StandardCharsets.UTF_8);
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;opinion&lt;/script&gt;"));
        assertTrue(html.contains("&lt;script&gt;advice&lt;/script&gt;"));
        ((ObjectNode) projection.path("categories").get(0)).remove("conciseOpinion");
        assertThrows(IllegalArgumentException.class, () -> renderer.render(snapshot.toString()));
    }

    @Test void opinionTwoDoesNotRepeatIdenticalSourceScopeInTheDefaultSummary() {
        var projection = project(amountFacts("SOURCE_UNAVAILABLE:LOCATOR_UNAVAILABLE"));
        ((ObjectNode) projection).put("opinionVersion", 2);
        String gap = projection.path("categories").get(0).path("uncoveredScopes").get(0).asText();
        var snapshot = JSON.createObjectNode().put("schemaVersion", 3);
        snapshot.set("businessProjection", projection);
        String html = new String(new DeterministicHtmlReportRenderer().render(snapshot.toString()).bytes(), StandardCharsets.UTF_8);
        String defaultSummary = html.substring(0, html.indexOf("<details>"));
        assertEquals(1, occurrences(defaultSummary, gap));
        assertTrue(html.substring(html.indexOf("<details>")).contains(gap), "full detail retains the complete source limitations");
    }

    private static JsonNode project(ObjectNode facts) { return CloseoutCanonical.tree(CloseoutCanonical.json(BusinessReviewProjection.fromFacts(facts.toString()))); }
    private static int occurrences(String text, String token) { return text.split(java.util.regex.Pattern.quote(token), -1).length - 1; }
    private static ObjectNode amountFacts(String sourceAttention) {
        var root = JSON.createObjectNode().put("runId", "frozen-run").put("runStatus", "COMPLETED");
        root.putArray("materials");
        var step = root.putArray("findings").addObject().put("stepType", "F02_DETERMINISTIC").put("status", "COMPLETED");
        var checks = step.putArray("checks"); var findings = step.putArray("findings"); var evidence = step.putArray("evidence");
        for (int index = 0; index < 52; index++) {
            UUID id = new UUID(0, index + 1); String carrier = FrozenF02ResultMapper.findingId(id).toString();
            String rule = index < 48 ? "F02_LINE_ITEM_ARITHMETIC" : switch (index) {
                case 48 -> "F02_LINE_SUM_MATCH"; case 49 -> "F02_AWARD_AMOUNT_MATCH";
                case 50 -> "F02_AWARD_SUPPLIER_MATCH"; default -> "F02_AMOUNT_WORDS_MATCH"; };
            var check = checks.addObject().put("id", id.toString()).put("ruleCode", rule).put("checkStatus", "PASS")
                    .put("detail", "逐行详细原文" + index).put("actualValue", index < 48 ? "1000.00" : "48000.00").put("expectedValue", index < 48 ? "1000.00" : "48000.00");
            if (index < 48) check.put("itemKey", "line-" + (index + 1));
            var finding = findings.addObject().put("findingId", carrier).put("ordinal", index + 1).put("statusCode", "PASS").put("category", rule);
            if (index == 0 && sourceAttention != null) finding.put("attentionCode", sourceAttention).put("recommendation", "人工确认值与原文不同，确认值用于本次计算。");
            for (int ordinal = 0; ordinal < (index < 37 ? 4 : 3); ordinal++) evidence.addObject().put("findingId", carrier)
                    .put("evidenceId", id + ":" + ordinal).put("fileId", "frozen-file").put("boundedExcerpt", "原文证据" + index + ":" + ordinal)
                    .put("locatorAvailable", true).put("validationStatus", "VALIDATED");
        }
        return root;
    }
}
