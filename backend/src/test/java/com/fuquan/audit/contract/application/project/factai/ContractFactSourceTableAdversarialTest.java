package com.fuquan.audit.contract.application.project.factai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.*;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionTransactionCoordinator.Prepared;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContractFactSourceTableAdversarialTest {
    private static final UUID PROJECT = UUID.fromString("57000000-0000-0000-0000-000000000001");
    private static final UUID FILE = UUID.fromString("57000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
    private static final CurrentUser ACTOR = new CurrentUser("source-test", "test", "Test", Set.of("AUDITOR"));
    private static final ProjectRequestMetadata META = new ProjectRequestMetadata("request", "trace", "127.0.0.1");
    private static final String[] HEADER = {"序号", "名称", "数量", "单价(元)", "金额(元)"};

    @Test void permutedColumnsGroundValuesInTheirOwnOriginalCells() throws Exception {
        try (var harness = harness(docx(new String[]{"金额(元)", "名称", "单价(元)", "序号", "数量"},
                List.<String[]>of(new String[]{"91.00", "Distinct item", "13.00", "A", "7"})))) {
            var result = harness.service.extract(PROJECT, FILE, "permuted", ACTOR, META);
            var facts = harness.extractions.findByProjectId(PROJECT);
            assertThat(result.acceptedCount()).isEqualTo(5);
            assertThat(value(facts, "LINE_ITEM_QUANTITY").compareTo(new BigDecimal("7"))).isZero();
            assertThat(value(facts, "LINE_ITEM_UNIT_PRICE").compareTo(new BigDecimal("13"))).isZero();
            assertThat(value(facts, "LINE_ITEM_DECLARED_AMOUNT").compareTo(new BigDecimal("91"))).isZero();
            for (var candidate : harness.attempts.findCandidates(result.attemptId())) {
                Segment source = harness.segments.stream().filter(segment -> segment.segmentId().equals(candidate.segmentId()))
                        .findFirst().orElseThrow();
                String excerpt = source.text().substring(source.text().offsetByCodePoints(0, candidate.excerptStart()),
                        source.text().offsetByCodePoints(0, candidate.excerptEnd()));
                assertThat(excerpt.equals(candidate.excerpt())).isTrue();
            }
        }
    }

    @Test void duplicateNamesRetainTwoPhysicalRowsAndDifferentOperands() throws Exception {
        try (var harness = harness(docx(HEADER, List.of(new String[]{"A", "Same name", "1", "10", "10"},
                new String[]{"B", "Same name", "2", "30", "60"})))) {
            var result = harness.service.extract(PROJECT, FILE, "same-name", ACTOR, META);
            var facts = harness.extractions.findByProjectId(PROJECT);
            assertThat(result.acceptedCount()).isEqualTo(10);
            assertThat(facts.stream().map(ExtractionResult::groupKey).distinct()).hasSize(2);
            assertThat(facts.stream().filter(fact -> fact.factType().equals("LINE_ITEM_DECLARED_AMOUNT"))
                    .map(fact -> fact.valueAmount().stripTrailingZeros().toPlainString())).containsExactlyInAnyOrder("10", "60");
        }
    }

    @Test void originalPhysicalIdentitySurvivesAFragmentedRowWhenEveryCellHasExactEvidence() throws Exception {
        try (var harness = harness(docx(HEADER, List.<String[]>of(
                new String[]{"A", "n".repeat(237), "2", "10", "20"})))) {
            var result = harness.service.extract(PROJECT, FILE, "fragmented", ACTOR, META);
            assertThat(result.acceptedCount()).isEqualTo(5);
            var audit = harness.attempts.findCandidates(result.attemptId());
            assertThat(audit.stream().map(ContractFactExtractionCandidate::groupKey).distinct()).hasSize(1);
            String group = audit.get(0).groupKey();
            List<Segment> row = harness.segments.stream().filter(segment -> ContractFactSegmenter.lineGroup(segment.rowIdentity())
                    .equals(group)).toList();
            assertThat(row).hasSize(2);
            assertThat(harness.model.requests.stream().filter(request -> request.scope() == Scope.LINE_ITEMS)
                    .flatMap(request -> request.segments().stream()).map(Segment::segmentId))
                    .doesNotContainAnyElementsOf(row.stream().map(Segment::segmentId).toList());
        }
    }

    @Test void oneCellAcrossTwoEvidenceFragmentsRemainsOnTheCompleteAiFallbackPath() throws Exception {
        try (var harness = harness(docx(HEADER, List.<String[]>of(
                new String[]{"r".repeat(10), "n".repeat(230), "2", "10", "20"})))) {
            assertFallback(harness);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"单价(万元)", "单价(美元)", "数量", "未知单价"})
    void unsupportedOrDuplicateHeaderNeverGrantsNumericColumnAuthority(String priceHeader) throws Exception {
        String[] header = HEADER.clone(); header[3] = priceHeader;
        try (var harness = harness(docx(header, List.<String[]>of(new String[]{"A", "Item", "2", "10", "20"})))) {
            assertFallback(harness);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"10元", "1,00", "-1", "1e2", "0.00001"})
    void anUnprovenCellFallsBackAsAWholeRow(String price) throws Exception {
        try (var harness = harness(docx(HEADER, List.<String[]>of(new String[]{"A", "Item", "2", price, "20"})))) {
            assertFallback(harness);
        }
    }

    @Test void tabSeparatedParagraphsCannotImpersonateNativeTableStructure() throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(String.join("\t", HEADER));
            document.createParagraph().createRun().setText("A\tItem\t2\t10\t20");
            document.write(out); bytes = out.toByteArray();
        }
        try (var harness = harness(bytes)) { assertFallback(harness); }
    }

    @Test void replacedStorageBytesGrantNoSourceAuthorityEvenWhenBothFilesAreValidDocx() throws Exception {
        byte[] original = docx(HEADER, List.<String[]>of(new String[]{"A", "Item", "2", "10", "20"}));
        byte[] replaced = docx(HEADER, List.<String[]>of(new String[]{"A", "Item", "2", "11", "22"}));
        try (var harness = new ContractFactSourceTablePipelineTest.Harness(original, replaced)) { assertFallback(harness); }
    }

    @Test void losingTheLastPlannedSourceFactCannotPublishAnyEarlierFact() throws Exception {
        try (var harness = harness(docx(HEADER, List.of(new String[]{"A", "First", "2", "10", "20"},
                new String[]{"B", "Tail", "3", "20", "60"})))) {
            var coordinator = harness.context.getBean(DefaultContractFactExtractionTransactionCoordinator.class);
            var prepared = coordinator.prepare(PROJECT, FILE, "lost-tail", ACTOR, META, NOW);
            assertThat(prepared.sourceBatches()).hasSize(1);
            var source = prepared.sourceBatches().get(0);
            var truncated = new Result(source.result().candidates().subList(0, source.result().candidates().size() - 1), source.result().provenance(), 0,
                    List.of(), BatchStatus.PARTIAL, Scope.LINE_ITEMS, List.of());
            var changed = new Prepared(prepared.attemptId(), prepared.requests(), false, prepared.currentResult(),
                    List.of(new ContractFactSourceTablePlanner.SourceBatch(source.request(), truncated, source.cellLocators())));
            var provider = prepared.requests().stream().map(harness.model::extract).toList();
            assertThatThrownBy(() -> coordinator.complete(changed, provider, NOW)).isInstanceOf(
                    com.fuquan.audit.common.exception.BusinessException.class);
            assertThat(harness.extractions.findByProjectId(PROJECT)).isEmpty();
            assertThat(harness.attempts.findCandidates(prepared.attemptId())).isEmpty();
        }
    }

    @Test void uncertainResidualTailCannotPublishTheAlreadyProvenSourceRows() throws Exception {
        try (var harness = harness(docx(HEADER, List.<String[]>of(new String[]{"A", "Item", "2", "10", "20"})))) {
            harness.model.finalLineStatus = BatchStatus.UNKNOWN;
            var result = harness.service.extract(PROJECT, FILE, "unknown-tail", ACTOR, META);
            assertThat(result.status()).isEqualTo("OUTCOME_UNKNOWN");
            assertThat(harness.extractions.findByProjectId(PROJECT)).isEmpty();
            assertThat(harness.attempts.findCandidates(result.attemptId())).isEmpty();
            assertThat(harness.attempts.findModelCalls(result.attemptId())).hasSize(harness.model.requests.size());
        }
    }

    @Test void aSourceBatchCannotClaimAnUnknownOutcomeAndStillPublishFacts() throws Exception {
        try (var harness = harness(docx(HEADER, List.<String[]>of(new String[]{"A", "Item", "2", "10", "20"})))) {
            var coordinator = harness.context.getBean(DefaultContractFactExtractionTransactionCoordinator.class);
            var prepared = coordinator.prepare(PROJECT, FILE, "unknown-source", ACTOR, META, NOW);
            var source = prepared.sourceBatches().get(0);
            var unknown = new Result(source.result().candidates(), source.result().provenance(), 0,
                    List.of(), BatchStatus.UNKNOWN, Scope.LINE_ITEMS, List.of());
            var changed = new Prepared(prepared.attemptId(), prepared.requests(), false, prepared.currentResult(),
                    List.of(new ContractFactSourceTablePlanner.SourceBatch(source.request(), unknown, source.cellLocators())));
            var provider = prepared.requests().stream().map(harness.model::extract).toList();
            assertThatThrownBy(() -> coordinator.complete(changed, provider, NOW))
                    .isInstanceOf(ContractFactValidationException.class);
            assertThat(harness.extractions.findByProjectId(PROJECT)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"参考合同", "合同模板", "合同范本", "附件示范文本"})
    void referenceOrTemplateTablesNeverGrantSourceFactAuthority(String heading) throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(heading);
            var table = document.createTable(2, HEADER.length);
            String[] cells = {"A", "Template example", "2", "10", "20"};
            for (int column = 0; column < HEADER.length; column++) {
                table.getRow(0).getCell(column).setText(HEADER[column]);
                table.getRow(1).getCell(column).setText(cells[column]);
            }
            document.write(out); bytes = out.toByteArray();
        }
        try (var harness = harness(bytes)) { assertFallback(harness); }
    }

    @Test void anUnrelatedEarlierTableCannotHideTheLaterProvenFormalTable() throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var unrelated = document.createTable(2, 2);
            unrelated.getRow(0).getCell(0).setText("联系人");
            unrelated.getRow(0).getCell(1).setText("联系单位");
            unrelated.getRow(1).getCell(0).setText("Example contact");
            unrelated.getRow(1).getCell(1).setText("Example organization");
            document.createParagraph().createRun().setText("正式合同");
            var table = document.createTable(2, HEADER.length);
            String[] cells = {"A", "Formal item", "2", "10", "20"};
            for (int column = 0; column < HEADER.length; column++) {
                table.getRow(0).getCell(column).setText(HEADER[column]);
                table.getRow(1).getCell(column).setText(cells[column]);
            }
            document.write(out); bytes = out.toByteArray();
        }
        try (var harness = harness(bytes)) {
            var result = harness.service.extract(PROJECT, FILE, "multiple-tables", ACTOR, META);
            assertThat(result.acceptedCount()).isEqualTo(5);
            assertThat(value(harness.extractions.findByProjectId(PROJECT), "LINE_ITEM_DECLARED_AMOUNT")
                    .compareTo(new BigDecimal("20"))).isZero();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"参考合同,1", "合同模板,1", "合同范本,1", "附件示范文本,1",
            "参考合同,3", "合同模板,3"})
    void nativeSinglePopulatedCellBannerCannotGrantSourceTableAuthority(String heading, int cells) throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var banner = document.createTable(1, cells);
            banner.getRow(0).getCell(cells / 2).setText(heading);
            var table = document.createTable(2, HEADER.length);
            String[] values = {"A", "Item", "2", "10", "20"};
            for (int column = 0; column < HEADER.length; column++) {
                table.getRow(0).getCell(column).setText(HEADER[column]);
                table.getRow(1).getCell(column).setText(values[column]);
            }
            document.write(out); bytes = out.toByteArray();
        }
        try (var harness = harness(bytes)) { assertFallback(harness); }
    }

    @Test void anOrdinaryMultiColumnItemNameIsNotATableSectionBanner() throws Exception {
        try (var harness = harness(docx(HEADER, List.<String[]>of(new String[]{"A", "合同模板", "2", "10", "20"})))) {
            var result = harness.service.extract(PROJECT, FILE, "ordinary-template-item", ACTOR, META);
            assertThat(result.acceptedCount()).isEqualTo(5);
            var item = harness.extractions.findByProjectId(PROJECT).stream()
                    .filter(fact -> fact.factType().equals("LINE_ITEM_NAME")).findFirst().orElseThrow();
            assertThat(item.valueText()).isEqualTo("合同模板");
        }
    }

    private static void assertFallback(ContractFactSourceTablePipelineTest.Harness harness) {
        var result = harness.service.extract(PROJECT, FILE, "fallback", ACTOR, META);
        assertThat(result.acceptedCount()).isZero();
        assertThat(harness.extractions.findByProjectId(PROJECT)).isEmpty();
        List<Segment> sent = harness.model.requests.stream().filter(request -> request.scope() == Scope.LINE_ITEMS)
                .flatMap(request -> request.segments().stream()).toList();
        assertThat(sent.equals(harness.segments)).as("every unknown source segment remains on the AI path").isTrue();
    }

    @Test void aSourceRowWithoutItsOwnKeyCannotPublishItsOtherFourFields() throws Exception {
        try (var harness = harness(docx(HEADER, List.<String[]>of(new String[]{"", "Item", "2", "10", "20"})))) {
            assertFallback(harness);
        }
    }

    @Test void aTableWithoutAnExplicitRowIdColumnRemainsOnTheAiPath() throws Exception {
        try (var harness = harness(docx(new String[]{"名称", "数量", "单价(元)", "金额(元)"},
                List.<String[]>of(new String[]{"Item", "2", "10", "20"})))) {
            assertFallback(harness);
        }
    }

    @Test void aDuplicateRawKeyCannotCreateTwoApparentlyConfirmedItemKeys() throws Exception {
        try (var harness = harness(docx(HEADER, List.of(new String[]{"A", "First", "1", "10", "10"},
                new String[]{"A", "Second", "2", "30", "60"})))) {
            assertFallback(harness);
        }
    }

    @Test void aDuplicateKeyOnAnInvalidRowStillPreventsSourceKeyAuthority() throws Exception {
        try (var harness = harness(docx(HEADER, List.of(new String[]{"A", "First", "1", "10", "10"},
                new String[]{"A", "Second", "2", "unknown", "60"})))) {
            assertFallback(harness);
        }
    }

    @Test void ordinaryProviderCannotPublishTheSourceOnlyRawLineKey() {
        UUID parse = UUID.fromString("57000000-0000-0000-0000-000000000003");
        String sha;
        try {
            sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest("A".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var segment = new ContractFactSegmenter().segment(parse, "A").get(0);
        var provenance = new Provenance("provider", "model", "revision", "prompt", "contract-fact-candidates-v2");
        var input = new ContractFactCandidateValidator.CurrentInput(PROJECT, FILE, "a".repeat(64), parse, 1, sha,
                "A", com.fuquan.audit.contract.domain.project.DocumentRole.CONTRACT_BODY, true);
        var request = new Request(PROJECT, FILE, "a".repeat(64), parse, 1, sha, List.of(segment), Scope.LINE_ITEMS);
        var candidate = new Candidate("cfc-v2-" + "b".repeat(64), FactType.valueOf("LINE_ITEM_KEY"),
                ContractFactSegmenter.lineGroup(segment.rowIdentity()), "A", null, segment.segmentId(),
                segment.sourceLocator(), "A", 0, 1, ContextCode.CONTRACT_LINE_ITEM, BigDecimal.ONE);
        var result = new Result(List.of(candidate), provenance, 0, List.of(), BatchStatus.PARTIAL, Scope.LINE_ITEMS, List.of());
        var validation = new ContractFactCandidateValidator(provenance).validate(input, request, result, NOW);
        assertThat(validation.publications()).isEmpty();
        assertThat(validation.decisions().get(0).reason()).isEqualTo(ContractFactCandidateValidator.Reason.ROLE_NOT_AUTHORIZED);
    }

    @Test void rawSourceKeyIsNotUnicodeNormalizedIntoADifferentItemIdentity() throws Exception {
        String raw = "Ａ-０１";
        try (var harness = harness(docx(HEADER, List.<String[]>of(new String[]{raw, "Item", "2", "10", "20"})))) {
            var result = harness.service.extract(PROJECT, FILE, "raw-key", ACTOR, META);
            assertThat(result.acceptedCount()).isEqualTo(5);
            var facts = harness.extractions.findByProjectId(PROJECT);
            var key = facts.stream().filter(fact -> fact.factType().equals("LINE_ITEM_KEY")).findFirst().orElseThrow();
            assertThat(key.normalizedValue().equals(raw)).isTrue();
        }
    }

    private static ContractFactSourceTablePipelineTest.Harness harness(byte[] bytes) throws Exception {
        return new ContractFactSourceTablePipelineTest.Harness(bytes);
    }

    private static BigDecimal value(List<ExtractionResult> facts, String type) {
        return facts.stream().filter(fact -> fact.factType().equals(type)).findFirst().orElseThrow().valueAmount();
    }

    private static byte[] docx(String[] header, List<String[]> rows) throws Exception {
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var table = document.createTable(rows.size() + 1, header.length);
            for (int column = 0; column < header.length; column++) table.getRow(0).getCell(column).setText(header[column]);
            for (int row = 0; row < rows.size(); row++) for (int column = 0; column < header.length; column++) {
                table.getRow(row + 1).getCell(column).setText(rows.get(row)[column]);
            }
            document.write(out); return out.toByteArray();
        }
    }
}
