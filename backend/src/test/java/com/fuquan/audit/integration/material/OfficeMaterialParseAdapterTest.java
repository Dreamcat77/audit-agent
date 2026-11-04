package com.fuquan.audit.integration.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.material.application.port.DocumentTextPort;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import com.fuquan.audit.common.material.application.port.MaterialParsePort;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfficeMaterialParseAdapterTest {
    @Test
    void realWorkbookPreservesCoordinatesRawPrecisionFormulaCacheAndMergedRange() throws Exception {
        byte[] bytes;
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var output=new java.io.ByteArrayOutputStream()) {
            var sheet=book.createSheet("万元预算");
            sheet.createRow(0).createCell(0).setCellValue("金额单位：万元");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0,0,0,1));
            var row=sheet.createRow(2);
            row.createCell(0).setCellValue("办公终端");
            var number=row.createCell(2);number.setCellValue(123.45);number.getCTCell().setV("123456789.12345");
            var formula=row.createCell(3);formula.setCellFormula("1+2");formula.getCTCell().setV("7");
            book.createSheet("第二页").createRow(4).createCell(2).setCellValue("另一个科目");
            book.write(output);bytes=output.toByteArray();
        }
        var raw=new OfficeDocumentTextReader(in -> {throw new AssertionError("No DOCX validation for Excel");},
                request -> {throw new AssertionError("No OCR for native cells");});
        var result=new OfficeMaterialParseAdapter(raw).parse(new MaterialParsePort.ParseRequest("typed", "预算.xlsx",null,
                new ByteArrayInputStream(bytes),1000000,10,10000,Map.of()));
        assertThat(result.tables()).hasSize(2).allMatch(t -> !t.structureInferred());
        assertThat(result.cells()).filteredOn(c -> c.locator().equals("excel/sheet:1/row:3/col:3")).singleElement()
                .satisfies(c -> {assertThat(c.rawValue()).isEqualTo("123456789.12345");assertThat(c.dataType()).isEqualTo("NUMERIC");});
        assertThat(result.cells()).filteredOn(c -> "1+2".equals(c.formula())).singleElement()
                .satisfies(c -> {assertThat(c.value()).isEqualTo("3");assertThat(c.cachedValue()).isEqualTo("7");});
        assertThat(result.cells().get(0).mergedRange()).isEqualTo("A1:B1");
        assertThat(result.canonicalText()).contains("金额单位：万元","另一个科目");
        assertThat(result.pages()).isEmpty();
        assertThat(result.coverageGaps()).doesNotContain("TABLE_STRUCTURE_INFERRED_FROM_CANONICAL_SEGMENTS");
    }

    @Test
    void slideAndExcelPartsKeepRealLocationsWithoutInventingPages() throws Exception {
        var reader=mock(DocumentTextPort.class);
        when(reader.read(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new DocumentTextPort.Result(List.of(new Segment("pptx/slide:2/shape:1/part:1","标题"),
                        new Segment("pptx/slide:2/shape:2/part:2","正文")),"OFFICE_TEXT","a".repeat(64)));
        var result=new OfficeMaterialParseAdapter(reader).parse(new MaterialParsePort.ParseRequest("slides","a.pptx",null,
                new ByteArrayInputStream(new byte[]{1}),1024,10,1000,Map.of()));
        assertThat(result.pages()).singleElement().satisfies(p -> {
            assertThat(p.locator()).isEqualTo("pptx/slide:2");assertThat(p.pageNumber()).isEqualTo(2);
            assertThat(p.text()).isEqualTo("标题\n正文");
        });
    }

    @Test
    void interruptedParseStaysUnknownAndBusyOcrIsNotRelabelledAsCorruptFile() throws Exception {
        var reader=mock(DocumentTextPort.class);
        when(reader.read(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new DocumentTextPort.ReadFailure("DOCUMENT_INTERRUPTED"));
        var result=new OfficeMaterialParseAdapter(reader).parse(new MaterialParsePort.ParseRequest("unknown","a.pdf",null,
                new ByteArrayInputStream(new byte[]{1}),1024,10,1000,Map.of()));
        assertThat(result.parseStatus()).isEqualTo(MaterialParsePort.ParseStatus.UNKNOWN);
        assertThat(result.usable()).isFalse();
    }

    @Test
    void projectsCanonicalSegmentsIntoPagesParagraphsAndSpreadsheetCells() throws Exception {
        DocumentTextPort reader = mock(DocumentTextPort.class);
        when(reader.read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new DocumentTextPort.Result(List.of(
                        new Segment("pdf/page:1/text", "预算 222600"),
                        new Segment("excel/sheet:1/row:2", "办公设备 | 40 | 4200")),
                        "OFFICE_TEXT", "a".repeat(64)));

        MaterialParsePort.ParseResult result = new OfficeMaterialParseAdapter(reader).parse(new MaterialParsePort.ParseRequest(
                "doc-1", "budget.xlsx", null, new ByteArrayInputStream(new byte[] {1}), 1024, 10, 1000,
                Map.of("ocr", "disabled")));

        assertThat(result.parseStatus()).isEqualTo(MaterialParsePort.ParseStatus.PARSED);
        assertThat(result.fileSha256()).isEqualTo("a".repeat(64));
        assertThat(result.canonicalText()).contains("预算 222600", "办公设备 | 40 | 4200");
        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).pageNumber()).isEqualTo(1);
        assertThat(result.paragraphs()).hasSize(2);
        assertThat(result.tables()).singleElement().satisfies(table -> assertThat(table.structureInferred()).isTrue());
        assertThat(result.cells()).extracting(MaterialParsePort.Cell::value)
                .containsExactly("办公设备", "40", "4200");
        assertThat(result.contentSha256()).hasSize(64);
        assertThat(result.parseOptions()).containsEntry("ocr", "disabled");
    }

    @Test
    void preservesUnsupportedAndReaderFailureAsDifferentStatuses() throws Exception {
        DocumentTextPort reader = mock(DocumentTextPort.class);
        OfficeMaterialParseAdapter adapter = new OfficeMaterialParseAdapter(reader);
        MaterialParsePort.ParseRequest unsupported = new MaterialParsePort.ParseRequest(
                "doc-2", "archive.zip", null, new ByteArrayInputStream(new byte[] {1}), 1024, 10, 1000, Map.of());
        assertThat(adapter.parse(unsupported).parseStatus()).isEqualTo(MaterialParsePort.ParseStatus.UNSUPPORTED);
        when(reader.read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new DocumentTextPort.ReadFailure("DOCUMENT_SIGNATURE_INVALID"));
        MaterialParsePort.ParseResult failed = adapter.parse(new MaterialParsePort.ParseRequest(
                "doc-3", "file.pdf", null, new ByteArrayInputStream(new byte[] {1}), 1024, 10, 1000, Map.of()));
        assertThat(failed.parseStatus()).isEqualTo(MaterialParsePort.ParseStatus.PARSE_FAILED);
        assertThat(failed.errorCode()).isEqualTo("DOCUMENT_SIGNATURE_INVALID");
        assertThat(failed.usable()).isFalse();
    }
}
