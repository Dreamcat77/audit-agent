package com.fuquan.audit.contract.application.closeout;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.application.ai.ContractMonetaryObservation;
import com.fuquan.audit.contract.application.ai.ContractAiTextRedactor;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.function.Predicate;

/** Versioned business wording derived only from the validated, immutable output of one run. */
public final class BusinessReviewProjection {
    public static final int VERSION = 1;
    public static final int OPINION_VERSION = 4;
    public static final String SCOPE = "结果仅针对本次冻结材料中已核对的事实；未覆盖、资料不足和外部核验项不能据此认定合规。人工确认表示已查看，不表示问题已解决；智能辅助提示需人工判断。";
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String MODEL_NUMBER = "[+-]?\\d[\\d,]*(?:\\.\\d+)?(?:\\s*[%％‰])?";
    private static final java.util.regex.Pattern MODEL_ARITHMETIC = java.util.regex.Pattern.compile(
            MODEL_NUMBER+"(?:\\s*[×*÷/＋+−]\\s*"+MODEL_NUMBER+")+(?:\\s*[=＝]\\s*"+MODEL_NUMBER+")?"
                    +"|"+MODEL_NUMBER+"\\s*-\\s*"+MODEL_NUMBER+"\\s*[=＝]\\s*"+MODEL_NUMBER);
    private static final List<String> IDS = List.of("QUANTITATIVE", "DRAFT_STRUCTURE", "BREACH", "TERM",
            "PARTY_QUALIFICATION", "ATTACHMENT_COMPLETENESS", "EFFECTIVENESS_COPIES", "CLAUSE_RISK");
    private static final List<String> TITLES = List.of("金额一致性", "合同结构", "违约责任", "期限与终止",
            "主体资格", "附件完整性", "生效条件与份数", "其他条款风险");
    private static final Map<String,String> RULES = Map.of(
            "F02_AMOUNT_WORDS_MATCH", "合同金额大小写", "F02_LINE_ITEM_ARITHMETIC", "分项金额计算",
            "F02_LINE_SUM_MATCH", "分项合计与总金额", "F02_AWARD_AMOUNT_MATCH", "合同与中标金额",
            "F02_AWARD_SUPPLIER_MATCH", "合同乙方与中标供应商");
    private BusinessReviewProjection() { }

    public static Projection from(ReviewStepOutputReadService.RunOutput output) {
        List<Map<String,Object>> steps = new ArrayList<>();
        for (var part : output.steps()) {
            var value = new TreeMap<String,Object>();
            value.put("stepType", part.step().stepType()); value.put("status", part.step().status());
            value.put("checks", part.auditCheckResults()); value.put("findings", part.findings());
            value.put("evidence", part.evidence()); value.put("typedComparisons", part.typedComparisons());
            steps.add(value);
        }
        var facts = new TreeMap<String, Object>();
        facts.put("runId", output.manifest().run().runId()); facts.put("runStatus", output.manifest().run().status());
        facts.put("findings", steps); facts.put("materials", output.manifest().materials());
        if (output.monetaryObservation() != null) facts.put("monetaryObservation", output.monetaryObservation());
        return fromFacts(CloseoutCanonical.json(facts));
    }

