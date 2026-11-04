package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.project.ExtractionKeyFactoryV2;
import com.fuquan.audit.contract.application.project.extraction.ProjectFactContextGuard;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.*;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Reopens the immutable native source; tabs in arbitrary text never prove a DOCX table. */
@Component
public final class ContractFactSourceTablePlanner {
    public static final String EXTRACTION_VERSION = "contract-source-table-v1";
    public static final Provenance PROVENANCE = new Provenance("JAVA_SOURCE_TABLE", "NOT_A_MODEL",
            EXTRACTION_VERSION, "NOT_APPLICABLE", "contract-fact-source-v1");
    private static final List<String> FIELDS = List.of("name", "quantity", "unitPrice", "declaredAmount", "rowId");
    private static final List<FactType> TYPES = List.of(FactType.LINE_ITEM_NAME, FactType.LINE_ITEM_QUANTITY,
            FactType.LINE_ITEM_UNIT_PRICE, FactType.LINE_ITEM_DECLARED_AMOUNT, FactType.LINE_ITEM_KEY);
    private static final String DECIMAL_CELL = "(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]+)?";
    // This grammar proves only the complete leading line-item assertion. Any following text,
    // including new identities, numbers or conflicting instructions, remains model evidence.
    private static final Pattern PROSE_ALIAS_PREFIX = Pattern.compile(
            "^(?:(?:[1-9][0-9]*\\.)+[1-9][0-9]* +)?工作包 +(?<key>[^\\s“”]{1,200})"
                    + "“(?<name>[^“”\\r\\n]{1,240})”：对应数量(?<quantity>" + DECIMAL_CELL
                    + ")、单价(?<price>" + DECIMAL_CELL + ")元，行金额(?<amount>" + DECIMAL_CELL + ")元。");
    private final ProjectFileRepository files;
    private final FileStoragePort storage;
    private final ProjectDocxTextParser parser;

    public ContractFactSourceTablePlanner(ProjectFileRepository files, FileStoragePort storage, ProjectDocxTextParser parser) {
        this.files = files; this.storage = storage; this.parser = parser;
    }

