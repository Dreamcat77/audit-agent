package com.fuquan.audit.contract.application.ai;

import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class ContractAiTextRedactor {

    public static final String MASK = "[确定性数值字段已移除]";

    private static final String CHINESE_AMOUNT =
            "[零〇一二三四五六七八九壹贰叁肆伍陆柒捌玖拾佰仟万亿圆元角分整正]+";
    private static final String NUMBER = "[+-]?\\d[\\d,]*(?:\\.\\d+)?";
    private static final String BRACKETED_NUMBER =
            "[【\\[][ \\t]*" + NUMBER + "[ \\t]*[】\\]]";
    private static final String BRACKETED_CHINESE_AMOUNT =
            "[【\\[][ \\t]*" + CHINESE_AMOUNT + "[ \\t]*[】\\]]";
    private static final String ABSOLUTE_LABELLED_NUMBER =
            "(?>" + NUMBER + ")(?![ \\t]*[%％‰])";
    private static final String PER_UNIT = "(?:[ \\t]*/[ \\t]*(?:台|套|件|项))?";
    private static final String MONETARY_UNIT = "(?:万元|元)" + PER_UNIT;
    private static final String MONETARY_LABEL =
            "(?:金额大写|大写金额|合计金额|金额合计|含税单价|不含税单价|含税总价|最高限价|"
                    + "预算口径|小计|税额|成交价|"
                    + "(?:(?:合同|中标|预算|投标|申报|含税|不含税|结算|支付)?金额)|"
                    + "单价|总价|价款|报价|价格|费用)";
    private static final String OPTIONAL_MONETARY_UNIT =
            "[ \\t]*(?:[（(][ \\t]*(?:万元|元)[ \\t]*[）)])?";
    private static final String EXPLICIT_MONETARY_VALUE =
            "(?:人民币[ \\t]*" + CHINESE_AMOUNT
                    + "|(?:人民币|[￥¥])[ \\t]*(?:" + BRACKETED_NUMBER + "|" + NUMBER + ")"
                    + "[ \\t]*(?:万元|元)?" + PER_UNIT
                    + "|" + NUMBER + "[ \\t]*" + MONETARY_UNIT + ")";
    private static final String LABELLED_CHINESE_AMOUNT =
            "(?:人民币[ \\t]*)?(?:" + BRACKETED_CHINESE_AMOUNT
                    + "[ \\t]*(?:元)?(?:整|正)?|" + CHINESE_AMOUNT + ")";

    private static final Pattern MONETARY_HEADER = Pattern.compile(
            "^" + MONETARY_LABEL + OPTIONAL_MONETARY_UNIT + "$"
    );
    private static final int MAX_FLATTENED_TABLE_COLUMNS = 20;
    private static final Pattern MONETARY_TABLE_VALUE = Pattern.compile(
            "^(?:人民币[ \\t]*" + CHINESE_AMOUNT
                    + "|(?:(?:人民币|[￥¥])[ \\t]*)?" + NUMBER
                    + "[ \\t]*(?:万元|元)?" + PER_UNIT + ")$"
    );

    private static final Pattern CHINESE_UPPERCASE_RMB = Pattern.compile(
            "人民币[ \\t]*(?:" + BRACKETED_CHINESE_AMOUNT
                    + "[ \\t]*(?:元)?(?:整|正)?|" + CHINESE_AMOUNT + ")"
    );
    private static final Pattern LABELLED_AMOUNT_FIELD = Pattern.compile(
            MONETARY_LABEL + OPTIONAL_MONETARY_UNIT + "[ \\t]*[:：为是]?[ \\t]*"
                    + "(?:" + EXPLICIT_MONETARY_VALUE + "|" + LABELLED_CHINESE_AMOUNT
                    + "|" + ABSOLUTE_LABELLED_NUMBER + ")"
    );
    private static final Pattern EXPLICIT_MONETARY_AMOUNT = Pattern.compile(EXPLICIT_MONETARY_VALUE);

    public String redact(String source) {
        Objects.requireNonNull(source, "source must not be null");
        String redacted = redactTableColumns(source);
        redacted = LABELLED_AMOUNT_FIELD.matcher(redacted).replaceAll(MASK);
        redacted = CHINESE_UPPERCASE_RMB.matcher(redacted).replaceAll(MASK);
        return EXPLICIT_MONETARY_AMOUNT.matcher(redacted).replaceAll(MASK);
    }

    private static String redactTableColumns(String source) {
        String newline = source.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = source.split("\\r?\\n", -1);
        boolean changed = redactDelimitedTables(lines, "\\s*\\|\\s*", " | ");
        changed |= redactDelimitedTables(lines, "\\t", "\t");
        changed |= redactFlattenedTableCells(lines);
        return changed ? String.join(newline, lines) : source;
    }

    private static boolean redactDelimitedTables(String[] lines, String delimiterPattern, String delimiter) {
        boolean changed = false;
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (!hasDelimiter(lines[lineIndex], delimiter)) {
                continue;
            }
            String[] headers = lines[lineIndex].split(delimiterPattern, -1);
            boolean[] monetaryColumns = monetaryColumns(headers);
            if (!containsTrue(monetaryColumns)) {
                continue;
            }
            maskColumns(headers, monetaryColumns);
            lines[lineIndex] = String.join(delimiter, headers);
            changed = true;

            for (int rowIndex = lineIndex + 1; rowIndex < lines.length; rowIndex++) {
                if (!hasDelimiter(lines[rowIndex], delimiter)) {
                    break;
                }
                String[] row = lines[rowIndex].split(delimiterPattern, -1);
                if (row.length != headers.length) {
                    break;
                }
                maskColumns(row, monetaryColumns);
                lines[rowIndex] = String.join(delimiter, row);
                lineIndex = rowIndex;
            }
        }
        return changed;
    }

    private static boolean hasDelimiter(String line, String delimiter) {
        return "\t".equals(delimiter) ? line.indexOf('\t') >= 0 : line.indexOf('|') >= 0;
    }

    private static boolean[] monetaryColumns(String[] headers) {
        boolean[] columns = new boolean[headers.length];
        for (int index = 0; index < headers.length; index++) {
            columns[index] = isMonetaryHeader(headers[index]);
        }
        return columns;
    }

    private static void maskColumns(String[] cells, boolean[] monetaryColumns) {
        for (int index = 0; index < cells.length; index++) {
            if (monetaryColumns[index]) {
                cells[index] = MASK;
            }
        }
    }

    private static boolean redactFlattenedTableCells(String[] lines) {
        boolean changed = false;
        for (int headerStart = 0; headerStart < lines.length; headerStart++) {
            FlattenedTable table = inferFlattenedTable(lines, headerStart);
            if (table == null) {
                continue;
            }
            for (int index = 0; index < table.columnCount(); index++) {
                if (table.monetaryColumns()[index]) {
                    lines[headerStart + index] = MASK;
                }
            }
            changed = true;

            int rowStart = headerStart + table.columnCount();
            for (int row = 0; row < table.rowCount(); row++) {
                for (int index = 0; index < table.columnCount(); index++) {
                    if (table.monetaryColumns()[index]) {
                        lines[rowStart + index] = MASK;
                    }
                }
                rowStart += table.columnCount();
            }
            headerStart = rowStart - 1;
        }
        return changed;
    }

    private static FlattenedTable inferFlattenedTable(String[] lines, int headerStart) {
        int availableCells = lines.length - headerStart;
        int maxColumns = Math.min(MAX_FLATTENED_TABLE_COLUMNS, availableCells / 2);
        FlattenedTable best = null;
        for (int columnCount = 2; columnCount <= maxColumns; columnCount++) {
            int headerEnd = headerStart + columnCount;
            if (!arePlausibleHeaders(lines, headerStart, headerEnd)) {
                continue;
            }
            boolean[] monetaryColumns = monetaryColumnsBetween(lines, headerStart, headerEnd);
            if (!containsTrue(monetaryColumns)) {
                continue;
            }
            if (headerEnd < lines.length && isMonetaryHeader(lines[headerEnd])) {
                continue;
            }
            int rowCount = countCompleteRows(lines, headerEnd, columnCount, monetaryColumns);
            if (rowCount == 0) {
                continue;
            }
            FlattenedTable candidate = new FlattenedTable(columnCount, monetaryColumns, rowCount);
            if (best == null || candidate.monetaryColumnCount() > best.monetaryColumnCount()
                    || candidate.monetaryColumnCount() == best.monetaryColumnCount()
                    && candidate.rowCount() > best.rowCount()) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean arePlausibleHeaders(String[] lines, int start, int end) {
        for (int index = start; index < end; index++) {
            String value = lines[index].trim();
            if (value.isEmpty() || value.length() > 40 || value.contains("：") || value.contains(":")) {
                return false;
            }
            if (!isMonetaryHeader(value) && MONETARY_TABLE_VALUE.matcher(value).matches()) {
                return false;
            }
        }
        return true;
    }

    private static int countCompleteRows(
            String[] lines, int rowStart, int columnCount, boolean[] monetaryColumns) {
        int rows = 0;
        while (rowStart + columnCount <= lines.length
                && hasMonetaryValues(lines, rowStart, monetaryColumns)) {
            rows++;
            rowStart += columnCount;
        }
        return rows;
    }

    private static boolean[] monetaryColumnsBetween(String[] lines, int start, int end) {
        boolean[] columns = new boolean[end - start];
        for (int index = 0; index < columns.length; index++) {
            columns[index] = isMonetaryHeader(lines[start + index]);
        }
        return columns;
    }

    private static boolean hasMonetaryValues(String[] lines, int rowStart, boolean[] monetaryColumns) {
        for (int index = 0; index < monetaryColumns.length; index++) {
            if (monetaryColumns[index]
                    && !MONETARY_TABLE_VALUE.matcher(lines[rowStart + index].trim()).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMonetaryHeader(String value) {
        return MONETARY_HEADER.matcher(value.trim()).matches();
    }

    private static boolean containsTrue(boolean[] values) {
        for (boolean value : values) {
            if (value) {
                return true;
            }
        }
        return false;
    }

    private record FlattenedTable(int columnCount, boolean[] monetaryColumns, int rowCount) {
        private int monetaryColumnCount() {
            int count = 0;
            for (boolean monetaryColumn : monetaryColumns) {
                if (monetaryColumn) {
                    count++;
                }
            }
            return count;
        }
    }
}
