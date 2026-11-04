package com.fuquan.audit.procurement.infra.excel;

import com.fuquan.audit.procurement.application.port.ProcurementProjectSpreadsheetPort;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** 使用 Apache POI 生成采购需求明细 Excel。 */
@Component
public class ApachePoiProcurementProjectSpreadsheetAdapter
        implements ProcurementProjectSpreadsheetPort {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    @Override
    public byte[] exportProject(ProcurementProject project) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("采购需求");
            CellStyle labelStyle = workbook.createCellStyle();
            labelStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            labelStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            writeHeader(sheet, labelStyle);
            writeRow(sheet, 1, "项目编号", project.projectCode(), labelStyle);
            writeRow(sheet, 2, "项目名称", project.projectName(), labelStyle);
            writeRow(sheet, 3, "申请部门", project.departmentName(), labelStyle);
            writeRow(sheet, 4, "采购类型", project.procurementType(), labelStyle);
            writeRow(sheet, 5, "预算金额", project.budgetAmount() == null
                    ? null : project.budgetAmount().toPlainString(), labelStyle);
            writeRow(sheet, 6, "预算科目编码", project.subjectCode(), labelStyle);
            writeRow(sheet, 7, "预算科目名称", project.subjectName(), labelStyle);
            writeRow(sheet, 8, "采购方式", project.procurementMethod(), labelStyle);
            writeRow(sheet, 9, "需求说明", project.requirementDescription(), labelStyle);
            writeRow(sheet, 10, "申请人", project.applicantName(), labelStyle);
            writeRow(sheet, 11, "创建时间", project.createdTime() == null
                    ? null : DATE_TIME_FORMATTER.format(project.createdTime()), labelStyle);
            writeRow(sheet, 12, "项目状态", project.status(), labelStyle);

            sheet.setColumnWidth(0, 18 * 256);
            sheet.setColumnWidth(1, 60 * 256);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void writeHeader(Sheet sheet, CellStyle labelStyle) {
        Row row = sheet.createRow(0);
        row.createCell(0).setCellValue("信息");
        row.getCell(0).setCellStyle(labelStyle);
        row.createCell(1).setCellValue("内容");
        row.getCell(1).setCellStyle(labelStyle);
    }

    private static void writeRow(Sheet sheet, int rowIndex, String label, String value,
                                 CellStyle labelStyle) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(label);
        row.getCell(0).setCellStyle(labelStyle);
        row.createCell(1).setCellValue(value == null ? "" : value);
    }
}
