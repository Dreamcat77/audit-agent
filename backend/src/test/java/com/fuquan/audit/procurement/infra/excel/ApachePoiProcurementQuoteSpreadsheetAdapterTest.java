package com.fuquan.audit.procurement.infra.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ApachePoiProcurementQuoteSpreadsheetAdapterTest {

    private final ApachePoiProcurementQuoteSpreadsheetAdapter adapter =
            new ApachePoiProcurementQuoteSpreadsheetAdapter();

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;

    @Test void eightyMegabyteUploadedSpreadsheetCanActuallyBeReadAndAnalyzed() throws Exception {
        byte[] base=workbook(new String[]{"供应商名称","报价金额"},new String[][]{{"供应商甲","壹万元整"}});
        var path=temporary.resolve("large.xlsx");
        try(var out=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(path));
                var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(base))) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if(entry.getName().equals("[Content_Types].xml")) {
                    String types=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    out.write(types.replace("</Types>","<Default Extension=\"bin\" ContentType=\"application/octet-stream\"/></Types>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else zip.transferTo(out);
                out.closeEntry();
            }
            // Valid stored media/padding must not be confused with a zip bomb.
            int bytes=80*1024*1024;byte[] buffer=new byte[65536];new java.util.Random(42).nextBytes(buffer);
            var crc=new java.util.zip.CRC32();for(int i=0;i<bytes/buffer.length;i++)crc.update(buffer);
            var padding=new java.util.zip.ZipEntry("synthetic-padding.bin");padding.setMethod(java.util.zip.ZipEntry.STORED);
            padding.setSize(bytes);padding.setCompressedSize(bytes);padding.setCrc(crc.getValue());out.putNextEntry(padding);
            for(int i=0;i<bytes/buffer.length;i++)out.write(buffer);out.closeEntry();
        }
        assertThat(java.nio.file.Files.size(path)).isGreaterThan(80L*1024*1024);
        try(var input=java.nio.file.Files.newInputStream(path)) { assertThat(adapter.preview(input,10).mappings()).isNotEmpty(); }
        try(var input=java.nio.file.Files.newInputStream(path)) { assertThat(adapter.read(input).rows().get(0).quoteAmount()).isEqualByComparingTo("10000"); }
    }

    @Test void traversalAndCompressedBombStillFailClosedAfterIncreasingFileLimits() throws Exception {
        for(String name:new String[]{"../outside.xml","bomb.xml"}) {
            var path=temporary.resolve(name.startsWith("..")?"traversal.xlsx":"bomb.xlsx");
            try(var out=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(path))) {
                out.putNextEntry(new java.util.zip.ZipEntry(name));
                out.write(new byte[2*1024*1024]);out.closeEntry();
            }
            try(var input=java.nio.file.Files.newInputStream(path)) {
                assertThatThrownBy(()->adapter.preview(input,10)).isInstanceOf(java.io.IOException.class);
            }
        }
    }

    @Test
    void readsChineseHeadersBudgetAndActualExcelRows() throws Exception {
        byte[] workbook = workbook(new String[] {"供应商名称", "报价金额", "产品描述", "预算金额"},
                new String[][] {{"供应商A", "1,100.129", "办公电脑", "1000"},
                        {"供应商B", "900", "办公电脑", ""}, {"", "", "", ""}});

        var parsed = adapter.read(new java.io.ByteArrayInputStream(workbook));

        assertThat(parsed.budgetAmount()).isEqualByComparingTo("1000");
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).rowNumber()).isEqualTo(2);
        assertThat(parsed.rows().get(0).quoteAmount()).isEqualByComparingTo("1100.129");
        assertThat(parsed.rows().get(1).supplierName()).isEqualTo("供应商B");
    }

    @Test
    void rejectsMissingRequiredColumnsAndInvalidAmount() throws Exception {
        byte[] missingQuote = workbook(new String[] {"供应商名称"}, new String[][] {{"供应商A"}});
        assertThatThrownBy(() -> adapter.read(new java.io.ByteArrayInputStream(missingQuote)))
                .hasMessageContaining("报价金额");

        byte[] invalid = workbook(new String[] {"供应商名称", "报价金额"},
                new String[][] {{"供应商A", "10abc"}});
        assertThatThrownBy(() -> adapter.read(new java.io.ByteArrayInputStream(invalid)))
                .hasMessageContaining("金额格式错误");
    }

    private static byte[] workbook(String[] headers, String[][] rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("询比价");
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                for (int columnIndex = 0; columnIndex < rows[rowIndex].length; columnIndex++) {
                    row.createCell(columnIndex).setCellValue(rows[rowIndex][columnIndex]);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
