package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.budget.infra.excel.ApachePoiBudgetMaterialParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelBudgetMaterialParserTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"合计", "总计", "小计"})
    void totalLabelBeforeAnEmptySubjectDoesNotBecomeAnotherBudgetLine(String label) throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("明细");
            var header = sheet.createRow(0);
            header.createCell(1).setCellValue("科目名称");
            header.createCell(2).setCellValue("申报金额");
            var detail = sheet.createRow(1);
            detail.createCell(0).setCellValue("1");
            detail.createCell(1).setCellValue("设备");
            detail.createCell(2).setCellValue(100);
            var total = sheet.createRow(2);
            total.createCell(0).setCellValue(label);
            total.createCell(2).setCellValue(100);
            workbook.write(out);
            assertThat(new ApachePoiBudgetMaterialParser().parse(
                    new ByteArrayInputStream(out.toByteArray()), "total.xlsx"))
                    .singleElement().satisfies(line -> assertThat(line.declaredAmount()).isEqualByComparingTo("100"));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"一级名称", "二级名称"})
    void parentCategoryAloneCannotBecomeTheBudgetSubject(String heading) throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("分类");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue(heading);
            header.createCell(1).setCellValue("申报金额");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("信息化建设");
            row.createCell(1).setCellValue(490000);
            workbook.write(out);
            assertThatThrownBy(() -> new ApachePoiBudgetMaterialParser().parse(
                    new ByteArrayInputStream(out.toByteArray()), "parent.xlsx"))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("无法完整读取");
        }
    }
    @Test
    void frozenF26B44WorkbookKeepsFourSubjectsAndDeclaredTotal() throws Exception {
        var path = java.nio.file.Path.of("..", "test-materials", "field-simulation-20261126-r3",
                "F26B44", "项目明细表.xlsx");
        try (var input = java.nio.file.Files.newInputStream(path)) {
            var lines = new ApachePoiBudgetMaterialParser().parse(input, path.getFileName().toString());
            assertThat(lines).hasSize(4);
            assertThat(lines).extracting(BudgetMaterialLine::subjectName)
                    .containsExactly("移动记录设备", "数据回传升级", "现场培训服务", "设备巡检服务");
            assertThat(lines.stream().map(BudgetMaterialLine::declaredAmount)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)).isEqualByComparingTo("490000");
        }
    }

    @Test
    void workbookPicturesMustUseOcrAwareParserInsteadOfSilentlyIgnoringThem() throws Exception {
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("图文");var h=sheet.createRow(0);h.createCell(0).setCellValue("科目名称");h.createCell(1).setCellValue("申报金额");
            try(var png=new ByteArrayOutputStream()) {
                javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(10,10,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",png);
                int picture=workbook.addPicture(png.toByteArray(),org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG);
                sheet.createDrawingPatriarch().createPicture(workbook.getCreationHelper().createClientAnchor(),picture);
            }
            workbook.write(out);var parser=new ApachePoiBudgetMaterialParser();
            assertThatThrownBy(()->parser.parse(new ByteArrayInputStream(out.toByteArray()),"budget.xlsx")).hasMessageContaining("需启用完整 OCR");
            var office=org.mockito.Mockito.mock(com.fuquan.audit.budget.infra.document.BudgetOfficeMaterialParser.class);
            org.mockito.Mockito.when(office.parse(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("budget.xlsx")))
                .thenReturn(List.of(new BudgetMaterialLine(1,"扫描科目",new java.math.BigDecimal("123"))));
            parser.setOfficeParser(office);
            assertThat(parser.parse(new ByteArrayInputStream(out.toByteArray()),"budget.xlsx").get(0).subjectName()).isEqualTo("扫描科目");
        }
    }

    @Test
    void readsAllBudgetSheetsAfterCoverAndHonorsHeaderUnitWithoutCountingTotals() throws Exception {
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            workbook.createSheet("封面").createRow(0).createCell(0).setCellValue("年度预算申请资料");
            for(int s=0;s<3;s++) {
                var sheet=workbook.createSheet("项目"+s);var header=sheet.createRow(14);
                header.createCell(0).setCellValue("科目名称");header.createCell(1).setCellValue("申报金额（万元）");
                header.createCell(2).setCellValue("批复参考金额");
                for(int i=0;i<12;i++){var row=sheet.createRow(15+i);row.createCell(0).setCellValue("科目"+s+"-"+i);row.createCell(1).setCellValue("3.12");row.createCell(2).setCellValue("9.99");}
                var total=sheet.createRow(27);total.createCell(0).setCellValue("合计");total.createCell(1).setCellValue("37.44");
            }
            workbook.write(out);
            var lines=new ApachePoiBudgetMaterialParser().parse(new ByteArrayInputStream(out.toByteArray()),"budget.xlsx");
            assertThat(lines).hasSize(36);assertThat(lines).allMatch(line->line.declaredAmount().compareTo(new java.math.BigDecimal("31200"))==0);
            assertThat(lines.get(35).subjectName()).isEqualTo("科目2-11");
        }
    }

    @Test
    void malformedSecondBudgetSheetCannotDisappearBehindSuccessfulFirstSheet() throws Exception {
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var first=workbook.createSheet("完整");var h=first.createRow(0);h.createCell(0).setCellValue("科目名称");h.createCell(1).setCellValue("申报金额");
            var r=first.createRow(1);r.createCell(0).setCellValue("办公设备");r.createCell(1).setCellValue(123);
            workbook.createSheet("缺列").createRow(0).createCell(0).setCellValue("科目名称");workbook.write(out);
            assertThatThrownBy(()->new ApachePoiBudgetMaterialParser().parse(new ByteArrayInputStream(out.toByteArray()),"budget.xlsx")).isInstanceOf(java.io.IOException.class).hasMessageContaining("无法完整读取");
        }
    }

    @Test void sheetLevelUnitAppliesOnlyToItsSheetAndExplicitHeaderUnitWins() throws Exception {
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var first=workbook.createSheet("万元");first.createRow(0).createCell(0).setCellValue("单位：万元");
            var h=first.createRow(1);h.createCell(0).setCellValue("科目名称");h.createCell(1).setCellValue("申报金额");
            var r=first.createRow(2);r.createCell(0).setCellValue("设备");r.createCell(1).setCellValue("12");
            var second=workbook.createSheet("元");var h2=second.createRow(0);h2.createCell(0).setCellValue("科目名称");h2.createCell(1).setCellValue("申报金额（元）");
            var r2=second.createRow(1);r2.createCell(0).setCellValue("耗材");r2.createCell(1).setCellValue("10");workbook.write(out);
            var lines=new ApachePoiBudgetMaterialParser().parse(new ByteArrayInputStream(out.toByteArray()),"budget.xlsx");
            assertThat(lines).extracting(BudgetMaterialLine::declaredAmount).containsExactly(new java.math.BigDecimal("120000.00"),new java.math.BigDecimal("10.00"));
        }
    }

    @Test
    void declaredAmountIsNotOverwrittenByReferenceOrOtherMoneyColumns() throws Exception {
        for (String[] headers : List.of(
                new String[]{"科目名称", "申报金额", "批复参考金额", "单价（元）"},
                new String[]{"科目名称", "批复参考金额", "单价（元）", "申报金额"},
                new String[]{"科目名称", "预算金额", "批复金额", "差额金额"})) {
            byte[] bytes;
            try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
                var sheet = workbook.createSheet("预算");
                var header = sheet.createRow(4); var row = sheet.createRow(5);
                for (int i = 0; i < headers.length; i++) {
                    header.createCell(i).setCellValue(headers[i]);
                    row.createCell(i).setCellValue(i == 0 ? "会议拾音设备"
                            : headers[i].equals("申报金额") || headers[i].equals("预算金额") ? "31,200.00" : "32,760.00");
                }
                workbook.write(out); bytes = out.toByteArray();
            }
            assertThat(new ApachePoiBudgetMaterialParser().parse(new ByteArrayInputStream(bytes), "budget.xlsx")
                    .get(0).declaredAmount()).isEqualByComparingTo("31200.00");
        }
    }

    @Test
    void approvalOnlySheetMustNotBeTreatedAsDeclaredAmount() throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("批复");
            var header = sheet.createRow(0); header.createCell(0).setCellValue("科目名称");
            header.createCell(1).setCellValue("批复参考金额");
            var row = sheet.createRow(1); row.createCell(0).setCellValue("办公设备");
            row.createCell(1).setCellValue(100);
            workbook.write(out);
            assertThatThrownBy(() -> new ApachePoiBudgetMaterialParser().parse(
                    new ByteArrayInputStream(out.toByteArray()), "approval.xlsx"))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("申报金额列");
        }
    }

    @Test
    void formulaAmountsAreEvaluatedAndDuplicateDeclaredColumnsAreRejected() throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("预算"); var header = sheet.createRow(0);
            header.createCell(0).setCellValue("科目名称"); header.createCell(1).setCellValue("数量");
            header.createCell(2).setCellValue("单价（元）"); header.createCell(3).setCellValue("申报金额");
            header.createCell(4).setCellValue("批复参考金额");
            var row = sheet.createRow(1); row.createCell(0).setCellValue("会议设备");
            row.createCell(1).setCellValue(12); row.createCell(2).setCellValue(2600);
            row.createCell(3).setCellFormula("B2*C2"); row.createCell(4).setCellValue(32760);
            workbook.write(out);
            assertThat(new ApachePoiBudgetMaterialParser().parse(new ByteArrayInputStream(out.toByteArray()), "formula.xlsx")
                    .get(0).declaredAmount()).isEqualByComparingTo("31200");
            header.createCell(5).setCellValue("申报金额（元）"); row.createCell(5).setCellValue(999);
            out.reset(); workbook.write(out);
            assertThatThrownBy(() -> new ApachePoiBudgetMaterialParser().parse(
                    new ByteArrayInputStream(out.toByteArray()), "ambiguous.xlsx"))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("多个申报金额列");
        }
    }

    @Test
    void parsesSubjectAndAmountColumnsFromWorkbook() throws Exception {
        byte[] workbookBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("预算");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("科目名称");
            header.createCell(1).setCellValue("申报金额");
            sheet.createRow(1).createCell(0).setCellValue("办公设备采购");
            sheet.getRow(1).createCell(1).setCellValue("1.5万元");
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                workbook.write(output);
                workbookBytes = output.toByteArray();
            }
        }

        List<BudgetMaterialLine> lines = new ApachePoiBudgetMaterialParser().parse(
                new ByteArrayInputStream(workbookBytes), "budget.xlsx");

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).rowNumber()).isEqualTo(1);
        assertThat(lines.get(0).subjectName()).isEqualTo("办公设备采购");
        assertThat(lines.get(0).declaredAmount()).isEqualByComparingTo("15000.00");
    }
}
