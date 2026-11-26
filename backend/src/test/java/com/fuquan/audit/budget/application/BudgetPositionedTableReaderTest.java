package com.fuquan.audit.budget.application;

import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import com.fuquan.audit.common.material.visual.*;
import com.fuquan.audit.common.material.visual.VisualIndex.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BudgetPositionedTableReaderTest {
    @Test void replaysNativeHundredTwentyRowsAndTwoScannedRotatedTables() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(String name:List.of("B03","B05approval","B05attachment")) {
            try(var source=getClass().getResourceAsStream("/budget-positioned/"+name+".json")) {
                var fixture=json.readTree(source);
                var segments=Arrays.asList(json.treeToValue(fixture.get("segments"),Segment[].class));
                var visual=json.treeToValue(fixture.get("visual"),VisualIndex.class);
                var result=BudgetPositionedTableReader.read(segments,visual);
                assertThat(result.rows()).as(name).hasSize(fixture.get("expected").asInt());
                for(var row:result.rows()) {
                    assertThat(row.facts()).as(name+row.rowKey()).anyMatch(f->f.field().equals("subjectCode"));
                    assertThat(row.facts()).as(name+row.rowKey()).anyMatch(f->f.field().equals("declaredAmount"));
                    assertThat(row.facts()).as(name+row.rowKey()).anyMatch(f->f.field().equals("approvalAmount"));
                    String code=row.facts().stream().filter(f->f.field().equals("subjectCode")).findFirst().orElseThrow().value();
                    var expected=java.util.stream.StreamSupport.stream(fixture.get("expectedRows").spliterator(),false)
                            .filter(e->e.get("code").asText().equals(code)).findFirst().orElseThrow();
                    for(String field:List.of("declaredAmount","approvalAmount")) {
                        var actual=row.facts().stream().filter(f->f.field().equals(field)).findFirst().orElseThrow();
                        assertThat(BudgetUploadedMaterialAnalyzer.money(actual.value())).as(name+code+field)
                                .isEqualByComparingTo(expected.get(field.equals("declaredAmount")?"declared":"approved").asText());
                    }
                }
                var ai=org.mockito.Mockito.mock(com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.class);
                org.mockito.Mockito.when(ai.extract(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                        .thenReturn(new Result(List.of(),"test","test"));
                var store=org.mockito.Mockito.mock(VisualIndexStore.class);
                org.mockito.Mockito.when(store.find(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(visual));
                var reader=new BudgetUploadedMaterialReader(null,ai,null);reader.setVisualIndexes(store);
                var snapshot=reader.extract(new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(segments,"OCR",visual.fileSha256()),UUID.randomUUID().toString());
                var captured=org.mockito.ArgumentCaptor.forClass(List.class);
                org.mockito.Mockito.verify(ai).extract(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),captured.capture());
                assertThat(captured.getValue()).hasSizeLessThanOrEqualTo(segments.size());
                assertThat(snapshot.issues()).isEmpty();
                assertThat(snapshot.rows().stream().filter(r->r.facts().stream().anyMatch(f->f.field().equals("declaredAmount")))).hasSize(fixture.get("expected").asInt());
                var task=UUID.randomUUID();var lines=new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(new BudgetUploadedFacts.Input(task,"test.pdf","PARSED",snapshot))).get(task).lines();
                assertThat(lines).hasSize(fixture.get("expected").asInt());
                assertThat(lines).noneMatch(l->Set.of("INSUFFICIENT","AMBIGUOUS").contains(l.status()));
            }
        }
    }
    static final String SHA="a".repeat(64);
    private static final class Page {
        StringBuilder text=new StringBuilder();List<Anchor> anchors=new ArrayList<>();int direction;
        Page(int direction){this.direction=direction;}
        void cell(String value,double x,double y) {
            int start=text.length();text.append(value).append('\n');
            var points=new ArrayList<Point>();
            for(double[] p:List.of(new double[]{x,y},new double[]{x+.08,y},new double[]{x+.08,y+.012},new double[]{x,y+.012})) {
                double u=p[0],v=p[1];points.add(switch(direction){case 1->new Point(1-v,u);case 2->new Point(1-u,1-v);case 3->new Point(v,1-u);default->new Point(u,v);});
            }
            anchors.add(new Anchor(1,"OCR",start,start+value.length(),points,.98));
        }
        BudgetPositionedTableReader.Read read(){return BudgetPositionedTableReader.read(List.of(new Segment("pdf/page:1/ocr/part:1",text.toString())),new VisualIndex("id",SHA,SHA,anchors));}
        void header(){cell("预算科目",.1,.1);cell("科目编码",.3,.1);cell("申报金额（万元）",.5,.1);cell("批复金额（万元）",.7,.1);}
    }
    @Test void shuffledTokensAndSameAmountsKeepSeparateCellsInEveryRightAngle() {
        for(int direction=0;direction<4;direction++) {
            var p=new Page(direction);p.header();
            p.cell("10.00",.7,.15);p.cell("A01",.3,.15);p.cell("10.00",.5,.15);p.cell("设备采购",.1,.15);
            p.cell("软件维护",.1,.2);p.cell("A02",.3,.2);p.cell("8.00",.7,.2); // declared cell intentionally empty
            var read=p.read();assertThat(read.rows()).hasSize(2);
            var one=read.rows().get(0).facts();assertThat(one).anyMatch(f->f.field().equals("declaredAmount")&&f.value().equals("10.00万元"));
            var declared=one.stream().filter(f->f.field().equals("declaredAmount")).findFirst().orElseThrow();
            var approved=one.stream().filter(f->f.field().equals("approvalAmount")).findFirst().orElseThrow();
            assertThat(declared.locator()).isNotEqualTo(approved.locator());
            assertThat(read.rows().get(1).facts()).noneMatch(f->f.field().equals("declaredAmount"));
            var snapshot=new BudgetUploadedFacts.Snapshot(read.rows(),List.of(),List.of(new Segment("pdf/page:1/ocr/part:1",p.text.toString())),SHA,"OCR");
            var evidence=BudgetSourceText.locate(new BudgetUploadedFacts.Evidence(UUID.randomUUID(),"test.pdf",declared.field(),declared.value(),declared.locator(),declared.excerpt()),snapshot);
            assertThat(evidence.pageNumber()).isEqualTo(1);assertThat(p.text.substring(evidence.start(),evidence.end())).isEqualTo("10.00");
        }
    }
    @Test void noHeaderOrCoordinatesDoesNotInventRowsAndUncertainCellStaysUnread() {
        var p=new Page(0);p.cell("设备采购",.1,.15);p.cell("10.00",.5,.15);assertThat(p.read().rows()).isEmpty();
        p=new Page(0);p.header();p.cell("设备采购",.1,.15);p.cell("10.00",.5,.15);
        var a=p.anchors.remove(p.anchors.size()-1);p.anchors.add(new Anchor(1,"OCR",a.start(),a.end(),a.polygon(),.5));
        assertThat(p.read().rows()).isEmpty();
        assertThat(BudgetPositionedTableReader.read(List.of(new Segment("pdf/page:1/text/part:1",p.text.toString())),null).rows()).isEmpty();
    }
    @Test void overlappingAmountCandidatesDoNotSelectTheFirstOrBorrowTheNextRow() {
        var p=new Page(0);p.header();p.cell("设备采购",.1,.15);p.cell("10.00",.5,.15);p.cell("20.00",.51,.15);
        assertThat(p.read().rows()).isEmpty();
    }
    @Test void maskedCellCannotBeReintroducedAsModelEvidence() throws Exception {
        var p=new Page(0);p.header();p.cell("设备采购",.1,.15);p.cell("A01",.3,.15);p.cell("10.00",.5,.15);p.cell("9.00",.7,.15);
        p.cell("用途：设备维修",.1,.4);
        var segment=new Segment("pdf/page:1/ocr/part:1",p.text.toString());
        var index=new VisualIndex("id",SHA,SHA,p.anchors);
        var store=org.mockito.Mockito.mock(VisualIndexStore.class);
        org.mockito.Mockito.when(store.find(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(index));
        var ai=org.mockito.Mockito.mock(com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.class);
        org.mockito.Mockito.when(ai.extract(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new Result(List.of(new Row("bad",List.of(new Fact("spendingPurpose","设备采购",segment.locator(),"设备采购")))),"test","test"));
        var reader=new BudgetUploadedMaterialReader(null,ai,null);reader.setVisualIndexes(store);
        var snapshot=reader.extract(new com.fuquan.audit.common.material.application.port.DocumentTextPort.Result(List.of(segment),"OCR",SHA),"id");
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream())).noneMatch(f->f.field().equals("spendingPurpose")&&f.value().equals("设备采购"));
        assertThat(snapshot.issues()).anyMatch(i->i.reasonCode().equals("EVIDENCE_UNVERIFIED"));
    }
}
