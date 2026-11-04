package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.procurement.application.port.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ProcurementReviewMaterialServiceTest {
    private final UUID id = UUID.randomUUID();
    private final ProcurementTenderDocumentService documents = mock(ProcurementTenderDocumentService.class);
    private final ProcurementTenderRuleScanService scans = mock(ProcurementTenderRuleScanService.class);
    private final ProcurementQuoteSpreadsheetPort spreadsheet = mock(ProcurementQuoteSpreadsheetPort.class);
    private final ProcurementQuoteCheckService quotes = mock(ProcurementQuoteCheckService.class);
    private final ProcurementProjectQueryService projects = mock(ProcurementProjectQueryService.class);
    private final ProcurementReviewMaterialService service = new ProcurementReviewMaterialService(documents, scans, spreadsheet, quotes, projects);
    private static InputStream input() { return new ByteArrayInputStream(new byte[]{1, 2, 3}); }
    private static SpreadsheetHeaderPreview preview(String... fields) {
        return new SpreadsheetHeaderPreview(1, 1, List.of(), 1,
                Arrays.stream(fields).map(f -> new SpreadsheetHeaderMapping(f, 0, f, 1.0)).toList(), false, null);
    }

    @Test void recognizedExcelAutomaticallyAnalyzesWithoutTenderScan() throws Exception {
        when(spreadsheet.preview(any(), eq(10))).thenReturn(preview("QUOTE_MATRIX"));
        assertThat(service.upload(id, "报价.xlsx", null, input(), "actor").status()).isEqualTo("QUOTE_ANALYZED");
        verify(quotes).upload(eq(id.toString()), any());
        verifyNoInteractions(documents, scans);
    }
    @Test void ordinarySpreadsheetDoesNotForceOrInventQuotationAnalysis() throws Exception {
        when(spreadsheet.preview(any(), eq(10))).thenReturn(preview("SUPPLIER_NAME"));
        assertThat(service.upload(id, "项目清单.xlsx", null, input(), "actor").status()).isEqualTo("NOT_APPLICABLE");
        verifyNoInteractions(quotes, documents, scans);
    }
    @Test void corruptRecognizedQuotationFailsInsteadOfLookingLikeAbsentInformation() throws Exception {
        when(spreadsheet.preview(any(), eq(10))).thenReturn(preview("SUPPLIER_NAME", "QUOTE_AMOUNT"));
        when(quotes.upload(anyString(), any())).thenThrow(new IOException("金额不完整"));
        assertThatThrownBy(() -> service.upload(id, "报价.xls", null, input(), "actor")).isInstanceOf(IOException.class);
    }
    @Test void wordIsReviewedWithoutNeedingQuotations() throws Exception {
        var doc = new ProcurementTenderDocumentService.TenderDocumentResult(UUID.randomUUID(), id, "材料.docx", "docx",
                "application/octet-stream", 3, "hash", "AVAILABLE", "DOCX_TEXT", "POI", 1.0, null, null, null,
                java.time.Instant.now(), java.time.Instant.now());
        when(documents.upload(eq(id), eq("材料.docx"), isNull(), any(), eq(false), eq("actor"))).thenReturn(doc);
        var scan = new ProcurementTenderRuleScanService.ScanResult(id, doc.id(), 0, List.of(), ProcurementTenderRuleScanService.AiStatus.AVAILABLE);
        when(scans.scan(id, doc.id())).thenReturn(scan);
        var result = service.upload(id, "材料.docx", null, input(), "actor");
        assertThat(result.scan()).isEqualTo(scan);
        assertThat(result.status()).isEqualTo("DOCUMENT_REVIEWED");
        verifyNoInteractions(spreadsheet, quotes);
    }
    @Test void boundedSlotsFailFastAndAreReleasedAfterReadErrors() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        when(spreadsheet.preview(any(), eq(10))).thenAnswer(a -> {
            entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout");
            throw new IOException("bad workbook");
        });
        var pool = Executors.newFixedThreadPool(2);
        Callable<Boolean> action = () -> { try { service.upload(id, "材料.xlsx", null, input(), "actor"); return false; }
                catch (IOException ex) { return true; } };
        try {
            var a = pool.submit(action); var b = pool.submit(action);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.upload(id, "材料.xlsx", null, input(), "actor")).hasMessageContaining("处理中");
            release.countDown(); assertThat(a.get(5, TimeUnit.SECONDS)).isTrue(); assertThat(b.get(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.upload(id, "材料.xlsx", null, input(), "actor")).isInstanceOf(IOException.class);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