    static Projection fromFacts(String factsJson) {
        final JsonNode root;
        try { root = JSON.readTree(factsJson); }
        catch (Exception failure) { throw new IllegalArgumentException("invalid business review facts", failure); }
        List<Source> sources = new ArrayList<>(); Map<String,JsonNode> steps = new HashMap<>();
        Map<String,String> labels = new HashMap<>();
        root.path("materials").forEach(m -> labels.put(text(m,"fileId"), materialLabel(m)));
        root.path("findings").forEach(step -> {
            String type = text(step,"stepType");
            if (steps.putIfAbsent(type, step) != null) throw new IllegalArgumentException("duplicate review step");
            if (!"COMPLETED".equals(text(step,"status"))) return;
            step.path("findings").forEach(f -> sources.add(new Source(step,f)));
        });
        String runId = text(root,"runId"); List<Category> result = new ArrayList<>();
        var f02 = steps.get("F02_DETERMINISTIC");
        List<Item> checks = checks(runId, f02, labels);
        Set<String> checkedRules = new HashSet<>();
        if (f02 != null && "COMPLETED".equals(text(f02,"status")))
            f02.path("checks").forEach(check -> checkedRules.add(text(check,"ruleCode")));
        List<String> amountGaps = new ArrayList<>(F02RuleCodes.ALL.stream().filter(rule -> !checkedRules.contains(rule))
                .map(rule -> RULES.get(rule)+"尚未核对").toList());
        if (checkedRules.stream().anyMatch(rule -> !RULES.containsKey(rule))) amountGaps.add("不支持的金额规则未形成可确认判断");
        amountGaps.addAll(sourceGaps(f02));
        String amountScope = F02RuleCodes.ALL.stream().filter(checkedRules::contains).map(RULES::get)
                .collect(java.util.stream.Collectors.joining("、"));
        List<Item> structure = items(runId,sources,s -> type(s,"TEMPLATE_STRUCTURE_COMPARISON"),labels);
        List<Item> structureHints = items(runId,sources,s -> semantic(s,"CONTRACT_STRUCTURE"),labels);
        List<Item> breach = items(runId,sources,s -> "BREACH".equals(text(s.step,"stepType"))
                || semantic(s,"BREACH_LIABILITY"),labels);
        List<Item> term = items(runId,sources,s -> ("TERM".equals(text(s.step,"stepType")) && !special(s))
                || semantic(s,"TERM_TERMINATION","DELIVERY_TERMS"),labels);
        List<Item> identity = new ArrayList<>(checks.stream().filter(i -> i.title.startsWith("合同乙方")).toList());
        identity.addAll(items(runId,sources,s -> semantic(s,"PROJECT_IDENTITY","SUPPLIER_IDENTITY")
                || type(s,"PARTY_QUALIFICATION","SUPPLIER_QUALIFICATION"),labels));
        List<Item> attachments = items(runId,sources,BusinessReviewProjection::attachment,labels);
        List<Item> effectiveness = items(runId,sources,s -> type(s,"EFFECTIVE_EVENT","EFFECTIVE_DATE",
                "CONTRACT_COPIES","COPY_COUNT","COPY_COUNT_REQUIREMENT_COMPARISON"),labels);
        List<Item> risks = items(runId,sources,s -> "SEMANTIC_AI".equals(text(s.step,"stepType"))
                && !semantic(s,"CONTRACT_STRUCTURE","PROJECT_IDENTITY","SUPPLIER_IDENTITY","BREACH_LIABILITY",
                    "TERM_TERMINATION","DELIVERY_TERMS") && !special(s)
                && !type(s,"PARTY_QUALIFICATION","SUPPLIER_QUALIFICATION"),labels);

        var amount = category(0,steps,"F02_DETERMINISTIC",checks, checks.isEmpty()?"NOT_COVERED":amountGaps.isEmpty()?"COVERED":"PARTIALLY_COVERED",
                amountGaps, amountScope.isEmpty()?"已有检查结果核对":amountScope+"核对");
        result.add(withOpinion(amount, "UNFINISHED".equals(amount.coverage()) ? amount.summary() : amountOpinion(f02, amountGaps)));
        result.add(category(1,steps,"SEMANTIC_AI",structure.isEmpty()?structureHints:structure,
                !structure.isEmpty()?"COVERED":structureHints.isEmpty()?"NOT_COVERED":"PARTIALLY_COVERED",
                structure.isEmpty()?List.of("未指定合同模板，未完成模板结构对标"):List.of(), "合同结构核对"));
        result.add(category(2,steps,"BREACH",breach,hasStep(sources,"BREACH")?"COVERED":"NOT_COVERED",
                hasStep(sources,"BREACH")?List.of():List.of("缺少完整违约责任量化结果"), "违约责任核对"));
        result.add(category(3,steps,"TERM",term,hasPrimary(term,"TERM")?"COVERED":"NOT_COVERED",
                hasPrimary(term,"TERM")?List.of():List.of("缺少完整期限与终止检查结果"), "期限与终止条件核对"));
        result.add(category(4,steps,"SEMANTIC_AI",identity,"WAITING_EXTERNAL",
                List.of("企业资质和失信状态等待外部核验"), "主体名称与身份基础核对"));
        boolean matched = !attachments.isEmpty() && attachments.stream().allMatch(i -> "NORMAL".equals(i.status));
        result.add(category(5,steps,"TERM",attachments,matched?"COVERED":attachments.isEmpty()?"NOT_COVERED":"PARTIALLY_COVERED",
                matched?List.of():List.of(attachments.isEmpty()?"缺少附件引用与实际附件匹配结果":"存在尚未证明匹配的附件引用"), "附件引用匹配核对"));
        boolean effective = sources.stream().anyMatch(s -> type(s,"EFFECTIVE_EVENT","EFFECTIVE_DATE"));
        boolean copies = sources.stream().anyMatch(s -> type(s,"CONTRACT_COPIES","COPY_COUNT"));
        boolean requirement = sources.stream().anyMatch(s -> type(s,"COPY_COUNT_REQUIREMENT_COMPARISON"));
        List<String> gaps = new ArrayList<>();
        if (!effective) gaps.add("合同生效条件未检查");
        if (!copies) gaps.add("合同份数未检查");
        if (copies && !requirement) gaps.add("缺少权威合同份数要求，当前仅展示原文份数事实");
        gaps.add("签章真实性不能仅凭文本确认");
        result.add(category(6,steps,"TERM",effectiveness,effectiveness.isEmpty()?"NOT_COVERED":"PARTIALLY_COVERED",
                gaps, "生效条件和份数文本核对"));
        result.add(category(7,steps,"SEMANTIC_AI",risks,risks.isEmpty()?"NOT_COVERED":"COVERED",
                risks.isEmpty()?List.of("没有独立的其他条款风险结果"):List.of(), "其他条款提示核对"));
        ContractMonetaryObservation observation;
        try {
            observation = root.hasNonNull("monetaryObservation")
                    ? JSON.treeToValue(root.path("monetaryObservation"), ContractMonetaryObservation.class)
                    : new ContractMonetaryObservation(ContractMonetaryObservation.PROTOCOL, "STRICT", List.of(), List.of());
        } catch (Exception invalid) { throw new IllegalArgumentException("invalid monetary observation", invalid); }
        return new Projection(VERSION,runId,text(root,"runStatus"),SCOPE,List.copyOf(result),OPINION_VERSION,
                overallAdvice(result),overallOpinion(root, f02, result, sources, observation),observation);
    }

