package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.parse.*;
import com.fuquan.audit.contract.domain.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.domain.rule.*;
import com.fuquan.audit.contract.infra.memory.InMemoryContractRuleConfigRepository;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.domain.*;
import com.fuquan.audit.review.infra.memory.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * CONFIRMED_INPUT_SEAM_LOCAL_ONLY: real parser/service/rules and in-memory facts.
 * A test-only fixture adapter selects confirmed operands from native source cells/text, not truth JSON.
 * This does not claim production candidate extraction, DM8, model, HTTP or browser verification.
 */
@DisplayName("CONFIRMED_INPUT_SEAM_LOCAL_ONLY: V2 native source to formal F02 service and rules")
class V2FormalF02RegressionTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static Path root;
    private static JsonNode truth;

    @BeforeAll static void optionalReadonlyV2Fixture() throws Exception {
        String configured = System.getProperty("audit.v2.fixtureRoot");
        if (configured == null || configured.isBlank()) return;
        root = Path.of(configured).toAbsolutePath().normalize();
        assertThat(Files.isDirectory(root)).as("V2 fixture directory exists").isTrue();
        Path manifest;
        try (var files = Files.walk(root.resolve("04_预期结果与验收资料"), 1)) {
            manifest = files.filter(path -> path.getFileName().toString().startsWith("业务真值_"))
                    .filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
        }
        truth = new ObjectMapper().readTree(Files.readAllBytes(manifest));
    }

    @BeforeEach void requireOptionalFixture() {
        assumeTrue(root != null, "external V2 fixture not configured");
    }

    // Catches dropped rows or mismatched source amounts; actual F02 persistence must produce all rule facts.
    @ParameterizedTest @ValueSource(strings = {"01", "02"})
    void sourceGroundedTwelveAndFortyEightLineContractsPassActualFiveRules(String scenario) throws Exception {
        Source input = source(scenario);
        assertSourceMatchesIndependentTruth(input, scenario);
        Execution execution = execute(input.contract(), "source-" + scenario);
        assertThat(execution.result().findings().stream().allMatch(value -> value.checkStatus() == CheckStatus.PASS))
                .as("all five rules evaluate source-grounded basis as PASS").isTrue();
        assertThat(execution.result().findings().size() == truth.get(scenario).get("line_count").asInt() + 4)
                .as("one persisted arithmetic result per source row and four aggregate rules").isTrue();
        assertPersisted(execution);
    }

    // Catches accidentally replacing the authoritative award amount with the contract total.
    @Test void scenario03PersistsContractAwardConflictWithExactTwoThousandDifference() throws Exception {
        Source input = source("03");
        assertSourceMatchesIndependentTruth(input, "03");
        Execution execution = execute(input.contract(), "source-03");
        AuditCheckResult award = only(execution.result(), F02RuleCodes.AWARD_AMOUNT_MATCH);
        assertThat(award.checkStatus() == CheckStatus.FAIL).as("formal award rule rejects cross-document conflict").isTrue();
        assertThat(new BigDecimal(award.actualValue()).compareTo(new BigDecimal("69000")) == 0)
                .as("actual result preserves source contract amount").isTrue();
        assertThat(new BigDecimal(award.expectedValue()).compareTo(new BigDecimal("67000")) == 0)
                .as("expected result preserves source award amount").isTrue();
        assertThat(new BigDecimal(award.actualValue()).subtract(new BigDecimal(award.expectedValue()))
                .compareTo(new BigDecimal("2000")) == 0).as("formal result difference is exact").isTrue();
        assertThat(execution.result().findings().stream().filter(value -> !value.ruleCode().equals(F02RuleCodes.AWARD_AMOUNT_MATCH))
                .allMatch(value -> value.checkStatus() == CheckStatus.PASS))
                .as("internal line totals and other supplied bases remain consistent").isTrue();
        assertPersisted(execution);
    }

    // Catches deduplicating stable rows merely because their display names match.
    @Test void scenario02SameNameRowsRemainDistinctFormalArithmeticResults() throws Exception {
        Source input = source("02");
        var first = input.contract().lineItems().stream().filter(value -> value.itemKey().equals("QA2-027")).findFirst().orElseThrow();
        var second = input.contract().lineItems().stream().filter(value -> value.itemKey().equals("QA2-028")).findFirst().orElseThrow();
        assertThat(first.itemName().equals(second.itemName())).as("source duplicate names are preserved").isTrue();
        assertThat(!first.id().equals(second.id())).as("source rows have independent domain identities").isTrue();
        Execution execution = execute(input.contract(), "duplicate-names-02");
        var found = execution.result().findings().stream().filter(value -> value.ruleCode().equals(F02RuleCodes.LINE_ITEM_ARITHMETIC))
                .filter(value -> Set.of("QA2-027", "QA2-028").contains(value.itemKey())).toList();
        assertThat(found.size() == 2 && found.stream().allMatch(value -> value.checkStatus() == CheckStatus.PASS)
                && found.stream().map(AuditCheckResult::id).distinct().count() == 2)
                .as("both stable source keys independently persist successful arithmetic findings").isTrue();
        assertPersisted(execution);
    }

    // This is a controlled missing-structured-basis variant, not an invented F02 attachment rule.
    @Test void scenario04MissingAttachmentDoesNotAuthorizeInventedFormalAmountBasis() throws Exception {
        Source input = source("04");
        assertThat(truth.get("04").get("expected_signals").toString().contains("附件"))
                .as("independent package truth identifies the attachment limitation").isTrue();
        assertThat(input.parsed().fullText().contains("附件"))
                .as("source contract contains appendix obligations").isTrue();
        try (var files = Files.list(input.directory())) {
            assertThat(files.filter(path -> path.getFileName().toString().contains("附件")).count() == 1)
                    .as("scenario04 supplies only one appendix file").isTrue();
        }
        Contract c = input.contract();
        Contract insufficient = new Contract(c.id(), c.contractNo(), c.contractName(), c.contractType(), c.partyA(),
                c.partyB(), c.partyBCreditCode(), c.contractAmount(), c.amountInWords(), c.status(), c.versionNo(),
                c.createdAt(), c.updatedAt(), List.of(), null);
        Execution execution = execute(insufficient, "missing-formal-basis-04");
        for (String rule : List.of(F02RuleCodes.LINE_ITEM_ARITHMETIC, F02RuleCodes.LINE_SUM_MATCH,
                F02RuleCodes.AWARD_AMOUNT_MATCH, F02RuleCodes.AWARD_SUPPLIER_MATCH)) {
            assertThat(only(execution.result(), rule).checkStatus() == CheckStatus.NOT_EVALUATED)
                    .as("missing formal basis remains NOT_EVALUATED").isTrue();
        }
        assertPersisted(execution);
    }

    private Source source(String scenario) throws Exception {
        Path directory;
        try (var directories = Files.list(root.resolve("00_解压后按场景多选上传"))) {
            directory = directories.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(scenario + "_")).findFirst().orElseThrow();
        }
        Path contractPath = find(directory, "04_", ".docx");
        Path awardPath = find(directory, "03_", ".pdf");
        UUID projectId = UUID.randomUUID();
        ProjectFile contractFile = file(contractPath, ProjectFileCategory.CONTRACT_BODY, projectId);
        ProjectFile awardFile = file(awardPath, ProjectFileCategory.AWARD_NOTICE, projectId);
        var limits = ProjectIntakeProperties.defaults();
        var parser = new ProjectDocxTextParser(new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(limits), limits));
        ParsedProjectDocument parsed;
        try (InputStream stream = Files.newInputStream(contractPath)) { parsed = parser.parse(contractFile, stream); }
        ParsedProjectDocument awardParsed;
        try (InputStream stream = Files.newInputStream(awardPath)) { awardParsed = new ProjectPdfTextParser().parse(awardFile, stream); }
        String expectedProject = truth.get(scenario).get("project_id").asText();
        assertThat(parsed.fullText().contains(expectedProject) && awardParsed.fullText().contains(expectedProject))
                .as("contract and selected notice both identify the same package scenario project").isTrue();
        // Explicit confirmation seam: fixture-specific selectors, never a production extraction fallback.
        String contractText = parsed.fullText();
        BigDecimal contractAmount = sourceAmount(contractText,
                "(?:合同金额|总金额|合同总价款)[^\\r\\n\\d]{0,40}([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元", "contract total");
        String words = sourceText(contractText,
                "大写[^零壹贰叁肆伍陆柒捌玖拾佰仟万亿圆元\\r\\n]{0,12}"
                        + "([零壹贰叁肆伍陆柒捌玖拾佰仟万亿圆]+元(?:[零壹贰叁肆伍陆柒捌玖拾角分]+|整|正)?)", "uppercase total");
        String partyA = sourceText(contractText, "(?m)^甲方[^\\r\\n：:]{0,30}[：:]\\s*([^\\r\\n；;]+)", "buyer");
        String partyB = sourceText(contractText, "(?m)^乙方[^\\r\\n：:]{0,30}[：:]\\s*([^\\r\\n；;]+)", "contract supplier");
        BigDecimal awardAmount = sourceAmount(awardParsed.fullText(),
                "(?m)^\\s*(?:成交(?:总)?金额|中标金额|成交价)[^\\r\\n\\d]{0,40}([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", "award total");
        String supplier = sourceText(awardParsed.fullText(),
                "(?m)^(?:成交供应商(?:名称)?|中标供应商|成交单位|供应商(?:名称)?)\\s*"
                        + "(?:[（(][^）)\\r\\n]{0,30}[）)])?\\s*[:：]?\\s*([^\\r\\n；;]+)", "award supplier");
        UUID contractId = UUID.randomUUID();
        List<ContractLineItem> lines = new ArrayList<>();
        for (var table : parsed.tables()) for (var row : table.rows()) {
            if (row.cells().size() != 5 || !stableRowKey(row.cells().get(0).text())) continue;
            var cells = row.cells();
            lines.add(new ContractLineItem(UUID.randomUUID(), contractId, cells.get(0).text().strip(),
                    cells.get(1).text().strip(), sourceDecimal(cells.get(2).text()), sourceDecimal(cells.get(3).text()),
                    Money.of(sourceDecimal(cells.get(4).text())), lines.size()));
        }
        assertThat(!lines.isEmpty()).as("fixture adapter reads actual native price table rows").isTrue();
        var award = new AwardNoticeReference(UUID.randomUUID(), contractId, "source-notice", "source-project", supplier,
                null, Money.of(awardAmount), LocalDate.of(2026, 10, 15), "LOCAL_TESTED_SOURCE");
        // Local resource identity is generated; it is not presented as a source contract-number extraction.
        var contract = new Contract(contractId, "LOCAL_CONFIRMED_INPUT_" + scenario, parsed.segments().get(0).text(), "PROCUREMENT",
                partyA, partyB, null, Money.of(contractAmount), words, "READY", 1, NOW, NOW, lines, award);
        return new Source(contract, parsed, directory);
    }

    private void assertSourceMatchesIndependentTruth(Source source, String scenario) {
        JsonNode expected = truth.get(scenario);
        Contract contract = source.contract();
        assertThat(contract.contractAmount().amount().compareTo(expected.get("contract_amount").decimalValue()) == 0)
                .as("contract amount matches independent truth").isTrue();
        assertThat(contract.awardNotice().winningAmount().amount().compareTo(expected.get("award_amount").decimalValue()) == 0)
                .as("notice amount matches independent truth").isTrue();
        assertThat(source.parsed().tables().stream().flatMap(table -> table.rows().stream())
                .filter(row -> row.cells().stream().anyMatch(cell -> stableRowKey(cell.text()))).count()
                == expected.get("line_count").asInt()).as("native parsed table row coverage matches truth").isTrue();
        for (JsonNode fact : expected.get("facts")) {
            String key = fact.get("row_id").asText();
            var matches = contract.lineItems().stream().filter(line -> line.itemKey().equals(key)).toList();
            assertThat(matches.size() == 1).as("each truth row maps to exactly one source row").isTrue();
            ContractLineItem line = matches.get(0);
            assertThat(line.itemName().equals(fact.get("name").asText())
                    && line.quantity().compareTo(fact.get("quantity").decimalValue()) == 0
                    && line.unitPrice().compareTo(fact.get("unit_price").decimalValue()) == 0
                    && line.declaredAmount().amount().compareTo(fact.get("line_total").decimalValue()) == 0)
                    .as("source row name and all numeric operands match independent truth").isTrue();
        }
    }

    private Execution execute(Contract contract, String key) {
        var tasks = new InMemoryAuditTaskRepository();
        var checks = new InMemoryAuditCheckResultRepository();
        var logger = new FlowDebugLogger(new ObservabilityProperties());
        var transactions = new ContractReviewTransactionCoordinator(checks, tasks, new InMemoryIdempotencyAdapter(), event -> { }, logger);
        var service = new ContractReviewService(id -> id.equals(contract.id()) ? Optional.of(contract) : Optional.empty(),
                new InMemoryContractRuleConfigRepository(), transactions,
                () -> Optional.of(new CurrentUser("v2-test", "auditor", "Local Test", Set.of("AUDITOR"))), CLOCK, logger,
                List.of(new AmountWordsMatchRule(new ChineseRmbUppercaseParser()), new LineItemArithmeticRule(),
                        new LineSumMatchRule(), new AwardAmountMatchRule(), new AwardSupplierMatchRule()));
        return new Execution(service.review(new ContractReviewCommand(contract.id(), key, "local-v2-test", "local-v2-test", null)), tasks, checks);
    }

    private void assertPersisted(Execution execution) {
        var result = execution.result();
        assertThat(execution.tasks().findById(result.task().id()).orElseThrow().status() == AuditTaskStatus.DONE)
                .as("actual application service stores completed AuditTask in local fake").isTrue();
        var stored = execution.checks().findByTaskId(result.task().id());
        assertThat(stored.size() == result.findings().size() && stored.stream().map(AuditCheckResult::id).collect(java.util.stream.Collectors.toSet())
                .equals(result.findings().stream().map(AuditCheckResult::id).collect(java.util.stream.Collectors.toSet())))
                .as("returned findings are the actual persisted task results").isTrue();
    }

    private static AuditCheckResult only(ContractReviewResult result, String rule) {
        var values = result.findings().stream().filter(value -> value.ruleCode().equals(rule)).toList();
        assertThat(values.size() == 1).as("aggregate rule persists exactly one finding").isTrue();
        return values.get(0);
    }
    private static boolean stableRowKey(String text) {
        return text.strip().matches("(?:QA[0-9]|MX)-[0-9]{3}");
    }
    private static String sourceText(String source, String regex, String field) {
        var matcher = Pattern.compile(regex).matcher(source);
        Set<String> matches = new LinkedHashSet<>();
        while (matcher.find()) matches.add(matcher.group(1).strip());
        assertThat(matches.size() == 1).as("unique confirmed source " + field).isTrue();
        return matches.iterator().next();
    }
    private static BigDecimal sourceAmount(String source, String regex, String field) {
        var matcher = Pattern.compile(regex).matcher(source);
        Set<BigDecimal> matches = new LinkedHashSet<>();
        while (matcher.find()) matches.add(sourceDecimal(matcher.group(1)).stripTrailingZeros());
        assertThat(matches.size() == 1).as("unique confirmed source " + field).isTrue();
        return matches.iterator().next();
    }
    private static BigDecimal sourceDecimal(String source) {
        String number = source.strip().replace(",", "");
        assertThat(number.matches("[0-9]+(?:\\.[0-9]+)?")).as("source numeric cell has exact decimal syntax").isTrue();
        return new BigDecimal(number);
    }
    private static Path find(Path directory, String prefix, String suffix) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.toString().endsWith(suffix)).findFirst().orElseThrow();
        }
    }
    private static ProjectFile file(Path path, ProjectFileCategory category, UUID projectId) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return new ProjectFile(UUID.randomUUID(), projectId, null, null, path.getFileName().toString(),
                "application/octet-stream", category, ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
                hash, hash, Files.size(path), null, null, NOW, NOW);
    }
    private record Source(Contract contract, ParsedProjectDocument parsed, Path directory) { }
    private record Execution(ContractReviewResult result, InMemoryAuditTaskRepository tasks, InMemoryAuditCheckResultRepository checks) { }
}
