package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import com.fuquan.audit.procurement.application.port.*;
import com.fuquan.audit.contract.application.port.StoredFileContent;
import java.io.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProcurementSpreadsheetMaterialServiceTest {
    final UUID project=UUID.randomUUID(), id=UUID.randomUUID();
    final DocumentTextPort reader=mock(DocumentTextPort.class);
    final MaterialFieldAiPort ai=mock(MaterialFieldAiPort.class);
    final ProcurementQuoteSpreadsheetPort sheets=mock(ProcurementQuoteSpreadsheetPort.class);
    final ProcurementQuoteCheckService quotes=mock(ProcurementQuoteCheckService.class);
    final ProcurementTenderDocumentService docs=mock(ProcurementTenderDocumentService.class);
    final ProcurementSpreadsheetMaterialService service=new ProcurementSpreadsheetMaterialService(reader,ai,sheets,quotes,docs);
    static Fact fact(String field,String value,String loc) { return new Fact(field,value,loc,value); }
    static Result reply(Fact... facts) { return new Result(List.of(new Row("row",List.of(facts))),"model","v1"); }
    ProcurementTenderDocumentService.TenderDocumentResult source(String status) {
        return new ProcurementTenderDocumentService.TenderDocumentResult(id,project,"任意材料.xlsx","xlsx",null,83887942,
                "a".repeat(64),"AVAILABLE",null,status,null,null,null,null,Instant.now(),Instant.now());
    }
    void prepare(List<Segment> segments) throws Exception {
        when(docs.requireMaterial(project,id)).thenReturn(source("QUOTE_STAGED"));
        when(docs.openMaterial(project,id)).thenAnswer(a->new StoredFileContent(new ByteArrayInputStream(new byte[]{1}),1));
        when(reader.read(any(),any(),any(),eq(500L*1024*1024),eq(300),eq(192000)))
                .thenReturn(new DocumentTextPort.Result(segments,"OFFICE_TEXT","a".repeat(64)));
        when(sheets.preview(any(),eq(10))).thenReturn(new SpreadsheetHeaderPreview(1,0,List.of(),null,List.of(),false,null));
    }
    @Test void irregularBudgetIsReadByModelAndUsefulFactsArePreservedWithoutInventingQuotation() throws Exception {
        var segments=List.of(new Segment("excel/sheet:1/row:1","项目名称 | 申报金额 | 批复参考金额"),
                new Segment("excel/sheet:1/row:2","办公设备 | 31200 | 32760"));
        prepare(segments);
        when(ai.extract(eq("PROCUREMENT_REVIEW"),eq(id.toString()),any(),any())).thenReturn(reply(fact("projectName","办公设备",segments.get(1).locator())));
        assertThat(service.analyze(project,id,"actor").status()).isEqualTo("NON_QUOTATION");
        verify(docs).recordSpreadsheet(eq(project),eq(id),eq("NON_QUOTATION"),argThat(p->p.extractedFields().size()==1&&p.fullText().contains("32760")),isNull());
        verifyNoInteractions(quotes);
    }
    @Test void nonstandardQuotationPreservesUppercasePenniesAndPhysicalRowRoles() throws Exception {
        var segments=List.of(new Segment("excel/sheet:1/row:1","报价单位 | 含税总价"),
                new Segment("excel/sheet:1/row:2","甲公司 | 壹万零壹元贰角叁分"));
        prepare(segments);
        when(ai.extract(any(),any(),any(),any())).thenReturn(reply(fact("supplierName","甲公司",segments.get(1).locator()),fact("quoteAmount","壹万零壹元贰角叁分",segments.get(1).locator())));
        assertThat(service.analyze(project,id,"actor").status()).isEqualTo("QUOTE_ANALYZED");
        verify(quotes).analyzeMaterialQuotes(eq(project),eq(id),any(),argThat(q->q.size()==1&&q.get(0).quoteAmount().toPlainString().equals("10001.23")));
    }
    @Test void budgetAmountCannotMasqueradeAsVendorQuoteEvenIfModelClaimsIt() throws Exception {
        var rows=List.of(new Segment("excel/sheet:1/row:1","供应商 | 批复参考金额"),new Segment("excel/sheet:1/row:2","甲公司 | 32760"));
        assertThatThrownBy(()->ProcurementSpreadsheetMaterialService.quotationInputs(List.of(fact("supplierName","甲公司",rows.get(1).locator()),
                fact("quoteAmount","32760",rows.get(1).locator())),rows)).hasMessage("QUOTATION_ROLE_UNPROVEN");
    }
    @Test void anotherRowVendorCannotBeJoinedAndOmittedQuoteRowsCannotPublishPartialAnalysis() {
        var rows=List.of(new Segment("excel/sheet:1/row:1","报价单位 | 含税总价"),new Segment("excel/sheet:1/row:2","甲公司 | 10000"),new Segment("excel/sheet:1/row:3","乙公司 | 20000"));
        var a=fact("supplierName","甲公司",rows.get(1).locator()); var b=fact("quoteAmount","20000",rows.get(2).locator());
        assertThatThrownBy(()->ProcurementSpreadsheetMaterialService.quotationInputs(List.of(a,b),rows)).hasMessage("QUOTATION_ROLE_UNPROVEN");
        assertThatThrownBy(()->ProcurementSpreadsheetMaterialService.quotationInputs(List.of(a,fact("quoteAmount","10000",rows.get(1).locator())),rows))
                .hasMessage("QUOTATION_FIELDS_INCOMPLETE");
    }
    @Test void corruptReadIsPersistedAsFailureInsteadOfNonQuotationOrProcessing() throws Exception {
        prepare(List.of(new Segment("excel/sheet:1/row:1","text")));
        when(reader.read(any(),any(),any(),anyLong(),anyInt(),anyInt())).thenThrow(new DocumentTextPort.ReadFailure("SPREADSHEET_ENTRY_SIZE_LIMIT"));
        assertThatThrownBy(()->service.analyze(project,id,"actor")).hasMessage("SPREADSHEET_ENTRY_SIZE_LIMIT");
        verify(docs).recordSpreadsheet(project,id,"QUOTE_PARSE_FAILED",null,"SPREADSHEET_ENTRY_SIZE_LIMIT");
        verifyNoInteractions(ai,quotes);
    }
    @Test void unknownModelCallIsNotMisreportedAsKnownFailureOrAbsence() throws Exception {
        prepare(List.of(new Segment("excel/sheet:1/row:1","项目名称：办公设备")));
        when(ai.extract(any(),any(),any(),any())).thenThrow(new MaterialExtractionUnknown("CALL_OUTCOME_UNKNOWN"));
        assertThatThrownBy(()->service.analyze(project,id,"actor")).isInstanceOf(MaterialExtractionUnknown.class);
        verify(docs).recordSpreadsheet(project,id,"QUOTE_UNKNOWN",null,"MODEL_OUTCOME_UNCONFIRMED");
        verifyNoInteractions(quotes);
    }
    @Test void successfulReopenDoesNotInvokeModelAndForeignEvidenceFailsClosed() throws Exception {
        when(docs.requireMaterial(project,id)).thenReturn(source("NON_QUOTATION"));
        assertThat(service.analyze(project,id,"actor").status()).isEqualTo("NON_QUOTATION");
        verifyNoInteractions(ai,reader,quotes);
        assertThatThrownBy(()->ProcurementSpreadsheetMaterialService.ground(reply(fact("projectName","外项目","foreign")),
                List.of(new Segment("here","本项目")))).hasMessage("MODEL_EVIDENCE_INVALID");
    }
    @Test void multiSheetReadsAllRowsAndDoesNotForceLegacySingleSheetComparison() throws Exception {
        prepare(List.of(new Segment("excel/sheet:1/row:1","说明"),new Segment("excel/sheet:2/row:1","项目名称：办公设备")));
        when(ai.extract(any(),any(),any(),any())).thenReturn(reply(fact("projectName","办公设备","excel/sheet:2/row:1")));
        assertThat(service.analyze(project,id,"actor").status()).isEqualTo("NON_QUOTATION");
        verifyNoInteractions(sheets,quotes);
    }
}
