package com.fuquan.audit.budget.application;
import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.integration.material.OfficeDocumentTextReader;
import java.util.*;
import org.junit.jupiter.api.Test;
class BudgetWrappedPdfTest {
 @Test void nativePdfWrappingRetainsApprovalAndCompleteCodeWithLiteralEvidence() throws Exception {
  var reader=new OfficeDocumentTextReader(null,r->{throw new AssertionError("Native PDF must not call OCR");});
  try(var in=getClass().getResourceAsStream("/material-v15/预算申报.pdf")) {
   var text=reader.read(in,"budget.pdf","test",1000000,100,192000);
   var extraction=new BudgetUploadedMaterialReader(reader,(a,b,c,d)->new MaterialFieldAiPort.Result(List.of(),"test","test"),null);
   var snapshot=extraction.extract(text,"test");
   var facts=snapshot.rows().stream().flatMap(r->r.facts().stream()).toList();
   assertThat(facts).anyMatch(f->f.field().equals("approvalAmount")&&f.value().equals("壹拾壹万元整"));
   assertThat(facts).anyMatch(f->f.field().equals("projectCode")&&f.value().equals("SYN-V15-001"));
   for(var f:facts)assertThat(text.segments()).anyMatch(seg->seg.text().contains(f.excerpt()));
  }
 }
 @Test void separateProjectRowsAreNotJoined() throws Exception {
  var text=new DocumentTextPort.Result(List.of(new MaterialFieldAiPort.Segment("pdf/page:1","项目编号：A-1；科目名称：设备；申报金额：5000元\n项目编号：B-2；科目名称：装修；批复金额：6000元")),"OFFICE_TEXT","sha");
  var extraction=new BudgetUploadedMaterialReader(null,(a,b,c,d)->new MaterialFieldAiPort.Result(List.of(),"test","test"),null);
  var rows=extraction.extract(text,"test").rows();
  assertThat(rows).hasSize(2);
  assertThat(rows.get(0).facts()).noneMatch(f->f.field().equals("approvalAmount"));
  assertThat(rows.get(1).facts()).noneMatch(f->f.field().equals("declaredAmount"));
 }
}
