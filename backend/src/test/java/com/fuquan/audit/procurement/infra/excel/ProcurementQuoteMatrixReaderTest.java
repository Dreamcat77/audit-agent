package com.fuquan.audit.procurement.infra.excel;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import com.fuquan.audit.procurement.domain.*;

class ProcurementQuoteMatrixReaderTest {
    private byte[] workbook(String bTotal, String aggregate, String quantity, boolean duplicate) throws Exception {
        try(var w=new XSSFWorkbook(); var out=new ByteArrayOutputStream()) {
            var s=w.createSheet("比价");
            String[][] rows={
                {"三方比价"}, {"预算上限：¥200.00"},
                {"序号","品目编号","品目名称","规格参数","单位","数量","供应商A（甲公司）","","供应商B（乙公司）","","供应商C（丙公司）"},
                {"","","","","","","单价","总价","单价","总价","单价","总价"},
                {"1","ITEM-1","打印纸","70g/500张","箱",quantity,"10","20","11",bTotal,"10","20"},
                {"合计","","","","","","","20","",aggregate,"","20"},
                {"所有报价均为含税价，含运费、安装调试费及一年质保"}};
            for(int r=0;r<rows.length;r++) { var row=s.createRow(r); for(int c=0;c<rows[r].length;c++) row.createCell(c).setCellValue(rows[r][c]); }
            if(duplicate) { s.getRow(2).getCell(8).setCellValue("供应商B（甲公司）"); }
            w.write(out);return out.toByteArray();
        }
    }
    @Test void readsMatrixIgnoresAuthoredRecommendationAndFindsTieWithTraceablePrices() throws Exception {
        var adapter=new ApachePoiProcurementQuoteSpreadsheetAdapter();
        byte[] file=workbook("22","22","2",false);
        var parsed=adapter.read(new ByteArrayInputStream(file));
        assertThat(parsed.rows()).hasSize(3);
        assertThat(parsed.items()).singleElement().satisfies(i->{
            assertThat(i.lowestAmount()).isEqualByComparingTo("20");
            assertThat(i.lowestSuppliers()).containsExactly("甲公司","丙公司");
            assertThat(i.rowNumber()).isEqualTo(5);
        });
        assertThat(parsed.budgetAmount()).isEqualByComparingTo("200");
        assertThat(adapter.preview(new ByteArrayInputStream(file),10).mappings()).singleElement()
                .satisfies(m->assertThat(m.standardField()).isEqualTo("QUOTE_MATRIX"));
        var inputs=parsed.rows().stream().map(r->new ProcurementQuoteCheck.QuoteInput(r.rowNumber(),r.supplierName(),r.quoteAmount(),r.productDescription())).toList();
        var advice=ProcurementQuoteComparison.advise("MATRIX",parsed.budgetAmount(),new BigDecimal("200"),parsed.conditions(),parsed.items(),inputs,Set.of());
        assertThat(advice.recommendedSuppliers()).containsExactly("甲公司","丙公司");
        assertThat(advice.recommendationStatus()).isEqualTo("CONDITIONAL");
        assertThat(advice.notes()).anyMatch(n->n.contains("资质"));
        assertThat(ProcurementQuoteComparison.advise("MATRIX",parsed.budgetAmount(),new BigDecimal("200"),parsed.conditions(),parsed.items(),inputs,Set.of("甲公司","丙公司")).recommendedSuppliers()).containsExactly("乙公司");
    }
    @Test void rejectsWrongLineTotalsWrongAggregateQuantityAndDuplicateSupplier() throws Exception {
        var adapter=new ApachePoiProcurementQuoteSpreadsheetAdapter();
        for(byte[] file:List.of(workbook("21","21","2",false),workbook("22","23","2",false),
                workbook("22","22","0",false),workbook("22","22","2",true)))
            assertThatThrownBy(()->adapter.read(new ByteArrayInputStream(file))).isInstanceOf(IOException.class);
    }
    @Test void cachedFormulaCannotStandInForAnOriginalUnitPrice() throws Exception {
        byte[] bytes=workbook("22","22","2",false);
        try(var w=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()) {
            var price=w.getSheetAt(0).getRow(4).getCell(6);
            price.setCellFormula("100/10");price.setCellValue(10);
            w.write(out);
            assertThatThrownBy(()->new ApachePoiProcurementQuoteSpreadsheetAdapter().read(new ByteArrayInputStream(out.toByteArray())))
                    .isInstanceOf(IOException.class).hasMessageContaining("公式缓存");
        }
    }
    @Test void differentSpecificationsNoDescriptionInsufficientSuppliersAndNoBudgetCandidateDoNotRecommend() {
        var inputs=List.of(new ProcurementQuoteCheck.QuoteInput(1,"A",new BigDecimal("90"),"i5"),
                new ProcurementQuoteCheck.QuoteInput(2,"B",new BigDecimal("80"),"i7"),
                new ProcurementQuoteCheck.QuoteInput(3,"C",new BigDecimal("85"),null));
        assertThat(ProcurementQuoteComparison.advise("SUPPLIER_ROWS",null,new BigDecimal("100"),List.of(),List.of(),inputs,Set.of()).recommendedAmount()).isNull();
        var equal=inputs.stream().map(i->new ProcurementQuoteCheck.QuoteInput(i.rowNumber(),i.supplierName(),i.quoteAmount(),"相同范围")).toList();
        assertThat(ProcurementQuoteComparison.advise("SUPPLIER_ROWS",null,new BigDecimal("100"),List.of(),List.of(),equal.subList(0,2),Set.of()).recommendedAmount()).isNull();
        assertThat(ProcurementQuoteComparison.advise("SUPPLIER_ROWS",null,new BigDecimal("70"),List.of(),List.of(),equal,Set.of()).recommendedAmount()).isNull();
    }
}
