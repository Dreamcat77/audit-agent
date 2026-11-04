package com.fuquan.audit.budget.infra.excel;

import com.fuquan.audit.budget.application.BudgetMaterialLine;
import com.fuquan.audit.budget.application.BudgetTextNormalizer;
import com.fuquan.audit.budget.application.port.BudgetMaterialParser;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

/**
 * F01 Excel 预算材料解析 Adapter。
 */
@Component
@Order(0)
public class ApachePoiBudgetMaterialParser implements BudgetMaterialParser {
    private com.fuquan.audit.budget.infra.document.BudgetOfficeMaterialParser officeParser;
    @org.springframework.beans.factory.annotation.Autowired
    public void setOfficeParser(com.fuquan.audit.budget.infra.document.BudgetOfficeMaterialParser parser){this.officeParser=parser;}

    private static final int MAX_HEADER_ROWS = 100;

    @Override
    public boolean supports(String fileName, String contentType) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx")
                || lower.endsWith(".xls")
                || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        .equalsIgnoreCase(contentType)
                || "application/vnd.ms-excel".equalsIgnoreCase(contentType);
    }

    @Override
    public List<BudgetMaterialLine> parse(InputStream inputStream, String fileName) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            if(!workbook.getAllPictures().isEmpty()) {
                if(officeParser==null)throw new IOException("Excel 含图片，需启用完整 OCR 读取");
                try(var out=new java.io.ByteArrayOutputStream()) {
                    workbook.write(out);
                    return officeParser.parse(new java.io.ByteArrayInputStream(out.toByteArray()),fileName);
                }
            }
            if (workbook.getNumberOfSheets() == 0) {
                throw new IOException("Excel 文件没有工作表");
            }
            List<BudgetMaterialLine> lines = new ArrayList<>();
            if(workbook.getNumberOfSheets()>100)throw new IOException("Excel 工作表过多，请分批处理");
            for(int sheetIndex=0;sheetIndex<workbook.getNumberOfSheets();sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            DataFormatter formatter = new DataFormatter();
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            int subjectCodeColumn = -1;
            int subjectColumn = -1;
            int amountColumn = -1;
            int supplierColumn = -1;
            int projectCodeColumn = -1;
            int headerRow = -1;
            String amountHeader = null;
            String sheetUnit = null;

            int inspected = 0;
            for (Row row : sheet) {
                if (inspected++ >= MAX_HEADER_ROWS) {
                    break;
                }
                // Each candidate header must stand on its own; unrelated rows
                // above a table must not contribute columns to it.
                subjectCodeColumn = subjectColumn = amountColumn = supplierColumn = projectCodeColumn = -1;
                int amountPriority = 0;
                boolean ambiguousAmount = false;
                for (Cell cell : row) {
                    String header = BudgetTextNormalizer.subject(formatter.formatCellValue(cell));
                    if (header == null) {
                        continue;
                    }
                    var unitLabel=java.util.regex.Pattern.compile("^(?:金额单位|单位)(万元|千元|元)$").matcher(header);
                    if(unitLabel.matches())sheetUnit=unitLabel.group(1);
                    if (isSubjectCodeHeader(header)) {
                        subjectCodeColumn = cell.getColumnIndex();
                    } else if (isSubjectHeader(header)) {
                        subjectColumn = cell.getColumnIndex();
                    } else if (declaredAmountPriority(header) > 0) {
                        int priority = declaredAmountPriority(header);
                        if (priority > amountPriority) {
                            amountColumn = cell.getColumnIndex();
                            amountPriority = priority;
                            amountHeader = header;
                            ambiguousAmount = false;
                        } else if (priority == amountPriority) {
                            ambiguousAmount = true;
                        }
                    } else if (isSupplierHeader(header)) {
                        supplierColumn = cell.getColumnIndex();
                    } else if (isProjectCodeHeader(header)) {
                        projectCodeColumn = cell.getColumnIndex();
                    }
                }
                if (subjectColumn >= 0 && amountColumn >= 0) {
                    if (ambiguousAmount) throw new IOException("Excel 存在多个申报金额列，无法确定适用列");
                    headerRow = row.getRowNum();
                    break;
                }
            }
            if (subjectColumn < 0 || amountColumn < 0) {
                // A title/notes sheet is allowed, but must not hide a second budget table.
                boolean budgetLike=false;
                for(var row:sheet)for(var cell:row) {
                    String value=BudgetTextNormalizer.subject(formatter.formatCellValue(cell));
                    if(value!=null&&(isSubjectHeader(value)||value.contains("申报金额")||value.contains("批复金额")))budgetLike=true;
                }
                if(budgetLike)throw new IOException("Excel 有工作表未找到科目名称和申报金额列，无法完整读取");
                continue;
            }

            for (int index = headerRow + 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) {
                    continue;
                }
                String subject = formatter.formatCellValue(row.getCell(subjectColumn));
                String subjectCode = subjectCodeColumn < 0
                        ? null : formatter.formatCellValue(row.getCell(subjectCodeColumn));
                String amountText = formatter.formatCellValue(row.getCell(amountColumn), evaluator);
                String supplierName = supplierColumn < 0
                        ? null : formatter.formatCellValue(row.getCell(supplierColumn));
                String projectCode = projectCodeColumn < 0
                        ? null : formatter.formatCellValue(row.getCell(projectCodeColumn));
                String normalizedSubject=BudgetTextNormalizer.subject(subject);
                if(normalizedSubject!=null&&isSubjectHeader(normalizedSubject)
                        &&declaredAmountPriority(BudgetTextNormalizer.subject(amountText))>0)continue;
                if(normalizedSubject!=null&&Set.of("合计","总计","小计").contains(normalizedSubject))continue;
                if ((subject == null || subject.isBlank())
                        && (subjectCode == null || subjectCode.isBlank())
                        && (amountText == null || amountText.isBlank())
                        && (supplierName == null || supplierName.isBlank())
                        && (projectCode == null || projectCode.isBlank())) {
                    continue;
                }
                String unit=amountHeader!=null&&amountHeader.endsWith("万元")?"万元"
                    :amountHeader!=null&&amountHeader.endsWith("千元")?"千元"
                    :amountHeader!=null&&amountHeader.endsWith("元")?"元":sheetUnit;
                String sourceAmount=amountText;
                if(unit==null&&sheetUnit!=null&&amountText.matches("[\\d,+. -]+|[零壹贰叁肆伍陆柒捌玖拾佰仟]+"))unit=sheetUnit;
                if(unit!=null&&amountText.matches("[\\d,+. -]+|[零壹贰叁肆伍陆柒捌玖拾佰仟]+"))sourceAmount+=unit;
                BigDecimal amount = BudgetTextNormalizer.amount(sourceAmount);
                if(lines.size()>=10000)throw new IOException("预算明细超过处理上限，未保存部分结果");
                lines.add(new BudgetMaterialLine(
                        lines.size() + 1, subjectCode, subject, amountText, amount,
                        supplierName, projectCode));
            }
            }
            if (lines.isEmpty()) {
                throw new IOException("Excel 未找到科目名称和申报金额列，未解析出预算明细行");
            }
            return lines;
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Excel 解析失败: " + exception.getMessage(), exception);
        }
    }

    private static boolean isSubjectHeader(String header) {
        return header.contains("科目名称") || header.equals("科目") || header.contains("预算科目");
    }

    private static boolean isSubjectCodeHeader(String header) {
        return header.contains("科目编码") || header.contains("科目代码") || header.contains("科目编号");
    }

    private static int declaredAmountPriority(String header) {
        // An amount in an approval/reference column is never a declaration.
        // Accept explicit aliases, with an optional unit, rather than arbitrary
        // text containing 金额 (unit prices, differences and totals included).
        if(header==null)return 0;
        String name = header.replaceFirst("(?:人民币)?(?:万元|千元|元)$", "");
        return switch (name) {
            case "申报金额", "申请金额", "本次申报金额", "本次申请金额" -> 3;
            case "预算金额" -> 2;
            case "金额" -> 1;
            default -> 0;
        };
    }

    private static boolean isSupplierHeader(String header) {
        return header.contains("供应商名称") || header.contains("供应商");
    }

    private static boolean isProjectCodeHeader(String header) {
        return header.contains("项目编号") || header.contains("项目代码") || header.contains("项目编码");
    }
}