    private static String overallOpinion(JsonNode root, JsonNode f02, List<Category> categories,
            List<Source> sources, ContractMonetaryObservation observation) {
        List<String> materialRoles = new ArrayList<>();
        for (var material : root.path("materials")) {
            String role = switch(text(material,"effectiveRole")) {
                case "CONTRACT_BODY" -> "合同正文"; case "AWARD_NOTICE" -> "成交或中标通知书";
                case "TENDER_DOCUMENT" -> "采购文件"; case "BID_RESPONSE" -> "响应文件";
                case "SUPPLEMENT" -> "补充材料"; default -> "相关材料";
            };
            if (!materialRoles.contains(role)) materialRoles.add(role);
        }
        List<String> sentences = new ArrayList<>();
        sentences.add(materialRoles.isEmpty()?"本次在冻结材料范围内进行了辅助核对。"
                :"本次对"+String.join("、",materialRoles)+"进行了辅助核对。");
        if (!"COMPLETED".equals(text(root,"runStatus"))) sentences.add("审核尚未完成，当前为阶段性意见。");
        if (f02 != null && "COMPLETED".equals(text(f02,"status"))) {
            sentences.add(categories.get(0).conciseOpinion());
            var differences = new LinkedHashSet<String>();
            for (var check : f02.path("checks")) {
                if (!"FAIL".equals(text(check,"checkStatus")) || !F02RuleCodes.ALL.contains(text(check,"ruleCode"))
                        || F02RuleCodes.AWARD_SUPPLIER_MATCH.equals(text(check,"ruleCode"))) continue;
                try {
                    var actual = new BigDecimal(check.path("actualValue").asText());
                    var expected = new BigDecimal(check.path("expectedValue").asText());
                    var delta = actual.subtract(expected).abs();
                    if (delta.signum() != 0) differences.add(RULES.getOrDefault(text(check,"ruleCode"),"金额核对")
                            +"相差"+money(delta.toPlainString())+"元");
                } catch (NumberFormatException invalid) { /* A non-numeric result never supplies a derived monetary fact. */ }
            }
            if (!differences.isEmpty()) sentences.add(String.join("；", differences)+"，请核对双方依据。");
        } else sentences.add("金额核对尚未完成，暂不能确认金额一致性。");
        Set<String> warningFindingIds = new HashSet<>();
        Set<String> arithmeticWarningFindingIds = new HashSet<>();
        for (var warning : observation.warnings()) {
            var match = java.util.regex.Pattern.compile("hints\\[(\\d+)\\]\\..+").matcher(warning.fieldPath());
            if (match.matches()) {
                int ordinal = Integer.parseInt(match.group(1)) + 1;
                sources.stream().filter(source -> "SEMANTIC_AI".equals(text(source.step,"stepType"))
                        && source.finding.path("ordinal").asInt() == ordinal)
                        .forEach(source -> {
                            String id = text(source.finding,"findingId"); warningFindingIds.add(id);
                            if ("MONETARY_CALCULATION".equals(warning.errorKind())) arithmeticWarningFindingIds.add(id);
                        });
            }
        }
        Set<String> seen = new HashSet<>(); List<String> risks = new ArrayList<>();
        var redactor = new ContractAiTextRedactor();
        for (var source : sources) {
            var finding = source.finding();
            if ("F02_DETERMINISTIC".equals(text(source.step(),"stepType"))) continue;
            String id = text(finding,"findingId");
            var comparison = findComparison(source.step(),id);
            if ("NORMAL".equals(status(finding,comparison)) || !seen.add(id)) continue;
            boolean model = "MODEL_ASSISTED".equals(text(finding,"source"));
            String detail = text(finding,"summary");
            if (finding.hasNonNull("recommendation")) detail += "，"+text(finding,"recommendation");
            var typed = typedFacts(comparison);
            if (!typed.isEmpty()) detail += "（"+String.join("；",typed)+"）";
            if (model) {
                detail = redactor.redact(detail).replace(ContractAiTextRedactor.MASK,"相关金额（待核对）");
                if (arithmeticWarningFindingIds.contains(id)) detail = MODEL_ARITHMETIC.matcher(detail).replaceAll("相关金额计算（待核对）");
                if (warningFindingIds.contains(id)) detail += "，涉及金额的模型表述尚待人工核对";
            }
            detail = customerOpinionText(detail,model);
            risks.add((model?"模型提示":"需关注")+"："+detail.replaceAll("[。！？]+$", ""));
        }
        if (!risks.isEmpty()) sentences.add(String.join("；",risks)+"。建议对照本次冻结原文逐项核实。");
        else sentences.add("在本次已核对范围内未发现其他差异。");
        var limitations = categories.stream().skip(1).flatMap(category -> category.uncoveredScopes().stream()).distinct().toList();
        if (!limitations.isEmpty()) sentences.add("尚待核实："+String.join("；",limitations)+"。");
        String result = String.join("",sentences).replaceAll("\\s+", " ").trim();
        if (result.length() > 1_000_000) throw new IllegalArgumentException("overall opinion exceeds bound");
        return result;
    }

