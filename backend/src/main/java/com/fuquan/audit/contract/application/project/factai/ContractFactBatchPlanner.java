package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Scope;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Segment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Output-aware packing; a planning estimate never proves business-fact completeness. */
final class ContractFactBatchPlanner {
    static final int OUTPUT_TOKEN_LIMIT = 4096;
    static final int OUTPUT_RESERVE = 512;
    static final int MAX_BATCHES = 128;
    static final String POLICY_VERSION = "fact-output-plan-v3-header:4096:512:512:128";
    private static final Pattern CORE_LABEL = Pattern.compile(
            "合同编号|合同名称|合同金额|总金额|总价款|价款|金额大写|大写金额|人民币|"
                    + "(?:甲方|乙方|采购人|供应商)(?:（[^）]*）|\\([^)]*\\))?\\s*[:：]|"
                    + "Contract\\s*(?:No|Name|Amount)|Party\\s*[AB]|Credit\\s*Code", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_LABEL = Pattern.compile(
            "(?:单价|数量)\\s*[:：=]?\\s*(?:人民币\\s*)?[0-9]|(?:Line\\s*Item|明细)\\s*\\[|"
                    + "(?:Quantity|Unit\\s*Price)\\s*[:：=]", Pattern.CASE_INSENSITIVE);
    private static final Pattern AWARD_LABEL = Pattern.compile("中标金额|成交金额|中标供应商|成交供应商|成交单位|Award|Winning", Pattern.CASE_INSENSITIVE);

    List<List<Segment>> plan(List<Segment> segments, Scope scope) {
        if (segments == null || segments.isEmpty()) throw new IllegalArgumentException("evidence is required");
        List<List<Segment>> batches = new ArrayList<>();
        List<Segment> current = new ArrayList<>();
        int currentUnits = 0;
        for (int start = 0; start < segments.size();) {
            int end = start + 1;
            String row = segments.get(start).rowIdentity();
            while (end < segments.size() && row != null && Objects.equals(row, segments.get(end).rowIdentity())) end++;
            List<Segment> physicalRow = segments.subList(start, end);
            int rowUnits = physicalRow.stream().mapToInt(segment -> estimatedOutputUnits(segment, scope)).sum();
            if (physicalRow.size() <= 32 && rowUnits <= OUTPUT_TOKEN_LIMIT - OUTPUT_RESERVE) {
                if (!current.isEmpty() && (current.size() + physicalRow.size() > 32
                        || currentUnits + rowUnits > OUTPUT_TOKEN_LIMIT - OUTPUT_RESERVE)) {
                    batches.add(List.copyOf(current)); current.clear(); currentUnits = 0;
                }
                current.addAll(physicalRow); currentUnits += rowUnits;
            } else {
                // An unusually long physical row may cross batches. Its original rowIdentity
                // remains authoritative, so fragments can merge without merging same-name rows.
                for (Segment segment : physicalRow) {
                    int units = estimatedOutputUnits(segment, scope);
                    if (units > OUTPUT_TOKEN_LIMIT - OUTPUT_RESERVE) throw tooLarge();
                    if (!current.isEmpty() && (current.size() == 32
                            || currentUnits + units > OUTPUT_TOKEN_LIMIT - OUTPUT_RESERVE)) {
                        batches.add(List.copyOf(current)); current.clear(); currentUnits = 0;
                    }
                    current.add(segment); currentUnits += units;
                }
            }
            start = end;
        }
        if (!current.isEmpty()) batches.add(List.copyOf(current));
        if (batches.size() > MAX_BATCHES) throw tooLarge();
        return List.copyOf(batches);
    }

    static int estimatedOutputUnits(Segment segment, Scope scope) {
        if (segment.text().isBlank()) return 16;
        int valueBytes = segment.text().getBytes(StandardCharsets.UTF_8).length;
        if (scope == Scope.LINE_ITEMS && segment.lineItemColumns() != null) {
            // One compact row expands server-side to the same four durable facts.
            return 512 + valueBytes * 2;
        }
        if (scope == Scope.LINE_ITEMS && segment.text().indexOf('\t') >= 0) {
            return 4 * 256 + valueBytes * 2;
        }
        Pattern labels = scope == Scope.LINE_ITEMS ? LINE_LABEL : scope == Scope.AWARD ? AWARD_LABEL : CORE_LABEL;
        int occurrences = (int) labels.matcher(segment.text()).results().count();
        if (occurrences == 0) return 16;
        int fields = scope == Scope.LINE_ITEMS ? 4 : occurrences;
        // Labels estimate likely output only; every segment is still transmitted.
        // Unanticipated output remains fail-closed behind the LENGTH gate, never
        // a basis for declaring missing facts or cutting source text.
        return fields * 256 + valueBytes * 2;
    }

    private static IllegalArgumentException tooLarge() {
        return new IllegalArgumentException("fact output plan exceeds bounded batch capacity");
    }
}
