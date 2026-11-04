package com.fuquan.audit.blacklist.infra.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderMapping;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ApachePoiBlacklistSpreadsheetAdapterTest {

    private final ApachePoiBlacklistSpreadsheetAdapter adapter =
            new ApachePoiBlacklistSpreadsheetAdapter();

    @Test
    void readsBatchSupplierColumnsAndKeepsExcelRowNumber() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("询比价");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("供应商名称");
            header.createCell(1).setCellValue("统一社会信用代码");
            header.createCell(2).setCellValue("采购编号");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("某供应商");
            row.createCell(1).setCellValue("CODE-1");
            row.createCell(2).setCellValue("P-1");

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);

            var rows = adapter.readSupplierRows(new ByteArrayInputStream(output.toByteArray()));

            assertThat(rows).singleElement().satisfies(item -> {
                assertThat(item.rowNumber()).isEqualTo(2);
                assertThat(item.supplierName()).isEqualTo("某供应商");
                assertThat(item.supplierCode()).isEqualTo("CODE-1");
                assertThat(item.procurementId()).isEqualTo("P-1");
            });
        }
    }

    @Test
    void rejectsWorkbookWithoutSupplierNameColumn() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("询比价").createRow(0).createCell(0).setCellValue("采购编号");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);

            assertThatThrownBy(() -> adapter.readSupplierRows(
                    new ByteArrayInputStream(output.toByteArray())))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("供应商名称");
        }
    }

    @Test
    void readsOnlyMappedSupplierNameAndSkipsUnmappedColumns() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("询比价");
            var header = sheet.createRow(1);
            header.createCell(0).setCellValue("公司名");
            header.createCell(1).setCellValue("价格");
            header.createCell(2).setCellValue("商品说明");
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("某供应商");
            row.createCell(1).setCellValue("95000");
            row.createCell(2).setCellValue("办公设备");

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);

            var rows = adapter.readSupplierRows(
                    new ByteArrayInputStream(output.toByteArray()),
                    2,
                    List.of(new SpreadsheetHeaderMapping(
                            "公司名", 0, "SUPPLIER_NAME", 0.95)));

            assertThat(rows).singleElement().satisfies(item -> {
                assertThat(item.rowNumber()).isEqualTo(3);
                assertThat(item.supplierName()).isEqualTo("某供应商");
                assertThat(item.supplierCode()).isNull();
                assertThat(item.procurementId()).isNull();
            });
        }
    }
}