    private static String customerOpinionText(String text, boolean model) {
        String result = text.replaceAll("(?<![A-Za-z0-9_])(?:SEMANTIC_|MODEL_|SOURCE_UNAVAILABLE:)[A-Z0-9_:]+(?![A-Za-z0-9_])",
                "相关审查尚待核实");
        if (model) return result;
        for (var entry : Map.of("MATCH","一致", "OTHER_OR_UNCLEAR","其他或尚不明确", "DIFFERENT","存在差异",
                "CONFLICT","存在冲突", "NOT_EVALUATED","尚未完成比较", "NOT_EXTRACTED","未提取到可比较事实",
                "NOT_APPLICABLE","该场景不适用", "NEEDS_REVIEW","建议复核", "INSUFFICIENT","事实不足").entrySet())
            result = result.replaceAll("(?<![A-Za-z0-9_])"+entry.getKey()+"(?![A-Za-z0-9_])",entry.getValue());
        return result;
    }

    private static Category category(int index, Map<String,JsonNode> steps, String requiredStep,
            List<Item> items, String coverage, List<String> gaps, String checkedScope) {
        var step = steps.get(requiredStep);
        if (step == null || !"COMPLETED".equals(text(step,"status"))) {
            String state = step == null ? "MISSING" : text(step,"status");
            String label = "UNKNOWN".equals(state)?"结果状态待确认":"系统未完成";
            return new Category(IDS.get(index),index+1,TITLES.get(index),"UNFINISHED","UNAVAILABLE",label,0,
                    "UNKNOWN".equals(state)?"本项结果仍待确认，请核实当前审核记录后继续。":"本项审核尚未完成，请完成审核后再形成正式意见。",
                    List.of("审核环节尚未完成"),List.of(),
                    "UNKNOWN".equals(state)?"本项结果状态待确认，请核实当前审核记录后继续。":"本项审核尚未完成，当前仅能形成阶段性意见。");
        }
        long attention = items.stream().filter(i -> "ATTENTION".equals(i.status)).count();
        long insufficient = items.stream().filter(i -> "INSUFFICIENT".equals(i.status)).count();
        String judgement = attention>0?"ATTENTION":insufficient>0 || !"COVERED".equals(coverage)?"INSUFFICIENT":"NORMAL";
        String label = attention>0?"发现差异需复核": "WAITING_EXTERNAL".equals(coverage)?"等待外部核验"
                :"NOT_COVERED".equals(coverage)?"未覆盖": "INSUFFICIENT".equals(judgement)?"材料不足无法完整判断":"已核对未发现问题";
        String summary = items.isEmpty()?"本次没有足以判断本项的检查结果。"
                : "已完成"+checkedScope+"，共"+items.size()+"项"+(attention>0?"，其中"+attention+"项需复核":"")
                  +(insufficient>0?"、"+insufficient+"项事实不足":"")+"。";
        if (!gaps.isEmpty()) summary += "仍需核实："+String.join("；",gaps)+"。";
        else if (attention>0) summary += "请核对下列差异及其来源材料。";
        else if (insufficient>0) summary += "请补齐缺失事实后重新审核。";
        else summary += "在本次已核对范围内未发现差异。";
        return new Category(IDS.get(index),index+1,TITLES.get(index),coverage,judgement,label,
                (int)(attention+insufficient),summary,List.copyOf(gaps),List.copyOf(items),conciseCategory(items,gaps,checkedScope));
    }

    private static List<Item> checks(String runId,JsonNode step,Map<String,String> labels) {
        if (step == null || !"COMPLETED".equals(text(step,"status"))) return List.of();
        List<Item> result = new ArrayList<>();
        for (var check : step.path("checks")) {
            String status = text(check,"checkStatus"); String id = text(check,"id");
            String carrier = FrozenF02ResultMapper.findingId(UUID.fromString(id)).toString();
            String rule = text(check,"ruleCode"); String key = nullable(check,"itemKey");
            String itemStatus = !RULES.containsKey(rule)?"INSUFFICIENT":"FAIL".equals(status)?"ATTENTION":"PASS".equals(status)?"NORMAL":"INSUFFICIENT";
            JsonNode finding = carrier(step, carrier);
            String sourceIssue = sourceIssue(step, finding, carrier);
            String recommendation = finding == null ? null : nullable(finding,"recommendation");
            if (recommendation == null) recommendation = sourceIssue != null ? sourceIssue+"，请核实冻结原文与确认记录。"
                    : "NORMAL".equals(itemStatus)?null:"ATTENTION".equals(itemStatus)?"请核对对应业务事实和来源材料。":"请补充缺失业务事实后重新审核。";
            result.add(new Item(runId+":F02_DETERMINISTIC:"+id,"F02_DETERMINISTIC",id,
                    RULES.getOrDefault(rule,"当前金额规则")+(key==null?"":"（"+key+"）"),itemStatus,
                    ruleDetail(text(check,"detail")),ruleValue(rule,nullable(check,"actualValue")),ruleValue(rule,nullable(check,"expectedValue")),
                    recommendation,sourceIssue == null ? List.of() : List.of("数值结论："+numericStatus(status),"来源说明："+sourceIssue),
                    "DETERMINISTIC",evidence(step,carrier,labels),true));
        }
        return result;
    }

