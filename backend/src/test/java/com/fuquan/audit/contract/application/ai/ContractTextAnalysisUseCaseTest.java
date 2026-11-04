package com.fuquan.audit.contract.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisRequest;
import com.fuquan.audit.contract.application.project.ProjectApplicationService;
import com.fuquan.audit.contract.application.project.ProjectParsedTextReadService;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ContractTextAnalysisUseCaseTest {

    @Test
    void sendsOnlySelectedMaterialsInCallerOrder() {
        UUID projectId = UUID.randomUUID();
        ProjectFile first = file(projectId, "contract.pdf", ProjectFileCategory.CONTRACT_BODY,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED);
        ProjectFile second = file(projectId, "award.jpg", ProjectFileCategory.AWARD_NOTICE,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED);
        ProjectFile unselected = file(projectId, "tender.pdf", ProjectFileCategory.TENDER_DOCUMENT,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ProjectParsedTextReadService parsedTexts = mock(ProjectParsedTextReadService.class);
        RecordingPort port = new RecordingPort();
        when(projects.files(projectId)).thenReturn(List.of(unselected, second, first));
        when(parsedTexts.read(first)).thenReturn("合同金额：407000.00元，交付期为20个工作日");
        when(parsedTexts.read(second)).thenReturn("中标范围说明");

        ContractTextAnalysisResult result = useCase(projects, parsedTexts, port)
                .analyze(projectId, List.of(first.id(), second.id()));

        assertThat(result.available()).isTrue();
        assertThat(port.request.projectId()).isEqualTo(projectId);
        assertThat(port.request.materials()).extracting(
                        material -> material.fileId(),
                        material -> material.category(),
                        material -> material.text())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(first.id(), "CONTRACT_BODY",
                                "[确定性数值字段已移除]，交付期为20个工作日"),
                        org.assertj.core.groups.Tuple.tuple(second.id(), "AWARD_NOTICE", "中标范围说明"));
        verify(parsedTexts).read(first);
        verify(parsedTexts).read(second);
        verify(parsedTexts, never()).read(unselected);
    }

    @Test
    void sendsTableMaterialsWithoutMonetaryHeadersOrCells() {
        UUID projectId = UUID.randomUUID();
        ProjectFile contract = file(projectId, "contract.docx", ProjectFileCategory.CONTRACT_BODY,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ProjectParsedTextReadService parsedTexts = mock(ProjectParsedTextReadService.class);
        RecordingPort port = new RecordingPort();
        when(projects.files(projectId)).thenReturn(List.of(contract));
        when(parsedTexts.read(contract)).thenReturn("""
                项目编号：CG-2026-002
                合同编号：HT-CG-2026-002
                统一社会信用代码：91310000TEST00001X
                交付期限：20个工作日
                质保期：36个月
                服务时间：5×8小时
                全天支持：7×24小时
                数量：100
                预算口径12.5万元
                参考报价：￥5,000/台、5,000.00元/台
                标识
                含税单价（元）
                税率
                申报金额（元）
                备注
                DESKTOP-01
                5000.00
                13%
                490000.00
                台式计算机
                """);

        useCase(projects, parsedTexts, port).analyze(projectId, List.of(contract.id()));

        String sent = port.request.materials().get(0).text();
        assertThat(sent).contains("CG-2026-002", "HT-CG-2026-002", "91310000TEST00001X",
                "20个工作日", "36个月", "5×8小时", "7×24小时", "数量：100",
                "DESKTOP-01", "13%", "台式计算机");
        assertThat(sent).doesNotContain("12.5万元", "￥5,000/台", "5,000.00元/台",
                "5000.00", "490000.00", "含税单价（元）", "申报金额（元）");
    }

    @Test
    void sendsClauseRatesAndThresholdsButNeverTheAbsoluteContractAmount() {
        UUID projectId = UUID.randomUUID();
        ProjectFile contract = file(projectId, "contract.docx", ProjectFileCategory.CONTRACT_BODY,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ProjectParsedTextReadService parsedTexts = mock(ProjectParsedTextReadService.class);
        RecordingPort port = new RecordingPort();
        when(projects.files(projectId)).thenReturn(List.of(contract));
        when(parsedTexts.read(contract)).thenReturn("""
                合同金额：67000元。
                每延期一周按合同金额1%支付违约金；累计最高不超过合同金额20%。
                延期超过1个月可解除。质保期3年，故障响应48小时。
                """);

        useCase(projects, parsedTexts, port).analyze(projectId, List.of(contract.id()));

        String sent = port.request.materials().get(0).text();
        assertThat(sent).doesNotContain("67000");
        assertThat(sent).contains("合同金额1%", "合同金额20%", "1个月", "3年", "48小时");
    }

    @Test
    void redactsOnlyTheOutboundCopyAndLeavesTheParsedSourceUnchanged() throws Exception {
        UUID projectId = UUID.randomUUID();
        ProjectFile contract = file(projectId, "contract.docx", ProjectFileCategory.CONTRACT_BODY,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ProjectParsedTextReadService parsedTexts = mock(ProjectParsedTextReadService.class);
        RecordingPort port = new RecordingPort();
        String rawParsedText = """
                人民币【 67000.00】元（大写金额：【陆万柒仟】元整）
                逾期按合同金额1%/周承担责任，特定责任为20%，维护费为6%/年。
                """;
        String rawHashBefore = sha256(rawParsedText);
        when(projects.files(projectId)).thenReturn(List.of(contract));
        when(parsedTexts.read(contract)).thenReturn(rawParsedText);

        useCase(projects, parsedTexts, port).analyze(projectId, List.of(contract.id()));

        String outboundText = port.request.materials().get(0).text();
        assertThat(sha256(outboundText)).isNotEqualTo(rawHashBefore);
        assertThat(sha256(rawParsedText)).isEqualTo(rawHashBefore);
        assertThat(rawParsedText).contains("67000.00", "陆万柒仟");
        assertThat(outboundText)
                .doesNotContain("67000.00", "陆万柒仟")
                .contains("合同金额1%/周", "20%", "6%/年");
        assertThat(contract.id()).isEqualTo(port.request.materials().get(0).fileId());
        verify(parsedTexts).read(contract);
    }

    @Test
    void validatesEvidenceLocatorAgainstRealParsedTextWithNfkcAndWhitespaceNormalization() {
        UUID projectId = UUID.randomUUID();
        ProjectFile contract = file(projectId, "contract.docx", ProjectFileCategory.CONTRACT_BODY,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ProjectParsedTextReadService parsedTexts = mock(ProjectParsedTextReadService.class);
        RecordingPort port = new RecordingPort();
        when(projects.files(projectId)).thenReturn(List.of(contract));
        when(parsedTexts.read(contract)).thenReturn("中标供应商：　测试科技有限公司\n交付期二十日");
        var modelCall = new ContractTextAnalysisResult.ModelCall(
                1, ContractTextAnalysisResult.CallPhase.TRANSPORT,
                ContractTextAnalysisResult.CallOutcome.SUCCEEDED, null, 5L, "STOP",
                10, 5, 15, "OPENAI_COMPATIBLE", "qwen-test");
        port.result = new ContractTextAnalysisResult(true, List.of("摘要"), List.of(
                new ContractTextAnalysisResult.Hint(
                        ContractTextAnalysisResult.SemanticCategory.SUPPLIER_IDENTITY,
                        ContractTextAnalysisResult.Attention.REVIEW,
                        "供应商表述", "建议复核供应商名称", List.of(contract.id()), List.of(
                        new ContractTextAnalysisResult.EvidenceItem(
                                contract.id(), ContractTextAnalysisResult.EvidenceStatus.REVIEW,
                                "中标供应商: 测试科技有限公司", false)))),
                "qwen-test", 5L, List.of(modelCall));

        ContractTextAnalysisResult result = useCase(projects, parsedTexts, port)
                .analyze(projectId, List.of(contract.id()));

        assertThat(result.hints()).singleElement().satisfies(hint ->
                assertThat(hint.evidenceItems()).singleElement().satisfies(item -> {
                    assertThat(item.fileId()).isEqualTo(contract.id());
                    assertThat(item.locatorAvailable()).isTrue();
                }));
        assertThat(result.modelCalls()).containsExactly(modelCall);
    }

    @Test
    void rejectsEmptyDuplicateNullAndOversizedSelectionsBeforeReadingFiles() {
        UUID projectId = UUID.randomUUID();
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ProjectParsedTextReadService parsedTexts = mock(ProjectParsedTextReadService.class);
        RecordingPort port = new RecordingPort();
        ContractTextAnalysisUseCase useCase = useCase(projects, parsedTexts, port);
        UUID duplicate = UUID.randomUUID();
        List<UUID> oversized = IntStream.range(0, 51).mapToObj(ignored -> UUID.randomUUID()).toList();

        List<List<UUID>> invalidSelections = List.of(List.of(), List.of(duplicate, duplicate), oversized);
        for (List<UUID> selection : invalidSelections) {
            assertBusinessError(() -> useCase.analyze(projectId, selection), BusinessErrorCode.INVALID_ARGUMENT);
        }
        assertBusinessError(() -> useCase.analyze(projectId, null), BusinessErrorCode.INVALID_ARGUMENT);
        assertBusinessError(() -> useCase.analyze(projectId, java.util.Arrays.asList(UUID.randomUUID(), null)),
                BusinessErrorCode.INVALID_ARGUMENT);
        verify(projects, never()).files(projectId);
        assertThat(port.request).isNull();
    }

    @Test
    void rejectsMissingForeignPackageUnavailableNonParsedAndBlankSelectedMaterials() {
        UUID projectId = UUID.randomUUID();
        assertMissingRejected(projectId);
        assertSelectedRejected(projectId, file(UUID.randomUUID(), "foreign.pdf", ProjectFileCategory.CONTRACT_BODY,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED), "text",
                BusinessErrorCode.CONFLICT);
        assertSelectedRejected(projectId, file(projectId, "package.zip", ProjectFileCategory.PACKAGE,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED), "text",
                BusinessErrorCode.BUSINESS_VALIDATION_FAILED);
        assertSelectedRejected(projectId, file(projectId, "pending.pdf", ProjectFileCategory.SUPPLEMENT,
                ProjectFileIngestionStatus.UPLOAD_PENDING, ProjectFileParseStatus.PENDING), null,
                BusinessErrorCode.BUSINESS_VALIDATION_FAILED);
        assertSelectedRejected(projectId, file(projectId, "failed.pdf", ProjectFileCategory.SUPPLEMENT,
                ProjectFileIngestionStatus.UPLOAD_FAILED, ProjectFileParseStatus.FAILED), null,
                BusinessErrorCode.BUSINESS_VALIDATION_FAILED);
        assertSelectedRejected(projectId, file(projectId, "unsupported.pdf", ProjectFileCategory.SUPPLEMENT,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.UNSUPPORTED), null,
                BusinessErrorCode.BUSINESS_VALIDATION_FAILED);
        assertSelectedRejected(projectId, file(projectId, "blank.pdf", ProjectFileCategory.SUPPLEMENT,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED), "  \n",
                BusinessErrorCode.BUSINESS_VALIDATION_FAILED);
    }

    private static ContractTextAnalysisUseCase useCase(ProjectApplicationService projects,
                                                       ProjectParsedTextReadService parsedTexts,
                                                       RecordingPort port) {
        return new ContractTextAnalysisUseCase(projects, parsedTexts, port, new ContractAiTextRedactor());
    }

    private static void assertMissingRejected(UUID projectId) {
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ProjectParsedTextReadService parsedTexts = mock(ProjectParsedTextReadService.class);
        RecordingPort port = new RecordingPort();
        when(projects.files(projectId)).thenReturn(List.of());
        assertBusinessError(() -> useCase(projects, parsedTexts, port)
                .analyze(projectId, List.of(UUID.randomUUID())), BusinessErrorCode.BUSINESS_VALIDATION_FAILED);
        assertThat(port.request).isNull();
    }

    private static void assertSelectedRejected(UUID projectId, ProjectFile file, String text,
                                               BusinessErrorCode expectedCode) {
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ProjectParsedTextReadService parsedTexts = mock(ProjectParsedTextReadService.class);
        RecordingPort port = new RecordingPort();
        when(projects.files(projectId)).thenReturn(List.of(file));
        if (text != null) {
            when(parsedTexts.read(file)).thenReturn(text);
        }
        assertBusinessError(() -> useCase(projects, parsedTexts, port)
                .analyze(projectId, List.of(file.id())), expectedCode);
        assertThat(port.request).isNull();
    }

    private static void assertBusinessError(Runnable operation, BusinessErrorCode expectedCode) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(expectedCode));
    }

    private static ProjectFile file(UUID projectId, String name, ProjectFileCategory category,
                                    ProjectFileIngestionStatus ingestionStatus,
                                    ProjectFileParseStatus parseStatus) {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        return new ProjectFile(UUID.randomUUID(), projectId, null, name, name,
                name.endsWith(".zip") ? "application/zip" : "application/pdf", category,
                ingestionStatus, parseStatus, "a".repeat(64), "b".repeat(64),
                10L, parseStatus == ProjectFileParseStatus.PARSED ? "persisted" : null,
                null, now, now);
    }

    private static final class RecordingPort implements ContractTextAnalysisPort {
        private ContractTextAnalysisRequest request;
        private ContractTextAnalysisResult result = new ContractTextAnalysisResult(
                true, List.of("摘要"), List.of(), "qwen-test", 5L);

        @Override
        public ContractTextAnalysisResult analyzeContractText(ContractTextAnalysisRequest request) {
            this.request = request;
            return result;
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        value.getBytes(StandardCharsets.UTF_8)));
    }
}
