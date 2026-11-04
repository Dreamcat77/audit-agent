package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser;
import com.fuquan.audit.contract.application.project.parse.ProjectPdfTextParser;
import com.fuquan.audit.procurement.application.port.ProcurementCollusionRepository;
import com.fuquan.audit.procurement.domain.ProcurementCollusionDocument;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

class ProcurementCollusionUploadCompensationTest {

    @Test
    void rejectsOversizedUploadBeforeReadingTheStream() {
        UUID projectId = UUID.randomUUID();
        ProcurementProjectQueryService projects = mock(ProcurementProjectQueryService.class);
        FileStoragePort storage = mock(FileStoragePort.class);
        when(projects.requireById(projectId)).thenReturn(mock(com.fuquan.audit.procurement.domain.ProcurementProject.class));
        ProcurementCollusionReviewService service = service(projects, storage,
                mock(ProcurementCollusionRepository.class), new ProcurementCollusionProperties(DataSize.ofBytes(2)));

        assertThatThrownBy(() -> service.uploadDocument(projectId, "供应商甲", null, null,
                "bid.docx", null, 3, new ByteArrayInputStream(new byte[] {1, 2, 3})))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class)
                .hasMessageContaining("不超过");

        verify(storage, never()).stage(any(), anyLong());
    }

    @Test
    void discardsStagedFileWhenActualSizeDoesNotMatchDeclaredSize() {
        UUID projectId = UUID.randomUUID();
        ProcurementProjectQueryService projects = mock(ProcurementProjectQueryService.class);
        FileStoragePort storage = mock(FileStoragePort.class);
        ProcurementCollusionRepository repository = mock(ProcurementCollusionRepository.class);
        StagedFile staged = new StagedFile(
                UUID.randomUUID() + ".part", 2, "a".repeat(64), new byte[] {1, 2});
        when(projects.requireById(projectId)).thenReturn(mock(com.fuquan.audit.procurement.domain.ProcurementProject.class));
        when(storage.stage(any(), anyLong())).thenReturn(staged);
        ProcurementCollusionReviewService service = service(projects, storage, repository,
                ProcurementCollusionProperties.defaults());

        assertThatThrownBy(() -> service.uploadDocument(projectId, "供应商甲", null, null,
                "bid.docx", null, 3, new ByteArrayInputStream(new byte[] {1, 2})))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class)
                .hasMessageContaining("实际大小与声明大小不一致");

        verify(storage).discard(staged);
        verify(repository, never()).saveDocument(any());
    }

    @Test
    void rejectsDatabaseColumnOverflowBeforeStaging() {
        UUID projectId = UUID.randomUUID();
        ProcurementProjectQueryService projects = mock(ProcurementProjectQueryService.class);
        FileStoragePort storage = mock(FileStoragePort.class);
        when(projects.requireById(projectId)).thenReturn(mock(com.fuquan.audit.procurement.domain.ProcurementProject.class));
        ProcurementCollusionReviewService service = service(projects, storage,
                mock(ProcurementCollusionRepository.class), ProcurementCollusionProperties.defaults());

        assertThatThrownBy(() -> service.uploadDocument(projectId, "供".repeat(501), null, null,
                "bid.docx", null, 3, new ByteArrayInputStream(new byte[] {1, 2, 3})))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class)
                .hasMessageContaining("投标人名称长度不能超过 500");

        verify(storage, never()).stage(any(), anyLong());
    }

    @Test
    void deletesPromotedObjectWhenDocumentMetadataCannotBeSaved() {
        UUID projectId = UUID.randomUUID();
        ProcurementProjectQueryService projects = mock(ProcurementProjectQueryService.class);
        FileStoragePort storage = mock(FileStoragePort.class);
        ProcurementCollusionRepository repository = mock(ProcurementCollusionRepository.class);
        ProjectDocxTextParser docxParser = mock(ProjectDocxTextParser.class);
        StagedFile staged = new StagedFile(
                UUID.randomUUID() + ".part", 3, "a".repeat(64), new byte[] {1, 2, 3});
        when(projects.requireById(projectId)).thenReturn(mock(com.fuquan.audit.procurement.domain.ProcurementProject.class));
        when(storage.stage(any(), anyLong())).thenReturn(staged);
        when(storage.open(any())).thenReturn(new StoredFileContent(
                new ByteArrayInputStream(new byte[] {1, 2, 3}), 3));
        when(docxParser.parse(any(), any())).thenReturn(new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment("docx/body/paragraph:1", "有效投标文本"))));
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).saveDocument(any(ProcurementCollusionDocument.class));
        ProcurementCollusionReviewService service = new ProcurementCollusionReviewService(
                projects, mock(ProcurementQuoteCheckService.class),
                mock(ProcurementReviewWorkflowService.class), repository, storage, docxParser,
                mock(ProjectPdfTextParser.class), Clock.systemUTC());

        assertThatThrownBy(() -> service.uploadDocument(projectId, "供应商甲", null, null,
                "bid.docx", null, 3, new ByteArrayInputStream(new byte[] {1, 2, 3})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        ArgumentCaptor<StorageKey> storageKey = ArgumentCaptor.forClass(StorageKey.class);
        verify(storage).delete(storageKey.capture());
        org.assertj.core.api.Assertions.assertThat(storageKey.getValue().value()).hasSize(64);
    }

    private static ProcurementCollusionReviewService service(ProcurementProjectQueryService projects,
            FileStoragePort storage, ProcurementCollusionRepository repository,
            ProcurementCollusionProperties properties) {
        ProcurementReviewWorkflowService workflow = mock(ProcurementReviewWorkflowService.class);
        return new ProcurementCollusionReviewService(projects, mock(ProcurementQuoteCheckService.class), workflow,
                repository, storage, mock(ProjectDocxTextParser.class), mock(ProjectPdfTextParser.class),
                Clock.systemUTC(), properties, new ProcurementCollusionTransactionCoordinator(repository, workflow));
    }
}