    // Translate only fixed rule vocabulary. Custom reasons, original evidence and durable facts stay intact.
    private static String ruleDetail(String value) {
        return switch (value) {
            case "Amount in words matches numeric contract amount" -> "合同金额大小写一致";
            case "Amount in words does not match numeric contract amount" -> "合同金额大小写不一致";
            case "Amount in words is missing or invalid" -> "合同大写金额缺失或无法识别";
            case "Line quantity multiplied by unit price matches declared amount" -> "数量乘以单价与分项金额一致";
            case "Line declared amount differs from quantity multiplied by unit price" -> "数量乘以单价与分项金额不一致";
            case "Line item has declared amount only; quantity and unit price are unavailable" -> "仅取得分项金额，数量和单价尚不可用";
            case "Contract has no line item basis" -> "尚未取得合同明细核对依据";
            case "Line item sum matches contract amount" -> "分项合计与合同金额一致";
            case "Line item sum differs from contract amount" -> "分项合计与合同金额不一致";
            case "Contract has no line totals to sum" -> "尚未取得可汇总的分项金额";
            case "Contract amount matches award amount" -> "合同金额与中标金额一致";
            case "Contract amount differs from award amount" -> "合同金额与中标金额不一致";
            case "Award notice amount basis is missing" -> "尚未取得中标金额核对依据";
            case "Contract supplier matches award supplier by normalized supplier name" -> "合同乙方与中标供应商的规范化名称一致";
            case "Contract supplier differs from award supplier by normalized supplier name" -> "合同乙方与中标供应商的规范化名称不一致";
            case "Contract supplier matches award supplier by credit code" -> "合同乙方与中标供应商的统一社会信用代码一致";
            case "Contract supplier differs from award supplier by credit code" -> "合同乙方与中标供应商的统一社会信用代码不一致";
            case "Award notice supplier basis is missing" -> "尚未取得中标供应商核对依据";
            case "Supplier identity basis is incomplete" -> "供应商身份核对依据不完整";
            default -> value;
        };
    }

    private static String ruleValue(String rule, String value) {
        if (!F02RuleCodes.AWARD_SUPPLIER_MATCH.equals(rule) || value == null) return value;
        return switch (value) { case "MATCH" -> "一致"; case "DIFFERENT" -> "不一致"; default -> value; };
    }

    private static JsonNode carrier(JsonNode step, String id) {
        for (var finding : step.path("findings")) if (id.equals(text(finding,"findingId"))) return finding;
        return null;
    }

    private static String sourceIssue(JsonNode step, JsonNode finding, String carrier) {
        String attention = finding == null ? "" : text(finding,"attentionCode");
        if ("SOURCE_UNAVAILABLE:CONFIRMED_VALUE_DIFFERS_FROM_SOURCE".equals(attention))
            return "人工确认值与原文不同，计算使用确认值，原文关联来源待核实";
        if (attention.startsWith("SOURCE_UNAVAILABLE:")) return "必要操作数的原文关联来源待核实";
        if (finding == null) return "原文关联尚无可验证记录，来源待核实";
        for (var evidence : step.path("evidence")) if (carrier.equals(text(evidence,"findingId"))
                && evidence.path("locatorAvailable").asBoolean() && "VALIDATED".equals(text(evidence,"validationStatus"))) return null;
        return "原文关联位置尚未验证，来源待核实";
    }

    private static List<String> sourceGaps(JsonNode step) {
        if (step == null || !"COMPLETED".equals(text(step,"status"))) return List.of();
        Map<String,Integer> groups = new LinkedHashMap<>();
        for (var check : step.path("checks")) {
            String id = FrozenF02ResultMapper.findingId(UUID.fromString(text(check,"id"))).toString();
            String issue = sourceIssue(step, carrier(step,id), id);
            if (issue != null) groups.merge(RULES.getOrDefault(text(check,"ruleCode"),"金额检查")+"："+issue,1,Integer::sum);
        }
        return groups.entrySet().stream().map(entry -> entry.getValue()+"项"+entry.getKey()).toList();
    }

    private static String numericStatus(String status) {
        return switch(status) { case "PASS" -> "通过"; case "FAIL" -> "存在差异"; default -> "未能评估"; };
    }

    private static Category withOpinion(Category c, String opinion) {
        return new Category(c.id(),c.order(),c.title(),c.coverage(),c.judgement(),c.statusLabel(),
                c.attentionCount(),c.summary(),c.uncoveredScopes(),c.items(),opinion);
    }

