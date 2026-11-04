package com.fuquan.audit.integration.material;

import static org.assertj.core.api.Assertions.assertThat;
import com.fuquan.audit.common.material.application.port.MaterialParsePort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class WordTableStructureTest {
    @Test
    void refusesAnOversizedCellMatrixInsteadOfReturningTruncatedStructure() {
        var table=org.mockito.Mockito.mock(org.apache.poi.xwpf.usermodel.XWPFTable.class);
        var row=org.mockito.Mockito.mock(org.apache.poi.xwpf.usermodel.XWPFTableRow.class);
        var cell=org.mockito.Mockito.mock(org.apache.poi.xwpf.usermodel.XWPFTableCell.class);
        org.mockito.Mockito.when(table.getRows()).thenReturn(java.util.List.of(row));
        org.mockito.Mockito.when(row.getTableCells()).thenReturn(java.util.Collections.nCopies(100001,cell));
        org.mockito.Mockito.when(cell.getText()).thenReturn("value");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> WordTableStructure.parse(table,"docx/table:1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("WORD_TABLE_CELL_LIMIT");
    }

    @Test
    void keepsEmptyCellsMultilineValuesAndPhysicalRowBoundaries() throws Exception {
        try (var document = new XWPFDocument()) {
            var table = document.createTable(2, 3);
            table.getRow(0).getCell(0).setText("项目");
            table.getRow(0).getCell(2).setText("金额（万元）");
            var cell = table.getRow(1).getCell(0);
            var run=cell.getParagraphs().get(0).createRun();
            run.setText("主项"); run.addBreak(); run.setText("附件");
            table.getRow(1).getCell(2).getParagraphs().get(0).createRun().setText("1.2300");
            var result = WordTableStructure.parse(table,"docx/table:1");
            assertThat(result.structureInferred()).isFalse();
            assertThat(result.cells()).hasSize(6);
            assertThat(result.cells().get(1).value()).isEmpty();
            assertThat(result.cells().get(3).value()).isEqualTo("主项\n附件");
            assertThat(result.cells().get(5).locator()).isEqualTo("docx/table:1/row:2/cell:3");
            assertThat(WordTableStructure.canonicalRow(result.cells().subList(3,6)))
                    .isEqualTo("主项\u2028附件\t\t1.2300");
        }
    }

    @Test
    void officeAdapterExposesNativeWordCellsWithoutRewritingLegacyTextLocatorsOrOcr() throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("原生Word采购附件");
            var table = document.createTable(2,2);
            table.getRow(0).getCell(0).setText("设备");table.getRow(0).getCell(1).setText("金额");
            table.getRow(1).getCell(0).setText("打印机");table.getRow(1).getCell(1).setText("123.45");
            document.write(output); bytes=output.toByteArray();
        }
        var raw = new OfficeDocumentTextReader(input -> bytes,
                request -> {throw new AssertionError("Native Word must not invoke OCR");});
        var result = new OfficeMaterialParseAdapter(raw).parse(new MaterialParsePort.ParseRequest(
                "native-word","附件.docx",null,new ByteArrayInputStream(bytes),1000000,10,10000,Map.of()));
        assertThat(result.parseStatus()).isEqualTo(MaterialParsePort.ParseStatus.PARSED);
        assertThat(result.canonicalText()).contains("原生Word采购附件","打印机","123.45");
        assertThat(result.paragraphs()).anyMatch(p -> p.locator().startsWith("docx/body/node:"));
        assertThat(result.tables()).singleElement().satisfies(table -> {
            assertThat(table.structureInferred()).isFalse();
            assertThat(table.cells().get(3).value()).isEqualTo("123.45");
            assertThat(table.cells().get(3).rowNumber()).isEqualTo(2);
            assertThat(table.cells().get(3).columnNumber()).isEqualTo(2);
        });
        assertThat(result.ocrProvenance()).isEmpty();
        assertThat(result.pages()).isEmpty();
        assertThat(result.coverageGaps()).contains("WORD_MERGE_GRID_AND_NESTED_TABLE_GEOMETRY_NOT_EXPOSED");
    }
}
