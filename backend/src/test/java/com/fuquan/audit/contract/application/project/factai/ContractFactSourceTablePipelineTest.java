package com.fuquan.audit.contract.application.project.factai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.*;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxContainerGuard;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.infra.memory.project.InMemoryContractFactExtractionRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

/** Real native fixture -> production planner/validator -> repository shapes. No HTTP or database. */
@EnabledIfSystemProperty(named = "audit.v2.fixtureRoot", matches = ".+")
class ContractFactSourceTablePipelineTest {
    private static final UUID PROJECT = UUID.fromString("57000000-0000-0000-0000-000000000001");
    private static final UUID FILE = UUID.fromString("57000000-0000-0000-0000-000000000002");
    private static final UUID PARSE = UUID.fromString("57000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
    private static final Provenance MODEL = new Provenance("test-provider", "offline-port", "none",
            "contract-fact-prompt-v2.3", "contract-fact-candidates-v2");
    private static final CurrentUser ACTOR = new CurrentUser("source-test", "test", "Test", Set.of("AUDITOR"));

    // Break caught: native proven rows still require model output, a row is lost, or one row borrows another's cells.
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void sourceRowsPublishEveryIndependentTruthOperandWithoutModelTableOutput(int scenario) throws Exception {
        Path root = Path.of(System.getProperty("audit.v2.fixtureRoot"));
        String folder = scenario == 1 ? "01_基准一致_长合同" : "02_超长合同_48行明细";
        byte[] bytes = Files.readAllBytes(root.resolve("00_解压后按场景多选上传").resolve(folder)
                .resolve("04_智慧维修系统采购及服务合同.docx"));
        JsonNode expected = new ObjectMapper().readTree(root.resolve("04_预期结果与验收资料")
                .resolve("业务真值_逐行金额与冲突.json").toFile()).get("%02d".formatted(scenario));
        try (Harness harness = new Harness(bytes)) {
            var result = harness.service.extract(PROJECT, FILE, "source-fixture-" + scenario, ACTOR,
                    new ProjectRequestMetadata("request", "trace", "127.0.0.1"));
            List<ExtractionResult> published = harness.extractions.findByProjectId(PROJECT);
            int rowCount = scenario == 1 ? 12 : 48;
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(published.size()).as("four operands plus original source row key per native row")
                    .isEqualTo(rowCount * 5);
            assertThat(published).allSatisfy(fact -> assertThat(fact.extractionVersion())
                    .isEqualTo("contract-source-table-v1"));
            var audit = harness.attempts.findCandidates(result.attemptId());
            assertThat(audit.size()).isEqualTo(rowCount * 5);
            assertThat(audit).allSatisfy(candidate ->
                    assertThat(candidate.candidateKey()).startsWith("cfs-v1-"));
            var sourceFile = harness.context.getBean(ProjectFileRepository.class).findByProjectIdAndId(PROJECT, FILE).orElseThrow();
            var assembled = new com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler(Clock.fixed(NOW, ZoneOffset.UTC))
                    .assemble(PROJECT, sourceFile, published);
            assertThat(assembled.lineItems().size()).isEqualTo(rowCount);
            for (JsonNode row : expected.get("facts")) {
                String rowId = row.get("row_id").asText();
                var sourceRows = harness.document.tables().stream().flatMap(table -> table.rows().stream())
                        .filter(source -> !source.cells().isEmpty() && source.cells().get(0).text().equals(rowId)).toList();
                assertThat(sourceRows).as("one original physical row for each truth row ID").hasSize(1);
                String rowLocator = sourceRows.get(0).sourceLocator();
                int offset = 0;
                String group = null;
                for (var source : harness.document.segments()) {
                    int length = source.text().codePointCount(0, source.text().length());
                    if (source.sourceLocator().equals(rowLocator)) {
                        int sourceStart = offset;
                        var segment = harness.segments.stream().filter(value -> value.startOffset() == sourceStart).findFirst();
                        assertThat(segment).isPresent();
                        group = ContractFactSegmenter.lineGroup(segment.orElseThrow().rowIdentity());
                        break;
                    }
                    offset += length + 1;
                }
                String finalGroup = group;
                List<ExtractionResult> facts = published.stream().filter(fact -> finalGroup.equals(fact.groupKey())).toList();
                assertThat(facts.size()).isEqualTo(5);
                assertThat(text(facts, "LINE_ITEM_KEY").equals(rowId)).as("raw source row ID is never replaced by a row hash").isTrue();
                var assembledRows = assembled.lineItems().stream().filter(line -> rowId.equals(line.itemKey())).toList();
                assertThat(assembledRows.size()).as("assembled itemKey matches each independent truth row ID").isEqualTo(1);
                assertThat(assembledRows.get(0).itemName().equals(row.get("name").asText())).isTrue();
                var keyFact = facts.stream().filter(fact -> fact.factType().equals("LINE_ITEM_KEY")).findFirst().orElseThrow();
                assertThat(keyFact.sourceText().equals(rowId)).isTrue();
                assertThat(text(facts, "LINE_ITEM_NAME").equals(row.get("name").asText()))
                        .as("name belongs to the independent original row").isTrue();
                assertThat(amount(facts, "LINE_ITEM_QUANTITY").compareTo(row.get("quantity").decimalValue())).isZero();
                assertThat(amount(facts, "LINE_ITEM_UNIT_PRICE").compareTo(row.get("unit_price").decimalValue())).isZero();
                assertThat(amount(facts, "LINE_ITEM_DECLARED_AMOUNT").compareTo(row.get("line_total").decimalValue())).isZero();
            }
            Set<String> sourceSegments = audit.stream().map(ContractFactExtractionCandidate::segmentId)
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(harness.model.requests.stream().filter(request -> request.scope() == Scope.LINE_ITEMS)
                    .flatMap(request -> request.segments().stream()).map(Segment::segmentId))
                    .doesNotContainAnyElementsOf(sourceSegments);
            assertThat(harness.attempts.findModelCalls(result.attemptId()))
                    .hasSize(harness.model.requests.size());
            var replay = harness.context.getBean(DefaultContractFactExtractionTransactionCoordinator.class)
                    .prepare(PROJECT, FILE, "source-fixture-" + scenario, ACTOR,
                            new ProjectRequestMetadata("request", "trace", "127.0.0.1"), NOW);
            assertThat(replay.replayed()).isTrue();
            for (var batch : replay.sourceBatches()) for (var candidate : batch.result().candidates()) {
                if (candidate.factType().name().equals("LINE_ITEM_KEY")) {
                    String cellLocator = batch.cellLocators().get(candidate.candidateKey());
                    var originalCell = harness.document.tables().stream().flatMap(table -> table.rows().stream())
                            .flatMap(source -> source.cells().stream()).filter(cell -> cell.sourceLocator().equals(cellLocator)).findFirst();
                    assertThat(originalCell.isPresent()).isTrue();
                    assertThat(originalCell.orElseThrow().text().equals(candidate.valueText())).isTrue();
                }
                assertThat(batch.result().modelCalls()).isEmpty();
            }
            System.out.println("V2_SOURCE_PLAN scenario=" + scenario + " sourceRows=" + rowCount
                    + " coreBatches=" + harness.model.requests.stream().filter(request -> request.scope() == Scope.CORE).count()
                    + " residualLineBatches=" + harness.model.requests.stream().filter(request -> request.scope() == Scope.LINE_ITEMS).count());
            var sourceRules = new com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor(Clock.fixed(NOW, ZoneOffset.UTC))
                    .extractPotential(sourceFile, harness.document, PARSE);
            Set<String> coreTypes = Set.of("CONTRACT_NO", "CONTRACT_NAME", "PARTY_A", "PARTY_B", "CREDIT_CODE", "AMOUNT_NUMERIC", "AMOUNT_UPPERCASE");
            System.out.println("V2_EXISTING_CORE_RULES scenario=" + scenario + " observedTypes=" + sourceRules.stream()
                    .map(ExtractionResult::factType).filter(coreTypes::contains).distinct().sorted().toList());
            Map<String, long[]> categories = new TreeMap<>();
            for (Segment segment : harness.model.requests.stream().filter(request -> request.scope() == Scope.LINE_ITEMS)
                    .flatMap(request -> request.segments().stream()).toList()) {
                int units = ContractFactBatchPlanner.estimatedOutputUnits(segment, Scope.LINE_ITEMS);
                String kind = segment.text().indexOf('\t') >= 0 ? "UNMAPPED_TAB" : units > 16 ? "LABEL_MATCH" : "NO_LABEL";
                int sourceOffset = 0;
                for (var nativeSource : harness.document.segments()) {
                    int nativeLength = nativeSource.text().codePointCount(0, nativeSource.text().length());
                    if (segment.startOffset() >= sourceOffset && segment.startOffset() <= sourceOffset + nativeLength) {
                        kind = (nativeSource.sourceLocator().startsWith("docx/table:") ? "TABLE_" : "PARAGRAPH_") + kind;
                        break;
                    }
                    sourceOffset += nativeLength + 1;
                }
                long[] counts = categories.computeIfAbsent(kind, ignored -> new long[3]);
                counts[0]++; counts[1] += units; counts[2] = Math.max(counts[2], units);
            }
            categories.forEach((kind, counts) -> System.out.println("V2_LINE_RESIDUAL_CATEGORY scenario=" + scenario
                    + " category=" + kind + " segments=" + counts[0] + " estimatedUnits=" + counts[1] + " maxUnits=" + counts[2]));
            // The whole source is retained for semantic review and original evidence drill-down.
            assertThat(harness.segments.stream().map(Segment::text).collect(java.util.stream.Collectors.joining())
                    .equals(harness.document.fullText())).isTrue();
        }
    }

    private static String text(List<ExtractionResult> facts, String type) {
        return facts.stream().filter(fact -> fact.factType().equals(type)).findFirst().orElseThrow().valueText();
    }

    private static BigDecimal amount(List<ExtractionResult> facts, String type) {
        return facts.stream().filter(fact -> fact.factType().equals(type)).findFirst().orElseThrow().valueAmount();
    }

    static final class Harness implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final InMemoryContractFactExtractionRepository attempts = new InMemoryContractFactExtractionRepository();
        final ExtractionResultRepository extractions;
        final EmptyModel model = new EmptyModel();
        final ParsedProjectDocument document;
        final List<Segment> segments;
        final ContractFactExtractionService service;

        Harness(byte[] bytes) throws Exception {
            this(bytes, bytes);
        }

        Harness(byte[] bytes, byte[] storedBytes) throws Exception {
            var repositories = new InMemoryProjectRepositories();
            extractions = repositories.extractionResultRepository();
            var fileRepository = repositories.projectFileRepository();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var parserProperties = ProjectIntakeProperties.defaults();
            var parser = new ProjectDocxTextParser(new ProjectDocxContainerGuard(
                    new SelectedProjectArchiveReader(parserProperties), parserProperties));
            var file = new ProjectFile(FILE, PROJECT, null, null, "contract.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    ProjectFileCategory.CONTRACT_BODY, ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
                    sha, sha, bytes.length, null, null, NOW, NOW,
                    new ProjectFileParseProvenance(ProjectFileParseMethod.DOCX_TEXT, "APACHE_POI_4.1.2", null, null, 0L));
            document = parser.parse(file, new ByteArrayInputStream(bytes));
            fileRepository.insert(file.complete(ProjectFileParseStatus.PARSED, document.fullText(), null,
                    file.parseProvenance(), NOW));
            String canonicalSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(document.fullText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var current = new ContractFactCandidateValidator.CurrentInput(PROJECT, FILE, sha, PARSE, 1,
                    canonicalSha, document.fullText(), DocumentRole.CONTRACT_BODY, true);
            ContractFactExtractionAuthorityResolver authority = (project, sourceFile) ->
                    new ContractFactExtractionAuthority(current, UUID.fromString("57000000-0000-0000-0000-000000000004"),
                            UUID.fromString("57000000-0000-0000-0000-000000000005"), true);
            segments = new ContractFactSegmenter().segment(PARSE, document.fullText());
            context.registerBean(ContractFactExtractionRepository.class, () -> attempts);
            context.registerBean(ExtractionResultRepository.class, () -> extractions);
            context.registerBean(ProjectFileRepository.class, () -> fileRepository);
            context.registerBean(FileStoragePort.class, () -> new ReadOnlyStorage(storedBytes));
            context.registerBean(ProjectDocxTextParser.class, () -> parser);
            context.registerBean(ContractFactCandidatePort.class, () -> model);
            context.registerBean(ContractFactExtractionAuthorityResolver.class, () -> authority);
            // Discovers the production source planner once present, without replacing orchestration with a test adapter.
            var scanner = new ClassPathBeanDefinitionScanner(context, false);
            scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*\\.ContractFactSourceTablePlanner")));
            scanner.scan("com.fuquan.audit.contract.application.project.factai");
            context.registerBean(DefaultContractFactExtractionTransactionCoordinator.class);
            context.refresh();
            service = new ContractFactExtractionService(model,
                    context.getBean(DefaultContractFactExtractionTransactionCoordinator.class), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Override public void close() { context.close(); }
    }

    static final class EmptyModel implements ContractFactCandidatePort {
        final List<Request> requests = new ArrayList<>();
        BatchStatus finalLineStatus = BatchStatus.EMPTY;
        @Override public Provenance configuredProvenance() { return MODEL; }
        @Override public Result extract(Request request) {
            requests.add(request);
            BatchStatus status = request.scope() == Scope.LINE_ITEMS && request.batchOrdinal() == request.batchCount()
                    ? finalLineStatus : BatchStatus.EMPTY;
            return new Result(List.of(), MODEL, 0, List.of(), status, request.scope(),
                    List.of(new ModelCall(1, CallPhase.TRANSPORT, CallOutcome.SUCCEEDED, null, 0, "STOP", 0, 0, 0)));
        }
    }

    static final class ReadOnlyStorage implements FileStoragePort {
        private final byte[] bytes;
        ReadOnlyStorage(byte[] bytes) { this.bytes = bytes.clone(); }
        @Override public StoredFileContent open(StorageKey key) {
            return new StoredFileContent(new ByteArrayInputStream(bytes), bytes.length);
        }
        @Override public StagedFile stage(InputStream input, long limit) { throw new UnsupportedOperationException(); }
        @Override public void promote(StagedFile file, StorageKey key) { throw new UnsupportedOperationException(); }
        @Override public void discard(StagedFile file) { throw new UnsupportedOperationException(); }
        @Override public StoredFileInspection inspect(StorageKey key) { throw new UnsupportedOperationException(); }
    }
}