    private static String amountOpinion(JsonNode step, List<String> gaps) {
        if (step == null || step.path("checks").isEmpty()) return "本次没有可验证的金额检查结果，请完成金额核对并核实原文来源。";
        List<String> facts = new ArrayList<>();
        List<JsonNode> lines = new ArrayList<>();
        for (var check : step.path("checks")) if (F02RuleCodes.LINE_ITEM_ARITHMETIC.equals(text(check,"ruleCode"))
                && check.hasNonNull("itemKey")) lines.add(check);
        if (!lines.isEmpty()) {
            long different = lines.stream().filter(check -> "FAIL".equals(text(check,"checkStatus"))).count();
            long missing = lines.stream().filter(check -> !List.of("PASS","FAIL").contains(text(check,"checkStatus"))).count();
            facts.add("已核对"+lines.size()+"项明细"+(different>0?"，其中"+different+"项计算存在差异":"")
                    +(missing>0?"，"+missing+"项事实不足":""));
        }
        for (String rule : List.of(F02RuleCodes.LINE_SUM_MATCH,F02RuleCodes.AWARD_AMOUNT_MATCH,
                F02RuleCodes.AMOUNT_WORDS_MATCH,F02RuleCodes.AWARD_SUPPLIER_MATCH)) {
            List<JsonNode> matches = new ArrayList<>();
            for (var check : step.path("checks")) if (rule.equals(text(check,"ruleCode"))) matches.add(check);
            if (matches.size() != 1) continue;
            var check = matches.get(0); String status = text(check,"checkStatus");
            String label = RULES.get(rule);
            if (!List.of("PASS","FAIL").contains(status)) { facts.add(label+"事实不足，暂无法比较"); continue; }
            if (F02RuleCodes.LINE_SUM_MATCH.equals(rule) || F02RuleCodes.AWARD_AMOUNT_MATCH.equals(rule)) {
                String actual = money(nullable(check,"actualValue")), expected = money(nullable(check,"expectedValue"));
                if (actual == null || expected == null) { facts.add(label+"的金额记录待核实"); continue; }
                facts.add((F02RuleCodes.LINE_SUM_MATCH.equals(rule)?"分项合计":"合同金额")+actual+"元与"
                        +(F02RuleCodes.LINE_SUM_MATCH.equals(rule)?"合同总额":"成交金额")+expected+"元"
                        +("PASS".equals(status)?"一致":"存在差异"));
            } else facts.add(label+("PASS".equals(status)?"一致":"存在差异"));
        }
        if (facts.isEmpty()) facts.add("金额检查尚未形成可概括的业务结论");
        return String.join("；",facts)+"。"+(gaps.isEmpty()?"":String.join("；",gaps)+"，请补充核实，数值结论与来源完整性分别判断。");
    }

    private static String money(String value) {
        if (value == null) return null;
        try {
            var amount = new BigDecimal(value).stripTrailingZeros();
            // Render every persisted decimal digit without converting RMB to a floating point value.
            var format = new DecimalFormat("#,##0",DecimalFormatSymbols.getInstance(Locale.ROOT));
            format.setMaximumFractionDigits(Math.max(0,amount.scale()));
            return format.format(amount);
        } catch (NumberFormatException invalid) { return null; }
    }

    private static String conciseCategory(List<Item> items, List<String> gaps, String scope) {
        if (items.isEmpty()) return "本次没有足以判断本项的检查结果。"+(gaps.isEmpty()?"":String.join("；",gaps)+"，请补充核实。");
        List<String> issues = new ArrayList<>();
        for (String status : List.of("ATTENTION","INSUFFICIENT")) {
            var values = items.stream().filter(item -> status.equals(item.status())).toList();
            Map<String,List<Item>> byClass = new LinkedHashMap<>();
            values.forEach(item -> byClass.computeIfAbsent(item.title(), ignored -> new ArrayList<>()).add(item));
            for (var group : byClass.entrySet()) {
                String descriptions = group.getValue().stream().map(Item::summary)
                        .map(value -> value.replaceAll("[。！？]+", "；").replaceAll("；+$", ""))
                        .distinct().collect(java.util.stream.Collectors.joining("；"));
                issues.add(("ATTENTION".equals(status)?"需复核":"事实不足")+"："+group.getKey()+"——"
                        +descriptions);
            }
        }
        String result = issues.isEmpty()?"已完成"+scope+"，在已核对事实内未发现差异。":String.join("；",issues)+"。";
        if (!gaps.isEmpty()) result += "仍待核实："+String.join("；",gaps)+"。";
        if (!issues.isEmpty()) result += "请对照冻结原文核实差异或补齐事实，保留全部检查明细。";
        return result;
    }

    private static String overallAdvice(List<Category> categories) {
        List<String> issues = new ArrayList<>();
        for (var category : categories) if (!"NORMAL".equals(category.judgement()) || !"COVERED".equals(category.coverage()))
            issues.add(category.title()+"（"+category.statusLabel()+"）");
        return issues.isEmpty()?"在本次冻结材料已核对的范围内未发现差异，请保留完整明细及其来源，按业务程序复核。"
                : "请优先核实"+String.join("、",issues)+"，差异、事实不足及原文关联限制详见各项说明。补齐依据后按既有流程复核，当前结果不能代替主体资格、签章真实性等外部核验。";
    }

    private static List<Item> items(String runId,List<Source> sources,Predicate<Source> filter,Map<String,String> labels) {
        return sources.stream().filter(filter).sorted(Comparator.comparing((Source s) -> text(s.step,"stepType"))
                .thenComparingInt(s -> s.finding.path("ordinal").asInt()).thenComparing(s -> text(s.finding,"findingId")))
                .map(s -> {
                    var f = s.finding; String id = text(f,"findingId"), step = text(s.step,"stepType");
                    var comparison = findComparison(s.step,id); List<String> typed = typedFacts(comparison);
                    String title = businessTitle(f);
                    return new Item(runId+":"+step+":"+id,step,id,title,status(f,comparison),text(f,"summary"),
                            null,null,nullable(f,"recommendation"),typed,text(f,"source"),evidence(s.step,id,labels),true);
                }).toList();
    }

