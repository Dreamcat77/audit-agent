package com.fuquan.audit.contract.application.clause;

import com.fuquan.audit.common.domain.ChineseNumber;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import static com.fuquan.audit.contract.application.clause.ContractTermClauseReview.*;

/** Source-bound document comparisons, shared by automatic and formal review. */
final class ContractDocumentChecks {
    private ContractDocumentChecks() {}
    private static final String NUM = "[0-9零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+";
    private static final Pattern TOTAL = Pattern.compile("一式\\s*(" + NUM + ")\\s*份");
    private static final Pattern SHARE = Pattern.compile("(甲方|乙方|丙方|采购人|供应商|委托方|受托方)\\s*(?:各)?(?:执|持|留存|保存|保留)\\s*(" + NUM + ")\\s*份");
    private static final Pattern BOTH_SHARE = Pattern.compile("(?:甲乙双方|甲、乙双方|双方)\\s*各(?:执|持|留存|保存|保留)\\s*(" + NUM + ")\\s*份");
    private static final Pattern TEMPLATE = Pattern.compile("^\\s*(?:第[一二三四五六七八九十百0-9]+章\\s*)?(?:合同条款(?:及|与)?格式|合同条款及合同格式|合同主要条款|合同格式|合同范本|合同模板)\\s*$");
    private static final Pattern CHAPTER = Pattern.compile("^\\s*第[一二三四五六七八九十百0-9]+章.*");
    private static final Pattern HEADING = Pattern.compile("^\\s*(?:第[一二三四五六七八九十百0-9]+条|[一二三四五六七八九十]+[、.]|[0-9]+(?:\\.[0-9]+)*[、.．)）]?)\\s*([^。；;！!？?：:]{2,45}?)(?:[。；;！!？?：:](.*))?$");
    private record Heading(String key, String label, int position) {}
    private record Section(ContractClauseMaterial file, String text, String anchor) {}

