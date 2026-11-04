package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.domain.project.DeterministicDocumentRoleResult;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.DocumentRoleCandidate;
import com.fuquan.audit.contract.domain.project.DocumentRoleSignal;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicDocumentRoleFixtureMatrixTest {
    private final DocumentRoleSignalExtractor extractor = new DocumentRoleSignalExtractor();
    private final DeterministicDocumentRoleResolver resolver = new DeterministicDocumentRoleResolver();

    @ParameterizedTest(name = "{0} is proposed as {1} from body signals")
    @MethodSource("genericFilenameMatrix")
    void genericFilenameNeverEntersResolution(String testLabelFilename, DocumentRole expectedRole, String body) {
        assertThat(testLabelFilename).endsWith(".pdf");

        DeterministicDocumentRoleResult result = resolver.resolve(extractor.extract(parsed(body)));

        assertThat(result.proposedRole()).isEqualTo(expectedRole);
        assertThat(result.conflict()).isFalse();
        assertThat(result.candidateRoles()).isNotEmpty();
        assertThat(result.evidence()).isNotEmpty();
        assertThat(result.decisionStatus())
                .isEqualTo(DeterministicDocumentRoleResult.DecisionStatus.NEEDS_CONFIRMATION);
    }

    @Test
    void attachmentOnlyHasNoPositiveRoleAndRemainsUnknownForHumanConfirmation() {
        String testLabelFilename = "附件.pdf";

        DeterministicDocumentRoleResult result = resolver.resolve(
                extractor.extract(parsed("附件\n这里仅列出随文材料。")));

        assertThat(testLabelFilename).endsWith(".pdf");
        assertThat(result.proposedRole()).isEqualTo(DocumentRole.UNKNOWN);
        assertThat(result.confidence()).isEqualByComparingTo("0");
        assertThat(result.candidateRoles()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.conflict()).isFalse();
        assertThat(result.decisionStatus())
                .isEqualTo(DeterministicDocumentRoleResult.DecisionStatus.NEEDS_CONFIRMATION);
    }

    @Test
    void exactWeightsAreAggregatedAndCandidatesAreOrderedByScoreThenRole() {
        UUID parseResultId = UUID.fromString("00000000-0000-0000-0000-000000000801");
        DocumentRoleSignal bid = signal(parseResultId, DocumentRole.BID_RESPONSE, "2.00", "BID_A", 0);
        DocumentRoleSignal contractFirst = signal(parseResultId, DocumentRole.CONTRACT_BODY, "1.10", "CONTRACT_A", 10);
        DocumentRoleSignal contractSecond = signal(parseResultId, DocumentRole.CONTRACT_BODY, "0.90", "CONTRACT_B", 20);
        DocumentRoleSignal award = signal(parseResultId, DocumentRole.AWARD_NOTICE, "0.50", "AWARD_A", 30);

        DeterministicDocumentRoleResult result = resolver.resolve(
                List.of(award, contractSecond, bid, contractFirst));

        assertThat(result.conflict()).isTrue();
        assertThat(result.proposedRole()).isEqualTo(DocumentRole.UNKNOWN);
        assertThat(result.candidateRoles()).extracting(DocumentRoleCandidate::role)
                .containsExactly(DocumentRole.CONTRACT_BODY, DocumentRole.BID_RESPONSE, DocumentRole.AWARD_NOTICE);
        assertThat(result.candidateRoles()).extracting(DocumentRoleCandidate::score)
                .containsExactly(new BigDecimal("2.00"), new BigDecimal("2.00"), new BigDecimal("0.50"));
        assertThat(result.candidateRoles()).extracting(DocumentRoleCandidate::confidence)
                .containsExactly(new BigDecimal("0.444444"), new BigDecimal("0.444444"), new BigDecimal("0.111111"));
        assertThat(result.confidence()).isEqualByComparingTo("0.444444");
        assertThat(result.evidence()).containsExactly(contractFirst, contractSecond, bid, award);
        assertThat(result.candidateRoles().get(0).evidence()).containsExactly(contractFirst, contractSecond);
        assertThat(result.candidateRoles().get(1).evidence()).containsExactly(bid);
    }

    @Test
    void confidenceNeverChangesTheHumanConfirmationRequirement() {
        UUID parseResultId = UUID.fromString("00000000-0000-0000-0000-000000000802");

        DeterministicDocumentRoleResult low = resolver.resolve(List.of(
                signal(parseResultId, DocumentRole.CONTRACT_BODY, "0.01", "LOW", 0),
                signal(parseResultId, DocumentRole.AWARD_NOTICE, "0.99", "OTHER", 10)));
        DeterministicDocumentRoleResult high = resolver.resolve(List.of(
                signal(parseResultId, DocumentRole.CONTRACT_BODY, "999999.99", "HIGH", 20)));

        assertThat(low.confidence()).isEqualByComparingTo("0.99");
        assertThat(high.confidence()).isEqualByComparingTo("1");
        assertThat(low.decisionStatus()).isEqualTo(DeterministicDocumentRoleResult.DecisionStatus.NEEDS_CONFIRMATION);
        assertThat(high.decisionStatus()).isEqualTo(DeterministicDocumentRoleResult.DecisionStatus.NEEDS_CONFIRMATION);
    }

    @Test
    void resolverPublicApiAcceptsSignalsOnlyAndNeverFileMetadata() {
        List<Method> publicDeclaredMethods = Arrays.stream(DeterministicDocumentRoleResolver.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        assertThat(publicDeclaredMethods).hasSize(1);
        assertThat(publicDeclaredMethods.get(0).getName()).isEqualTo("resolve");
        assertThat(publicDeclaredMethods.get(0).getParameterTypes()).containsExactly(List.class);
    }

    @ParameterizedTest(name = "{0} has a durable title signal for {1}")
    @MethodSource("realWorldTitleMatrix")
    void realWorldChineseTitlesAreRecognizedWithoutFilenameHints(
            String body, DocumentRole expectedRole, String expectedRuleCode) {
        List<DocumentRoleSignal> signals = extractor.extract(parsed(body));

        assertThat(signals)
                .anySatisfy(signal -> {
                    assertThat(signal.role()).isEqualTo(expectedRole);
                    assertThat(signal.signalType()).isEqualTo(DocumentRoleSignal.SignalType.TITLE);
                    assertThat(signal.ruleCode()).isEqualTo(expectedRuleCode);
                });
    }

    private static Stream<Arguments> realWorldTitleMatrix() {
        return Stream.of(
                Arguments.of("""
                        竞争性磋商采购文件
                        项目编号：CS-2026-001
                        供应商须知
                        评审办法
                        合同条款
                        """, DocumentRole.TENDER_DOCUMENT, "ROLE_TENDER_TITLE"),
                Arguments.of("""
                        澄清与补充说明
                        原合同编号：HT-2026-001
                        本说明对原合同部分条款作如下补充。
                        """, DocumentRole.SUPPLEMENT, "ROLE_SUPPLEMENT_TITLE"));
    }

    private static Stream<Arguments> genericFilenameMatrix() {
        return Stream.of(
                Arguments.of("001.pdf", DocumentRole.CONTRACT_BODY, """
                        采购合同
                        甲方：示例采购单位
                        乙方：示例供应商
                        第一条 合同价款
                        第二条 履行期限
                        第三条 违约责任
                        双方签字盖章后生效。
                        """),
                Arguments.of("扫描件.pdf", DocumentRole.AWARD_NOTICE, """
                        中标通知书
                        项目名称：合成测试项目
                        中标人：示例供应商
                        中标金额：人民币壹佰元
                        贵公司已被确定为中标人，请按通知签订合同。
                        """),
                Arguments.of("采购资料.pdf", DocumentRole.TENDER_DOCUMENT, """
                        公开招标文件
                        招标人：示例采购单位
                        第一章 招标公告
                        第二章 投标人须知
                        第三章 评标办法
                        第四章 合同条款及格式
                        """),
                Arguments.of("响应材料.pdf", DocumentRole.BID_RESPONSE, """
                        投标文件
                        投标人：示例供应商
                        第一章 投标函
                        第二章 投标报价
                        第三章 商务响应
                        第四章 技术响应
                        法定代表人签字并加盖公章。
                        """));
    }

    private static DocumentRoleSignal signal(
            UUID parseResultId, DocumentRole role, String weight, String ruleCode, int start) {
        String excerpt = "合成证据" + ruleCode;
        return new DocumentRoleSignal(role, DocumentRoleSignal.SignalType.KEY_PHRASE,
                excerpt, ruleCode, new BigDecimal(weight), parseResultId,
                start, start + excerpt.codePointCount(0, excerpt.length()),
                "parse-result:%s:canonical-codepoint:%d-%d".formatted(
                        parseResultId, start, start + excerpt.codePointCount(0, excerpt.length())),
                sha256(excerpt), excerpt, DocumentRoleSignalExtractor.EXTRACTOR_VERSION);
    }

    private static ProjectFileParseResult parsed(String text) {
        Instant completedAt = Instant.parse("2026-09-14T02:00:00Z");
        return new ProjectFileParseResult(
                UUID.nameUUIDFromBytes(("parse:" + text).getBytes(StandardCharsets.UTF_8)),
                UUID.fromString("00000000-0000-0000-0000-000000000811"),
                UUID.fromString("00000000-0000-0000-0000-000000000812"),
                "a".repeat(64), 1, ProjectFileParseOrigin.CURRENT_PIPELINE,
                ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT,
                "synthetic-parser", "1", text, sha256(text), 1, 5L, BigDecimal.ONE,
                null, completedAt.minusSeconds(1), completedAt, 0);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