    private static String businessTitle(JsonNode finding) {
        String type = text(finding,"findingType");
        if (List.of("SIGNING_DATE","EFFECTIVE_DATE","PERFORMANCE_START_DATE","DELIVERY_DEADLINE","TERMINATION_DATE").contains(type)) return human(type);
        if (type.startsWith("ATTACHMENT_")) return "附件引用匹配";
        if (List.of("CONTRACT_COPIES","COPY_COUNT","COPY_COUNT_REQUIREMENT_COMPARISON").contains(type)) return "合同份数";
        if ("TEMPLATE_STRUCTURE_COMPARISON".equals(type)) return "合同模板结构";
        if ("EFFECTIVE_EVENT".equals(type)) return "合同生效条件";
        return switch(text(finding,"category")) {
            case "CONTRACT_STRUCTURE" -> "合同结构";
            case "PROJECT_IDENTITY" -> "项目身份";
            case "SUPPLIER_IDENTITY" -> "供应商身份";
            case "DELIVERY_TERMS" -> "交付与验收";
            case "SERVICE_WARRANTY" -> "服务与质保";
            case "SCOPE_AND_SUBCONTRACTING" -> "履约范围与分包";
            case "BREACH","BREACH_LIABILITY" -> "违约责任";
            case "TERM","TERM_TERMINATION" -> "期限与终止";
            case "PARTY_QUALIFICATION","SUPPLIER_QUALIFICATION" -> "主体资格";
            default -> "MODEL_ASSISTED".equals(text(finding,"source"))?"其他条款辅助提示":"条款事实核对";
        };
    }

    private static String status(JsonNode f,JsonNode comparison) {
        String code = text(f,"statusCode"), attention = text(f,"attentionCode"), risk = text(f,"riskLevel");
        if ("INSUFFICIENT".equals(code) || "INSUFFICIENT".equals(attention)
                || !code.isEmpty() && !List.of("NORMAL","INFO","PASS","MATCH","ATTENTION","REVIEW","NEEDS_REVIEW").contains(code)
                || comparison != null && "NOT_EVALUATED".equals(text(comparison,"result"))) return "INSUFFICIENT";
        if (List.of("ATTENTION","REVIEW","NEEDS_REVIEW").contains(code)
                || List.of("ATTENTION","REVIEW").contains(attention) || List.of("RED","YELLOW").contains(risk)
                || comparison != null && List.of("DIFFERENT","CONFLICT").contains(text(comparison,"result"))) return "ATTENTION";
        if (code.isEmpty() && !List.of("INFO","NORMAL","PASS","MATCH").contains(attention)) return "INSUFFICIENT";
        return "NORMAL";
    }

