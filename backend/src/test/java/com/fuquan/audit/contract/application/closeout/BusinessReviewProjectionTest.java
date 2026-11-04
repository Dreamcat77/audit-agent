package com.fuquan.audit.contract.application.closeout;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BusinessReviewProjectionTest {
    @Test void failedRunProducesHonestStageReportWithUnfinishedScopes() {
        var f = new CloseoutFixtures(com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus.FAILED);
        var actual = f.outputs.getRun(f.project, f.contract, f.runId);
        var steps = new java.util.ArrayList<>(actual.steps());
        var part = steps.get(1); var step = part.step();
        var failed = new com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep(step.stepId(), step.runId(),
                step.stepType(), true, com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus.FAILED,
                step.stepPolicyVersion(), step.currentAttemptNo(), null, null, null, step.startedAt(), null, step.versionNo() + 1);
        steps.set(1, new com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.StepOutput(
                part.run(), failed, part.attempts(), part.auditTask(), part.auditCheckResults(), java.util.List.of(), java.util.List.of()));
        var reader = org.mockito.Mockito.mock(com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.class);
        org.mockito.Mockito.when(reader.getRun(f.project, f.contract, f.runId)).thenReturn(
                new com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.RunOutput(actual.manifest(), steps));
        var snapshot = new ReportSnapshotFactory(reader, f.frozen, f.closeout, f.f02Acks)
                .freeze(f.project, f.contract, f.runId);
        var body = CloseoutCanonical.tree(snapshot.json());
        assertEquals(4, body.path("schemaVersion").asInt());
        assertEquals("FAILED", body.path("businessProjection").path("runStatus").asText());
        assertEquals(8, body.path("businessProjection").path("categories").size());
        var html = new String(new DeterministicHtmlReportRenderer().render(snapshot.json()).bytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains("本报告记录本次处理截至结束时已完成的部分"));
    }

    @Test void frozenReportIncludesEightBusinessOpinionsWithRealAmountsAndUncoveredScopes() {
        var f = new CloseoutFixtures();
        var snapshot = new ReportSnapshotFactory(f.outputs, f.frozen, f.closeout, f.f02Acks)
                .freeze(f.project, f.contract, f.runId);
        var body = CloseoutCanonical.tree(snapshot.json());
        var business = body.path("businessProjection");
        assertEquals(1, business.path("version").asInt());
        assertEquals(8, business.path("categories").size());
        assertEquals("100", business.path("categories").get(0).path("items").get(0).path("actualValue").asText());
        assertEquals("200", business.path("categories").get(0).path("items").get(0).path("expectedValue").asText());
        assertEquals("WAITING_EXTERNAL", business.path("categories").get(4).path("coverage").asText());
        assertEquals("NOT_COVERED", business.path("categories").get(5).path("coverage").asText());
        assertEquals(CloseoutCanonical.tree(CloseoutCanonical.json(BusinessReviewProjection.from(
                f.outputs.getRun(f.project, f.contract, f.runId)))), business);
        var html = new String(new DeterministicHtmlReportRenderer().render(snapshot.json()).bytes(), StandardCharsets.UTF_8);
        for (var category : business.path("categories")) {
            assertTrue(html.contains(category.path("title").asText()));
            assertTrue(html.contains(category.path("summary").asText()));
            assertTrue(html.contains(category.path("statusLabel").asText()));
            for (var scope : category.path("uncoveredScopes")) assertTrue(html.contains(scope.asText()));
        }
        assertTrue(html.contains("100")); assertTrue(html.contains("200"));
        assertTrue(html.contains("合成合同条款"));
        assertTrue(html.contains("不表示问题已解决"));
    }

    @Test void typedDecimalFactsRemainExactAndMissingCounterpartCannotBecomeNormal() {
        var projection = BusinessReviewProjection.fromFacts("""
                {"runId":"r","runStatus":"COMPLETED","materials":[],"findings":[
                {"stepType":"BREACH","status":"COMPLETED","checks":[],"evidence":[],
                 "findings":[{"findingId":"f","ordinal":1,"findingType":"BREACH_COMPARISON",
                 "category":"BREACH","statusCode":"NORMAL","source":"DETERMINISTIC","summary":"已识别违约事实"}],
                 "typedComparisons":[{"findingId":"f","result":"NOT_EVALUATED","values":[
                 {"sideKey":"CONTRACT_BODY:c","state":"KNOWN","responsibleParty":"PARTY_B",
                  "calculationBasis":"CONTRACT_AMOUNT","rate":0.0000000000000000000001,"rateUnit":"PERCENT",
                  "frequency":"WEEK","capAmount":1234567890123.45},
                 {"sideKey":"TENDER_DOCUMENT:t","state":"NOT_EXTRACTED"}]}]}]}
                """);
        var breach=projection.categories().get(2);
        assertEquals("INSUFFICIENT",breach.judgement());
        assertEquals("INSUFFICIENT",breach.items().get(0).status());
        assertEquals("合同侧：责任方 乙方；基数 合同总价；费率 0.0000000000000000000001%；频次 每周；上限金额 1234567890123.45",
                breach.items().get(0).typedFacts().get(0));
        assertEquals("采购依据侧：未提取到可比较事实",breach.items().get(0).typedFacts().get(1));
    }

    @Test void unsupportedFindingStateIsInsufficientEvenWhenItsRiskColorSuggestsAttention() {
        var projection=BusinessReviewProjection.fromFacts("""
                {"runId":"r","runStatus":"COMPLETED","materials":[],"findings":[
                {"stepType":"TERM","status":"COMPLETED","checks":[],"evidence":[],
                "findings":[{"findingId":"f","ordinal":1,"findingType":"DATE",
                "statusCode":"FUTURE_STATE","riskLevel":"YELLOW","summary":"待核实事实","source":"DETERMINISTIC"}]}]}
                """);
        assertEquals("INSUFFICIENT",projection.categories().get(3).items().get(0).status());
    }

    @Test void unknownStepNeverTurnsIntoMissingMaterialOrACompletedOpinion() {
        var projection=BusinessReviewProjection.fromFacts("""
                {"runId":"r","runStatus":"UNKNOWN","materials":[],"findings":[
                {"stepType":"BREACH","status":"UNKNOWN","checks":[],"findings":[],"evidence":[]}]}
                """);
        var breach=projection.categories().get(2);
        assertEquals("UNFINISHED",breach.coverage());
        assertEquals("UNAVAILABLE",breach.judgement());
        assertEquals("结果状态待确认",breach.statusLabel());
        assertTrue(breach.items().isEmpty());
    }

    @Test void partialAmountOutputCannotClaimUnrunRulesOrRecognizeAFutureRuleAsPassed() {
        var projection=BusinessReviewProjection.fromFacts("""
                {"runId":"r","runStatus":"COMPLETED","materials":[],"findings":[
                {"stepType":"F02_DETERMINISTIC","status":"COMPLETED","findings":[],"evidence":[],"checks":[
                {"id":"00000000-0000-0000-0000-000000000001","ruleCode":"F02_AMOUNT_WORDS_MATCH",
                 "checkStatus":"PASS","detail":"合同金额大小写一致","actualValue":"100.00","expectedValue":"100.00"},
                {"id":"00000000-0000-0000-0000-000000000002","ruleCode":"FUTURE_RULE",
                 "checkStatus":"PASS","detail":"未识别的检查结果"}]}]}
                """);
        var amount=projection.categories().get(0);
        assertEquals("PARTIALLY_COVERED",amount.coverage());
        assertTrue(amount.uncoveredScopes().contains("合同与中标金额尚未核对"));
        assertEquals("INSUFFICIENT",amount.items().get(1).status());
        assertFalse(amount.summary().contains("已完成金额与供应商一致性核对"));
    }

    @Test void savedProjectionRendersWithoutReinterpretingRawFactsAfterAnUpgrade() {
        var f=new CloseoutFixtures();
        var snapshot=new ReportSnapshotFactory(f.outputs,f.frozen,f.closeout,f.f02Acks).freeze(f.project,f.contract,f.runId);
        var renderer=new DeterministicHtmlReportRenderer();
        var root=CloseoutCanonical.tree(snapshot.json());
        ((com.fasterxml.jackson.databind.node.ObjectNode)root).set("findings",CloseoutCanonical.tree("[]"));
        assertArrayEquals(renderer.render(snapshot.json()).bytes(),renderer.render(CloseoutCanonical.json(root)).bytes());
    }

    @Test void legacyV2ReportStillHasExactlyTheOriginalBytes() {
        String snapshot = """
                {"schemaVersion":2,"contractName":"旧合同","findings":[{"stepType":"F02_DETERMINISTIC",
                "checks":[{"id":"c","detail":"大小写一致","checkStatus":"PASS"}],"findings":[]}],
                "acknowledgements":[],"materials":[]}
                """;
        String expected = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>合同审核报告</title></head>"
                + "<body><h1>合同审核报告</h1><h2>旧合同</h2>"
                + "<p>本报告基于已完成审核时冻结的材料和结果。人工确认表示已查看并留下处理意见，不表示问题已解决。智能辅助提示需由业务人员判断。</p>"
                + "<section><h2>精确校验</h2><article><p>大小写一致</p><p>结果：通过</p><p>尚未确认查看</p></article></section></body></html>\n";
        assertEquals(expected, new String(new DeterministicHtmlReportRenderer().render(snapshot).bytes(), StandardCharsets.UTF_8));
    }

    @Test void legacyV3BusinessReportRemainsReadable() {
        String categories = java.util.stream.IntStream.range(0, 8).mapToObj(i ->
                "{\"order\":" + (i + 1) + ",\"title\":\"类别\",\"statusLabel\":\"已核对\","
                        + "\"summary\":\"旧摘要\",\"uncoveredScopes\":[],\"items\":[]}")
                .collect(java.util.stream.Collectors.joining(","));
        String snapshot = "{\"schemaVersion\":3,\"contractName\":\"旧版合同\",\"businessProjection\":"
                + "{\"version\":1,\"runStatus\":\"COMPLETED\",\"categories\":[" + categories
                + "],\"scopeStatement\":\"旧范围\",\"opinionVersion\":1,\"overallAdvice\":\"旧意见\"},"
                + "\"acknowledgements\":[]}";
        assertTrue(new String(new DeterministicHtmlReportRenderer().render(snapshot).bytes(), StandardCharsets.UTF_8)
                .contains("旧版合同"));
    }
}
