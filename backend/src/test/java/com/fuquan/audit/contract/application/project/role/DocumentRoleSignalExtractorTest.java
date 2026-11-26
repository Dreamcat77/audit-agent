package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.domain.project.DocumentRole;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentRoleSignalExtractorTest {
    private final DocumentRoleSignalExtractor extractor = new DocumentRoleSignalExtractor();

    @Test void amountAppendixCoverIsSupportingMaterialButAnEmbeddedTableDoesNotReplaceTheContractRole(){
        String appendix="合同价款明细表（本表列明全部价款构成，无其他分项或另计费用） |  | \n项目名称：青禾项目 | 合同编号：HT-001 | 单位：元\n分项名称 | 分项金额（元） | 备注\n设备 | 300,000.00 | 含税\n安装服务 | 150,000.00 | 含税\n合计 | 450,000.00 | 以上为全部合同价款明细";
        assertThat(new DeterministicDocumentRoleResolver().resolve(extractor.extract(parsed(appendix))).proposedRole()).isEqualTo(DocumentRole.SUPPLEMENT);
        String current="服务合同\n甲方：当前单位\n乙方：当前公司\n违约责任：逾期承担责任。\n双方签字盖章后生效。\n"+appendix;
        assertThat(new DeterministicDocumentRoleResolver().resolve(extractor.extract(parsed(current))).proposedRole()).isEqualTo(DocumentRole.CONTRACT_BODY);
    }

    @Test void numberedCurrentPartRestartsSignalsAfterHistory(){
        String text="历史合同\n合同书\n甲方：旧单位\n乙方：旧公司\n第二部分 当前合同正文\n合同书\n甲方：当前单位\n乙方：当前公司\n违约责任：逾期承担责任。\n双方签字盖章后生效。";
        var signals=extractor.extract(parsed(text));
        assertThat(new DeterministicDocumentRoleResolver().resolve(signals).proposedRole()).isEqualTo(DocumentRole.CONTRACT_BODY);
        assertThat(signals).allMatch(s->s.charStart()>=text.indexOf("第二部分"));
    }

    @Test void historicalPerformanceCaptionsDoNotBecomeTheCurrentContract() {
        for(String text:List.of("供应商履约业绩说明\n供应商：青禾服务有限公司\n历史项目合同金额：9600000元，历史项目甲方：东河档案管理中心。\n本页仅为历史业绩，不是当前合同。",
                "历史合同\n合同书\n甲方：东河档案管理中心\n乙方：青禾服务有限公司\n违约责任：迟延交付承担责任。\n双方签字盖章后生效。")) {
            var signals=extractor.extract(parsed(text));
            assertThat(signals).isNotEmpty().allMatch(s->s.role()==DocumentRole.OTHER);
            assertThat(new DeterministicDocumentRoleResolver().resolve(signals).proposedRole()).isEqualTo(DocumentRole.OTHER);
        }
    }

    @Test void explicitCurrentSectionAfterHistoricalMaterialRetainsItsOwnContractSignals() {
        String text="历史业绩\n甲方：历史单位\n乙方：历史服务公司\n违约责任：历史约定。\n本次项目合同正文\n合同书\n甲方：当前单位\n乙方：当前服务公司\n违约责任：延迟交付承担责任。\n双方签字盖章后生效。";
        var signals=extractor.extract(parsed(text));
        assertThat(new DeterministicDocumentRoleResolver().resolve(signals).proposedRole()).isEqualTo(DocumentRole.CONTRACT_BODY);
        assertThat(signals).allMatch(s->s.charStart()>=text.indexOf("本次项目合同正文"));
    }

    @Test
    void exposesTheExactBusinessRoleVocabulary() {
        assertThat(DocumentRole.values()).containsExactly(
                DocumentRole.CONTRACT_BODY,
                DocumentRole.AWARD_NOTICE,
                DocumentRole.TENDER_DOCUMENT,
                DocumentRole.BID_RESPONSE,
                DocumentRole.SUPPLEMENT,
                DocumentRole.OTHER,
                DocumentRole.UNKNOWN);
    }

    @ParameterizedTest(name = "{0} body produces {1} signals")
    @MethodSource("genericNamedDocuments")
    void derivesRolesFromCanonicalBodyWhileGenericFilenameRemainsOutsideTheApi(
            String surroundingFilename, DocumentRole expectedRole, String canonicalText) {
        assertThat(surroundingFilename).endsWith(".pdf");

        List<DocumentRoleSignal> signals = extractor.extract(parsed(canonicalText));

        assertThat(signals).extracting(DocumentRoleSignal::role).contains(expectedRole);
    }

    @Test
    void publicExtractionApiAcceptsOnlyTheImmutableParseResult() {
        List<Method> publicDeclaredMethods = Arrays.stream(DocumentRoleSignalExtractor.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        assertThat(publicDeclaredMethods).hasSize(1);
        assertThat(publicDeclaredMethods.get(0).getName()).isEqualTo("extract");
        assertThat(publicDeclaredMethods.get(0).getParameterTypes())
                .containsExactly(ProjectFileParseResult.class);
    }

    @Test
    void everySignalCarriesParseBoundCodePointLocatorAndExactSpanHash() {
        String text = "😀\n中标通知书\n项目名称：合成测试项目\n中标人：示例供应商\n中标金额：人民币壹佰元\n请于收到本通知后签订合同。";
        ProjectFileParseResult parseResult = parsed(text);

        List<DocumentRoleSignal> signals = extractor.extract(parseResult);

        assertThat(signals).isNotEmpty();
        for (DocumentRoleSignal signal : signals) {
            String span = codePointSubstring(text, signal.charStart(), signal.charEnd());
            assertThat(signal.role()).isNotNull();
            assertThat(signal.signalType()).isNotNull();
            assertThat(signal.ruleCode()).isNotBlank();
            assertThat(signal.weight()).isGreaterThan(BigDecimal.ZERO);
            assertThat(signal.parseResultId()).isEqualTo(parseResult.parseResultId());
            assertThat(signal.sourceLocator()).isEqualTo("parse-result:%s:canonical-codepoint:%d-%d"
                    .formatted(parseResult.parseResultId(), signal.charStart(), signal.charEnd()));
            assertThat(signal.textHash()).isEqualTo(sha256(span));
            assertThat(signal.excerpt()).isNotBlank();
            assertThat(signal.excerpt().codePointCount(0, signal.excerpt().length())).isLessThanOrEqualTo(160);
            assertThat(signal.normalizedValue()).isNotBlank();
            assertThat(signal.extractorVersion()).isNotBlank();
        }
        assertThat(signals.stream().filter(signal -> signal.excerpt().equals("中标通知书")))
                .allMatch(signal -> signal.charStart() == 2);
    }

    @Test
    void attachmentWordAloneDoesNotCreateAnySupplementSignal() {
        List<DocumentRoleSignal> signals = extractor.extract(parsed("附件\n这里仅列出随文材料。"));

        assertThat(signals).noneMatch(signal -> signal.role() == DocumentRole.SUPPLEMENT);
    }

    @Test
    void rejectsAnythingOtherThanACompletedParsedCanonicalInput() {
        assertThatThrownBy(() -> extractor.extract(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PARSED");
        assertThatThrownBy(() -> extractor.extract(nonParsed()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PARSED");
    }

    @Test
    void repeatedAndOverlappingPhrasesProduceStableDeduplicatedSignals() {
        String text = "补充协议书\n补充协议书\n原合同编号：SYNTHETIC-001\n"
                + "经双方协商一致，对原合同作如下变更。\n补充条款\n补充条款\n"
                + "本协议与原合同约定不一致的，以本协议为准。";
        ProjectFileParseResult parseResult = parsed(text);

        List<DocumentRoleSignal> first = extractor.extract(parseResult);
        List<DocumentRoleSignal> second = extractor.extract(parseResult);

        assertThat(second).isEqualTo(first);
        assertThat(first.stream().map(signal -> signal.ruleCode() + "|" + signal.sourceLocator()).toList())
                .doesNotHaveDuplicates();
        assertThat(first).filteredOn(signal -> signal.ruleCode().equals("ROLE_SUPPLEMENT_TITLE"))
                .singleElement()
                .extracting(DocumentRoleSignal::excerpt)
                .isEqualTo("补充协议书");
        assertThat(first).filteredOn(signal -> signal.ruleCode().equals("ROLE_SUPPLEMENT_HEADING"))
                .hasSize(1);
    }

    private static Stream<Arguments> genericNamedDocuments() {
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
                        """),
                Arguments.of("附件.pdf", DocumentRole.SUPPLEMENT, """
                        补充协议
                        原合同编号：SYNTHETIC-001
                        经双方协商一致，对原合同作如下变更。
                        第一条 变更内容
                        第二条 补充条款
                        本协议与原合同约定不一致的，以本协议为准。
                        """));
    }

    private static ProjectFileParseResult parsed(String text) {
        Instant completedAt = Instant.parse("2026-09-14T02:00:00Z");
        return new ProjectFileParseResult(
                UUID.nameUUIDFromBytes(("parse:" + text).getBytes(StandardCharsets.UTF_8)),
                UUID.fromString("00000000-0000-0000-0000-000000000701"),
                UUID.fromString("00000000-0000-0000-0000-000000000702"),
                "a".repeat(64), 1, ProjectFileParseOrigin.CURRENT_PIPELINE,
                ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT,
                "synthetic-parser", "1", text, sha256(text), 1, 5L, BigDecimal.ONE,
                null, completedAt.minusSeconds(1), completedAt, 0);
    }

    private static ProjectFileParseResult nonParsed() {
        Instant completedAt = Instant.parse("2026-09-14T02:00:00Z");
        return new ProjectFileParseResult(
                UUID.fromString("00000000-0000-0000-0000-000000000703"),
                UUID.fromString("00000000-0000-0000-0000-000000000701"),
                UUID.fromString("00000000-0000-0000-0000-000000000702"),
                "a".repeat(64), 1, ProjectFileParseOrigin.CURRENT_PIPELINE,
                ProjectFileParseStatus.FAILED, ProjectFileParseMethod.PDF_TEXT,
                "synthetic-parser", "1", null, null, null, 5L, null,
                null, completedAt.minusSeconds(1), completedAt, 0);
    }

    private static String codePointSubstring(String text, int start, int end) {
        int startIndex = text.offsetByCodePoints(0, start);
        int endIndex = text.offsetByCodePoints(0, end);
        return text.substring(startIndex, endIndex);
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
