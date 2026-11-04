package com.fuquan.audit.contract.application.closeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuquan.audit.contract.application.closeout.port.ReportRendererPort;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Initial artifact format only; not a claim about customer PDF, template or signature acceptance. */
@Component
public class DeterministicHtmlReportRenderer implements ReportRendererPort {
    public static final String MEDIA_TYPE = "text/html; charset=utf-8";
    public static final int MAX_BYTES = 8_000_000;
    @Override public RenderedReport render(String snapshotJson) {
        var root = CloseoutCanonical.tree(snapshotJson);
        int schemaVersion = root.path("schemaVersion").asInt();
        if (schemaVersion != 1 && schemaVersion != 2 && schemaVersion != 3 && schemaVersion != ReportSnapshotFactory.SCHEMA)
            throw new IllegalArgumentException("report schema unsupported");
        if (schemaVersion == 3 || schemaVersion == 4) return renderBusiness(root);
        Map<String, JsonNode> acknowledgements = new HashMap<>();
        root.path("acknowledgements").forEach(a -> acknowledgements.put(a.path("findingId").asText(), a));
        Map<String, String> materialLabels = new HashMap<>();
        if (schemaVersion == 2) root.path("materials").forEach(material -> materialLabels.put(
                material.path("fileId").asText(), materialLabel(material)));
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>合同审核报告</title></head><body><h1>合同审核报告</h1>");
        html.append("<h2>").append(escape(root.path("contractName").asText())).append("</h2>");
        html.append("<p>本报告基于已完成审核时冻结的材料和结果。人工确认表示已查看并留下处理意见，不表示问题已解决。智能辅助提示需由业务人员判断。</p>");
        for (JsonNode step : root.path("findings")) {
            html.append("<section><h2>").append(label(step.path("stepType").asText())).append("</h2>");
            for (JsonNode check : step.path("checks")) {
                html.append("<article><p>").append(escape(check.path("detail").asText())).append("</p>");
                if (schemaVersion == 2) html.append("<p>结果：")
                        .append(checkStatus(check.path("checkStatus").asText())).append("</p>");
                acknowledgement(html, acknowledgements.get(check.path("id").asText())); html.append("</article>");
            }
            Iterable<JsonNode> visibleFindings = "F02_DETERMINISTIC".equals(step.path("stepType").asText())
                    && step.path("checks").size() > 0 ? List.of() : step.path("findings");
            for (JsonNode finding : visibleFindings) {
                html.append("<article><p>").append(escape(finding.path("summary").asText())).append("</p><p>")
                        .append(escape(finding.path("recommendation").asText(""))).append("</p>");
                if (schemaVersion == 2) findingDetails(html, step, finding, materialLabels);
                acknowledgement(html, acknowledgements.get(finding.path("findingId").asText())); html.append("</article>");
            }
            html.append("</section>");
        }
        html.append("</body></html>\n");
        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("report exceeds size bound");
        return new RenderedReport(bytes, MEDIA_TYPE);
    }
    private static RenderedReport renderBusiness(JsonNode root) {
        var projection = root.path("businessProjection");
        String runStatus = projection.path("runStatus").asText();
        if (projection.path("version").asInt() != 1 || projection.path("categories").size() != 8
                || !Set.of("COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "UNKNOWN").contains(runStatus)
                || (root.path("schemaVersion").asInt() == 3 && !"COMPLETED".equals(runStatus)))
            throw new IllegalArgumentException("frozen business report projection unsupported");
        int opinionVersion = projection.path("opinionVersion").asInt(1);
        if (opinionVersion != 1 && opinionVersion != 2 && opinionVersion != 3 && opinionVersion != 4)
            throw new IllegalArgumentException("frozen opinion version unsupported");
        boolean currentOpinion = opinionVersion == 3;
        boolean paragraphOpinion = opinionVersion == 4;
        boolean concise = opinionVersion == 2 || currentOpinion || paragraphOpinion;
        if (paragraphOpinion && (projection.path("overallOpinion").asText().isBlank()
                || !com.fuquan.audit.contract.application.ai.ContractMonetaryObservation.PROTOCOL
                    .equals(projection.path("monetaryObservation").path("protocol").asText())
                || !Set.of("STRICT", "OBSERVE").contains(projection.path("monetaryObservation").path("mode").asText())))
            throw new IllegalArgumentException("frozen overall opinion or mode incomplete");
        // This nested version is frozen with schema 3. Old prepared snapshots
        // lack it and retain the original byte-for-byte rendering path.
        if (concise && (projection.path("overallAdvice").asText().isBlank()
                || java.util.stream.StreamSupport.stream(projection.path("categories").spliterator(),false)
                    .anyMatch(category -> category.path("conciseOpinion").asText().isBlank())))
            throw new IllegalArgumentException("frozen concise opinion incomplete");
        Map<String,JsonNode> acks = new HashMap<>();
        root.path("acknowledgements").forEach(a -> acks.put(a.path("findingId").asText(),a));
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>合同审核报告</title></head><body><h1>合同审核报告</h1>");
        html.append("<h2>").append(escape(root.path("contractName").asText())).append("</h2>");
        html.append("<p>").append(escape(projection.path("scopeStatement").asText())).append("</p>");
        if (!"COMPLETED".equals(runStatus)) html.append("<p>本报告记录本次处理截至结束时已完成的部分；未完成或暂不能确认的环节已单独标明，不构成完整审核结论。</p>");
        if (paragraphOpinion) {
            if ("OBSERVE".equals(projection.path("monetaryObservation").path("mode").asText()))
                html.append("<p>").append(escape(com.fuquan.audit.contract.application.ai.ContractMonetaryObservation.OBSERVE_LABEL)).append("</p>");
            html.append("<p>意见版本：4</p><h2>综合审核意见</h2><p>")
                    .append(escape(projection.path("overallOpinion").asText())).append("</p>");
            monetaryWarnings(html, projection.path("monetaryObservation"));
        } else if (currentOpinion) html.append("<p>意见版本：3</p><h2>综合建议</h2><p>")
                .append(escape(projection.path("overallAdvice").asText())).append("</p>");
        else if (concise) html.append("<p>意见版本：2</p><h2>综合建议</h2><p>")
                .append(escape(projection.path("overallAdvice").asText())).append("</p>");
        for (var category : projection.path("categories")) {
            html.append("<section><h2>").append(category.path("order").asInt()).append(". ")
                    .append(escape(category.path("title").asText())).append("</h2><p>状态：")
                    .append(escape(category.path("statusLabel").asText())).append("</p><p>")
                    .append(escape(category.path(concise?"conciseOpinion":"summary").asText())).append("</p>");
            for (var scope : category.path("uncoveredScopes"))
                if (!concise || !category.path("conciseOpinion").asText().contains(scope.asText()))
                    html.append("<p>未覆盖范围：").append(escape(scope.asText())).append("</p>");
            if (concise) html.append("<details><summary>完整明细</summary><p>")
                    .append(escape(category.path("summary").asText())).append("</p>");
            for (var item : category.path("items")) {
                html.append("<article><h3>").append(escape(item.path("title").asText())).append("</h3><p>")
                        .append(escape(item.path("summary").asText())).append("</p><p>状态：")
                        .append(businessItemStatus(item.path("status").asText())).append("；来源：")
                        .append(findingSource(item.path("source").asText())).append("</p>");
                businessValue(html,item,"actualValue","合同/当前值"); businessValue(html,item,"expectedValue","对照/期望值");
                for (var fact:item.path("typedFacts")) html.append("<p>").append(escape(fact.asText())).append("</p>");
                for (var evidence:item.path("evidence")) html.append("<p>冻结证据：").append(escape(evidence.path("fileName").asText()))
                        .append("；").append(evidence.path("locatorAvailable").asBoolean()?"位置已验证":"位置无法精确确认")
                        .append("；摘录：").append(escape(evidence.path("excerpt").asText())).append("</p>");
                businessValue(html,item,"recommendation","处理建议");
                acknowledgement(html,acks.get(item.path("outputId").asText())); html.append("</article>");
            }
            if (concise) html.append("</details>");
            html.append("</section>");
        }
        html.append("</body></html>\n");
        byte[] bytes=html.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES) throw new IllegalArgumentException("report exceeds size bound");
        return new RenderedReport(bytes,MEDIA_TYPE);
    }
    private static void businessValue(StringBuilder html,JsonNode item,String key,String label) {
        if(item.hasNonNull(key)) html.append("<p>").append(label).append("：").append(escape(item.path(key).asText())).append("</p>");
    }
    private static void monetaryWarnings(StringBuilder html,JsonNode observation) {
        if (observation.path("warnings").isEmpty() && observation.path("workWarnings").isEmpty()) return;
        html.append("<details><summary>金额模型表述核对提示</summary>");
        for (var warning : observation.path("warnings")) html.append("<p>")
                .append(escape(warning.path("fieldPath").asText())).append("；")
                .append(escape(warning.path("errorKind").asText())).append("；")
                .append(escape(warning.path("category").asText("综合摘要"))).append("；")
                .append(escape(warning.path("ruleSubtypes").toString())).append("；内容指纹：")
                .append(escape(warning.path("textSha256").asText())).append("</p>");
        for (var warning : observation.path("workWarnings")) html.append("<p>已持久化分析提示：")
                .append(escape(warning.toString())).append("</p>");
        html.append("</details>");
    }
    private static String businessItemStatus(String status) {
        return switch(status) { case "ATTENTION" -> "建议复核"; case "NORMAL" -> "已核对";
            case "INSUFFICIENT" -> "关键事实暂无法确认"; default -> "待核实"; };
    }
    private static void findingDetails(StringBuilder html, JsonNode step, JsonNode finding,
                                       Map<String, String> materialLabels) {
        html.append("<p>状态：").append(findingStatus(finding.path("statusCode").asText()))
                .append("；来源：").append(findingSource(finding.path("source").asText())).append("</p>");
        for (JsonNode evidence : step.path("evidence")) {
            if (!finding.path("findingId").asText().equals(evidence.path("findingId").asText())) continue;
            String material = materialLabels.get(evidence.path("fileId").asText());
            if (material == null) throw new IllegalArgumentException("report evidence source missing");
            html.append("<p>冻结证据：").append(escape(material)).append("；")
                    .append("VALIDATED".equals(evidence.path("validationStatus").asText())
                            && evidence.path("locatorAvailable").asBoolean()
                            ? "位置已验证" : "位置无法精确确认")
                    .append("；摘录：").append(escape(evidence.path("boundedExcerpt").asText()))
                    .append("</p>");
        }
    }
    private static String materialLabel(JsonNode material) {
        String role = switch (material.path("effectiveRole").asText()) {
            case "CONTRACT_BODY" -> "合同正文";
            case "AWARD_NOTICE" -> "成交或中标通知书";
            case "TENDER_DOCUMENT" -> "采购文件";
            case "BID_RESPONSE" -> "响应文件";
            case "SUPPLEMENT" -> "补充材料";
            default -> "其他材料";
        };
        return role + "（冻结材料 " + material.path("ordinal").asInt() + "）";
    }
    private static String checkStatus(String status) {
        return switch (status) {
            case "PASS" -> "通过";
            case "FAIL" -> "存在差异";
            case "NOT_EVALUATED" -> "未能评估";
            default -> "待核实";
        };
    }
    private static String findingStatus(String status) {
        return switch (status) {
            case "REVIEW", "NEEDS_REVIEW" -> "建议复核";
            case "INSUFFICIENT" -> "关键事实暂无法确认";
            case "NORMAL" -> "已识别";
            case "INFO" -> "信息提示";
            default -> "待核实";
        };
    }
    private static String findingSource(String source) {
        return switch (source) {
            case "DETERMINISTIC" -> "规则校验结果";
            case "MODEL_ASSISTED" -> "智能分析提示";
            default -> "来源待核实";
        };
    }
    private static void acknowledgement(StringBuilder html, JsonNode ack) {
        boolean seen = ack != null && "ACKNOWLEDGED".equals(ack.path("state").asText());
        html.append("<p>").append(seen ? "已查看" : "尚未确认查看");
        if (seen) html.append("：").append(escape(ack.path("fact").path("handlingNote").asText("")));
        html.append("</p>");
    }
    private static String label(String type) {
        return switch(type) {
            case "MATERIAL_VALIDATION" -> "材料确认";
            case "F02_DETERMINISTIC" -> "精确校验";
            case "SEMANTIC_AI" -> "智能语义审查";
            case "BREACH" -> "违约条款审查";
            case "TERM" -> "履约期限与终止条件审查";
            default -> "审核环节";
        };
    }
    private static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
}
