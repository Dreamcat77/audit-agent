package com.fuquan.audit.procurement.infra.excel;

import com.fuquan.audit.procurement.application.port.ProcurementQuoteSpreadsheetPort;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderMapping;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderPreview;
import com.fuquan.audit.common.material.application.RepeatableMaterialInput;
import com.fuquan.audit.common.material.infra.SpreadsheetSafety;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/** F06 Apache POI Excel 输入适配器。 */
@Component
public class ApachePoiProcurementQuoteSpreadsheetAdapter
        implements ProcurementQuoteSpreadsheetPort {
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(2);

    private static final List<String> SUPPLIER_HEADERS = List.of("供应商名称", "supplierName");
    private static final List<String> QUOTE_HEADERS = List.of("报价金额", "报价", "quoteAmount");
    private static final List<String> PRODUCT_HEADERS = List.of("产品描述", "产品说明", "productDescription");
    private static final List<String> BUDGET_HEADERS = List.of("预算金额", "预算价", "预算总额", "budgetAmount");

    @Override
    public ParsedSheet read(InputStream inputStream) throws IOException {
        acquire();
        try (var source = RepeatableMaterialInput.copy(inputStream); Workbook workbook = open(source)) {
            return readWorkbook(workbook, null, List.of());
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("询比价 Excel 解析失败: " + exception.getMessage(), exception);
        } finally { capacity.release(); }
    }

    @Override
    public SpreadsheetHeaderPreview preview(InputStream inputStream, int maxRows) throws IOException {
        acquire();
        try (var source = RepeatableMaterialInput.copy(inputStream); Workbook workbook = open(source)) {
            if (workbook.getNumberOfSheets() == 0) throw new IOException("Excel 没有工作表");
            Sheet sheet = workbook.getSheetAt(0);
            int matrixHeader = ProcurementQuoteMatrixReader.header(sheet);
            DataFormatter formatter = new DataFormatter();
            int first = sheet.getFirstRowNum();
            int last = Math.min(sheet.getLastRowNum(), first + Math.max(1, maxRows) - 1);
            List<SpreadsheetHeaderPreview.PreviewRow> rows = new ArrayList<>();
            for (int rowIndex = first; rowIndex <= last; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int column = 0; column < Math.max(0, row.getLastCellNum()); column++) {
                        cells.add(value(row, column, formatter));
                    }
                }
                rows.add(new SpreadsheetHeaderPreview.PreviewRow(rowIndex + 1, cells));
            }
            List<SpreadsheetHeaderMapping> ruleMappings = matrixHeader >= 0
                    ? List.of(new SpreadsheetHeaderMapping("三方比价横向报价", 0, "QUOTE_MATRIX", 1.0)) : rows.isEmpty()
                    ? List.of() : ruleMappings(rows.get(0));
            return new SpreadsheetHeaderPreview(first + 1, rows.size(), rows,
                    ruleMappings.isEmpty() ? null : matrixHeader >= 0 ? matrixHeader + 1 : rows.get(0).rowNumber(),
                    ruleMappings, false, null);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("询比价 Excel 预览失败: " + exception.getMessage(), exception);
        } finally { capacity.release(); }
    }

    @Override
    public ParsedSheet read(InputStream inputStream, int headerRowNumber,
                            List<SpreadsheetHeaderMapping> mappings) throws IOException {
        acquire();
        try (var source = RepeatableMaterialInput.copy(inputStream); Workbook workbook = open(source)) {
            return readWorkbook(workbook, headerRowNumber, mappings);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("询比价 Excel 解析失败: " + exception.getMessage(), exception);
        } finally { capacity.release(); }
    }

    private void acquire() throws IOException {
        if (!capacity.tryAcquire()) throw new IOException("询比价读取繁忙，请稍后重试");
    }
    private static Workbook open(RepeatableMaterialInput source) throws IOException {
        SpreadsheetSafety.verify(source.path());
        return WorkbookFactory.create(source.path().toFile());
    }

    private static ParsedSheet readWorkbook(Workbook workbook, Integer mappedHeaderRow,
                                            List<SpreadsheetHeaderMapping> mappings) throws IOException {
        if (workbook.getNumberOfSheets() == 0) throw new IOException("Excel 没有工作表");
        Sheet sheet = workbook.getSheetAt(0);
        if (workbook.getNumberOfSheets() != 1) throw new IOException("请将待比价的报价表单独上传，避免遗漏其他工作表");
        if (sheet.getLastRowNum() > 5000) throw new IOException("报价行数超过上限");
        int matrixHeader = ProcurementQuoteMatrixReader.header(sheet);
        if (matrixHeader >= 0) return ProcurementQuoteMatrixReader.read(sheet, matrixHeader);
        DataFormatter formatter = new DataFormatter();
        int headerIndex = mappedHeaderRow == null ? sheet.getFirstRowNum() : mappedHeaderRow - 1;
        Row headerRow = sheet.getRow(headerIndex);
        Map<String, Integer> headers = mappedHeaderRow == null
                ? headers(headerRow, formatter)
                : mappedHeaders(mappings);
        if (headerRow == null || headerRow.getLastCellNum() > 64) throw new IOException("报价表头不完整或列数超过上限");
        int supplierColumn = mappedHeaderRow == null ? find(headers, SUPPLIER_HEADERS)
                : mappedColumn(headers, "SUPPLIER_NAME");
        int quoteColumn = mappedHeaderRow == null ? find(headers, QUOTE_HEADERS)
                : mappedColumn(headers, "QUOTE_AMOUNT");
        int budgetColumn = mappedHeaderRow == null ? find(headers, BUDGET_HEADERS)
                : mappedColumn(headers, "BUDGET_AMOUNT");
        int productColumn = mappedHeaderRow == null ? find(headers, PRODUCT_HEADERS)
                : mappedColumn(headers, "PRODUCT_DESCRIPTION");
        if (supplierColumn < 0) throw new IOException("Excel 缺少必填列：供应商名称");
        if (quoteColumn < 0) throw new IOException("Excel 缺少必填列：报价金额");

        BigDecimal budget = null;
        List<QuoteRow> rows = new ArrayList<>();
        for (int rowIndex = headerIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || row.getPhysicalNumberOfCells() == 0) continue;
            String supplier = value(row, supplierColumn, formatter);
            String quoteText = value(row, quoteColumn, formatter);
            String budgetText = value(row, budgetColumn, formatter);
            String productText = value(row, productColumn, formatter);
            if (isBlank(supplier) && isBlank(quoteText) && isBlank(budgetText)
                    && isBlank(productText)) continue;
            if (isBlank(supplier)) throw new IOException("第" + (rowIndex + 1) + "行供应商名称不能为空");
            if (isBlank(quoteText)) throw new IOException("第" + (rowIndex + 1) + "行报价金额不能为空");
            try {
                rows.add(new QuoteRow(rowIndex + 1, supplier.trim(), parseAmount(quoteText), productText));
                if (budget == null && !isBlank(budgetText)) budget = parseAmount(budgetText);
            } catch (NumberFormatException exception) {
                throw new IOException("第" + (rowIndex + 1) + "行金额格式错误: " + quoteText, exception);
            }
        }
        if (rows.isEmpty()) throw new IOException("Excel 没有有效报价数据");
        if (rows.stream().map(row -> normalize(row.supplierName())).distinct().count() != rows.size())
            throw new IOException("供应商重复；多品目报价请使用横向三方比价表，避免重复计算供应商家数");
        return new ParsedSheet(budget, rows);
    }

    private static Map<String, Integer> mappedHeaders(List<SpreadsheetHeaderMapping> mappings) {
        Map<String, Integer> result = new HashMap<>();
        for (SpreadsheetHeaderMapping mapping : mappings) {
            if (mapping.standardField() != null) result.put(mapping.standardField(), mapping.sourceColumnIndex());
        }
        return result;
    }

    private static List<SpreadsheetHeaderMapping> ruleMappings(
            SpreadsheetHeaderPreview.PreviewRow row) {
        Map<String, Integer> found = new HashMap<>();
        for (int i = 0; i < row.cells().size(); i++) {
            String header = row.cells().get(i);
            if (header == null || header.isBlank()) continue;
            String normalized = normalize(header);
            if (find(Map.of(normalized, i), SUPPLIER_HEADERS) >= 0) {
                found.put("SUPPLIER_NAME", i);
            } else if (find(Map.of(normalized, i), QUOTE_HEADERS) >= 0) {
                found.put("QUOTE_AMOUNT", i);
            } else if (find(Map.of(normalized, i), PRODUCT_HEADERS) >= 0) {
                found.put("PRODUCT_DESCRIPTION", i);
            } else if (find(Map.of(normalized, i), BUDGET_HEADERS) >= 0) {
                found.put("BUDGET_AMOUNT", i);
            }
        }
        if (!found.containsKey("SUPPLIER_NAME") || !found.containsKey("QUOTE_AMOUNT")) {
            return List.of();
        }
        return found.entrySet().stream()
                .map(entry -> new SpreadsheetHeaderMapping(
                        row.cells().get(entry.getValue()), entry.getValue(), entry.getKey(), 1.0))
                .toList();
    }

    private static int mappedColumn(Map<String, Integer> mappings, String field) {
        return mappings.getOrDefault(field, -1);
    }

    private static Map<String, Integer> headers(Row row, DataFormatter formatter) throws IOException {
        if (row == null) throw new IOException("Excel 缺少表头");
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            String value = row.getCell(i) == null ? "" : formatter.formatCellValue(row.getCell(i));
            if (value != null && !value.isBlank()) result.put(normalize(value), i);
        }
        return result;
    }

    private static int find(Map<String, Integer> headers, List<String> candidates) {
        for (String candidate : candidates) {
            Integer index = headers.get(normalize(candidate));
            if (index != null) return index;
        }
        return -1;
    }

    private static String value(Row row, int column, DataFormatter formatter) {
        if (column < 0 || row.getCell(column) == null) return null;
        String value = formatter.formatCellValue(row.getCell(column));
        return value == null ? null : value.trim();
    }

    private static BigDecimal parseAmount(String value) {
        String numeric=value.replace(",", "").replace("，", "").trim();
        // Preserve the established source precision for existing decimal quotations.
        if(numeric.matches("[+]?[0-9]+(?:\\.[0-9]+)?")) return new BigDecimal(numeric);
        return com.fuquan.audit.common.domain.RmbAmountParser.parse(value)
                .orElseThrow(()->new NumberFormatException("invalid source amount"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }
}