    Plan plan(ContractFactCandidateValidator.CurrentInput input, List<Segment> segments) {
        if (input.effectiveRole() != DocumentRole.CONTRACT_BODY) return Plan.fallback(segments);
        var file = files.findByProjectIdAndId(input.projectId(), input.fileId()).orElse(null);
        if (file == null || file.parseProvenance().method() != ProjectFileParseMethod.DOCX_TEXT
                || !file.sha256().equals(input.fileSha256()) || !parser.supports(file)) return Plan.fallback(segments);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var content = storage.open(new StorageKey(file.storageKey()));
            ParsedProjectDocument document;
            try (var stream = new DigestInputStream(content.inputStream(), digest)) {
                document = parser.parse(file, stream);
            }
            if (content.size() != file.sizeBytes() || !HexFormat.of().formatHex(digest.digest()).equals(input.fileSha256())
                    || !document.fullText().equals(input.canonicalText())) return Plan.fallback(segments);
            if (!ProjectFactContextGuard.potentialContractTablesAllowed(document)) return Plan.fallback(segments);
            return planProvenDocument(input, document, segments);
        } catch (IOException | RuntimeException failure) {
            // An unavailable/replaced/unreadable source grants no deterministic authority.
            return Plan.fallback(segments);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Plan planProvenDocument(ContractFactCandidateValidator.CurrentInput input,
            ParsedProjectDocument document, List<Segment> segments) {
        Map<String, LocatedRow> locations = new HashMap<>();
        int offset = 0;
        for (var source : document.segments()) {
            locations.put(source.sourceLocator(), new LocatedRow(offset, source.text()));
            offset += length(source.text()) + 1;
        }
        List<ProvenRow> proven = new ArrayList<>();
        Map<String, Integer> keyCounts = new HashMap<>();
        Set<String> tablesWithMissingKeys = new HashSet<>();
        // Count source keys independently of value validity; a bad second row cannot hide a duplicate key.
        for (var table : document.tables()) {
            List<String> columns = null;
            for (var row : table.rows()) {
                List<String> header = columns(row);
                if (header != null) { columns = header; continue; }
                if (columns == null || row.cells().size() != columns.size()) continue;
                String key = row.cells().get(columns.indexOf("rowId")).text().strip();
                var keyColumns = columns;
                if (key.isEmpty() && List.of("quantity", "unitPrice", "declaredAmount").stream()
                        .allMatch(field -> row.cells().get(keyColumns.indexOf(field)).text().strip().matches(DECIMAL_CELL))) {
                    tablesWithMissingKeys.add(table.sourceLocator());
                }
                else if (!key.isEmpty()) keyCounts.merge(key, 1, Integer::sum);
            }
        }
        for (var table : document.tables()) {
            if (tablesWithMissingKeys.contains(table.sourceLocator())) continue;
            List<String> columns = null;
            for (var row : table.rows()) {
                List<String> header = columns(row);
                if (header != null) { columns = header; continue; }
                if (columns == null) continue;
                ProvenRow accepted = row(input, row, columns, locations.get(row.sourceLocator()), segments);
                if (accepted == null || keyCounts.getOrDefault(accepted.rowKey(), 0) != 1) {
                    columns = null; continue;
                }
                proven.add(accepted);
            }
        }
        if (proven.isEmpty()) return Plan.fallback(segments);
        Set<String> covered = new HashSet<>();
        proven.forEach(row -> row.segments().forEach(segment -> {
            if (!covered.add(segment.segmentId())) throw new IllegalStateException("source row coverage overlaps");
        }));
        Map<String, List<Segment>> aliasRemainders = coverProvenProsePrefixes(input, document, locations, segments, proven);
        List<Segment> residual = segments.stream().filter(segment -> !covered.contains(segment.segmentId()))
                .flatMap(segment -> aliasRemainders.getOrDefault(segment.segmentId(), List.of(segment)).stream()).toList();
        List<List<ProvenRow>> groups = new ArrayList<>();
        List<ProvenRow> group = new ArrayList<>();
        int groupSegments = 0;
        for (ProvenRow row : proven) {
            if (!group.isEmpty() && (group.size() == 20 || groupSegments + row.segments().size() > 32)) {
                groups.add(List.copyOf(group)); group.clear(); groupSegments = 0;
            }
            group.add(row); groupSegments += row.segments().size();
        }
        if (!group.isEmpty()) groups.add(List.copyOf(group));
        List<SourceBatch> batches = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            var rows = groups.get(index);
            List<Segment> source = rows.stream().flatMap(row -> row.segments().stream()).toList();
            List<Candidate> candidates = rows.stream().flatMap(row -> row.candidates().stream()).toList();
            Map<String, String> cells = new LinkedHashMap<>();
            rows.forEach(row -> cells.putAll(row.cellLocators()));
            var request = new Request(input.projectId(), input.fileId(), input.fileSha256(), input.parseResultId(),
                    input.parseVersion(), input.canonicalTextSha256(), source, Scope.LINE_ITEMS, index + 1, groups.size(), 900000);
            batches.add(new SourceBatch(request, new Result(candidates, PROVENANCE, 0, List.of(),
                    BatchStatus.PARTIAL, Scope.LINE_ITEMS, List.of()), cells));
        }
        return new Plan(batches, residual);
    }

