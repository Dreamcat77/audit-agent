package com.fuquan.audit.common.material.application;

import com.fuquan.audit.common.material.domain.MaterialIntakeFields;
import com.fuquan.audit.common.material.domain.MaterialIntakeFields.Evidence;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Verify AI citations and normalize values; never calculate a business conclusion. */
@Component
public class MaterialFieldNormalizer {
    private static final Logger LOG = LoggerFactory.getLogger(MaterialFieldNormalizer.class);
    public record Segment(String locator, String text) {}
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("projectName", "项目名称|采购项目名称|采购名称");
        LABELS.put("projectNo", "项目编号|采购项目编号");
        LABELS.put("departmentName", "申请部门|需求部门|申报部门");
        LABELS.put("procurementType", "采购类型|采购类别");
        LABELS.put("budgetAmount", "预算金额|采购预算金额|采购预算");
        LABELS.put("subjectCode", "预算科目编码|预算科目代码|科目编码");
        LABELS.put("subjectName", "预算科目名称|科目名称");
        LABELS.put("procurementMethod", "采购方式");
        LABELS.put("requirementDescription", "需求说明和技术要求|需求说明|技术要求|采购需求");
        LABELS.put("applicantName", "申请人姓名|申请人|申报人");
    }
    private static final Set<String> METHODS = Set.of("公开招标", "邀请招标", "竞争性磋商", "竞争性谈判", "询价", "单一来源");
    public MaterialIntakeFields normalize(MaterialFieldAiPort.Result result, List<Segment> segments) {
        if (result == null || result.rows() == null || result.rows().size() > 100 || result.model() == null
                || result.model().isBlank() || result.promptVersion() == null || result.promptVersion().isBlank())
            throw new IllegalArgumentException("invalid or multi-project extraction");
        Map<String, String> source = new HashMap<>();
        segments.forEach(s -> {
            if (source.put(s.locator(), s.text()) != null) throw new IllegalArgumentException("duplicate source locator");
        });
        Map<String, List<Evidence>> evidence = new LinkedHashMap<>();
        Map<String, String> selected = new HashMap<>();
        List<String> conflicts = new ArrayList<>();
        int factCount = 0;
        for (var row : result.rows()) {
            if (row == null || row.facts() == null || row.facts().size() > 2000) throw new IllegalArgumentException("invalid extracted facts");
            factCount += row.facts().size();
            if (factCount > 2000) throw new IllegalArgumentException("too many extracted facts");
            for (var fact : row.facts()) {
                String text = source.get(fact.locator());
                if (!LABELS.containsKey(fact.field()) || text == null || fact.value() == null || fact.value().isBlank()
                        || fact.excerpt() == null || fact.excerpt().isBlank() || fact.excerpt().length() > 2400
                        || !text.contains(fact.excerpt()) || !fact.excerpt().contains(fact.value())) {
                    LOG.info("MATERIAL_FACT_EXCLUDED stage=FIELD_NORMALIZATION reasonCode=EVIDENCE_UNVERIFIED count=1");
                    continue;
                }
                try {
                    verifyExplicitRole(fact, text);
                } catch (IllegalArgumentException unverified) {
                    // A single role-mismatched or otherwise uncertain candidate must
                    // not discard independently grounded fields from this material.
                    LOG.info("MATERIAL_FACT_EXCLUDED stage=FIELD_NORMALIZATION reasonCode=FIELD_ROLE_MISMATCH count=1");
                    continue;
                }
                evidence.computeIfAbsent(fact.field(), key -> new ArrayList<>())
                        .add(new Evidence(fact.value(), fact.locator(), fact.excerpt()));
            }
        }
        // Model row keys are local to each chunk, not durable project identities.
        // Conflicting, grounded project identities cannot be auto-filled into one demand.
        for (String identity : List.of("projectNo", "projectName")) {
            if (evidence.getOrDefault(identity, List.of()).stream()
                    .map(e -> Normalizer.normalize(e.value(), Normalizer.Form.NFKC).strip())
                    .distinct().count() > 1) throw new MultipleProjectMaterial();
        }
        // Explicit labels supplement AI omissions, with the same source evidence contract.
        boolean inRequirements = false;
        for (var segment : segments) {
            for (String line : segment.text().split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.matches("^(?:[一二三四五六七八九十]+[、.．]|第.+章).*")) {
                    inRequirements = trimmed.matches(".*(?:技术要求|采购需求|需求说明).*" );
                }
                for (var label : LABELS.entrySet()) {
                    var match = Pattern.compile("(?:^|[|；;]\\s*)(?:第[0-9一二三四五六七八九十]+条\\s*)?(?:" + label.getValue()
                            + ")\\s*[:：]\\s*([^|；;]+)").matcher(line);
                    while (match.find()) {
                        String raw = match.group(1).strip();
                        if (!raw.isBlank()) addEvidence(evidence, label.getKey(), raw, segment.locator(), line);
                    }
                }
                if (inRequirements && !trimmed.isBlank() && !trimmed.matches(".*(?:技术要求|采购需求|需求说明)\\s*[:：]?")) {
                    addEvidence(evidence, "requirementDescription", trimmed, segment.locator(), line);
                }
            }
        }
        for (var entry : evidence.entrySet()) {
            var distinct = entry.getValue().stream().map(e -> normalize(entry.getKey(), e.value()))
                    .filter(Objects::nonNull).distinct().toList();
            boolean invalid = entry.getValue().stream().anyMatch(e -> normalize(entry.getKey(), e.value()) == null);
            if (entry.getKey().equals("requirementDescription") && !invalid) {
                // A requirement section has multiple complementary statements, not scalar conflicts.
                String joined = String.join("\n", distinct);
                // The complete requirements already live in the CLOB evidence.
                // A bounded, explicitly labelled excerpt fits the existing form
                // and deployed VARCHAR columns; length alone is not a conflict.
                selected.put(entry.getKey(), joined.length() <= 1000 ? joined
                    : "以下为部分原文摘录，完整需求以所选材料为准：\n" + joined.substring(0, joined.offsetByCodePoints(0, Math.min(800, joined.codePointCount(0,joined.length())))));
            } else {
                if (distinct.size() == 1 && !invalid) selected.put(entry.getKey(), distinct.get(0));
                if (distinct.size() > 1 || invalid) conflicts.add(entry.getKey());
            }
            entry.setValue(List.copyOf(entry.getValue()));
        }
        return new MaterialIntakeFields(selected.get("projectName"), selected.get("projectNo"),
                selected.get("departmentName"), selected.get("procurementType"),
                selected.containsKey("budgetAmount") ? new BigDecimal(selected.get("budgetAmount")) : null,
                selected.get("subjectCode"), selected.get("subjectName"), selected.get("procurementMethod"),
                selected.get("requirementDescription"), selected.get("applicantName"),
                Collections.unmodifiableMap(evidence), List.copyOf(conflicts), result.model(), result.promptVersion());
    }
    public static final class MultipleProjectMaterial extends IllegalArgumentException {
        public MultipleProjectMaterial() { super("multiple grounded project identities"); }
    }
    private static void verifyExplicitRole(MaterialFieldAiPort.Fact fact,String text) {
        // A true substring under a different explicit label is not evidence for
        // the proposed field. Scan original rows so a cropped excerpt cannot hide it.
        boolean explicit=false,compatible=false;
        var roles=new LinkedHashMap<>(LABELS);
        roles.put("quoteAmount", "投标报价|投标总价|响应报价|响应总价|报价总额|报价金额|含税总价");
        roles.put("contractAmount","合同金额|合同价款");
        roles.put("awardAmount","中标金额|成交金额");
        roles.put("ceilingAmount","最高限价|控制价");
        for(String line:text.split("\\R"))for(var role:roles.entrySet()) {
            var match=Pattern.compile("(?:^|[|；;，,]\\s*)(?:"+role.getValue()+")\\s*[:：]\\s*([^|；;，,]+)").matcher(line);
            while(match.find())if(explicitValueMatches(fact, match.group(1).strip())) {
                explicit=true;if(role.getKey().equals(fact.field()))compatible=true;
            }
        }
        if(explicit&&!compatible)throw new IllegalArgumentException("material explicit field role mismatch");
    }
    private static boolean explicitValueMatches(MaterialFieldAiPort.Fact fact, String sourceValue) {
        if (sourceValue.equals(fact.value())) return true;
        if (!Set.of("budgetAmount", "contractAmount", "awardAmount", "ceilingAmount").contains(fact.field())) return false;
        var candidate = com.fuquan.audit.common.domain.RmbAmountParser.parse(fact.value()).orElse(null);
        var source = com.fuquan.audit.common.domain.RmbAmountParser.parse(sourceValue).orElse(null);
        return candidate != null && source != null && candidate.compareTo(source) == 0;
    }
    private static void addEvidence(Map<String, List<Evidence>> target, String field, String value,
                                    String locator, String excerpt) {
        if (excerpt.length() > 2400 || value.length() > 2000) return;
        var fact = new Evidence(value, locator, excerpt);
        var facts = target.computeIfAbsent(field, key -> new ArrayList<>());
        if (facts.stream().noneMatch(e -> e.locator().equals(locator) && e.value().equals(value))) facts.add(fact);
    }
    private static String normalize(String field, String value) {
        value = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        if (value.isBlank() || value.length() > (field.equals("requirementDescription") ? 2000 : 200)
                || Set.of("待填", "待定", "暂无", "无", "N/A").contains(value)) return null;
        if (field.equals("budgetAmount")) {
            var amount = com.fuquan.audit.common.domain.RmbAmountParser.parse(value).orElse(null);
            return amount == null || amount.signum() <= 0 ? null : amount.toPlainString();
        }
        if (field.equals("procurementType") && !Set.of("货物", "服务", "工程").contains(value)) return null;
        if (field.equals("procurementMethod") && !METHODS.contains(value)) return null;
        return value;
    }
}