    private static List<Evidence> evidence(JsonNode step,String id,Map<String,String> labels) {
        List<Evidence> values = new ArrayList<>();
        for (var e:step.path("evidence")) if (id.equals(text(e,"findingId"))) {
            boolean located = e.path("locatorAvailable").asBoolean() && "VALIDATED".equals(text(e,"validationStatus"));
            String file = text(e,"fileId");
            values.add(new Evidence(text(e,"evidenceId"),file,labels.getOrDefault(file,"来源材料"),text(e,"boundedExcerpt"),located,
                    located?number(e,"pageNumber"):null,located?number(e,"paragraphIndex"):null,located?number(e,"segmentIndex"):null,
                    located?number(e,"startOffset"):null,located?number(e,"endOffset"):null));
        }
        return List.copyOf(values);
    }
    private static JsonNode findComparison(JsonNode step,String id) {
        for (var value:step.path("typedComparisons")) if (id.equals(text(value,"findingId"))) return value;
        return null;
    }
    private static List<String> typedFacts(JsonNode comparison) {
        if (comparison == null) return List.of(); List<String> facts = new ArrayList<>();
        for (var value:comparison.path("values")) {
            String key = text(value,"sideKey"); String side = key.startsWith("CONTRACT_BODY:")?"合同侧"
                    :key.startsWith("TENDER_DOCUMENT:")?"采购依据侧":key.startsWith("BID_RESPONSE:")?"响应依据侧":"对照侧";
            String state = text(value,"state");
            if (!"KNOWN".equals(state)) {
                facts.add(side+"："+switch(state) {case "NOT_EXTRACTED"->"未提取到可比较事实";
                    case "NOT_APPLICABLE"->"该场景不适用";case "CONFLICT"->"存在事实冲突";default->"事实状态待核实";});
                continue;
            }
            List<String> parts = new ArrayList<>();
            add(parts,"责任方",value,"responsibleParty"); add(parts,"基数",value,"calculationBasis");
            if (!value.path("rate").isNull() && value.has("rate")) parts.add("费率 "+decimal(value,"rate")+human(text(value,"rateUnit")));
            add(parts,"频次",value,"frequency"); add(parts,"周期",value,"period");
            addDecimal(parts,"上限费率",value,"capRate"); addDecimal(parts,"上限金额",value,"capAmount");
            add(parts,"解除阈值",value,"terminationThreshold"); add(parts,"日期类型",value,"dateType");
            add(parts,"日期",value,"normalizedDate"); add(parts,"期限起点",value,"termStart");
            add(parts,"期限",value,"duration"); add(parts,"生效条件",value,"effectiveCondition");
            add(parts,"终止条件",value,"terminationCondition");
            facts.add(side+"："+(parts.isEmpty()?"已取得可比较事实":String.join("；",parts)));
        }
        return List.copyOf(facts);
    }
    private static void add(List<String> parts,String label,JsonNode value,String key) {
        String text = nullable(value,key); if(text!=null) parts.add(label+" "+human(text));
    }
    private static void addDecimal(List<String> parts,String label,JsonNode value,String key) {
        if(value.hasNonNull(key)) parts.add(label+" "+decimal(value,key));
    }
    private static String decimal(JsonNode value,String key) { return value.path(key).decimalValue().toPlainString(); }
    private static String human(String value) {
        return switch(value) {
            case "PARTY_A"->"甲方";case "PARTY_B"->"乙方";case "BOTH"->"双方";
            case "CONTRACT_AMOUNT"->"合同总价";case "UNDELIVERED_AMOUNT"->"未交付部分金额";
            case "OTHER_OR_UNCLEAR"->"其他或尚不明确";
            case "PERCENT"->"%";case "PER_MILLE"->"‰";
            case "DAY","PER_DAY","DAILY"->"每天";case "WEEK","PER_WEEK","WEEKLY"->"每周";
            case "MONTH","PER_MONTH","MONTHLY"->"每月";case "EVENT"->"每次";
            case "ONCE","ONE_OFF"->"一次性";
            case "SIGNING_DATE"->"签约日期";case "EFFECTIVE_DATE"->"生效日期";
            case "PERFORMANCE_START_DATE"->"履约开始日期";case "DELIVERY_DEADLINE"->"交付日期";
            case "TERMINATION_DATE"->"终止日期";default->value;
        };
    }
    private static boolean semantic(Source s,String... categories) { return "SEMANTIC_AI".equals(text(s.step,"stepType")) && List.of(categories).contains(text(s.finding,"category")); }
    private static boolean type(Source s,String... types) { return List.of(types).contains(text(s.finding,"findingType")); }
    private static boolean attachment(Source s) { String type=text(s.finding,"findingType"); return type.startsWith("ATTACHMENT_") && (type.contains("REFERENCE")||type.contains("MATCH")); }
    private static boolean special(Source s) { return attachment(s)||type(s,"EFFECTIVE_EVENT","EFFECTIVE_DATE","CONTRACT_COPIES","COPY_COUNT","COPY_COUNT_REQUIREMENT_COMPARISON","TEMPLATE_STRUCTURE_COMPARISON"); }
    private static boolean hasStep(List<Source> sources,String step) {return sources.stream().anyMatch(s->step.equals(text(s.step,"stepType")));}
    private static boolean hasPrimary(List<Item> items,String step) {return items.stream().anyMatch(i->step.equals(i.outputType));}
    private static String text(JsonNode node,String key) {return node.path(key).asText("");}
    private static String nullable(JsonNode node,String key) {return node.hasNonNull(key)?node.path(key).asText():null;}
    private static Integer number(JsonNode node,String key) {return node.hasNonNull(key)?node.path(key).asInt():null;}
    private static String materialLabel(JsonNode material) {
        String role = switch(text(material,"effectiveRole")) {case "CONTRACT_BODY"->"合同正文";case "AWARD_NOTICE"->"成交或中标通知书";
            case "TENDER_DOCUMENT"->"采购文件";case "BID_RESPONSE"->"响应文件";case "SUPPLEMENT"->"补充材料";default->"其他材料";};
        return role+"（冻结材料 "+material.path("ordinal").asInt()+"）";
    }
    private record Source(JsonNode step,JsonNode finding) { }
    public record Projection(int version,String runId,String runStatus,String scopeStatement,List<Category> categories,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer opinionVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) String overallAdvice,
            @JsonInclude(JsonInclude.Include.NON_NULL) String overallOpinion,
            @JsonInclude(JsonInclude.Include.NON_NULL) ContractMonetaryObservation monetaryObservation) {
        public Projection(int version,String runId,String runStatus,String scopeStatement,List<Category> categories,
                Integer opinionVersion,String overallAdvice) {
            this(version,runId,runStatus,scopeStatement,categories,opinionVersion,overallAdvice,null,null);
        }
        public Projection(int version,String runId,String runStatus,String scopeStatement,List<Category> categories) {
            this(version,runId,runStatus,scopeStatement,categories,null,null,null,null);
        }
    }
    public record Category(String id,int order,String title,String coverage,String judgement,String statusLabel,
            int attentionCount,String summary,List<String> uncoveredScopes,List<Item> items,
            @JsonInclude(JsonInclude.Include.NON_NULL) String conciseOpinion) {
        public Category(String id,int order,String title,String coverage,String judgement,String statusLabel,
                int attentionCount,String summary,List<String> uncoveredScopes,List<Item> items) {
            this(id,order,title,coverage,judgement,statusLabel,attentionCount,summary,uncoveredScopes,items,null);
        }
    }
    public record Item(String stableId,String outputType,String outputId,String title,String status,String summary,
            String actualValue,String expectedValue,String recommendation,List<String> typedFacts,String source,List<Evidence> evidence,boolean primary) { }
    public record Evidence(String evidenceId,String fileId,String fileName,String excerpt,boolean locatorAvailable,
            Integer pageNumber,Integer paragraphIndex,Integer segmentIndex,Integer startOffset,Integer endOffset) { }
}
