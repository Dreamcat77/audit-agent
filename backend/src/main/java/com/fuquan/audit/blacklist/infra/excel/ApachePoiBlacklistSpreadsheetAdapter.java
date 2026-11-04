package com.fuquan.audit.blacklist.infra.excel;

import com.fuquan.audit.blacklist.application.port.BlacklistBatchSpreadsheetPort;
import com.fuquan.audit.blacklist.application.port.BlacklistSpreadsheetPort;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderMapping;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderPreview;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * F03 Apache POI Excel Adapter。
 */
@Component
public class ApachePoiBlacklistSpreadsheetAdapter
        implements BlacklistSpreadsheetPort, BlacklistBatchSpreadsheetPort {

    @Override
    public List<SupplierRow> readSupplierRows(InputStream inputStream) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getNumberOfSheets() == 0
                    ? null : workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IOException("Excel 没有工作表");
            }
            DataFormatter formatter = new DataFormatter();
            List<String> headers = headers(sheet.getRow(sheet.getFirstRowNum()), formatter);
            if (!containsHeader(headers, "supplierName", "供应商名称")) {
                throw new IOException("Excel 缺少必填列：供应商名称");
            }
            List<SupplierRow> rows = new ArrayList<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1;
                    rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || row.getPhysicalNumberOfCells() == 0) {
                    continue;
                }
                String supplierName = value(row, headers, formatter, "supplierName", "供应商名称");
                if (supplierName == null || supplierName.isBlank()) {
                    throw new IOException("第" + (rowIndex + 1) + "行供应商名称不能为空");
                }
                rows.add(new SupplierRow(
                        rowIndex + 1,
                        supplierName,
                        value(row, headers, formatter,
                                "supplierCode", "统一社会信用代码", "supplier_code"),
                        value(row, headers, formatter,
                                "procurementId", "采购编号", "procurement_id")));
            }
            return List.copyOf(rows);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Excel 批量检查文件解析失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public SpreadsheetHeaderPreview preview(InputStream inputStream, int maxRows) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null) throw new IOException("Excel 没有工作表");
            DataFormatter formatter = new DataFormatter();
            int first = sheet.getFirstRowNum();
            int last = Math.min(sheet.getLastRowNum(), first + Math.max(1, maxRows) - 1);
            List<SpreadsheetHeaderPreview.PreviewRow> rows = new ArrayList<>();
            for (int rowIndex = first; rowIndex <= last; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int column = 0; column < Math.max(0, row.getLastCellNum()); column++) {
                        String value = row.getCell(column) == null
                                ? "" : formatter.formatCellValue(row.getCell(column)).trim();
                        cells.add(value);
                    }
                }
                rows.add(new SpreadsheetHeaderPreview.PreviewRow(rowIndex + 1, cells));
            }
            List<SpreadsheetHeaderMapping> ruleMappings = rows.isEmpty()
                    ? List.of() : ruleMappings(rows.get(0));
            return new SpreadsheetHeaderPreview(first + 1, rows.size(), rows,
                    ruleMappings.isEmpty() ? null : rows.get(0).rowNumber(),
                    ruleMappings, false, null);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("黑名单 Excel 预览失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public List<SupplierRow> readSupplierRows(InputStream inputStream, int headerRowNumber,
                                              List<SpreadsheetHeaderMapping> mappings)
            throws IOException {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null) throw new IOException("Excel 没有工作表");
            DataFormatter formatter = new DataFormatter();
            MapFields fields = mapFields(mappings);
            if (fields.supplierColumn < 0) throw new IOException("Excel 缺少必填列：供应商名称");
            List<SupplierRow> rows = new ArrayList<>();
            for (int rowIndex = headerRowNumber; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || row.getPhysicalNumberOfCells() == 0) continue;
                String supplierName = cellValue(row, fields.supplierColumn, formatter);
                String supplierCode = cellValue(row, fields.supplierCodeColumn, formatter);
                String procurementId = cellValue(row, fields.procurementIdColumn, formatter);
                if (supplierName == null && supplierCode == null && procurementId == null) continue;
                if (supplierName == null || supplierName.isBlank()) {
                    throw new IOException("第" + (rowIndex + 1) + "行供应商名称不能为空");
                }
                rows.add(new SupplierRow(rowIndex + 1, supplierName, supplierCode, procurementId));
            }
            return List.copyOf(rows);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Excel 批量检查文件解析失败: " + exception.getMessage(), exception);
        }
    }

    private static List<SpreadsheetHeaderMapping> ruleMappings(
            SpreadsheetHeaderPreview.PreviewRow row) {
        List<SpreadsheetHeaderMapping> result = new ArrayList<>();
        for (int i = 0; i < row.cells().size(); i++) {
            String header = row.cells().get(i);
            if ("supplierName".equals(header) || "供应商名称".equals(header)) {
                result.add(new SpreadsheetHeaderMapping(header, i, "SUPPLIER_NAME", 1.0));
            } else if ("supplierCode".equals(header) || "统一社会信用代码".equals(header)
                    || "supplier_code".equals(header)) {
                result.add(new SpreadsheetHeaderMapping(header, i, "SUPPLIER_CODE", 1.0));
            } else if ("procurementId".equals(header) || "采购编号".equals(header)
                    || "procurement_id".equals(header)) {
                result.add(new SpreadsheetHeaderMapping(header, i, "PROCUREMENT_ID", 1.0));
            }
        }
        return result.stream().anyMatch(item -> "SUPPLIER_NAME".equals(item.standardField()))
                ? List.copyOf(result) : List.of();
    }

    private static MapFields mapFields(List<SpreadsheetHeaderMapping> mappings) {
        int supplier = -1, code = -1, procurement = -1;
        for (SpreadsheetHeaderMapping mapping : mappings) {
            if ("SUPPLIER_NAME".equals(mapping.standardField())) supplier = mapping.sourceColumnIndex();
            if ("SUPPLIER_CODE".equals(mapping.standardField())) code = mapping.sourceColumnIndex();
            if ("PROCUREMENT_ID".equals(mapping.standardField())) procurement = mapping.sourceColumnIndex();
        }
        return new MapFields(supplier, code, procurement);
    }

    private record MapFields(int supplierColumn, int supplierCodeColumn, int procurementIdColumn) {}

    @Override
    public List<ImportRow> readImport(InputStream inputStream) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getNumberOfSheets() == 0
                    ? null : workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IOException("Excel 没有工作表");
            }
            DataFormatter formatter = new DataFormatter();
            List<String> headers = headers(sheet.getRow(sheet.getFirstRowNum()), formatter);
            List<ImportRow> rows = new ArrayList<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1;
                    rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || row.getPhysicalNumberOfCells() == 0) {
                    continue;
                }
                rows.add(new ImportRow(
                        rowIndex + 1,
                        value(row, headers, formatter, "supplierName", "供应商名称"),
                        value(row, headers, formatter,
                                "supplierCode", "统一社会信用代码", "supplier_code"),
                        value(row, headers, formatter, "blacklistType", "黑名单类型"),
                        value(row, headers, formatter, "reason", "列入原因"),
                        value(row, headers, formatter, "source", "数据来源"),
                        value(row, headers, formatter, "effectiveDate", "生效日期")));
            }
            return List.copyOf(rows);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Excel 导入失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public byte[] exportEntries(List<BlacklistSupplier> entries) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("黑名单");
            writeHeader(sheet, new String[] {"供应商名称", "统一社会信用代码", "黑名单类型", "列入原因",
                    "数据来源", "有效性", "状态", "录入人", "创建时间"});
            for (int i = 0; i < entries.size(); i++) {
                BlacklistSupplier entry = entries.get(i);
                writeValues(sheet.createRow(i + 1), new String[] {
                        entry.supplierName(), entry.supplierCode(), entry.blacklistType(),
                        entry.reason(), entry.source(), "长期有效", entry.status(),
                        entry.createdBy(), entry.createdTime().toString()
                });
            }
            return bytes(workbook);
        }
    }

    @Override
    public byte[] exportAlerts(List<BlacklistAlert> alerts) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("报警记录");
            writeHeader(sheet, new String[] {"批次ID", "行号", "供应商名称", "统一社会信用代码",
                    "黑名单类型", "匹配方式", "匹配分", "报警级别", "处理状态", "创建时间"});
            for (int i = 0; i < alerts.size(); i++) {
                BlacklistAlert alert = alerts.get(i);
                writeValues(sheet.createRow(i + 1), new String[] {
                        alert.batchId(),
                        alert.rowNumber() == null ? "" : String.valueOf(alert.rowNumber()),
                        alert.supplierName(), alert.supplierCode(), alert.blacklistType(),
                        alert.matchType(), String.valueOf(alert.matchScore()), alert.alertLevel(),
                        alert.status(), alert.createdTime().toString()
                });
            }
            return bytes(workbook);
        }
    }

    private static List<String> headers(Row row, DataFormatter formatter) {
        List<String> headers = new ArrayList<>();
        if (row == null) {
            return headers;
        }
        for (Cell cell : row) {
            headers.add(formatter.formatCellValue(cell).trim());
        }
        return headers;
    }

    private static String value(
            Row row, List<String> headers, DataFormatter formatter, String... names) {
        for (String name : names) {
            int index = headers.indexOf(name);
            if (index >= 0) {
                return cellValue(row.getCell(index), formatter);
            }
        }
        return null;
    }

    private static boolean containsHeader(List<String> headers, String... names) {
        for (String name : names) {
            if (headers.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static String cellValue(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC
                && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        String value = formatter.formatCellValue(cell).trim();
        return value.isBlank() ? null : value;
    }

    private static String cellValue(Row row, int columnIndex, DataFormatter formatter) {
        return columnIndex < 0 ? null : cellValue(row.getCell(columnIndex), formatter);
    }

    private static void writeHeader(Sheet sheet, String[] headers) {
        Row row = sheet.createRow(0);
        writeValues(row, headers);
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private static void writeValues(Row row, String[] values) {
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
        }
    }

    private static byte[] bytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }
}
