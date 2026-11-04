package com.fuquan.audit.contract.application.clause;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.Normalizer;
import org.springframework.stereotype.Component;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;

@Component
public final class ContractTermClauseAnalyzer {
    private static final Pattern EFFECTIVE_EVENT = Pattern.compile(
            "(?:签字[^。！？；\\n]{0,16}(?:盖章|签章)|(?:盖章|签章)[^。！？；\\n]{0,16}签字|签章)"
                    + "[^。！？；\\n]{0,20}(?:后|之日|之日起|时|完成)?[^。！？；\\n]{0,8}生效");
    private static final String LABEL = "(签约日期|签订日期|签署日期|签订时间|签署时间|生效日期|生效时间|履约开始日期|交付截止日期|交付截止|交付期限|交付日期|完成日期|终止日期|终止时间|到期日期|到期日)";
    private static final String DATE_CONNECTOR = "\\s*(?:[:：]|为)?\\s*";
    private static final String DATE_VALUE = "(([0-9零〇一二三四五六七八九壹贰叁肆伍陆柒捌玖]{4})\\s*[-/.年]\\s*([0-9零〇一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾]{1,3})\\s*[-/.月]\\s*([0-9零〇一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾]{1,3})\\s*日?)";
    private static final Pattern DATE = Pattern.compile(LABEL + DATE_CONNECTOR + DATE_VALUE);
    private static final Pattern TERM_RANGE = Pattern.compile(
            "(?:服务期(?:限)?|履约期(?:限)?|合同期(?:限)?)\\s*(?:[:：]|为)?\\s*(?:自|从)?\\s*"
            + DATE_VALUE + "\\s*(?:起)?\\s*(?:至|到)\\s*" + DATE_VALUE + "\\s*(?:止)?");
    private static final Pattern NATURAL_DELIVERY = Pattern.compile("(?:(?:全部)?成果于|于|在)\\s*" + DATE_VALUE
            + "\\s*(?:之前|前)?\\s*(?:(?:完成)?交付|完成(?:系统)?部署[^。！？；;\\r\\n]{0,48}交付)"
            + "[^。！？；;\\r\\n]{0,64}[。；;]");
    private static final Pattern NATURAL_COMPLETION = Pattern.compile(
            "(?:交付|部署)[^。！？；;\\r\\n]{0,48}(?:于|在)\\s*" + DATE_VALUE
                    + "\\s*(?:之前|前)?\\s*完成[。；;]");
    private static final Pattern RESULT_DEPLOYMENT = Pattern.compile("(?:全部)?成果于\\s*" + DATE_VALUE
            + "\\s*(?:之前|前)?\\s*完成部署[^。！？；;\\r\\n]{0,48}[。；;]");
    private static final Pattern BLANK_DATE = Pattern.compile(LABEL
            + DATE_CONNECTOR + "年\\s*月\\s*日");
    private static final Pattern COPY_COUNT = Pattern.compile(
            "本合同[^。！？；\\n]{0,12}?一式\\s*([0-9零〇一二两三四五六七八九十百壹贰叁肆伍陆柒捌玖拾佰]+)\\s*份");
    private static final Pattern TEMPLATE_SECTION_START = Pattern.compile(
            "(?m)^\\s*[【\\[]?(?:指定)?合同(?:模板|范本)(?:开始|起始)[】\\]]?\\s*$");
    private static final Pattern TEMPLATE_SECTION_END = Pattern.compile(
            "(?m)^\\s*[【\\[]?(?:指定)?合同(?:模板|范本)(?:结束|终止)[】\\]]?\\s*$");
    private static final Pattern ATTACHMENT_LIST = Pattern.compile(
            "(?:附件清单|本合同附件包括)\\s*[：:]\\s*([^。\\n]{2,160})");
    private static final Pattern ATTACHMENT_SECTION = Pattern.compile(
            "(?m)^\\s*(?:(?:第[一二三四五六七八九十百0-9]+[章节条]|[一二三四五六七八九十]+、)"
                    + "[^。！？；;…\\r\\n]{0,32})?附件清单[^。！？；;…\\r\\n]{0,20}$");
    private static final Pattern NUMBERED_ATTACHMENT = Pattern.compile(
            "附件\\s*[一二三四五六七八九十0-9]+\\s*(?:[：:]?\\s*《([^》\\r\\n]{2,60})》|[：:]\\s*([^；;。、，\\r\\n（）()《》]{2,60}))");
    private static final Pattern INLINE_ATTACHMENT = Pattern.compile(
            "附件\\s*[一二三四五六七八九十0-9]+\\s*《([^》\\r\\n]{2,60})》\\s*(?:为|是|作为|系)[^。；;\\r\\n]{0,12}(?:组成部分|不可分割)");
    private static final Pattern SECTION_HEADING = Pattern.compile(
            "^\\s*(?:第[一二三四五六七八九十百0-9]+[章节条]|[一二三四五六七八九十]+、).*");
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "^\\s*(?:第[一二三四五六七八九十百0-9]+[章节]|[一二三四五六七八九十]+、)[^。！？；;\\r\\n]*$");
    private static final Pattern NEGATED_TERMINATION = Pattern.compile(
            "(?:不得|无权|不能|不应|不允许)[^。！？；;\\r\\n]{0,8}(?:解除|终止)");
    private static final Pattern CONDITION_NOT_APPLICABLE = Pattern.compile(
            "(生效条件|终止条件)\\s*[：:]?\\s*(?:不适用|无|不涉及)(?:[。；;]|$)");
    private static final List<StructureHeading> STRUCTURE_HEADINGS = List.of(
            new StructureHeading("合同标的", List.of("合同标的", "服务内容", "采购内容")),
            new StructureHeading("合同价款", List.of("合同价款", "合同金额", "合同总价")),
            new StructureHeading("履行期限", List.of("履行期限", "服务期限", "交付期限")),
            new StructureHeading("违约责任", List.of("违约责任")),
            new StructureHeading("争议解决", List.of("争议解决", "争议的解决")),
            new StructureHeading("合同生效", List.of("合同生效", "生效条件")));

    public ContractTermClauseReview analyze(List<ContractClauseMaterial> materials) {
        List<ContractTermClauseReview.DateItem> dates = new ArrayList<>();
        List<ContractTermClauseReview.ConditionItem> conditions = new ArrayList<>();
        List<DateEvidence> dateEvidence = new ArrayList<>();
        List<ConditionEvidence> conditionEvidence = new ArrayList<>();
        for (ContractClauseMaterial material : materials) {
            Matcher dateMatcher = DATE.matcher(material.text());
            while (dateMatcher.find()) {
                String source = dateMatcher.group(2);
                String normalized = normalize(dateMatcher.group(3), dateMatcher.group(4), dateMatcher.group(5));
                ContractTermClauseReview.DateItem item = new ContractTermClauseReview.DateItem(
                        dateType(dateMatcher.group(1)), source, normalized,
                        normalized == null ? "INSUFFICIENT" : "NORMAL",
                        material.fileId(), dateMatcher.group(), true);
                dates.add(item);
                dateEvidence.add(new DateEvidence(item, material));
            }
            Matcher range = TERM_RANGE.matcher(material.text());
            while (range.find()) {
                String prefix = material.text().substring(Math.max(0, range.start() - 12), range.start());
                if (prefix.matches("(?s).*(?:预计|暂定|例如|示例|不适用|并非|不是|不按)\\s*$")) continue;
                for (int offset : new int[]{0, 4}) {
                    String normalized = normalize(range.group(offset + 2), range.group(offset + 3), range.group(offset + 4));
                    var item = new ContractTermClauseReview.DateItem(offset == 0 ? "PERFORMANCE_START_DATE" : "TERMINATION_DATE",
                            range.group(offset + 1), normalized, normalized == null ? "INSUFFICIENT" : "NORMAL",
                            material.fileId(), range.group(), true);
                    dates.add(item);
                    dateEvidence.add(new DateEvidence(item, material));
                }
            }
            var naturalLocations = new HashSet<String>();
            for (Pattern syntax : List.of(NATURAL_DELIVERY, NATURAL_COMPLETION, RESULT_DEPLOYMENT)) {
                Matcher delivery = syntax.matcher(material.text());
                while (delivery.find()) {
                    if (!uniqueDeliveryDate(material.text(), delivery)) continue;
                    if (!naturalLocations.add(delivery.start() + ":" + delivery.end())) continue;
                    String normalized = normalize(delivery.group(2), delivery.group(3), delivery.group(4));
                    if (normalized == null) continue;
                    var item = new ContractTermClauseReview.DateItem("DELIVERY_DEADLINE", delivery.group(1),
                            normalized, "NORMAL", material.fileId(), delivery.group(), true);
                    dates.add(item);
                    dateEvidence.add(new DateEvidence(item, material));
                }
            }
            Matcher blankMatcher = BLANK_DATE.matcher(material.text());
            while (blankMatcher.find()) {
                ContractTermClauseReview.DateItem item = new ContractTermClauseReview.DateItem(
                        dateType(blankMatcher.group(1)), null, null, "INSUFFICIENT",
                        material.fileId(), blankMatcher.group(), true);
                dates.add(item);
                dateEvidence.add(new DateEvidence(item, material));
            }
            Matcher copyMatcher = COPY_COUNT.matcher(material.text());
            while (copyMatcher.find()) {
                Integer count = parseCopyCount(copyMatcher.group(1));
                if (count != null && count > 0) {
                    conditions.add(new ContractTermClauseReview.ConditionItem(
                            "CONTRACT_COPIES", count.toString(), "INSUFFICIENT",
                            material.fileId(), copyMatcher.group(), true));
                }
            }
            for (String clause : clauses(material.text())) {
                String notApplicableType = notApplicableConditionType(clause);
                if (notApplicableType != null) {
                    var item = new ContractTermClauseReview.ConditionItem(
                            notApplicableType, "不适用", "NORMAL", material.fileId(), clause, true);
                    conditions.add(item);
                    conditionEvidence.add(new ConditionEvidence(item, material,
                            "NOT_APPLICABLE", null));
                } else if (isEffectiveEvent(clause)) {
                    var item = new ContractTermClauseReview.ConditionItem(
                            "EFFECTIVE_EVENT", clause, "NORMAL", material.fileId(), clause, true);
                    conditions.add(item);
                    conditionEvidence.add(new ConditionEvidence(item, material,
                            "KNOWN", "SIGNATURE_AND_SEAL"));
                } else if (isTerminationCondition(clause)) {
                    var item = new ContractTermClauseReview.ConditionItem(
                            "TERMINATION", clause, "NORMAL", material.fileId(), clause, true);
                    conditions.add(item);
                    conditionEvidence.add(new ConditionEvidence(item, material,
                            "KNOWN", terminationSemanticValue(clause)));
                }
            }
        }
        boolean hasContractBody = materials.stream()
                .anyMatch(material -> material.category() == ProjectFileCategory.CONTRACT_BODY);
        List<java.util.UUID> contractFiles = materials.stream()
                .filter(material -> material.category() == ProjectFileCategory.CONTRACT_BODY)
                .map(ContractClauseMaterial::fileId).toList();
        List<ContractTermClauseReview.DateItem> authoritativeDates = hasContractBody
                ? dates.stream().filter(item -> contractFiles.contains(item.sourceFileId())).toList() : dates;
        List<ContractTermClauseReview.ConditionItem> authoritativeConditions = hasContractBody
                ? conditions.stream().filter(item -> contractFiles.contains(item.sourceFileId())).toList() : conditions;
        return new ContractTermClauseReview(authoritativeDates, authoritativeConditions, materials.isEmpty(),
                comparisons(dateEvidence, conditionEvidence, materials), deterministicChecks(materials, authoritativeDates, authoritativeConditions));
    }

    private static List<ContractTermClauseReview.DeterministicCheck> deterministicChecks(
            List<ContractClauseMaterial> materials, List<ContractTermClauseReview.DateItem> dates,
            List<ContractTermClauseReview.ConditionItem> conditions) {
        List<ContractTermClauseReview.DeterministicCheck> checks = new ArrayList<>();
        templateStructureCheck(materials).ifPresent(checks::add);
        checks.addAll(attachmentChecks(materials));
        checks.addAll(ContractDocumentChecks.analyze(materials, dates, conditions));
        return List.copyOf(checks);
    }

    private static java.util.Optional<ContractTermClauseReview.DeterministicCheck> templateStructureCheck(
            List<ContractClauseMaterial> materials) {
        ContractClauseMaterial contract = materials.stream()
                .filter(value -> value.category() == ProjectFileCategory.CONTRACT_BODY)
                .findFirst().orElse(null);
        TemplateSection template = materials.stream()
                .filter(value -> value.category() == ProjectFileCategory.TENDER_DOCUMENT)
                .map(ContractTermClauseAnalyzer::boundedTemplateSection)
                .flatMap(java.util.Optional::stream)
                .findFirst().orElse(null);
        if (contract == null || template == null) return java.util.Optional.empty();
        List<StructureHeading> requiredHeadings = STRUCTURE_HEADINGS.stream()
                .filter(value -> containsAny(template.text(), value.aliases()))
                .sorted(java.util.Comparator.comparingInt(value -> headingPosition(template.text(), value)))
                .toList();
        List<String> required = requiredHeadings.stream().map(StructureHeading::label).toList();
        if (required.size() < 2) return java.util.Optional.empty();
        List<String> missing = requiredHeadings.stream()
                .filter(value -> !containsAny(contract.text(), value.aliases()))
                .map(StructureHeading::label).toList();
        List<String> expectedPresentOrder = requiredHeadings.stream()
                .filter(value -> containsAny(contract.text(), value.aliases()))
                .map(StructureHeading::label).toList();
        List<String> actualPresentOrder = requiredHeadings.stream()
                .filter(value -> containsAny(contract.text(), value.aliases()))
                .sorted(java.util.Comparator.comparingInt(value -> headingPosition(contract.text(), value)))
                .map(StructureHeading::label).toList();
        boolean orderMismatch = !expectedPresentOrder.equals(actualPresentOrder);
        String contractAnchor = firstNonBlankLine(contract.text());
        if (contractAnchor == null) return java.util.Optional.empty();
        String status = missing.isEmpty() && !orderMismatch ? "NORMAL" : "REVIEW";
        String summary = missing.isEmpty() && !orderMismatch
                ? "已按冻结材料中的指定模板核对合同结构，模板要求的结构项均可识别。"
                : "已按冻结材料中的指定模板核对合同结构；"
                        + (missing.isEmpty() ? "" : "当前合同未识别到：" + String.join("、", missing) + "。")
                        + (orderMismatch ? "已识别条款顺序与指定模板不一致。" : "");
        return java.util.Optional.of(new ContractTermClauseReview.DeterministicCheck(
                "TEMPLATE_STRUCTURE_COMPARISON", status, summary,
                "NORMAL".equals(status) ? null : "请人工核对模板结构项、顺序及其适用性。",
                List.of(new ContractTermClauseReview.CheckSource(contract.fileId(), contractAnchor),
                        new ContractTermClauseReview.CheckSource(template.material().fileId(), template.anchor()))));
    }

    private static java.util.Optional<TemplateSection> boundedTemplateSection(ContractClauseMaterial material) {
        Matcher start = TEMPLATE_SECTION_START.matcher(material.text());
        if (!start.find()) return java.util.Optional.empty();
        Matcher end = TEMPLATE_SECTION_END.matcher(material.text());
        if (!end.find(start.end()) || end.start() <= start.end()) return java.util.Optional.empty();
        String section = material.text().substring(start.end(), end.start());
        if (section.isBlank()) return java.util.Optional.empty();
        return java.util.Optional.of(new TemplateSection(material, section, start.group().strip()));
    }

    private static List<ContractTermClauseReview.DeterministicCheck> attachmentChecks(
            List<ContractClauseMaterial> materials) {
        List<ContractTermClauseReview.DeterministicCheck> checks = new ArrayList<>();
        for (ContractClauseMaterial contract : materials) {
            if (contract.category() != ProjectFileCategory.CONTRACT_BODY) continue;
            Matcher lists = ATTACHMENT_LIST.matcher(contract.text());
            while (lists.find()) {
                String declaration = lists.group();
                for (String raw : lists.group(1).split("[、,，;；]")) {
                    String reference = raw.strip().replaceFirst("^附件[一二三四五六七八九十0-9]+[：:\\s]*", "");
                    if (reference.length() < 2 || reference.length() > 60) continue;
                    ContractTermClauseReview.CheckSource matched = findAttachment(
                            materials, contract, lists.end(), reference);
                    String status = matched == null ? "ATTENTION" : "NORMAL";
                    String summary = matched == null
                            ? "合同正文引用附件“" + reference + "”，但本次冻结材料中未能证明匹配。"
                            : "合同正文引用附件“" + reference + "”，并在本次冻结材料中找到可定位内容。";
                    List<ContractTermClauseReview.CheckSource> sources = new ArrayList<>();
                    sources.add(new ContractTermClauseReview.CheckSource(contract.fileId(), declaration));
                    if (matched != null) sources.add(matched);
                    checks.add(new ContractTermClauseReview.DeterministicCheck(
                            "ATTACHMENT_REFERENCE_MATCH", status, summary,
                            matched == null ? "请核对该附件是否另行提供或已完整内嵌。" : null, sources));
                }
            }
            Matcher sections = ATTACHMENT_SECTION.matcher(contract.text());
            while (sections.find()) {
                // Only the first explicit numbered-list paragraph in this bounded section is
                // a declaration. Later notes or another chapter cannot manufacture attachments.
                int end = Math.min(contract.text().length(), sections.end() + 1600);
                String tail = contract.text().substring(sections.end(), end);
                int consumed = sections.end();
                for (String line : tail.split("\\R", -1)) {
                    int lineStart = contract.text().indexOf(line, consumed);
                    consumed = lineStart + line.length();
                    if (SECTION_HEADING.matcher(line).matches()) break;
                    Matcher numbered = NUMBERED_ATTACHMENT.matcher(line);
                    Map<String, String> references = new LinkedHashMap<>();
                    while (numbered.find()) {
                        String reference = (numbered.group(1) != null ? numbered.group(1) : numbered.group(2)).strip();
                        references.putIfAbsent(attachmentIdentity(reference), reference);
                    }
                    if (references.isEmpty()) continue;
                    String declaration = line.strip();
                    for (String reference : references.values()) {
                        var matched = findAttachment(materials, contract, consumed, reference);
                        List<ContractTermClauseReview.CheckSource> sources = new ArrayList<>();
                        sources.add(new ContractTermClauseReview.CheckSource(contract.fileId(), declaration));
                        if (matched != null) sources.add(matched);
                        checks.add(new ContractTermClauseReview.DeterministicCheck("ATTACHMENT_REFERENCE_MATCH",
                                matched == null ? "ATTENTION" : "NORMAL",
                                matched == null ? "合同正文引用附件“" + reference + "”，但本次冻结材料中未能证明匹配。"
                                        : "合同正文引用附件“" + reference + "”，并在本次冻结材料中找到可定位内容。",
                                matched == null ? "请核对该附件是否另行提供或已完整内嵌。" : null, sources));
                    }
                    break;
                }
            }
        }
        for (var contract : materials) if (contract.category() == ProjectFileCategory.CONTRACT_BODY) {
            var inline = INLINE_ATTACHMENT.matcher(contract.text());
            while (inline.find()) {
                String reference = inline.group(1);
                if (checks.stream().anyMatch(c -> c.type().equals("ATTACHMENT_REFERENCE_MATCH")
                        && c.summary().contains("“" + reference + "”")
                        && c.sources().stream().anyMatch(s -> s.fileId().equals(contract.fileId())))) continue;
                var match = findAttachment(materials, contract, inline.end(), reference);
                var sources = new ArrayList<ContractTermClauseReview.CheckSource>();
                sources.add(new ContractTermClauseReview.CheckSource(contract.fileId(), inline.group()));
                if (match != null) sources.add(match);
                checks.add(new ContractTermClauseReview.DeterministicCheck("ATTACHMENT_REFERENCE_MATCH", match == null ? "ATTENTION" : "NORMAL",
                        match == null ? "合同正文引用附件“" + reference + "”，但本次冻结材料中未能证明匹配。"
                                : "合同正文引用附件“" + reference + "”，并在本次冻结材料中找到可定位内容。",
                        match == null ? "请核对该附件是否另行提供或已完整内嵌。" : null, sources));
            }
        }
        return List.copyOf(checks);
    }

    private static ContractTermClauseReview.CheckSource findEmbeddedAttachment(
            ContractClauseMaterial contract, int declarationEnd, String reference) {
        String remainder = contract.text().substring(Math.min(declarationEnd, contract.text().length()));
        Pattern headingAndBody = Pattern.compile("(?m)^(?:附件(?:[一二三四五六七八九十0-9]+)?\\s*[：:]?\\s*)?"
                + Pattern.quote(reference) + "\\s*\\R(?:[\\t \\p{Z}]*\\R)*([^\\r\\n]+)$");
        Matcher match = headingAndBody.matcher(remainder);
        if (match.find() && !looksLikeAttachmentHeading(match.group(1).strip())) {
            return new ContractTermClauseReview.CheckSource(contract.fileId(), match.group().strip());
        }
        return null;
    }

    private static boolean looksLikeAttachmentHeading(String value) {
        return value.matches("^附件(?:[一二三四五六七八九十0-9]+)?\\s*[：:].*$")
                || value.matches("^(?:附件清单|本合同附件包括)\\s*[：:].*$");
    }

    private static ContractTermClauseReview.CheckSource findAttachment(
            List<ContractClauseMaterial> materials, ContractClauseMaterial contract,
            int declarationEnd, String reference) {
        ContractTermClauseReview.CheckSource embedded = findEmbeddedAttachment(
                contract, declarationEnd, reference);
        if (embedded != null) return embedded;
        String expected = attachmentIdentity(reference);
        if (expected.isEmpty()) return null;
        List<ContractTermClauseReview.CheckSource> matches = new ArrayList<>();
        for (ContractClauseMaterial material : materials) {
            if (material.fileId().equals(contract.fileId())
                    || material.category() == ProjectFileCategory.CONTRACT_BODY
                    || material.category() == ProjectFileCategory.PACKAGE
                    ) continue;
            // A body heading plus substantive text identifies an annex even when
            // its filename is generic. A passing mention never proves an annex.
            var headingMatch = findEmbeddedAttachment(material, 0, reference);
            if (headingMatch != null) { matches.add(headingMatch); continue; }
            if (material.category() == ProjectFileCategory.UNKNOWN
                    || !attachmentIdentity(material.fileName()).equals(expected)) continue;
            String anchor = readableAttachmentAnchor(material.text());
            if (anchor != null) matches.add(new ContractTermClauseReview.CheckSource(material.fileId(), anchor));
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static String readableAttachmentAnchor(String text) {
        List<String> lines = java.util.Arrays.stream(text.split("\\R"))
                .map(String::strip).filter(value -> !value.isEmpty()).toList();
        if (lines.size() < 2) return null;
        String body = lines.get(1);
        if (body.replaceAll("\\s+", "").length() < 4) return null;
        return body.length() <= 240 ? body : body.substring(0, 240);
    }

    private static String attachmentIdentity(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .replaceFirst("(?i)\\.(?:pdf|docx?|xlsx?|png|jpe?g)$", "")
                .replaceFirst("^[0-9]{1,3}[_ -]+(?=附件[一二三四五六七八九十0-9]+)", "")
                .replaceFirst("^附件[一二三四五六七八九十0-9]+[：:\\s]*", "");
        return normalized.replaceAll("[\\s·•—_()（）\\[\\]【】《》<>:：,，。.;；]+", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean containsAny(String text, List<String> values) {
        return values.stream().anyMatch(text::contains);
    }

    private static int headingPosition(String text, StructureHeading heading) {
        return heading.aliases().stream().mapToInt(text::indexOf)
                .filter(index -> index >= 0).min().orElse(Integer.MAX_VALUE);
    }

    private static String firstNonBlankLine(String text) {
        return java.util.Arrays.stream(text.split("\\R")).map(String::strip)
                .filter(value -> !value.isEmpty()).findFirst().orElse(null);
    }

    private static List<ClauseComparison> comparisons(List<DateEvidence> evidence,
            List<ConditionEvidence> conditionEvidence, List<ContractClauseMaterial> materials) {
        Map<String, LinkedHashMap<java.util.UUID, List<DateEvidence>>> byType = new LinkedHashMap<>();
        for (DateEvidence value : evidence) {
            byType.computeIfAbsent(value.item().type(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(value.material().fileId(), ignored -> new ArrayList<>()).add(value);
        }
        List<ClauseComparison> comparisons = new ArrayList<>();
        byType.forEach((type, byFile) -> {
            List<ContractClauseMaterial> participants = materials.stream()
                    .filter(material -> comparisonParticipant(material, byFile.containsKey(material.fileId())))
                    .toList();
            if (participants.size() < 2) return;
            List<DateSide> sides = participants.stream().map(material ->
                    dateSide(material, byFile.getOrDefault(material.fileId(), List.of()))).toList();
            List<String> normalized = sides.stream().flatMap(value -> value.values().stream())
                    .map(value -> value.item().normalizedDate()).filter(java.util.Objects::nonNull)
                    .distinct().toList();
            boolean conflict = sides.stream().anyMatch(value -> "CONFLICT".equals(value.state()));
            boolean insufficient = sides.stream().anyMatch(value -> "NOT_EXTRACTED".equals(value.state()));
            String status = conflict || normalized.size() > 1 ? "REVIEW"
                    : insufficient || normalized.isEmpty() ? "INSUFFICIENT" : "NORMAL";
            String message = conflict ? "同一冻结材料存在相互冲突的日期：" + String.join("、", normalized)
                    : normalized.size() > 1 ? "所选材料日期不一致：" + String.join("、", normalized)
                    : insufficient || normalized.isEmpty() ? "部分所选材料的日期未能由程序确认，请核对本次冻结原文"
                    : "所选材料日期一致：" + normalized.get(0);
            comparisons.add(new ClauseComparison("TERM_DATE:" + type, dateLabel(type), status, message,
                    sides.stream().flatMap(value -> value.values().stream()).map(value ->
                            new ClauseComparison.Source(value.material().fileId(), value.material().fileName(),
                                    value.material().category().name(), value.item().excerpt()))
                            .toList(),
                    participants.stream().map(material -> {
                        DateSide side = sides.stream().filter(value ->
                                value.material().fileId().equals(material.fileId())).findFirst().orElseThrow();
                        DateEvidence value = "KNOWN".equals(side.state()) ? side.value() : null;
                        String normalizedDate = value == null ? null : value.item().normalizedDate();
                        return new ClauseComparison.TypedSide(
                                material.category().name() + ":" + material.fileId(), material.fileId(),
                                side.state(), type, null, null, null, null, null, null, null, null,
                                null, type, normalizedDate,
                                "PERFORMANCE_START_DATE".equals(type) ? normalizedDate : null,
                                null, null, null);
                    }).toList()));
        });
        comparisons.addAll(conditionComparisons(conditionEvidence, materials));
        return List.copyOf(comparisons);
    }

    private static List<ClauseComparison> conditionComparisons(List<ConditionEvidence> evidence,
            List<ContractClauseMaterial> materials) {
        Map<String, LinkedHashMap<java.util.UUID, List<ConditionEvidence>>> byType = new LinkedHashMap<>();
        for (ConditionEvidence value : evidence) {
            byType.computeIfAbsent(value.item().type(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(value.material().fileId(), ignored -> new ArrayList<>()).add(value);
        }
        List<ClauseComparison> comparisons = new ArrayList<>();
        byType.forEach((type, byFile) -> {
            List<ContractClauseMaterial> participants = materials.stream()
                    .filter(material -> comparisonParticipant(material, byFile.containsKey(material.fileId())))
                    .toList();
            if (participants.size() < 2) return;
            List<ConditionSide> sides = participants.stream().map(material ->
                    conditionSide(material, byFile.getOrDefault(material.fileId(), List.of()))).toList();
            boolean conflict = sides.stream().anyMatch(value -> "CONFLICT".equals(value.state()));
            boolean unavailable = sides.stream().anyMatch(value -> !"KNOWN".equals(value.state()));
            List<String> semanticValues = sides.stream().map(ConditionSide::semanticValue)
                    .filter(java.util.Objects::nonNull).toList();
            boolean comparable = !conflict && !unavailable
                    && semanticValues.size() == sides.size()
                    && (!"TERMINATION".equals(type) || sides.stream()
                            .allMatch(side -> side.value().terminationIdentity().complete()));
            boolean knownDifference = "TERMINATION".equals(type) && terminationDifference(
                    sides.stream().flatMap(side -> side.values().stream()).toList());
            String status = conflict || knownDifference ? "REVIEW" : !comparable ? "INSUFFICIENT"
                    : semanticValues.stream().distinct().count() > 1 ? "REVIEW" : "NORMAL";
            String label = "EFFECTIVE_EVENT".equals(type) ? "生效条件" : "终止条件";
            String message = conflict ? "同一冻结材料存在相互冲突的" + label
                    : "REVIEW".equals(status) ? "所选材料的" + label + "不一致"
                    : !comparable ? "部分所选材料的" + label + "不可进行语义比较"
                    : "TERMINATION".equals(type)
                            ? "所选材料已识别的终止触发类型"
                                    + (semanticValues.stream().allMatch(value -> value.startsWith("DELAY:"))
                                            ? "及阈值" : "")
                                    + "一致；书面催告、补救程序等其他适用条件仍需核对完整条款"
                            : "所选材料的" + label + "一致";
            comparisons.add(new ClauseComparison("TERM_CONDITION:" + type, label, status, message,
                    sides.stream().flatMap(value -> value.values().stream()).map(value ->
                            new ClauseComparison.Source(value.material().fileId(), value.material().fileName(),
                                    value.material().category().name(), value.item().excerpt())).toList(),
                    sides.stream().map(side -> {
                        ConditionEvidence value = "KNOWN".equals(side.state()) ? side.value() : null;
                        String description = value == null ? null : value.item().description();
                        return new ClauseComparison.TypedSide(
                                side.material().category().name() + ":" + side.material().fileId(),
                                side.material().fileId(), side.state(), type,
                                 value != null && "TERMINATION".equals(type)
                                         ? value.terminationIdentity().breachedParty() : null,
                                 null, null, null, null, null, null, null, null,
                                null, null, null, null,
                                "EFFECTIVE_EVENT".equals(type) ? description : null,
                                "TERMINATION".equals(type) ? description : null);
                    }).toList()));
        });
        return List.copyOf(comparisons);
    }

    private static ConditionSide conditionSide(ContractClauseMaterial material,
            List<ConditionEvidence> raw) {
        if (raw.isEmpty()) return new ConditionSide(material, "NOT_EXTRACTED", List.of());
        boolean notApplicable = raw.stream().anyMatch(value -> "NOT_APPLICABLE".equals(value.typedState()));
        List<ConditionEvidence> known = raw.stream()
                .filter(value -> "KNOWN".equals(value.typedState())).toList();
        if (notApplicable && known.isEmpty()) {
            return new ConditionSide(material, "NOT_APPLICABLE", raw);
        }
        long distinct = known.stream().map(ConditionEvidence::semanticValue)
                .filter(java.util.Objects::nonNull).distinct().count();
        String state = notApplicable || known.size() != raw.size() || distinct > 1
                || terminationDifference(known)
                ? "CONFLICT" : "KNOWN";
        return new ConditionSide(material, state, raw);
    }

    private static String notApplicableConditionType(String clause) {
        Matcher matcher = CONDITION_NOT_APPLICABLE.matcher(clause);
        if (!matcher.find()) return null;
        return "生效条件".equals(matcher.group(1)) ? "EFFECTIVE_EVENT" : "TERMINATION";
    }

    private static String terminationSemanticValue(String clause) {
        String threshold = ClauseTerminationIdentity.thresholdKey(clause);
        if (threshold != null) return "DELAY:" + threshold;
        if (clause.contains("不符合")) return "NON_CONFORMITY";
        if (clause.contains("不可抗力")) return "FORCE_MAJEURE";
        if (clause.contains("严重违约")) return "MATERIAL_BREACH";
        if (clause.contains("协商一致")) return "MUTUAL_AGREEMENT";
        return null;
    }

    private static boolean terminationDifference(List<ConditionEvidence> values) {
        var identities = values.stream().filter(value -> "TERMINATION".equals(value.item().type()))
                .map(ConditionEvidence::terminationIdentity).toList();
        return differentKnown(identities, ClauseTerminationIdentity::triggerType)
                || differentKnown(identities, ClauseTerminationIdentity::thresholdKey)
                || differentKnown(identities, ClauseTerminationIdentity::breachedParty)
                || differentKnown(identities, ClauseTerminationIdentity::entitledParty);
    }

    private static boolean differentKnown(List<ClauseTerminationIdentity> values,
            java.util.function.Function<ClauseTerminationIdentity, String> field) {
        return values.stream().map(field).filter(java.util.Objects::nonNull).distinct().count() > 1;
    }

    private static DateSide dateSide(ContractClauseMaterial material, List<DateEvidence> raw) {
        List<DateEvidence> values = raw.stream().collect(java.util.stream.Collectors.toMap(
                value -> String.valueOf(value.item().normalizedDate()),
                java.util.function.Function.identity(), (left, right) -> left, LinkedHashMap::new))
                .values().stream().toList();
        String state = values.isEmpty() || values.stream().allMatch(value ->
                value.item().normalizedDate() == null) ? "NOT_EXTRACTED"
                : values.size() == 1 && values.get(0).item().normalizedDate() != null
                        ? "KNOWN" : "CONFLICT";
        return new DateSide(material, state, values);
    }

    private static String dateLabel(String type) {
        return switch (type) {
            case "SIGNING_DATE" -> "签约日期";
            case "EFFECTIVE_DATE" -> "生效日期";
            case "PERFORMANCE_START_DATE" -> "履约开始日期";
            case "DELIVERY_DEADLINE" -> "交付日期";
            default -> "终止日期";
        };
    }

    private static boolean comparisonParticipant(ContractClauseMaterial material, boolean hasTypedFact) {
        return switch (material.category()) {
            case CONTRACT_BODY, TENDER_DOCUMENT, BID_RESPONSE -> true;
            case SUPPLEMENT, AWARD_NOTICE, OTHER -> hasTypedFact;
            case PACKAGE, UNKNOWN -> false;
        };
    }

    private static boolean uniqueDeliveryDate(String text, Matcher delivery) {
        int start = delivery.start();
        while (start > 0 && "。；;\n\r".indexOf(text.charAt(start - 1)) < 0) start--;
        String clause = text.substring(start, delivery.end());
        if (List.of("不要求", "不约定", "不需要", "无需", "无须", "并非", "不是", "不得", "不应", "不能")
                .stream().anyMatch(clause::contains)) return false;
        if (List.of("另行约定", "另行确定", "待定", "预计", "仅完成")
                .stream().anyMatch(clause::contains)) return false;
        if (Pattern.compile(DATE_VALUE).matcher(clause).results().count() != 1
                || text.indexOf(delivery.group()) != text.lastIndexOf(delivery.group())) return false;
        int lineEnd = text.indexOf('\n', delivery.end());
        if (lineEnd < 0) lineEnd = text.length();
        String line = text.substring(start, lineEnd);
        if (line.contains("…") || line.contains("...")) return false;
        int contents = text.lastIndexOf("目录", start);
        if (contents >= 0) {
            boolean bodyHeading = text.substring(contents, start).lines()
                    .anyMatch(value -> !value.contains("…") && !value.contains("...")
                            && SECTION_HEADING.matcher(value).matches());
            if (!bodyHeading) return false;
        }
        return true;
    }

    private static String normalize(String year, String month, String day) {
        try {
            return LocalDate.of(com.fuquan.audit.common.domain.ChineseNumber.parse(year).orElseThrow().intValueExact(),
                            com.fuquan.audit.common.domain.ChineseNumber.parse(month).orElseThrow().intValueExact(),
                            com.fuquan.audit.common.domain.ChineseNumber.parse(day).orElseThrow().intValueExact())
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (IllegalArgumentException | java.util.NoSuchElementException | ArithmeticException | DateTimeException ignored) {
            return null;
        }
    }

    private static String dateType(String label) {
        if (label.startsWith("签")) return "SIGNING_DATE";
        if (label.startsWith("生效")) return "EFFECTIVE_DATE";
        if (label.startsWith("履约")) return "PERFORMANCE_START_DATE";
        if (label.startsWith("交付") || label.startsWith("完成")) return "DELIVERY_DEADLINE";
        return "TERMINATION_DATE";
    }

    private static List<String> clauses(String text) {
        return Pattern.compile("(?<=[。！？；;\\n])").splitAsStream(text)
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static boolean isEffectiveEvent(String clause) {
        if (!EFFECTIVE_EVENT.matcher(clause).find()) {
            return false;
        }
        return !hasNegatedEffectiveness(clause)
                && clause.contains("生效")
                && (clause.contains("签字") || clause.contains("签章"))
                && (clause.contains("盖章") || clause.contains("签章"));
    }

    private static boolean hasNegatedEffectiveness(String clause) {
        return clause.contains("不生效") || clause.contains("未生效")
                || clause.contains("不得生效") || clause.contains("不能生效")
                || clause.contains("不发生效力") || clause.contains("无效");
    }

    private static boolean isTerminationCondition(String clause) {
        if (CHAPTER_HEADING.matcher(clause).matches() || NEGATED_TERMINATION.matcher(clause).find()) return false;
        return (clause.contains("解除") || clause.contains("终止"))
                && (clause.contains("可") || clause.contains("有权") || clause.contains("协商")
                || clause.contains("延期") || clause.contains("逾期") || clause.contains("不符合")
                || clause.contains("不可抗力") || clause.contains("严重违约"));
    }

    private static Integer parseCopyCount(String value) {
        if (value.matches("[0-9]+")) {
            try { return Integer.valueOf(value); } catch (NumberFormatException ignored) { return null; }
        }
        int total = 0;
        int digit = 0;
        for (int index = 0; index < value.length(); index++) {
            int mapped = chineseDigit(value.charAt(index));
            if (mapped >= 0) {
                digit = mapped;
                continue;
            }
            int unit = switch (value.charAt(index)) {
                case '十', '拾' -> 10;
                case '百', '佰' -> 100;
                default -> -1;
            };
            if (unit < 0) return null;
            total += (digit == 0 ? 1 : digit) * unit;
            digit = 0;
        }
        return total + digit;
    }

    private static int chineseDigit(char value) {
        return switch (value) {
            case '零', '〇' -> 0;
            case '一', '壹' -> 1;
            case '二', '两', '贰' -> 2;
            case '三', '叁' -> 3;
            case '四', '肆' -> 4;
            case '五', '伍' -> 5;
            case '六', '陆' -> 6;
            case '七', '柒' -> 7;
            case '八', '捌' -> 8;
            case '九', '玖' -> 9;
            default -> -1;
        };
    }

    private record DateEvidence(ContractTermClauseReview.DateItem item,
                                ContractClauseMaterial material) {
    }

    private record ConditionEvidence(ContractTermClauseReview.ConditionItem item,
                                      ContractClauseMaterial material,
                                      String typedState, String semanticValue) {
        private ClauseTerminationIdentity terminationIdentity() {
            return ClauseTerminationIdentity.from(item.description());
        }
    }

    private record DateSide(ContractClauseMaterial material, String state, List<DateEvidence> values) {
        private DateEvidence value() { return values.size() == 1 ? values.get(0) : null; }
    }

    private record ConditionSide(ContractClauseMaterial material, String state,
                                 List<ConditionEvidence> values) {
        private ConditionEvidence value() {
            return values.stream().filter(value -> "KNOWN".equals(value.typedState()))
                    .findFirst().orElse(null);
        }

        private String semanticValue() {
            ConditionEvidence value = value();
            return value == null ? null : value.semanticValue();
        }
    }

    private record StructureHeading(String label, List<String> aliases) {
        private StructureHeading {
            aliases = List.copyOf(aliases);
        }
    }

    private record TemplateSection(ContractClauseMaterial material, String text, String anchor) {
    }
}
