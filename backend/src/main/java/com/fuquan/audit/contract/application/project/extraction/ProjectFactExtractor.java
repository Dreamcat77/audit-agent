package com.fuquan.audit.contract.application.project.extraction;

import com.fuquan.audit.contract.application.project.ExtractionKeyFactory;
import com.fuquan.audit.contract.application.project.ExtractionKeyFactoryV2;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ProjectFactExtractor {
    public static final String EXTRACTION_VERSION = "project-extraction-v2";
    private static final String SEP = "\\s*[:\\uFF1A]\\s*";
    private static final String FACT_LABEL = "(?:Project\\s*(?:Name|No\\.?|Number)|项目名称"
            + "|项目编号(?:（[^）\\r\\n]{0,120}）)?"
            + "|项目号|Purchaser|采购人|Procurement\\s*Agent|采购代理机构|Contract\\s*(?:Name|Type|No\\.?|Number)"
            + "|合同名称|合同类型|合同编号|合同号|Party\\s*[AB]|甲方|乙方|Supplier|供应商|Credit\\s*Code"
            + "|统一社会信用代码|信用代码|Contract\\s*Amount|合同金额|总金额|Amount\\s*Uppercase"
            + "|Amount\\s*In\\s*Words|金额大写|大写金额|Winning\\s*Amount|Award\\s*Amount|中标金额|成交金额|成交价"
            + "|Winning\\s*Supplier|Award\\s*Supplier|中标供应商|成交供应商(?:名称)?|成交单位)";
    private static final String TEXT = "((?:(?!\\s*(?:[，,]\\s*)?" + FACT_LABEL + "\\s*[:\\uFF1A])"
            + "[^\\r\\n；;。]){1,200}?)(?=\\s*(?:\\r?\\n|[；;。]|$|[，,]?\\s*" + FACT_LABEL
            + "\\s*[:\\uFF1A]))";
    private static final String TOKEN = "\\s*[【\\[]?\\s*([A-Za-z0-9._/-]+)\\s*[】\\]]?";
    private static final String PROJECT_NUMBER_LABEL =
            "(?:Project\\s*(?:No\\.?|Number)|项目编号(?:（[^）\\r\\n]{0,120}）)?|项目号)";
    private static final String MONEY = "(?:CNY|RMB|人民币|\\u00A5)?\\s*[【\\[]?\\s*"
            + "([0-9][0-9,]*(?:\\.[0-9]{1,4})?)(?![0-9,]|\\.[0-9]|\\s*[】\\]]?\\s*[%％])"
            + "\\s*[】\\]]?\\s*(?:元)?";
    private static final String LABELED_MONEY = "(?:CNY|RMB|人民币|\\u00A5)?\\s*[【\\[]?\\s*"
            + "([0-9][0-9,]*(?:\\.[0-9]{1,4})?)(?![0-9,]|\\.[0-9])"
            + "\\s*[】\\]]?+\\s*(万元|元)?+(?![ \\t]*[%％\\p{L}】\\]])";
    private static final Set<String> PROJECT_MATERIAL_FACT_TYPES = Set.of(
            "PROJECT_NAME", "PROJECT_NO", "PURCHASER", "PROCUREMENT_AGENT");
    private static final List<Rule> RULES = List.of(
            text("PROJECT_NAME", "(?:Project\\s*Name|项目名称)" + SEP + TEXT, false),
            text("PROJECT_NO", PROJECT_NUMBER_LABEL + SEP + TOKEN, false),
            organization("PURCHASER", "(?:Purchaser|采购人)" + SEP + TEXT, false),
            organization("PROCUREMENT_AGENT", "(?:Procurement\\s*Agent|采购代理机构)" + SEP + TEXT, false),
            text("CONTRACT_NO", "(?:Contract\\s*(?:No\\.?|Number)|合同编号|合同号)" + SEP + TOKEN, true),
            text("CONTRACT_NAME", "(?:Contract\\s*Name|合同名称)" + SEP + TEXT, true),
            text("CONTRACT_TYPE", "(?:Contract\\s*Type|合同类型)" + SEP + TEXT, true),
            organization("PARTY_A", "(?:Party\\s*A|甲方)" + SEP + TEXT, true),
            organization("PARTY_B", "(?:Party\\s*B|乙方)" + SEP + TEXT, true),
            organization("SUPPLIER_NAME", "(?<!Winning\\s)(?<!Award\\s)(?<!中标)(?<!成交)(?:Supplier|供应商)"
                    + SEP + TEXT, false),
            text("CREDIT_CODE", "(?<!Winning\\s)(?<!Winning\\sSupplier\\s)(?<!中标供应商)"
                    + "(?:Credit\\s*Code|统一社会信用代码|信用代码)" + SEP + "([0-9A-Z]{18})", true),
            amountRule("AMOUNT_NUMERIC",
                    "(?:本?合同(?:标的[^：:\\r\\n]{0,40})?总价款(?:为)?|Contract\\s*Amount|合同金额|总金额)"
                            + SEP + LABELED_MONEY, true),
            uppercase("AMOUNT_UPPERCASE",
                    "(?:Amount\\s*Uppercase|Amount\\s*In\\s*Words|金额大写|大写金额)"
                            + SEP + "([^\\r\\n。）]{1,100}(?:元整)?)", true),
            amountRule("AWARD_AMOUNT", "(?:Winning\\s*Amount|Award\\s*Amount|中标金额|成交金额|成交价)"
                    + SEP + LABELED_MONEY, false),
            organization("AWARD_SUPPLIER", "(?:Winning\\s*Supplier|Award\\s*Supplier|中标供应商|成交供应商(?:名称)?|成交单位)"
                    + SEP + TEXT, false),
            text("AWARD_SUPPLIER_CREDIT_CODE",
                    "(?:Winning\\s*Supplier\\s*Credit\\s*Code|Winning\\s*Credit\\s*Code|"
                            + "中标供应商(?:统一社会)?信用代码)" + SEP + "([0-9A-Z]{18})", false));
    private static final Pattern PACKAGE_AMOUNT = Pattern.compile(
            "(预算金额|最高限价)\\s*[（(]\\s*([A-Za-z])\\s*包\\s*[-—－]?\\s*([^）)]+?)\\s*[）)]"
                    + SEP + "([0-9][0-9,]*(?:\\.[0-9]{1,4})?)\\s*(万元|元)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_CONTRACT_TITLE = Pattern.compile(
            "^[^：:\\r\\n]{1,100}(?:合同|Contract|Agreement)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_ITEM = Pattern.compile(
            "(?:Line\\s*Item|明细)\\s*\\[([^]\\r\\n]{1,100})]\\s*"
                    + "(?:Name|名称)" + SEP + "(.+?)\\s+"
                    + "(?:Quantity|数量)" + SEP + "([0-9][0-9,]*(?:\\.[0-9]{1,4})?)\\s+"
                    + "(?:Unit\\s*Price|单价)" + SEP + MONEY + "\\s+"
                    + "(?:Declared\\s*Amount|申报金额)" + SEP + MONEY,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_NUMBER = Pattern.compile("[0-9][0-9,]*(?:\\.[0-9]{1,4})?");
    private static final Pattern AWARD_MONEY_CELL = Pattern.compile("^" + LABELED_MONEY + "$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AWARD_MONEY_LINE = Pattern.compile(
            "(?m)^[ \\t]*(?:Winning\\s*Amount|Award\\s*Amount|中标金额|成交金额|成交价)[ \\t]+"
                    + LABELED_MONEY, Pattern.CASE_INSENSITIVE);
    private static final Pattern AWARD_SUPPLIER_LINE = Pattern.compile(
            "(?m)^[ \\t]*(?:Winning\\s*Supplier|Award\\s*Supplier|中标供应商|成交供应商(?:名称)?|成交单位)"
                    + "[ \\t]+([^\\r\\n；;。]{2,120})", Pattern.CASE_INSENSITIVE);
    private static final Pattern AWARD_MONEY_INLINE_CELL = Pattern.compile(
            "^(?:Winning\\s*Amount|Award\\s*Amount|中标金额|成交金额|成交价)"
                    + SEP + LABELED_MONEY + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AWARD_SUPPLIER_INLINE_CELL = Pattern.compile(
            "^(?:Winning\\s*Supplier|Award\\s*Supplier|中标供应商|成交供应商(?:名称)?|成交单位)"
                    + SEP + "([^\\r\\n；;。]{2,120})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANDIDATE_CONTEXT = Pattern.compile("候选人|候选供应商");
    private static final Pattern FINAL_AWARD_CONTEXT = Pattern.compile("最终成交结果|成交结果公告|最终中标结果|中标结果|成交结果");
    private static final Set<String> PLACEHOLDERS = Set.of(
            "TBD", "N/A", "N-A", "NA", "待填", "待定", "暂无", "示例");

    private final ExtractionKeyFactory keys;
    private final ExtractionKeyFactoryV2 keysV2 = new ExtractionKeyFactoryV2();
    private final Clock clock;

    @Autowired
    public ProjectFactExtractor(Clock clock) {
        this(new ExtractionKeyFactory(), clock);
    }

    public ProjectFactExtractor(ExtractionKeyFactory keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    public List<ExtractionResult> extract(ProjectFile source, ParsedProjectDocument parsed) {
        return extract(source, parsed, null, ProjectFactExtractionMode.POTENTIAL_CONTRACT);
    }

    public List<ExtractionResult> extractPotential(ProjectFile source, ParsedProjectDocument parsed,
                                                   UUID parseResultId) {
        return extract(source, parsed, java.util.Objects.requireNonNull(parseResultId),
                ProjectFactExtractionMode.POTENTIAL_CONTRACT);
    }

    public List<ExtractionResult> extract(ProjectFile source, ParsedProjectDocument parsed,
            UUID parseResultId, ProjectFactExtractionMode mode) {
        List<ExtractionResult> results = new ArrayList<>();
        ProjectFactContextGuard context = new ProjectFactContextGuard(mode);
        for (ParsedProjectDocument.Segment segment : parsed.segments()) {
            ProjectFactContextGuard.SegmentAccess access = context.accessFor(segment);
            extractBareContractTitle(source, segment, parseResultId, access.formalScalarAllowed(), results);
            for (Rule rule : RULES) {
                if (segment.sourceLocator().startsWith("docx/table:")
                        && rule.factType().startsWith("AWARD_")) continue;
                boolean projectMaterialAllowed = !PROJECT_MATERIAL_FACT_TYPES.contains(rule.factType())
                        || access.projectMaterialCandidateAllowed();
                if (projectMaterialAllowed
                        && (!rule.formalContractScalar() || access.formalScalarAllowed())) {
                    extractRule(source, segment, parseResultId, rule, results);
                }
            }
            if (access.projectMaterialCandidateAllowed()
                    && source.category() == ProjectFileCategory.AWARD_NOTICE
                    && !segment.sourceLocator().startsWith("docx/table:")) {
                extractLooseAwardLines(source, segment, parseResultId, results);
            }
            if (access.projectMaterialCandidateAllowed()) {
                extractPackageAmounts(source, segment, parseResultId, results);
            }
            if (access.potentialContractAllowed()) {
                extractLineItems(source, segment, parseResultId, results);
            }
        }
        if (context.potentialContractTableAllowed()) {
            for (ParsedProjectDocument.Table table : parsed.tables()) {
                extractLineItemTable(source, table, parseResultId, results);
                extractAwardKeyValueTable(source, table, parseResultId, results);
            }
        }
        return List.copyOf(results);
    }

    private void extractBareContractTitle(ProjectFile source, ParsedProjectDocument.Segment segment,
                                          UUID parseResultId,
                                          boolean allowed, List<ExtractionResult> results) {
        String raw = segment.text().strip();
        if (!allowed || !frontMatter(segment) || !BARE_CONTRACT_TITLE.matcher(raw).matches()
                || clauseLikeTitle(raw) || placeholder(normalizedText(raw))) {
            return;
        }
        add(source, segment, parseResultId, results, "CONTRACT_NAME", null, raw, normalizedText(raw),
                null, raw, 1);
    }

    private void extractRule(ProjectFile source, ParsedProjectDocument.Segment segment,
                             UUID parseResultId, Rule rule,
                             List<ExtractionResult> results) {
        Matcher matcher = rule.pattern().matcher(segment.text());
        int occurrence = 0;
        while (matcher.find()) {
            if (!matchContextAllowed(rule.factType(), segment.text(), matcher.start())) {
                continue;
            }
            String raw = matcher.group(1).trim();
            BigDecimal valueAmount = null;
            String normalized;
            if (rule.kind() == ValueKind.AMOUNT) {
                String unit = matcher.groupCount() > 1 ? matcher.group(2) : null;
                raw = raw + (unit == null ? "" : unit);
                valueAmount = amount(raw);
                normalized = canonicalAmount(valueAmount);
            } else {
                normalized = rule.kind() == ValueKind.UPPERCASE
                        ? normalizedUppercase(raw) : normalizedText(raw);
            }
            if (placeholder(normalized) || !validTypedValue(rule.kind(), normalized, raw)) {
                continue;
            }
            occurrence++;
            add(source, segment, parseResultId, results, rule.factType(), null, raw, normalized,
                    valueAmount, matcher.group(), occurrence);
        }
    }

    private void extractLooseAwardLines(ProjectFile source, ParsedProjectDocument.Segment segment,
                                        UUID parseResultId, List<ExtractionResult> results) {
        Matcher amounts = AWARD_MONEY_LINE.matcher(segment.text());
        int amountOccurrence = 0;
        while (amounts.find()) {
            if (amounts.group().contains("\n") || amounts.group().contains("\r")
                    || !matchContextAllowed("AWARD_AMOUNT", segment.text(), amounts.start())) continue;
            String raw = amounts.group(1).trim() + (amounts.group(2) == null ? "" : amounts.group(2));
            BigDecimal value = amount(raw);
            add(source, segment, parseResultId, results, "AWARD_AMOUNT", null, raw,
                    canonicalAmount(value), value, amounts.group().strip(), ++amountOccurrence);
        }
        Matcher suppliers = AWARD_SUPPLIER_LINE.matcher(segment.text());
        int supplierOccurrence = 0;
        while (suppliers.find()) {
            if (!matchContextAllowed("AWARD_SUPPLIER", segment.text(), suppliers.start())) continue;
            String raw = suppliers.group(1).strip();
            String normalized = normalizedText(raw);
            if (placeholder(normalized) || !validTypedValue(ValueKind.ORGANIZATION, normalized, raw)) continue;
            add(source, segment, parseResultId, results, "AWARD_SUPPLIER", null, raw,
                    normalized, null, suppliers.group().strip(), ++supplierOccurrence);
        }
    }

    private void extractPackageAmounts(ProjectFile source, ParsedProjectDocument.Segment segment,
                                       UUID parseResultId,
                                       List<ExtractionResult> results) {
        Matcher matcher = PACKAGE_AMOUNT.matcher(segment.text());
        int occurrence = 0;
        while (matcher.find()) {
            occurrence++;
            String groupKey = "PACKAGE:" + matcher.group(2).toUpperCase(Locale.ROOT);
            String scope = normalizedText(matcher.group(3));
            String rawAmount = matcher.group(4) + matcher.group(5);
            if (placeholder(scope)) {
                continue;
            }
            String type = matcher.group(1).equals("预算金额") ? "BUDGET_AMOUNT" : "CONTROL_PRICE";
            BigDecimal valueAmount = amount(rawAmount);
            add(source, segment, parseResultId, results, type, groupKey, rawAmount, canonicalAmount(valueAmount),
                    valueAmount, matcher.group(), occurrence);
            String rawScope = matcher.group(2).toUpperCase(Locale.ROOT) + "包-" + matcher.group(3).trim();
            add(source, segment, parseResultId, results, "PACKAGE_SCOPE", groupKey, rawScope, scope,
                    null, matcher.group(), occurrence);
        }
    }

    private void extractLineItems(ProjectFile source, ParsedProjectDocument.Segment segment,
                                  UUID parseResultId,
                                  List<ExtractionResult> results) {
        Matcher matcher = LINE_ITEM.matcher(segment.text());
        int occurrence = 0;
        while (matcher.find()) {
            occurrence++;
            String key = matcher.group(1).trim();
            String sourceText = matcher.group();
            add(source, segment, parseResultId, results, "LINE_ITEM_KEY", key, key, key, null, sourceText, occurrence);
            add(source, segment, parseResultId, results, "LINE_ITEM_NAME", key, matcher.group(2).trim(),
                    normalizedText(matcher.group(2)), null, sourceText, occurrence);
            add(source, segment, parseResultId, results, "LINE_ITEM_QUANTITY", key, matcher.group(3).trim(),
                    matcher.group(3).trim(), amount(matcher.group(3)), sourceText, occurrence);
            add(source, segment, parseResultId, results, "LINE_ITEM_UNIT_PRICE", key, matcher.group(4).trim(),
                    matcher.group(4).trim(), amount(matcher.group(4)), sourceText, occurrence);
            add(source, segment, parseResultId, results, "LINE_ITEM_DECLARED_AMOUNT", key, matcher.group(5).trim(),
                    matcher.group(5).trim(), amount(matcher.group(5)), sourceText, occurrence);
        }
    }

    private void extractLineItemTable(ProjectFile source, ParsedProjectDocument.Table table,
                                      UUID parseResultId,
                                      List<ExtractionResult> results) {
        for (int headerIndex = 0; headerIndex < table.rows().size(); headerIndex++) {
            Map<LineColumn, Integer> columns = headerColumns(table.rows().get(headerIndex));
            if (columns.size() != LineColumn.values().length) {
                continue;
            }
            for (int rowIndex = headerIndex + 1; rowIndex < table.rows().size(); rowIndex++) {
                extractTypedLine(source, table.rows().get(rowIndex), parseResultId, columns, results);
            }
            return;
        }
    }

    private static Map<LineColumn, Integer> headerColumns(ParsedProjectDocument.Row row) {
        Map<LineColumn, Integer> columns = new EnumMap<>(LineColumn.class);
        for (int index = 0; index < row.cells().size(); index++) {
            String header = normalizedHeader(row.cells().get(index).text());
            for (LineColumn column : LineColumn.values()) {
                if (column.aliases().contains(header)) {
                    columns.putIfAbsent(column, index);
                }
            }
        }
        return columns;
    }

    private void extractTypedLine(ProjectFile source, ParsedProjectDocument.Row row,
                                  UUID parseResultId,
                                  Map<LineColumn, Integer> columns, List<ExtractionResult> results) {
        int lastColumn = columns.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        if (row.cells().size() <= lastColumn) {
            return;
        }
        ParsedProjectDocument.Cell keyCell = cell(row, columns, LineColumn.KEY);
        ParsedProjectDocument.Cell nameCell = cell(row, columns, LineColumn.NAME);
        ParsedProjectDocument.Cell quantityCell = cell(row, columns, LineColumn.QUANTITY);
        ParsedProjectDocument.Cell unitPriceCell = cell(row, columns, LineColumn.UNIT_PRICE);
        ParsedProjectDocument.Cell declaredAmountCell = cell(row, columns, LineColumn.DECLARED_AMOUNT);
        String key = keyCell.text().strip();
        String name = nameCell.text().strip();
        String quantity = quantityCell.text().strip();
        String unitPrice = unitPriceCell.text().strip();
        String declaredAmount = declaredAmountCell.text().strip();
        if (key.isEmpty() || name.isEmpty() || !number(quantity) || !number(unitPrice) || !number(declaredAmount)) {
            return;
        }
        String rowContext = bounded(row.cells().stream()
                .map(ParsedProjectDocument.Cell::text)
                .collect(Collectors.joining(" ")));
        add(source, keyCell, parseResultId, results, "LINE_ITEM_KEY", key, key, key, null, rowContext, 1);
        add(source, nameCell, parseResultId, results, "LINE_ITEM_NAME", key, name, normalizedText(name), null, rowContext, 1);
        add(source, quantityCell, parseResultId, results, "LINE_ITEM_QUANTITY", key, quantity, quantity,
                amount(quantity), rowContext, 1);
        add(source, unitPriceCell, parseResultId, results, "LINE_ITEM_UNIT_PRICE", key, unitPrice, unitPrice,
                amount(unitPrice), rowContext, 1);
        add(source, declaredAmountCell, parseResultId, results, "LINE_ITEM_DECLARED_AMOUNT", key,
                declaredAmount, declaredAmount, amount(declaredAmount), rowContext, 1);
    }

    private static ParsedProjectDocument.Cell cell(ParsedProjectDocument.Row row,
                                                    Map<LineColumn, Integer> columns,
                                                    LineColumn column) {
        return row.cells().get(columns.get(column));
    }

    private void extractAwardKeyValueTable(ProjectFile source, ParsedProjectDocument.Table table,
                                           UUID parseResultId, List<ExtractionResult> results) {
        boolean candidateSection = false;
        for (ParsedProjectDocument.Row row : table.rows()) {
            String rowText = row.cells().stream().map(ParsedProjectDocument.Cell::text)
                    .collect(Collectors.joining(" "));
            if (FINAL_AWARD_CONTEXT.matcher(rowText).find()) candidateSection = false;
            if (CANDIDATE_CONTEXT.matcher(rowText).find()) candidateSection = true;
            if (candidateSection) continue;
            for (ParsedProjectDocument.Cell cell : row.cells()) {
                extractAwardInlineCell(source, cell, parseResultId, results);
            }
            for (int index = 0; index + 1 < row.cells().size(); index++) {
                ParsedProjectDocument.Cell labelCell = row.cells().get(index);
                ParsedProjectDocument.Cell valueCell = row.cells().get(index + 1);
                String label = normalizedHeader(labelCell.text()).replaceFirst(":$", "");
                String factType = switch (label) {
                    case "中标金额", "成交金额", "成交价", "winningamount", "awardamount" -> "AWARD_AMOUNT";
                    case "中标供应商", "成交供应商", "成交供应商名称", "成交单位",
                            "winningsupplier", "awardsupplier" -> "AWARD_SUPPLIER";
                    default -> null;
                };
                if (factType == null) continue;
                String value = valueCell.text().strip();
                String normalized;
                BigDecimal valueAmount = null;
                String raw;
                if (factType.equals("AWARD_AMOUNT")) {
                    Matcher amount = AWARD_MONEY_CELL.matcher(value);
                    if (!amount.matches()) continue;
                    raw = amount.group(1) + (amount.group(2) == null ? "" : amount.group(2));
                    valueAmount = amount(raw);
                    normalized = canonicalAmount(valueAmount);
                } else {
                    raw = value;
                    normalized = normalizedText(value);
                    if (placeholder(normalized) || !validTypedValue(ValueKind.ORGANIZATION, normalized, value))
                        continue;
                }
                add(source, valueCell, parseResultId, results, factType, null, raw, normalized,
                        valueAmount, labelCell.text().strip() + "：" + value, 1);
            }
        }
    }

    private void extractAwardInlineCell(ProjectFile source, ParsedProjectDocument.Cell cell,
                                        UUID parseResultId, List<ExtractionResult> results) {
        String value = cell.text().strip();
        Matcher amountMatch = AWARD_MONEY_INLINE_CELL.matcher(value);
        if (amountMatch.matches()) {
            String raw = amountMatch.group(1) + (amountMatch.group(2) == null ? "" : amountMatch.group(2));
            BigDecimal valueAmount = amount(raw);
            add(source, cell, parseResultId, results, "AWARD_AMOUNT", null, raw,
                    canonicalAmount(valueAmount), valueAmount, value, 1);
            return;
        }
        Matcher supplierMatch = AWARD_SUPPLIER_INLINE_CELL.matcher(value);
        if (!supplierMatch.matches()) return;
        String raw = supplierMatch.group(1).strip();
        String normalized = normalizedText(raw);
        if (placeholder(normalized) || !validTypedValue(ValueKind.ORGANIZATION, normalized, raw)) return;
        add(source, cell, parseResultId, results, "AWARD_SUPPLIER", null, raw, normalized, null, value, 1);
    }

    private void add(ProjectFile source, ParsedProjectDocument.Segment segment, UUID parseResultId,
                     List<ExtractionResult> results,
                     String factType, String groupKey, String raw, String normalized, BigDecimal valueAmount,
                     String evidence, int occurrence) {
        String locator = segment.sourceLocator() + "/fact:" + factType + "/occurrence:" + occurrence;
        results.add(new ExtractionResult(UUID.randomUUID(), source.projectId(), source.id(), parseResultId, null,
                extractionKey(source.id(), parseResultId, factType, groupKey, locator), EXTRACTION_VERSION,
                factType, groupKey, raw, normalized, valueAmount, bounded(evidence), segment.pageNumber(),
                segment.paragraphIndex(), locator, clock.instant()));
    }

    private void add(ProjectFile source, ParsedProjectDocument.Cell cell, UUID parseResultId,
                     List<ExtractionResult> results,
                     String factType, String groupKey, String raw, String normalized, BigDecimal valueAmount,
                     String evidence, int occurrence) {
        String locator = cell.sourceLocator() + "/fact:" + factType + "/occurrence:" + occurrence;
        results.add(new ExtractionResult(UUID.randomUUID(), source.projectId(), source.id(), parseResultId, null,
                extractionKey(source.id(), parseResultId, factType, groupKey, locator), EXTRACTION_VERSION,
                factType, groupKey, raw, normalized, valueAmount, bounded(evidence), null, null,
                locator, clock.instant()));
    }

    private String extractionKey(UUID sourceFileId, UUID parseResultId, String factType,
                                 String groupKey, String locator) {
        return parseResultId == null
                ? keys.create(sourceFileId, EXTRACTION_VERSION, factType, groupKey, locator)
                : keysV2.create(sourceFileId, parseResultId, EXTRACTION_VERSION, factType, groupKey, locator);
    }

    private static String normalizedText(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").strip()
                .replaceFirst("[,;]+$", "");
        while (normalized.length() >= 2 && wrapper(normalized.charAt(0), normalized.charAt(normalized.length() - 1))) {
            normalized = normalized.substring(1, normalized.length() - 1).strip();
        }
        return normalized;
    }

    private static String normalizedUppercase(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\s【】\\[\\]()（）{}《》「」『』]+", "");
    }

    private static boolean wrapper(char first, char last) {
        return first == '【' && last == '】'
                || first == '[' && last == ']'
                || first == '(' && last == ')'
                || first == '（' && last == '）'
                || first == '《' && last == '》'
                || first == '「' && last == '」'
                || first == '『' && last == '』';
    }

    private static boolean placeholder(String value) {
        String normalized = normalizedText(value);
        String canonical = normalized.replaceAll("[\\s【】\\[\\]()（）{}《》「」『』]+", "");
        if (canonical.isEmpty() || canonical.matches("[\\p{P}\\p{S}_]+")) {
            return true;
        }
        String upper = canonical.toUpperCase(Locale.ROOT);
        return upper.matches("X+") || PLACEHOLDERS.contains(upper);
    }

    private static boolean validTypedValue(ValueKind kind, String value, String raw) {
        return switch (kind) {
            case ORGANIZATION -> value.codePoints().filter(Character::isLetter).count() >= 2
                    && com.fuquan.audit.contract.domain.ContractPartyName.isPresent(value);
            case UPPERCASE -> validUppercaseAmount(value, raw);
            default -> true;
        };
    }

    private static boolean validUppercaseAmount(String value, String raw) {
        String english = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("^[\\s【\\[({《「『]+|[\\s】\\])}》」』]+$", "").strip();
        String word = "(?:ZERO|ONE|TWO|THREE|FOUR|FIVE|SIX|SEVEN|EIGHT|NINE|TEN|ELEVEN|TWELVE"
                + "|THIRTEEN|FOURTEEN|FIFTEEN|SIXTEEN|SEVENTEEN|EIGHTEEN|NINETEEN|TWENTY"
                + "|THIRTY|FORTY|FIFTY|SIXTY|SEVENTY|EIGHTY|NINETY|HUNDRED|THOUSAND|MILLION"
                + "|BILLION|TRILLION|AND|ONLY|YUAN|RMB|DOLLARS?|CENTS?)";
        if (english.matches("(?i)^" + word + "(?:[ -]+" + word + ")*$")) {
            return true;
        }
        return value.matches("^(?:人民币)?[零壹贰叁肆伍陆柒捌玖拾佰仟万亿圆]+元"
                + "(?:[零壹贰叁肆伍陆柒捌玖拾角分]+|整|正)?$");
    }

    private static boolean frontMatter(ParsedProjectDocument.Segment segment) {
        if (segment.paragraphIndex() != null) {
            return segment.paragraphIndex() <= 20;
        }
        return segment.pageNumber() == null || segment.pageNumber() == 1;
    }

    private static boolean clauseLikeTitle(String value) {
        String normalized = normalizedText(value);
        return normalized.endsWith("本合同")
                || normalized.matches(".*(?:可以|应当|必须|不得|解除|终止|履行|签订|签署|约定).*(?:合同|协议)$")
                || normalized.toLowerCase(Locale.ROOT).matches(".*\\bthis\\s+(?:contract|agreement)$");
    }

    private static boolean matchContextAllowed(String factType, String text, int matchStart) {
        if (factType.equals("AWARD_AMOUNT") || factType.equals("AWARD_SUPPLIER")) {
            String preceding = text.substring(Math.max(0, matchStart - 40), matchStart)
                    .replaceAll("\\s+", "");
            if (preceding.matches(".*(?:候选人|候选供应商|预算|限价|报价|未|非)$")) {
                return false;
            }
            String earlier = text.substring(0, matchStart);
            int candidateAt = lastMatchStart(CANDIDATE_CONTEXT, earlier);
            return candidateAt < 0 || lastMatchStart(FINAL_AWARD_CONTEXT, earlier) > candidateAt;
        }
        if (!factType.equals("AMOUNT_NUMERIC")) {
            return true;
        }
        String prefix = text.substring(Math.max(0, matchStart - 32), matchStart)
                .replaceAll("\\s+", "");
        return !prefix.matches(".*(?:预算|最高限价|控制价|维修费用|维护费用|保养费用|代理服务费|服务费|违约金|赔偿金)$");
    }

    private static int lastMatchStart(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int last = -1;
        while (matcher.find()) last = matcher.start();
        return last;
    }

    private static BigDecimal amount(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace(",", "").replaceAll("\\s+", "").strip();
        boolean tenThousand = normalized.endsWith("万元");
        normalized = normalized.replaceFirst("(?:万元|元)$", "");
        BigDecimal parsed = new BigDecimal(normalized);
        return tenThousand ? parsed.movePointRight(4) : parsed;
    }

    private static String canonicalAmount(BigDecimal value) {
        BigDecimal canonical = value.stripTrailingZeros();
        return canonical.signum() == 0 ? "0" : canonical.toPlainString();
    }

    private static boolean number(String value) {
        return TABLE_NUMBER.matcher(value).matches();
    }

    private static String normalizedHeader(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\s_:/\\\\-]", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String bounded(String value) {
        return value.substring(0, Math.min(240, value.length()));
    }

    private static Rule text(String type, String regex, boolean formalContractScalar) {
        return new Rule(type, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), ValueKind.TEXT,
                formalContractScalar);
    }

    private static Rule amountRule(String type, String regex, boolean formalContractScalar) {
        return new Rule(type, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), ValueKind.AMOUNT,
                formalContractScalar);
    }

    private static Rule organization(String type, String regex, boolean formalContractScalar) {
        return new Rule(type, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), ValueKind.ORGANIZATION,
                formalContractScalar);
    }

    private static Rule uppercase(String type, String regex, boolean formalContractScalar) {
        return new Rule(type, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), ValueKind.UPPERCASE,
                formalContractScalar);
    }

    private record Rule(String factType, Pattern pattern, ValueKind kind, boolean formalContractScalar) {
    }

    private enum ValueKind {
        TEXT,
        ORGANIZATION,
        AMOUNT,
        UPPERCASE
    }

    private enum LineColumn {
        KEY(Set.of("标识", "明细标识", "物料标识", "itemkey", "itemid", "lineitem")),
        NAME(Set.of("货物服务名称", "货物名称", "服务名称", "名称", "itemname")),
        QUANTITY(Set.of("数量", "quantity", "qty")),
        UNIT_PRICE(Set.of("单价(元)", "单价", "unitprice")),
        DECLARED_AMOUNT(Set.of("申报金额(元)", "申报金额", "declaredamount"));

        private final Set<String> aliases;

        LineColumn(Set<String> aliases) {
            this.aliases = aliases;
        }

        Set<String> aliases() {
            return aliases;
        }
    }
}
