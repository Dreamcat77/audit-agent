package com.fuquan.audit.contract.application.project.factai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native bytes -> production planner/coordinator; no model, HTTP or database. */
class ContractFactSourceProseAliasTest {
    private static final UUID PROJECT = UUID.fromString("57000000-0000-0000-0000-000000000001");
    private static final UUID FILE = UUID.fromString("57000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
    private static final CurrentUser ACTOR = new CurrentUser("source-test", "test", "Test", Set.of("AUDITOR"));
    private static final ProjectRequestMetadata META = new ProjectRequestMetadata("request", "trace", "127.0.0.1");
    private static final String[] HEADER = {"序号", "名称", "数量", "单价(元)", "金额(元)"};
    private static final String NAME = "统一接口联调与验收支持";
    private static final List<String[]> ROWS = List.of(
            new String[]{"ITEM-027", NAME, "1", "3550.00", "3550.00"},
            new String[]{"ITEM-028", NAME, "2", "1660.00", "3320.00"});
    private static final String FIRST = "5.27 工作包 ITEM-027“" + NAME + "”：对应数量1、单价3,550.00元，行金额3,550.00元。";
    private static final String SECOND = "5.28 工作包 ITEM-028“" + NAME + "”：对应数量2.0000、单价1660元，行金额3320元。";

    // Break caught: matching by name would merge distinct 027/028 rows; dropping aliases would lose proof.
    @Test void equalClosedProseReusesOnlyItsOwnNativeFactsAndRetainsEveryOriginalProofSegment() throws Exception {
        try (var harness = harness(ROWS, FIRST, SECOND)) {
            var prepared = prepare(harness, "closed-alias");
            var residual = residual(prepared);
            assertThat(residual.stream().map(Segment::text)).noneMatch(text -> text.contains("工作包 ITEM-"));
            var proof = prepared.sourceBatches().stream().flatMap(batch -> batch.request().segments().stream()).toList();
            var aliases = harness.segments.stream().filter(segment -> segment.text().contains("工作包 ITEM-")).toList();
            assertThat(proof).containsAll(aliases);
            assertThat(prepared.sourceBatches().stream().flatMap(batch -> batch.result().candidates().stream()))
                    .hasSize(10).allMatch(candidate -> candidate.candidateKey().startsWith("cfs-v1-"));
            assertThat(prepared.requests().stream().filter(request -> request.scope() == Scope.CORE)
                    .flatMap(request -> request.segments().stream()).toList()).containsExactlyElementsOf(harness.segments);
            var covered = java.util.stream.Stream.concat(proof.stream(), residual.stream()).toList();
            assertThat(covered).containsExactlyInAnyOrderElementsOf(harness.segments);
            assertThat(harness.document.fullText()).contains(FIRST, SECOND);
        }
    }

    // Every changed operand, identity, field boundary or extra field must retain the original AI evidence.
    @ParameterizedTest
    @ValueSource(strings = {
            "5.27 工作包 ITEM-027“统一接口联调与验收支持”：对应数量2、单价3,550.00元，行金额3,550.00元。",
            "5.27 工作包 ITEM-027“统一接口联调与验收支持”：对应数量1、单价1,660.00元，行金额3,550.00元。",
            "5.27 工作包 ITEM-027“统一接口联调与验收支持”：对应数量1、单价3,550.00元，行金额3,320.00元。",
            "5.27 工作包 ITEM-028“统一接口联调与验收支持”：对应数量1、单价3,550.00元，行金额3,550.00元。",
            "5.27 工作包 UNKNOWN“统一接口联调与验收支持”：对应数量1、单价3,550.00元，行金额3,550.00元。",
            "5.27 工作包 ITEM-027“改名服务”：对应数量1、单价3,550.00元，行金额3,550.00元。",
            "5.27 工作包 ITEM-027“统一接口联调与验收支持”：对应数量1、单价3,550.00万元，行金额3,550.00元。",
            "5.27 工作包 ITEM-027“统一接口联调与验收支持”：对应数量1、单价3,550.00元。",
            "5.27 工作包 ITEM-027“统一接口联调与验收支持”：对应数量1、数量2、单价3,550.00元，行金额3,550.00元。",
            "5.27 工作包 ITEM-027 ITEM-028“统一接口联调与验收支持”：对应数量1、单价3,550.00元，行金额3,550.00元。",
            "5.27 工作包 ITEM-027“统一接口联调与验收支持”：对应数量1、单价3,55.00元，行金额3,550.00元。",
            "5.27 工作包 ITEM-027“统一接口联调与验收支持”：对应数量-1、单价3,550.00元，行金额3,550.00元。",
            "5.27 工作包 ITEM-027“统一接口联调与验收支持”：对应数量１、单价3,550.00元，行金额3,550.00元。"
    })
    void unprovenClosedProseKeepsEveryOriginalSegment(String prose) throws Exception {
        try (var harness = harness(ROWS, prose)) {
            var prepared = prepare(harness, "unproven-alias");
            var source = harness.segments.stream().filter(segment -> segment.text().contains("工作包 ")).toList();
            assertThat(residual(prepared)).containsAll(source);
            assertThat(prepared.sourceBatches().stream().flatMap(batch -> batch.request().segments().stream()).toList())
                    .doesNotContainAnyElementsOf(source);
        }
    }

    // Break caught: treating unknown prose after a valid alias as redundant could erase a new conflict.
    @ParameterizedTest
    @ValueSource(strings = {
            "第4工作包，第27项；交付证据独立归档；合格证明为提交双方确认记录。",
            "数量另改为7，追加金额900元。",
            "服务改名为另一服务，第二编码ITEM-028不再对应原报价。",
            "参考合同模板，仅供参考；另按壹万元计算。",
            "Extra identity OTHER-999, tax 17%, quantity 6 and changed final amount 77."
    })
    void unknownTailRemainsExactAiEvidenceAfterOnlyTheProvenPrefixIsCovered(String tail) throws Exception {
        try (var harness = harness(ROWS, FIRST + tail)) {
            var prepared = prepare(harness, "unknown-tail");
            String sent = residual(prepared).stream().map(Segment::text).collect(java.util.stream.Collectors.joining());
            assertThat(sent).contains(tail).doesNotContain(FIRST);
            assertCoverage(harness, prepared);
            assertThat(prepared.sourceBatches().stream().flatMap(batch -> batch.result().candidates().stream())).hasSize(10);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {239, 240, 241})
    void codepointSplitKeepsSupplementaryUnicodeAndEveryFragmentAcrossThe240Boundary(int prefixLength) throws Exception {
        String skeleton = "5.1 工作包 A“”：对应数量1、单价2元，行金额2元。";
        String name = "𠀀" + "N".repeat(prefixLength - skeleton.codePointCount(0, skeleton.length()) - 1);
        String prefix = "5.1 工作包 A“" + name + "”：对应数量1、单价2元，行金额2元。";
        String tail = "尾部交付说明".repeat(90) + "数量改为9；ID-B属于其他服务。";
        assertThat(prefix.codePointCount(0, prefix.length())).isEqualTo(prefixLength);
        try (var harness = harness(List.<String[]>of(new String[]{"A", name, "1", "2", "2"}), prefix + tail)) {
            var prepared = prepare(harness, "unicode-fragments");
            String sent = residual(prepared).stream().map(Segment::text).collect(java.util.stream.Collectors.joining());
            assertThat(sent).contains(tail).doesNotContain(prefix);
            assertThat(prepared.sourceBatches().stream().flatMap(batch -> batch.result().candidates().stream())).hasSize(5);
            assertCoverage(harness, prepared);
            var provider = prepared.requests().stream().map(harness.model::extract).toList();
            var completed = harness.context.getBean(DefaultContractFactExtractionTransactionCoordinator.class)
                    .complete(prepared, provider, NOW);
            assertThat(completed.acceptedCount()).isEqualTo(5);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DROP", "REWRITE"})
    void aliasProofCannotBeRemovedOrChangedBetweenPrepareAndPublication(String mutation) throws Exception {
        try (var harness = harness(ROWS, FIRST + "仍须保留的尾文。")) {
            var prepared = prepare(harness, "immutable-alias-proof");
            var batches = new ArrayList<>(prepared.sourceBatches());
            var source = batches.get(0);
            var proof = new ArrayList<>(source.request().segments());
            int index = -1;
            for (int i = 0; i < proof.size(); i++) if (proof.get(i).text().contains("工作包 ITEM-027")) index = i;
            assertThat(index).as("the original prose prefix participates in durable authority").isNotNegative();
            if (mutation.equals("DROP")) proof.remove(index);
            else {
                var original = proof.get(index);
                proof.set(index, new Segment(original.segmentId(), original.sourceLocator(), original.textSha256(),
                        original.startOffset(), original.endOffset(), original.text().replace("3550", "3551") + "x",
                        original.rowIdentity(), original.lineItemColumns()));
            }
            var request = source.request();
            batches.set(0, new ContractFactSourceTablePlanner.SourceBatch(new Request(request.projectId(), request.fileId(),
                    request.fileSha256(), request.parseResultId(), request.parseVersion(), request.canonicalTextSha256(),
                    proof, request.scope(), request.batchOrdinal(), request.batchCount(), request.remainingBudgetMs()),
                    source.result(), source.cellLocators()));
            var altered = new ContractFactExtractionTransactionCoordinator.Prepared(prepared.attemptId(), prepared.requests(),
                    false, prepared.currentResult(), batches);
            var provider = prepared.requests().stream().map(harness.model::extract).toList();
            assertThatThrownBy(() -> harness.context.getBean(DefaultContractFactExtractionTransactionCoordinator.class)
                    .complete(altered, provider, NOW)).isInstanceOfAny(
                            com.fuquan.audit.common.exception.BusinessException.class, ContractFactValidationException.class);
            assertThat(harness.extractions.findByProjectId(PROJECT)).isEmpty();
            assertThat(harness.attempts.findCandidates(prepared.attemptId())).isEmpty();
        }
    }

    @Test void identicalProseCannotMoveSourceProofToAnotherPhysicalParagraphBeforePublication() throws Exception {
        try (var harness = harness(ROWS, FIRST, FIRST, FIRST)) {
            var prepared = prepare(harness, "immutable-alias-location");
            var batches = new ArrayList<>(prepared.sourceBatches());
            var source = batches.get(0);
            var proof = new ArrayList<>(source.request().segments());
            var aliases = proof.stream().filter(segment -> segment.text().startsWith(FIRST)).toList();
            assertThat(aliases).hasSize(3);
            var first = aliases.get(0);
            var second = aliases.get(1);
            assertThat(first.text()).isEqualTo(second.text());
            assertThat(first.textSha256()).isEqualTo(second.textSha256());
            assertThat(first.rowIdentity()).isNotEqualTo(second.rowIdentity());
            proof.set(proof.indexOf(first), new Segment(first.segmentId(), first.sourceLocator(), first.textSha256(),
                    second.startOffset(), second.endOffset(), first.text(), second.rowIdentity(), first.lineItemColumns()));
            var request = source.request();
            batches.set(0, new ContractFactSourceTablePlanner.SourceBatch(new Request(request.projectId(), request.fileId(),
                    request.fileSha256(), request.parseResultId(), request.parseVersion(), request.canonicalTextSha256(),
                    proof, request.scope(), request.batchOrdinal(), request.batchCount(), request.remainingBudgetMs()),
                    source.result(), source.cellLocators()));
            var altered = new ContractFactExtractionTransactionCoordinator.Prepared(prepared.attemptId(), prepared.requests(),
                    false, prepared.currentResult(), batches);
            var provider = prepared.requests().stream().map(harness.model::extract).toList();
            assertThatThrownBy(() -> harness.context.getBean(DefaultContractFactExtractionTransactionCoordinator.class)
                    .complete(altered, provider, NOW)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
            assertThat(harness.extractions.findByProjectId(PROJECT)).isEmpty();
            assertThat(harness.attempts.findCandidates(prepared.attemptId())).isEmpty();
        }
    }

    @Test void duplicateNativeKeysOrTemplateContextCannotBeRescuedByAnAlias() throws Exception {
        List<String[]> duplicates = List.of(ROWS.get(0), ROWS.get(0));
        try (var harness = harness(duplicates, FIRST)) {
            var prepared = prepare(harness, "duplicate-native-key");
            assertThat(prepared.sourceBatches()).isEmpty();
            assertThat(residual(prepared)).containsExactlyElementsOf(harness.segments);
        }
        try (var harness = harness(ROWS, "参考合同", FIRST)) {
            var prepared = prepare(harness, "template-alias");
            assertThat(prepared.sourceBatches()).isEmpty();
            assertThat(residual(prepared)).containsExactlyElementsOf(harness.segments);
        }
    }

    @Test void boundedSourceBatchesKeepExcessAliasEvidenceOnTheAiPath() throws Exception {
        try (var harness = harness(ROWS, java.util.Collections.nCopies(45, FIRST).toArray(String[]::new))) {
            var prepared = prepare(harness, "many-aliases");
            assertThat(prepared.sourceBatches()).allSatisfy(batch -> assertThat(batch.request().segments()).hasSizeLessThanOrEqualTo(32));
            assertCoverage(harness, prepared);
            assertThat(prepared.sourceBatches().stream().flatMap(batch -> batch.result().candidates().stream())).hasSize(10);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @EnabledIfSystemProperty(named = "audit.v2.fixtureRoot", matches = ".+")
    void realFixturePlanRetainsEveryCodepointAndReducesOnlyProvenDuplicateLineEvidence(int scenario) throws Exception {
        String folder = scenario == 1 ? "01_基准一致_长合同" : "02_超长合同_48行明细";
        byte[] bytes = Files.readAllBytes(Path.of(System.getProperty("audit.v2.fixtureRoot"))
                .resolve("00_解压后按场景多选上传").resolve(folder).resolve("04_智慧维修系统采购及服务合同.docx"));
        try (var harness = new ContractFactSourceTablePipelineTest.Harness(bytes)) {
            var prepared = prepare(harness, "real-alias-fixture-" + scenario);
            assertCoverage(harness, prepared);
            long core = prepared.requests().stream().filter(request -> request.scope() == Scope.CORE).count();
            long line = prepared.requests().stream().filter(request -> request.scope() == Scope.LINE_ITEMS).count();
            long facts = prepared.sourceBatches().stream().flatMap(batch -> batch.result().candidates().stream()).count();
            long aliasPrefixes = prepared.sourceBatches().stream().flatMap(batch -> batch.request().segments().stream())
                    .filter(segment -> segment.segmentId().contains(":alias:")).count();
            long tails = residual(prepared).stream().filter(segment -> segment.segmentId().contains(":tail:")).count();
            assertThat(core).isEqualTo(scenario == 1 ? 7 : 9);
            assertThat(facts).isEqualTo((scenario == 1 ? 12 : 48) * 5);
            assertThat(aliasPrefixes).isEqualTo(scenario == 1 ? 0 : 48);
            assertThat(tails).isEqualTo(aliasPrefixes);
            if (scenario == 2) assertThat(line).isLessThan(53);
            System.out.println("V2_ALIAS_PLAN scenario=" + scenario + " sourceFacts=" + facts + " coreBatches=" + core
                    + " residualLineBatches=" + line + " maximumModelCalls=" + ((core + line) * 2)
                    + " aliasPrefixes=" + aliasPrefixes + " preservedTails=" + tails);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = BatchStatus.class, names = {"FAILED", "UNKNOWN"})
    void failedResidualNeverPublishesNativeFactsEvenWhenProseAliasWasProven(BatchStatus terminal) throws Exception {
        try (var harness = harness(ROWS, FIRST)) {
            harness.model.finalLineStatus = terminal;
            var result = harness.service.extract(PROJECT, FILE, "failed-alias-plan", ACTOR, META);
            assertThat(result.status()).isEqualTo(terminal == BatchStatus.UNKNOWN ? "OUTCOME_UNKNOWN" : "FAILED");
            assertThat(harness.extractions.findByProjectId(PROJECT)).isEmpty();
            assertThat(harness.attempts.findCandidates(result.attemptId())).isEmpty();
        }
    }

    @Test void committedAliasPlanReopensWithIdenticalSourceProofAndNoSecondCandidateGroup() throws Exception {
        try (var harness = harness(ROWS, FIRST + "交付说明原样保留。", SECOND)) {
            var prepared = prepare(harness, "reopen-alias");
            var provider = prepared.requests().stream().map(harness.model::extract).toList();
            var result = harness.context.getBean(DefaultContractFactExtractionTransactionCoordinator.class)
                    .complete(prepared, provider, NOW);
            assertThat(result.acceptedCount()).isEqualTo(10);
            var replay = prepare(harness, "reopen-alias");
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.attemptId()).isEqualTo(prepared.attemptId());
            assertThat(replay.requests()).isEqualTo(prepared.requests());
            assertThat(replay.sourceBatches()).isEqualTo(prepared.sourceBatches());
            assertThat(harness.extractions.findByProjectId(PROJECT)).hasSize(10);
            assertThat(harness.attempts.findCandidates(result.attemptId()).stream()
                    .map(ContractFactExtractionCandidate::groupKey).distinct()).hasSize(2);
        }
    }

    private static ContractFactExtractionTransactionCoordinator.Prepared prepare(
            ContractFactSourceTablePipelineTest.Harness harness, String key) {
        return harness.context.getBean(DefaultContractFactExtractionTransactionCoordinator.class)
                .prepare(PROJECT, FILE, key, ACTOR, META, NOW);
    }

    private static List<Segment> residual(ContractFactExtractionTransactionCoordinator.Prepared prepared) {
        return prepared.requests().stream().filter(request -> request.scope() == Scope.LINE_ITEMS)
                .flatMap(request -> request.segments().stream()).toList();
    }

    private static void assertCoverage(ContractFactSourceTablePipelineTest.Harness harness,
            ContractFactExtractionTransactionCoordinator.Prepared prepared) throws Exception {
        var all = java.util.stream.Stream.concat(residual(prepared).stream(),
                prepared.sourceBatches().stream().flatMap(batch -> batch.request().segments().stream()))
                .sorted(Comparator.comparingInt(Segment::startOffset)).toList();
        int offset = 0;
        String canonical = harness.document.fullText();
        for (var segment : all) {
            assertThat(segment.startOffset()).as("no missing or overlapping codepoints").isEqualTo(offset);
            assertThat(segment.text()).isEqualTo(canonical.substring(canonical.offsetByCodePoints(0, offset),
                    canonical.offsetByCodePoints(0, segment.endOffset())));
            assertThat(segment.textSha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(segment.text().getBytes(StandardCharsets.UTF_8))));
            var parents = harness.segments.stream().filter(parent -> parent.startOffset() <= segment.startOffset()
                    && parent.endOffset() >= segment.endOffset()).toList();
            assertThat(parents).hasSize(1);
            var parent = parents.get(0);
            assertThat(segment.rowIdentity()).isEqualTo(parent.rowIdentity());
            if (!segment.equals(parent)) {
                assertThat(segment.segmentId()).startsWith(parent.segmentId() + ":").hasSizeLessThanOrEqualTo(64);
                assertThat(segment.sourceLocator()).endsWith(":canonical-codepoint:" + segment.startOffset() + "-" + segment.endOffset());
            }
            offset = segment.endOffset();
        }
        assertThat(offset).isEqualTo(canonical.codePointCount(0, canonical.length()));
        assertThat(prepared.requests().stream().filter(request -> request.scope() == Scope.CORE)
                .flatMap(request -> request.segments().stream()).toList()).containsExactlyElementsOf(harness.segments);
    }

    private static ContractFactSourceTablePipelineTest.Harness harness(List<String[]> rows, String... prose) throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            var table = document.createTable(rows.size() + 1, HEADER.length);
            for (int column = 0; column < HEADER.length; column++) table.getRow(0).getCell(column).setText(HEADER[column]);
            for (int row = 0; row < rows.size(); row++) for (int column = 0; column < HEADER.length; column++) {
                table.getRow(row + 1).getCell(column).setText(rows.get(row)[column]);
            }
            for (String paragraph : prose) document.createParagraph().createRun().setText(paragraph);
            document.write(output);
            return new ContractFactSourceTablePipelineTest.Harness(output.toByteArray());
        }
    }
}