    static List<DeterministicCheck> analyze(List<ContractClauseMaterial> materials,
            List<DateItem> dates, List<ConditionItem> conditions) {
        var checks = new ArrayList<DeterministicCheck>();
        for (var contract : materials) if (contract.category() == ProjectFileCategory.CONTRACT_BODY) {
            copies(contract, materials, checks);
            dateCompleteness(contract, dates, conditions, checks);
            for (var tender : materials) if (tender.category() == ProjectFileCategory.TENDER_DOCUMENT) {
                // Explicit legacy delimiters are handled by the original analyzer.
                if (tender.text().contains("合同模板开始") || tender.text().contains("合同范本开始")) continue;
                for (var section : sections(tender)) structure(contract, section, checks);
            }
        }
        return List.copyOf(checks);
    }
    private static List<Section> sections(ContractClauseMaterial file) {
        var result = new ArrayList<Section>();
        var lines = file.text().split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!TEMPLATE.matcher(lines[i]).matches()) continue;
            StringBuilder body = new StringBuilder();
            for (int j = i + 1; j < lines.length && !CHAPTER.matcher(lines[j]).matches(); j++) body.append(lines[j]).append('\n');
            // A table of contents or a list of titles alone is not the template body.
            long substantive = body.toString().lines().filter(ContractDocumentChecks::substantive).count();
            if (substantive >= 2 && headings(body.toString()).size() >= 2) result.add(new Section(file, body.toString(), lines[i].strip()));
        }
        return result;
    }
    private static List<Heading> headings(String text) {
        var result = new LinkedHashMap<String, Heading>();
        int offset = 0;
        for (String line : text.split("\\R", -1)) {
            var m = HEADING.matcher(line);
            if (m.matches()) {
                String title = m.group(1).strip().replaceFirst("[：:]$", "");
                // Text after the title belongs to the clause, not its structural identity.
                title = title.split("[：:]|\\s{2,}", 2)[0];
                String key = canonical(title);
                result.putIfAbsent(key, new Heading(key, title, offset));
            }
            offset += line.length() + 1;
        }
        return List.copyOf(result.values());
    }
    private static String canonical(String text) {
        if (text.matches(".*(?:标的|采购内容|服务内容).*$")) return "合同标的";
        if (text.matches(".*(?:价款|合同金额|合同总价).*$")) return "合同价款";
        if (text.matches(".*(?:履行期限|履约期限|服务期限|交付期限).*$")) return "履行期限";
        if (text.contains("争议")) return "争议解决";
        if (text.contains("违约")) return "违约责任";
        if (text.contains("生效")) return "合同生效";
        return text.replaceAll("[\\s：:、]", "");
    }
    private static void structure(ContractClauseMaterial contract, Section template, List<DeterministicCheck> checks) {
        var required = headings(template.text());
        var actual = headings(contract.text());
        var actualKeys = actual.stream().map(Heading::key).toList();
        var missing = required.stream().filter(h -> !actualKeys.contains(h.key())).map(Heading::label).toList();
        var expectedOrder = required.stream().map(Heading::key).filter(actualKeys::contains).toList();
        var actualOrder = actualKeys.stream().filter(expectedOrder::contains).toList();
        boolean order = !expectedOrder.equals(actualOrder);
        checks.add(new DeterministicCheck("TEMPLATE_STRUCTURE_COMPARISON", missing.isEmpty() && !order ? "NORMAL" : "REVIEW",
                "按招标文件“" + template.anchor() + "”核对合同结构："
                + (missing.isEmpty() ? "未发现缺失的模板章节。" : "缺少章节：" + String.join("、", missing) + "。")
                + (order ? "条款顺序不同，模板顺序为：" + String.join("→", expectedOrder) + "。" : ""),
                missing.isEmpty() && !order ? null : "请关注所列章节差异，并结合合同适用范围判断是否需要补充。",
                List.of(source(contract, firstLine(contract.text())), source(template.file(), template.anchor()))));
    }
    private static void copies(ContractClauseMaterial contract, List<ContractClauseMaterial> materials, List<DeterministicCheck> checks) {
        var totals = new LinkedHashMap<BigDecimal, String>();
        var matcher = TOTAL.matcher(contract.text());
        while (matcher.find()) {
            var amount = ChineseNumber.parse(matcher.group(1));
            if (amount.isPresent()) totals.putIfAbsent(amount.get(), matcher.group());
        }
        if (totals.isEmpty()) return;
        var sources = new ArrayList<CheckSource>();
        totals.values().forEach(v -> sources.add(source(contract, v)));
        var shares = new TreeMap<String, Set<BigDecimal>>();
        var share = SHARE.matcher(contract.text());
        while (share.find()) {
            var count = ChineseNumber.parse(share.group(2));
            if (count.isEmpty()) continue;
            String party = share.group(1).replace("采购人", "甲方").replace("供应商", "乙方");
            shares.computeIfAbsent(party, k -> new TreeSet<>()).add(count.get());
            sources.add(source(contract, share.group()));
        }
        var both = BOTH_SHARE.matcher(contract.text());
        while (both.find()) {
            var count=ChineseNumber.parse(both.group(1));
            if(count.isEmpty())continue;
            for(String party:List.of("甲方","乙方"))shares.computeIfAbsent(party,k->new TreeSet<>()).add(count.get());
            sources.add(source(contract,both.group()));
        }
        boolean ambiguous = totals.size() != 1 || shares.values().stream().anyMatch(v -> v.size() != 1);
        boolean allocated = shares.size() >= 2 && !ambiguous;
        BigDecimal sum = shares.values().stream().filter(v -> v.size() == 1).map(v -> v.iterator().next()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = totals.keySet().iterator().next();
        boolean mismatch = allocated && sum.compareTo(total) != 0;
        if (ambiguous || allocated) checks.add(new DeterministicCheck("COPY_COUNT_REQUIREMENT_COMPARISON", ambiguous || mismatch ? "REVIEW" : "NORMAL",
                ambiguous ? "合同份数存在不同约定。" : "合同约定一式" + total.toPlainString() + "份；"
                + (allocated ? "各方留存合计" + sum.toPlainString() + "份" + (mismatch ? "，与总份数不一致。" : "，与总份数一致。") : "未列出完整的各方份数分配。"),
                ambiguous || mismatch ? "统一总份数与各方留存份数。" : null, sources));
        for (var material : materials) if (material.category() == ProjectFileCategory.TENDER_DOCUMENT) {
            var expected = TOTAL.matcher(material.text());
            var requirements = new TreeMap<BigDecimal, String>();
            while (expected.find()) {
                String before = material.text().substring(Math.max(0, expected.start() - 12), expected.start());
                if (before.matches("(?s).*(?:投标文件|响应文件|报价文件).*$")) continue;
                if (!before.contains("合同")) continue;
                ChineseNumber.parse(expected.group(1)).ifPresent(v -> requirements.put(v, expected.group()));
            }
            if (requirements.size() == 1 && totals.size() == 1) {
                var target = requirements.firstKey();
                checks.add(new DeterministicCheck("COPY_COUNT_REQUIREMENT_COMPARISON", total.compareTo(target) == 0 ? "NORMAL" : "REVIEW",
                        "招标文件要求合同" + target.toPlainString() + "份，合同约定" + total.toPlainString() + "份。", null,
                        List.of(source(contract, totals.values().iterator().next()), source(material, requirements.get(target)))));
            }
        }
    }
    private static void dateCompleteness(ContractClauseMaterial contract, List<DateItem> dates, List<ConditionItem> conditions, List<DeterministicCheck> checks) {
        var own = dates.stream().filter(d -> d.sourceFileId().equals(contract.fileId())).toList();
        // A short extract/attachment declaration is not proof of a complete
        // agreement. Do not turn such snippets into global missing-date risks.
        boolean substantive=contract.text().lines().filter(ContractDocumentChecks::substantive).count()>=2;
        boolean structured=contract.text().length()>=300 && substantive && (Pattern.compile("第[一二三四五六七八九十百0-9]+条").matcher(contract.text()).results().count()>=2
                ||contract.text().contains("甲方")&&contract.text().contains("乙方")&&contract.text().length()>=150);
        if(own.isEmpty()&&!structured)return;
        for (String type : List.of("SIGNING_DATE", "EFFECTIVE_DATE", "TERMINATION_DATE")) {
            var found = own.stream().filter(d -> d.type().equals(type)).toList();
            if (found.isEmpty()) continue; // Missing labels are handled by conditions below, never invented dates.
            if (found.stream().anyMatch(d -> d.normalizedDate() == null))
                checks.add(new DeterministicCheck("DATE_FORMAT_COMPLETENESS", "REVIEW", "日期存在空白或无效格式。", "补全真实日期并使用有效的年月日。",
                        found.stream().filter(d -> d.normalizedDate() == null).map(d -> source(contract, d.excerpt())).toList()));
        }
        var sign = own.stream().filter(d -> d.type().equals("SIGNING_DATE") && d.normalizedDate() != null).map(DateItem::normalizedDate).distinct().toList();
        if (structured && sign.isEmpty()) checks.add(new DeterministicCheck("SIGN_DATE_COMPLETENESS", "ATTENTION", "合同签订日期未完整列明。", "补全签订日期。", List.of(source(contract, firstLine(contract.text())))));
        for (String first : List.of("SIGNING_DATE", "EFFECTIVE_DATE")) {
            var starts = own.stream().filter(d -> d.type().equals(first) && d.normalizedDate() != null).map(DateItem::normalizedDate).distinct().toList();
            var ends = own.stream().filter(d -> d.type().equals("TERMINATION_DATE") && d.normalizedDate() != null).map(DateItem::normalizedDate).distinct().toList();
            if (starts.size() == 1 && ends.size() == 1 && LocalDate.parse(starts.get(0)).isAfter(LocalDate.parse(ends.get(0))))
                checks.add(new DeterministicCheck("DATE_SEQUENCE", "REVIEW", "合同终止日期早于" + (first.equals("SIGNING_DATE") ? "签订" : "生效") + "日期。", "统一合同日期约定。",
                        own.stream().filter(d -> d.type().equals(first) || d.type().equals("TERMINATION_DATE")).map(d -> source(contract, d.excerpt())).toList()));
        }
        if (structured && own.stream().noneMatch(d -> d.type().equals("TERMINATION_DATE"))
                && conditions.stream().noneMatch(c -> c.sourceFileId().equals(contract.fileId()) && c.type().equals("TERMINATION")))
            checks.add(new DeterministicCheck("TERMINATION_COMPLETENESS", "ATTENTION", "合同未列明可识别的终止日期或终止条件。", "明确合同结束节点或终止条件。", List.of(source(contract, firstLine(contract.text())))));
    }
    private static boolean substantive(String line) {
        var heading=HEADING.matcher(line);
        return heading.matches()?heading.group(2)!=null&&heading.group(2).strip().length()>8:line.strip().length()>12;
    }
    private static CheckSource source(ContractClauseMaterial file, String text) { return new CheckSource(file.fileId(), text); }
    private static String firstLine(String text) { return text.lines().filter(l -> !l.isBlank()).findFirst().orElse("合同"); }
}
