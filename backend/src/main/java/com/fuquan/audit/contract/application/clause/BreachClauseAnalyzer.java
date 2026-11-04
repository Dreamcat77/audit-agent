package com.fuquan.audit.contract.application.clause;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;

@Component
public final class BreachClauseAnalyzer {
    private static final Pattern RATE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([%％‰])");
    private static final Pattern WRITTEN_RATE = Pattern.compile("(百|千|万)分之\\s*([0-9零〇一二两三四五六七八九十百壹贰叁肆伍陆柒捌玖拾佰点.]+)");
    private static final Pattern ACTUAL_PERIOD = Pattern.compile("(?:实际|已经|已)(?:累计)?(?:逾期|延期)(?:交付|交货|付款|供货)?\\s*([0-9零〇一二两三四五六七八九十百壹贰叁肆伍陆柒捌玖拾佰]+)\\s*(天|日|周|月|次)");
    private static final Pattern FREQUENCY = Pattern.compile("每(?:延期|逾期)?(?:满)?(?:一)?(天|日|周|月|次)");
    private static final Pattern RATE_SLASH_FREQUENCY = Pattern.compile("[%％‰]\\s*[/／]\\s*([^\\s，,。；;])");
    private static final Pattern CAP_CONTEXT = Pattern.compile("(?:累计(?:违约金)?不超过|最高(?:不超过)?|上限|累计\\s*$)");
    private static final Pattern BREACH_NOT_APPLICABLE = Pattern.compile(
            "(?:违约责任|违约条款)\\s*[：:]\\s*(?:不适用|无|不涉及)(?:[。；;]|$)");
    private static final Pattern RESPONSIBLE_PARTY = Pattern.compile(
            "(?:由\\s*)?(甲方|乙方)(?=[^甲乙。！？；\\n]{0,24}(?:应当|应|须|需|负责|承担)[^甲乙。！？；\\n]{0,16}(?:支付|赔偿|承担))");
    private static final Pattern TERMINATION_THRESHOLD = ClauseTerminationIdentity.THRESHOLD;
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "^\\s*(?:第[一二三四五六七八九十百0-9]+[章节]|[一二三四五六七八九十]+、)[^。！？；;\\r\\n]*$");
    private static final Pattern NEGATED_TERMINATION = Pattern.compile(
            "(?:不得|无权|不能|不应|不允许)[^。！？；;\\r\\n]{0,8}(?:解除|终止)");

    public BreachClauseReview analyze(BigDecimal contractAmount, List<ContractClauseMaterial> materials) {
        List<BreachClauseReview.Item> items = new ArrayList<>();
        List<BreachFact> facts = new ArrayList<>();
        for (ContractClauseMaterial material : materials) {
            List<String> clauses = clauses(material.text());
            for (String clause : clauses) {
                if (BREACH_NOT_APPLICABLE.matcher(clause).find()) {
                    facts.add(new BreachFact("违约责任", "NOT_APPLICABLE", null,
                            null, null, null, null, null, null, null, null, null,
                            null, material, clause.trim()));
                    continue;
                }
                if (!isBreachClause(clause)) continue;
                List<RateMatch> rates = rates(clause);
                RateMatch cap = rates.stream().filter(RateMatch::cap).findFirst().orElse(null);
                List<RateMatch> appliedRates = rates.stream().filter(value -> !value.cap()).toList();
                if (appliedRates.isEmpty() && cap != null) {
                    String basis = basis(clause);
                    String termination = terminationFor(clauses, clause);
                    facts.add(new BreachFact(scenario(clause), "KNOWN", eventParty(clause),
                            null, null, basis, null, period(termination), cap.value(), cap.unit(),
                            amount(contractAmount, cap, basis), terminationThreshold(termination),
                            termination, material, clause.trim()));
                }
                for (RateMatch rate : appliedRates) {
                    String frequency = frequency(clause);
                    boolean frequencySupported = frequency != null;
                    String basis = basis(clause);
                    BigDecimal corresponding = frequencySupported && "CONTRACT_AMOUNT".equals(basis)
                            ? calculate(contractAmount, rate.value(), rate.unit()) : null;
                    BigDecimal capAmount = frequencySupported && cap != null && "CONTRACT_AMOUNT".equals(basis)
                            ? calculate(contractAmount, cap.value(), cap.unit()) : null;
                    BigDecimal periods = actualPeriods(clause, frequency);
                    BigDecimal actual = corresponding == null || periods == null ? null
                            : calculate(contractAmount,rate.value().multiply(periods),rate.unit());
                    boolean exceedsCap = actual != null && capAmount != null && actual.compareTo(capAmount) > 0;
                    String termination = terminationFor(clauses, clause);
                    String warning = !frequencySupported
                            ? "违约频次单位未能精确识别，建议复核"
                            : "OTHER_OR_UNCLEAR".equals(basis) ? "违约金计算基数未能精确识别，建议复核"
                             : cap == null && !"ONCE".equals(frequency)
                                     ? "累计违约金上限未明确，建议复核" : null;
                    if (exceedsCap) warning = "按实际逾期期间计算违约金 " + actual.toPlainString()
                            + " 元，超过约定上限 " + capAmount.toPlainString() + " 元，差额 "
                            + actual.subtract(capAmount).toPlainString() + " 元。";
                    BreachClauseReview.Item item = new BreachClauseReview.Item(
                            scenario(clause), rate.value(), rate.unit(), frequency, basis,
                            corresponding, cap == null ? null : cap.value(),
                            cap == null ? null : cap.unit(), capAmount, actual,
                            termination, "OTHER_OR_UNCLEAR".equals(basis) ? "INSUFFICIENT"
                                    : warning == null ? "NORMAL" : "REVIEW", warning,
                            material.fileId(), clause.trim());
                    items.add(item);
                    facts.add(new BreachFact(item.scenario(), "KNOWN", eventParty(clause),
                            item.rate(), item.rateUnit(), item.calculationBasis(), item.frequency(),
                            period(termination), item.capRate(), item.capRateUnit(), item.capAmount(),
                            terminationThreshold(termination), item.terminationCondition(),
                            material, clause.trim()));
                }
            }
        }
        boolean hasContractBody = materials.stream()
                .anyMatch(material -> material.category() == ProjectFileCategory.CONTRACT_BODY);
        List<BreachClauseReview.Item> authoritativeItems = hasContractBody
                ? items.stream().filter(item -> materials.stream().anyMatch(material ->
                        material.fileId().equals(item.sourceFileId())
                                && material.category() == ProjectFileCategory.CONTRACT_BODY)).toList()
                : List.copyOf(items);
        return new BreachClauseReview(authoritativeItems, materials.isEmpty(), comparisons(facts, materials));
    }

    private static List<ClauseComparison> comparisons(List<BreachFact> facts,
            List<ContractClauseMaterial> materials) {
        Map<String, LinkedHashMap<java.util.UUID, List<BreachFact>>> byScenario = new LinkedHashMap<>();
        for (BreachFact fact : facts) {
            byScenario.computeIfAbsent(fact.scenario(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(fact.material().fileId(), ignored -> new ArrayList<>()).add(fact);
        }
        List<ClauseComparison> comparisons = new ArrayList<>();
        byScenario.forEach((scenario, byFile) -> {
            List<ContractClauseMaterial> participants = materials.stream()
                    .filter(material -> comparisonParticipant(material, byFile.containsKey(material.fileId())))
                    .toList();
            if (participants.size() < 2) return;
            List<SideFacts> sides = participants.stream().map(material ->
                    sideFacts(material, byFile.getOrDefault(material.fileId(), List.of()))).toList();
            boolean conflict = sides.stream().anyMatch(value -> "CONFLICT".equals(value.state()));
            boolean missing = sides.stream().anyMatch(value -> !"KNOWN".equals(value.state())
                     || "OTHER_OR_UNCLEAR".equals(value.fact().basis())
                     || "延期事项未明确".equals(value.fact().scenario())
                    || unresolvedAppliedFrequency(value.fact()));
            boolean compareCaps = !missing && !conflict
                    && sides.stream().allMatch(value -> value.fact().capRate() != null);
            boolean compareRates = !missing && !conflict
                    && sides.stream().allMatch(value -> value.fact().rate() != null
                            && value.fact().frequency() != null);
            long distinct = compareCaps || compareRates ? sides.stream().map(value ->
                    comparisonSignature(value.fact(), compareCaps, compareRates)).distinct().count() : 0;
            boolean differentKnownParties = sides.stream().filter(value -> "KNOWN".equals(value.state()))
                    .map(value -> value.fact().responsibleParty()).filter(java.util.Objects::nonNull)
                    .distinct().count() > 1;
            boolean differentKnownThresholds = sides.stream().filter(value -> "KNOWN".equals(value.state()))
                    .map(value -> thresholdKey(value.fact().terminationCondition()))
                    .filter(java.util.Objects::nonNull).distinct().count() > 1;
            boolean different = distinct > 1 || differentKnownParties || differentKnownThresholds;
            String status = conflict ? "REVIEW" : missing || (!compareCaps && !compareRates)
                    ? "INSUFFICIENT" : different ? "REVIEW" : "NORMAL";
            String joined = sides.stream().flatMap(value -> value.facts().stream())
                    .map(BreachClauseAnalyzer::factText).distinct()
                    .collect(java.util.stream.Collectors.joining("、"));
            comparisons.add(new ClauseComparison("BREACH_TYPED:" + scenario, scenario + "双方约定",
                    status, conflict ? "同一冻结材料存在相互冲突的约定：" + joined
                            : "INSUFFICIENT".equals(status) ? "所选材料未取得语义可比的双方约定：" + joined
                            : different ? "所选材料约定不一致：" + joined
                            : "所选材料约定一致：" + joined,
                    sides.stream().flatMap(value -> value.facts().stream()).map(value ->
                            new ClauseComparison.Source(value.material().fileId(), value.material().fileName(),
                                    value.material().category().name(), value.excerpt())).toList(),
                    participants.stream().map(material -> {
                        SideFacts side = sides.stream().filter(value ->
                                value.material().fileId().equals(material.fileId())).findFirst().orElseThrow();
                        BreachFact value = "KNOWN".equals(side.state()) ? side.fact() : null;
                        return new ClauseComparison.TypedSide(
                                material.category().name() + ":" + material.fileId(), material.fileId(),
                                 side.state(), scenario, value == null ? null : value.responsibleParty(),
                                 value == null ? null : value.basis(), value == null ? null : value.rate(),
                                 value == null ? null : value.rateUnit(), value == null ? null : value.frequency(),
                                 value == null ? null : value.period(), value == null ? null : value.capRate(),
                                 value == null ? null : value.capAmount(),
                                 value == null ? null : value.terminationThreshold(),
                                 null, null, null, null, null,
                                 value == null ? null : value.terminationCondition());
                    }).toList()));
        });
        return List.copyOf(comparisons);
    }

    private static String comparisonSignature(BreachFact fact, boolean compareCaps, boolean compareRates) {
        return (compareCaps ? decimal(fact.capRate()) + ":" + fact.capRateUnit() : "")
                + (compareRates ? "|" + decimal(fact.rate()) + ":" + fact.rateUnit()
                        + ":" + fact.frequency() + ":" + fact.basis() : "");
    }

    private static SideFacts sideFacts(ContractClauseMaterial material, List<BreachFact> raw) {
        List<BreachFact> facts = raw.stream().collect(java.util.stream.Collectors.toMap(
                BreachClauseAnalyzer::signature, java.util.function.Function.identity(),
                (left, right) -> left, LinkedHashMap::new)).values().stream().toList();
        if (facts.isEmpty()) return new SideFacts(material, "NOT_EXTRACTED", facts, null);
        boolean notApplicable = facts.stream().anyMatch(value -> "NOT_APPLICABLE".equals(value.state()));
        List<BreachFact> known = facts.stream().filter(value -> "KNOWN".equals(value.state())).toList();
        BreachFact unresolved = known.stream().filter(BreachClauseAnalyzer::unresolvedAppliedFrequency)
                .findFirst().orElse(null);
        if (!notApplicable && unresolved != null && !knownTypedConflict(known)) {
            // Unknown is neither a wildcard nor proof of contradiction. Keep the original
            // unresolved fact (not a synthetic WEEK/basis), and keep every source for review.
            return new SideFacts(material, "KNOWN", facts, unresolved);
        }
        List<BreachFact> representatives = known.stream().filter(partial -> known.stream()
                .noneMatch(detail -> detail != partial && detailedRepresentative(detail, partial))).toList();
        String state = notApplicable && known.isEmpty() ? "NOT_APPLICABLE"
                : notApplicable || representatives.size() != 1 ? "CONFLICT" : "KNOWN";
        // Original facts are retained as sources. The typed representative is an existing
        // detailed fact, never a synthetic fact with a basis copied into its summary.
        return new SideFacts(material, state, facts,
                "KNOWN".equals(state) ? representatives.get(0) : null);
    }

    private static boolean detailedRepresentative(BreachFact detail, BreachFact partial) {
        if (unresolvedAppliedFrequency(partial) || unresolvedAppliedFrequency(detail)) return false;
        if (!"OTHER_OR_UNCLEAR".equals(partial.basis()) || "OTHER_OR_UNCLEAR".equals(detail.basis())) return false;
        return compatibleNumber(detail.rate(), partial.rate())
                && compatibleValue(detail.rateUnit(), partial.rateUnit())
                && compatibleValue(detail.frequency(), partial.frequency())
                && compatibleNumber(detail.capRate(), partial.capRate())
                && compatibleValue(detail.capRateUnit(), partial.capRateUnit())
                && compatibleValue(detail.responsibleParty(), partial.responsibleParty())
                && compatibleValue(detail.period(), partial.period())
                && compatibleValue(detail.terminationThreshold(), partial.terminationThreshold());
    }

    private static boolean unresolvedAppliedFrequency(BreachFact fact) {
        // Cap-only facts have no applied rate. For an applied rate, frequency() returns
        // ONCE when absent, and null only for an explicit unsupported recurrence.
        return fact.rate() != null && fact.frequency() == null;
    }

    private static boolean knownTypedConflict(List<BreachFact> facts) {
        return differentKnown(facts, value -> value.rate() == null ? null : value.rate().stripTrailingZeros())
                || differentKnown(facts, BreachFact::rateUnit)
                || differentKnown(facts, BreachFact::frequency)
                || differentKnown(facts, value -> value.capRate() == null ? null : value.capRate().stripTrailingZeros())
                || differentKnown(facts, BreachFact::capRateUnit)
                || differentKnown(facts, BreachFact::period)
                || differentKnown(facts, BreachFact::terminationThreshold)
                || differentKnown(facts, BreachFact::responsibleParty)
                || differentKnown(facts, value -> "OTHER_OR_UNCLEAR".equals(value.basis()) ? null : value.basis());
    }

    private static boolean differentKnown(List<BreachFact> facts, Function<BreachFact, ?> value) {
        return facts.stream().map(value).filter(java.util.Objects::nonNull).distinct().limit(2).count() > 1;
    }

    private static boolean compatibleValue(String detail, String partial) {
        return partial == null || partial.equals(detail);
    }

    private static boolean compatibleNumber(BigDecimal detail, BigDecimal partial) {
        return partial == null || detail != null && detail.compareTo(partial) == 0;
    }

    private static String signature(BreachFact value) {
        return String.join("|", value.state(), String.valueOf(value.responsibleParty()),
                String.valueOf(value.basis()),
                decimal(value.rate()), String.valueOf(value.rateUnit()),
                String.valueOf(value.frequency()), decimal(value.capRate()),
                String.valueOf(value.capRateUnit()), String.valueOf(value.period()),
                String.valueOf(value.terminationThreshold()), String.valueOf(value.terminationCondition()));
    }

    private static String factText(BreachFact fact) {
        String rate = fact.rate() == null ? "未提取费率" : decimal(fact.rate())
                + rateSuffix(fact.rateUnit());
        String cap = fact.capRate() == null ? "未提取上限" : decimal(fact.capRate())
                + rateSuffix(fact.capRateUnit());
        if ("ONCE".equals(fact.frequency()) && fact.capRate() == null) {
            return rate + "（一次性"
                    + ("OTHER_OR_UNCLEAR".equals(fact.basis()) ? "；计算基数未能确认" : "") + "）";
        }
        return rate + "/" + cap;
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
    private static String rateSuffix(String unit) {return "PER_MILLE".equals(unit)?"‰":"PER_TEN_THOUSAND".equals(unit)?"‱":"%";}

    private static String basis(String clause) {
        return clause.contains("合同金额") || clause.contains("合同价款") || clause.contains("合同总价款")
                || clause.contains("合同总价") || clause.contains("合同总金额") ? "CONTRACT_AMOUNT"
                : clause.contains("分项金额") ? "LINE_ITEM_AMOUNT" : "OTHER_OR_UNCLEAR";
    }

    private static boolean comparisonParticipant(ContractClauseMaterial material, boolean hasTypedFact) {
        return switch (material.category()) {
            case CONTRACT_BODY, TENDER_DOCUMENT, BID_RESPONSE -> true;
            case SUPPLEMENT, AWARD_NOTICE, OTHER -> hasTypedFact;
            case PACKAGE, UNKNOWN -> false;
        };
    }

    private static BigDecimal amount(BigDecimal contractAmount, RateMatch rate, String basis) {
        return rate != null && "CONTRACT_AMOUNT".equals(basis)
                ? calculate(contractAmount, rate.value(), rate.unit()) : null;
    }

    private static List<String> clauses(String text) {
        List<String> fragments = Pattern.compile("(?<=[。！？；;\\n])").splitAsStream(text)
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        List<String> groups = new ArrayList<>();
        for (String fragment : fragments) {
            int previousIndex = groups.size() - 1;
            if (previousIndex >= 0 && shouldContinue(groups.get(previousIndex), fragment)) {
                groups.set(previousIndex, groups.get(previousIndex) + fragment);
            } else {
                groups.add(fragment);
            }
        }
        return groups;
    }

    private static boolean endsWithSemicolon(String value) {
        return value.endsWith("；") || value.endsWith(";");
    }

    private static boolean isCapContinuation(String value) {
        List<RateMatch> matches = rates(value);
        return !matches.isEmpty() && matches.stream().allMatch(RateMatch::cap);
    }

    private static boolean shouldContinue(String previous, String fragment) {
        if (!endsWithSemicolon(previous) && !isCapContinuation(fragment)) return false;
        String previousScenario = explicitScenario(previous);
        String fragmentScenario = explicitScenario(fragment);
        String previousParty = eventParty(previous);
        String fragmentParty = eventParty(fragment);
        // An omitted repeated topic within this same semicolon sentence may inherit
        // its explicit scope. A new named actor or a second applied rate cannot.
        boolean scopedContinuation = ("逾期交付".equals(previousScenario) || "逾期付款".equals(previousScenario))
                && "延期事项未明确".equals(fragmentScenario)
                && (isTerminationCondition(fragment) || ACTUAL_PERIOD.matcher(fragment).find())
                && rates(fragment).stream().noneMatch(value -> !value.cap())
                && (fragmentParty == null || fragmentParty.equals(previousParty));
        if (scopedContinuation) return true;
        if (previousScenario != null && fragmentScenario != null
                && !previousScenario.equals(fragmentScenario)) return false;
        if (previousParty != null && fragmentParty != null && !previousParty.equals(fragmentParty)) return false;
        if (isCapContinuation(fragment)) return true;
        List<RateMatch> previousRates = rates(previous).stream().filter(value -> !value.cap()).toList();
        List<RateMatch> fragmentRates = rates(fragment).stream().filter(value -> !value.cap()).toList();
        if (previousRates.isEmpty()) return true;
        if (fragmentRates.isEmpty()) return true;
        // A second applied rate owns its own cycle even for the same event and party.
        return false;
    }

    private static boolean isBreachClause(String clause) {
        return clause.matches(".*(?:违约|逾期|延期|赔偿|解除|终止|不符合).*");
    }

    private static List<RateMatch> rates(String clause) {
        List<RateMatch> values = new ArrayList<>();
        Matcher matcher = RATE.matcher(clause);
        while (matcher.find()) {
            int contextStart = Math.max(0, matcher.start() - 18);
            boolean cap = CAP_CONTEXT.matcher(clause.substring(contextStart, matcher.start())).find();
            values.add(new RateMatch(new BigDecimal(matcher.group(1)),
                    "‰".equals(matcher.group(2)) ? "PER_MILLE" : "PERCENT", cap));
        }
        Matcher written = WRITTEN_RATE.matcher(clause);
        while (written.find()) {
            var number = com.fuquan.audit.common.domain.ChineseNumber.parse(written.group(2));
            if (number.isEmpty()) continue;
            boolean cap = CAP_CONTEXT.matcher(clause.substring(Math.max(0, written.start() - 18), written.start())).find();
            values.add(new RateMatch(number.get(), switch (written.group(1)) {
                case "千" -> "PER_MILLE"; case "万" -> "PER_TEN_THOUSAND"; default -> "PERCENT";
            }, cap));
        }
        return values;
    }

    private static BigDecimal calculate(BigDecimal basis, BigDecimal rate, String unit) {
        BigDecimal divisor = "PER_MILLE".equals(unit) ? new BigDecimal("1000")
                : "PER_TEN_THOUSAND".equals(unit) ? new BigDecimal("10000") : new BigDecimal("100");
        return basis.multiply(rate).divide(divisor).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal actualPeriods(String clause, String frequency) {
        if (frequency == null) return null;
        var values = new java.util.TreeSet<BigDecimal>();
        var matcher = ACTUAL_PERIOD.matcher(clause);
        while (matcher.find()) if (frequency.equals(frequency(matcher.group(2), null)))
            com.fuquan.audit.common.domain.ChineseNumber.parse(matcher.group(1)).ifPresent(values::add);
        // A conditional threshold such as "逾期超过30天" is never elapsed time.
        return values.size() == 1 ? values.first() : null;
    }

    private static String frequency(String clause) {
        Matcher slashMatcher = RATE_SLASH_FREQUENCY.matcher(clause);
        if (slashMatcher.find()) return frequency(slashMatcher.group(1), null);
        Matcher matcher = FREQUENCY.matcher(clause);
        if (matcher.find()) return frequency(matcher.group(1), "ONCE");
        // An explicitly recurring but unsupported period is not a one-off penalty.
        return Pattern.compile("每(?:延期|逾期)?[^，。；;\\s]{0,8}(?:天|日|周|月|次)")
                .matcher(clause).find() ? null : "ONCE";
    }

    private static String frequency(String token, String fallback) {
        return switch (token) {
            case "天", "日" -> "DAY";
            case "周" -> "WEEK";
            case "月" -> "MONTH";
            case "次" -> "EVENT";
            default -> fallback;
        };
    }

    private static String scenario(String clause) {
        String explicit = explicitScenario(clause);
        if (explicit != null) return explicit;
        return "违约责任";
    }

    private static String explicitScenario(String clause) {
        if (clause.matches(".*(?:软件|服务|产品).*(?:不符合|不合格).*")
                || clause.contains("重大缺陷") || clause.contains("质量缺陷")
                || clause.contains("严重不符合")) return "软件/服务不符合约定";
        if (clause.matches(".*(?:逾期|延期).*")) {
            if (clause.contains("付款") || clause.contains("支付货款")) return "逾期付款";
            if (clause.contains("交付") || clause.contains("交货") || clause.contains("供货")) return "逾期交付";
            return "延期事项未明确";
        }
        return null;
    }

    private static String terminationFor(List<String> clauses, String rateClause) {
        if (isTerminationCondition(rateClause)) return rateClause.trim();
        String event = explicitScenario(rateClause);
        if (event == null || event.equals("延期事项未明确")) return null;
        String party = eventParty(rateClause);
        if (party == null) return null;
        List<String> candidates = clauses.stream().filter(BreachClauseAnalyzer::isTerminationCondition)
                .filter(value -> event.equals(explicitScenario(value)))
                .filter(value -> party.equals(eventParty(value)))
                .map(String::trim).distinct().toList();
        // Multiple event-compatible remedies remain uncertain; proximity cannot select one.
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static boolean isTerminationCondition(String clause) {
        if (CHAPTER_HEADING.matcher(clause).matches() || NEGATED_TERMINATION.matcher(clause).find()) return false;
        return (clause.contains("解除") || clause.contains("终止"))
                && (clause.contains("可") || clause.contains("有权") || clause.contains("协商")
                        || clause.contains("达到解除条件") || TERMINATION_THRESHOLD.matcher(clause).find());
    }

    private static String eventParty(String clause) {
        String explicit = responsibleParty(clause);
        if (explicit != null) return explicit;
        Matcher subject = Pattern.compile("(甲方|乙方)(?=[^甲乙。！？；;\\r\\n]{0,12}(?:延期|逾期|不符合|不合格|重大缺陷))")
                .matcher(clause);
        return subject.find() ? subject.group(1) : null;
    }

    private static String responsibleParty(String clause) {
        Matcher matcher = RESPONSIBLE_PARTY.matcher(clause);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String period(String terminationCondition) {
        Matcher matcher = terminationCondition == null ? null : TERMINATION_THRESHOLD.matcher(terminationCondition);
        return matcher != null && matcher.find() ? matcher.group(2) + matcher.group(3) : null;
    }

    private static String terminationThreshold(String terminationCondition) {
        Matcher matcher = terminationCondition == null ? null : TERMINATION_THRESHOLD.matcher(terminationCondition);
        return matcher != null && matcher.find() ? matcher.group() : null;
    }

    private static String thresholdKey(String condition) {
        return ClauseTerminationIdentity.thresholdKey(condition);
    }

    private record RateMatch(BigDecimal value, String unit, boolean cap) {
    }

    private record BreachFact(String scenario, String state, String responsibleParty, BigDecimal rate,
            String rateUnit, String basis, String frequency, String period, BigDecimal capRate,
            String capRateUnit, BigDecimal capAmount, String terminationThreshold,
            String terminationCondition,
            ContractClauseMaterial material, String excerpt) {
    }

    private record SideFacts(ContractClauseMaterial material, String state, List<BreachFact> facts,
                             BreachFact fact) {
    }
}