    /** Adds exact prefix evidence to its native row, without creating another candidate or row identity. */
    private static Map<String, List<Segment>> coverProvenProsePrefixes(ContractFactCandidateValidator.CurrentInput input,
            ParsedProjectDocument document, Map<String, LocatedRow> locations, List<Segment> segments,
            List<ProvenRow> proven) {
        Map<String, Integer> rowIndexes = new HashMap<>();
        for (int index = 0; index < proven.size(); index++) rowIndexes.put(proven.get(index).rowKey(), index);
        Map<String, List<Segment>> remainders = new HashMap<>();
        for (var paragraph : document.segments()) {
            if (!paragraph.sourceLocator().startsWith("docx/body/paragraph:")
                    || paragraph.text().indexOf('\n') >= 0 || paragraph.text().indexOf('\r') >= 0) continue;
            Matcher alias = PROSE_ALIAS_PREFIX.matcher(paragraph.text());
            if (!alias.lookingAt()) continue;
            Integer rowIndex = rowIndexes.get(alias.group("key"));
            if (rowIndex == null) continue;
            ProvenRow row = proven.get(rowIndex);
            if (!sameNativeValues(alias, row)) continue;
            LocatedRow located = locations.get(paragraph.sourceLocator());
            int paragraphEnd = located.start() + length(located.text());
            int canonicalLength = length(input.canonicalText());
            // Native paragraph separators also need exact coverage, including a newline-only fragment.
            if (paragraphEnd < canonicalLength) paragraphEnd++;
            int prefixEnd = alias.end() == paragraph.text().length() ? paragraphEnd
                    : located.start() + paragraph.text().codePointCount(0, alias.end());
            int completeEnd = paragraphEnd;
            List<Segment> fragments = segments.stream().filter(segment -> segment.startOffset() >= located.start()
                    && segment.endOffset() <= completeEnd).toList();
            if (fragments.isEmpty() || fragments.get(0).startOffset() != located.start()
                    || fragments.get(fragments.size() - 1).endOffset() != paragraphEnd) continue;
            String identity = fragments.get(0).rowIdentity();
            if (identity == null || fragments.stream().anyMatch(segment -> !identity.equals(segment.rowIdentity()))) continue;
            List<Segment> proof = new ArrayList<>();
            Map<String, List<Segment>> remaining = new HashMap<>();
            for (Segment original : fragments) {
                if (original.startOffset() >= prefixEnd) continue;
                proof.add(slice(input.parseResultId(), original, original.startOffset(),
                        Math.min(original.endOffset(), prefixEnd), "alias"));
                remaining.put(original.segmentId(), original.endOffset() > prefixEnd
                        ? List.of(slice(input.parseResultId(), original, prefixEnd, original.endOffset(), "tail")) : List.of());
            }
            // Never create an oversized source request or discard an alias whose proof cannot fit.
            if (row.segments().size() + proof.size() > 32) continue;
            var allProof = new ArrayList<>(row.segments());
            allProof.addAll(proof);
            proven.set(rowIndex, new ProvenRow(row.rowKey(), List.copyOf(allProof), row.candidates(), row.cellLocators()));
            remainders.putAll(remaining);
        }
        return remainders;
    }

    private static boolean sameNativeValues(Matcher alias, ProvenRow row) {
        // Candidate order is the native FIELDS/TYPES order above; identity matching never uses the name alone.
        return row.candidates().get(0).valueText().equals(alias.group("name"))
                && row.candidates().get(1).valueDecimal().compareTo(new BigDecimal(alias.group("quantity").replace(",", ""))) == 0
                && row.candidates().get(2).valueDecimal().compareTo(new BigDecimal(alias.group("price").replace(",", ""))) == 0
                && row.candidates().get(3).valueDecimal().compareTo(new BigDecimal(alias.group("amount").replace(",", ""))) == 0;
    }

    private static Segment slice(UUID parseResultId, Segment original, int start, int end, String purpose) {
        if (start == original.startOffset() && end == original.endOffset()) return original;
        String text = original.text().substring(original.text().offsetByCodePoints(0, start - original.startOffset()),
                original.text().offsetByCodePoints(0, end - original.startOffset()));
        String id = original.segmentId() + ":" + purpose + ":" + start + "-" + end;
        String locator = "parse-result:%s:canonical-codepoint:%d-%d".formatted(parseResultId, start, end);
        try {
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            return new Segment(id, locator, sha, start, end, text, original.rowIdentity(), null);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ProvenRow row(ContractFactCandidateValidator.CurrentInput input, ParsedProjectDocument.Row row,
            List<String> columns, LocatedRow located, List<Segment> segments) {
        if (located == null || row.cells().size() != columns.size()) return null;
        List<String> texts = row.cells().stream().map(cell -> cell.text().replace("\r\n", "\u2028")
                .replace('\r', '\u2028').replace('\n', '\u2028')).toList();
        if (!String.join("\t", texts).equals(located.text())) return null;
        int rowEnd = located.start() + length(located.text());
        List<Segment> fragments = segments.stream().filter(segment -> segment.startOffset() >= located.start()
                && segment.startOffset() < rowEnd).toList();
        if (fragments.isEmpty() || fragments.size() > 32) return null;
        String identity = fragments.get(0).rowIdentity();
        if (identity == null || fragments.stream().anyMatch(fragment -> !identity.equals(fragment.rowIdentity()))) return null;
        List<Candidate> candidates = new ArrayList<>();
        Map<String, String> cellLocators = new LinkedHashMap<>();
        for (int field = 0; field < FIELDS.size(); field++) {
            int cellIndex = columns.indexOf(FIELDS.get(field));
            String original = texts.get(cellIndex);
            String value = original.strip();
            if (value.isEmpty() || value.indexOf('\t') >= 0 || value.indexOf('\u2028') >= 0) return null;
            int start = located.start();
            for (int before = 0; before < cellIndex; before++) start += length(texts.get(before)) + 1;
            start += length(original) - length(original.stripLeading());
            int end = start + length(value);
            int absoluteStart = start;
            var containing = fragments.stream().filter(segment -> segment.startOffset() <= absoluteStart
                    && segment.endOffset() >= end).findFirst();
            if (containing.isEmpty()) return null;
            Segment segment = containing.orElseThrow();
            BigDecimal number = null;
            boolean textField = field == 0 || field == 4;
            if (textField) {
                if (value.getBytes(StandardCharsets.UTF_8).length > (field == 4 ? 200 : 240)) return null;
            } else {
                if (!value.matches(DECIMAL_CELL)) return null;
                number = new BigDecimal(value.replace(",", ""));
                if (number.precision() > 18 || Math.max(0, number.stripTrailingZeros().scale()) > (field == 3 ? 2 : 4)) return null;
            }
            String cellLocator = row.cells().get(cellIndex).sourceLocator();
            String key = "cfs-v1-" + new ExtractionKeyFactoryV2().create(input.fileId(), input.parseResultId(),
                    EXTRACTION_VERSION, TYPES.get(field).name(), ContractFactSegmenter.lineGroup(identity),
                    cellLocator + ":canonical-codepoint:" + start + "-" + end);
            candidates.add(new Candidate(key, TYPES.get(field), ContractFactSegmenter.lineGroup(identity),
                    textField ? value : null, number, segment.segmentId(), segment.sourceLocator(), value,
                    start - segment.startOffset(), end - segment.startOffset(), ContextCode.CONTRACT_LINE_ITEM, BigDecimal.ONE));
            cellLocators.put(key, cellLocator);
        }
        return new ProvenRow(texts.get(columns.indexOf("rowId")).strip(), fragments, candidates, cellLocators);
    }

    /** Units are explicit: only bare numeric columns or yuan are accepted, never arbitrary parenthetical text. */
    private static List<String> columns(ParsedProjectDocument.Row row) {
        if (row.cells().size() != 5) return null;
        List<String> result = new ArrayList<>();
        for (var cell : row.cells()) {
            String label = Normalizer.normalize(cell.text(), Normalizer.Form.NFKC).replaceAll("\\s+", "");
            String column = switch (label) {
                case "序号", "编号", "行号", "行号/稳定编码", "明细ID", "ID" -> "rowId";
                case "名称", "明细名称", "项目名称", "货物名称", "服务名称", "软件或服务项目", "品名" -> "name";
                case "数量" -> "quantity";
                case "单价", "单价(元)" -> "unitPrice";
                case "金额", "行金额", "小计", "合价", "总价", "金额(元)", "行金额(元)", "小计(元)", "合价(元)", "总价(元)" -> "declaredAmount";
                default -> null;
            };
            if (column == null || result.contains(column)) return null;
            result.add(column);
        }
        return result.containsAll(FIELDS) ? List.copyOf(result) : null;
    }

    private static int length(String text) { return text.codePointCount(0, text.length()); }
    private record LocatedRow(int start, String text) { }
    private record ProvenRow(String rowKey, List<Segment> segments, List<Candidate> candidates, Map<String, String> cellLocators) { }

    public record SourceBatch(Request request, Result result, Map<String, String> cellLocators) {
        public SourceBatch { cellLocators = Map.copyOf(cellLocators); }
    }

    record Plan(List<SourceBatch> batches, List<Segment> residual) {
        Plan { batches = List.copyOf(batches); residual = List.copyOf(residual); }
        static Plan fallback(List<Segment> segments) { return new Plan(List.of(), segments); }
    }
}
