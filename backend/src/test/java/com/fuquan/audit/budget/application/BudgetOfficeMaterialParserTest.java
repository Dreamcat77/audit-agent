package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.budget.infra.document.BudgetOfficeMaterialParser;
import com.fuquan.audit.common.material.application.port.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BudgetOfficeMaterialParserTest {
    @Test void allCommonFormatsAreAcceptedButArchivesAndExecutablesAreNot() {
        var parser=new BudgetOfficeMaterialParser(mock(DocumentTextPort.class),mock(MaterialFieldAiPort.class));
        for(String ext:List.of("doc","docx","xls","xlsx","pdf","ppt","pptx","txt","csv","rtf","png","jpg","jpeg","bmp","tif","tiff"))assertThat(parser.supports("预算."+ext,null)).isTrue();
        for(String ext:List.of("zip","rar","7z","exe","js"))assertThat(parser.supports("预算."+ext,null)).isFalse();
    }
    @Test void amountsRemainSourceGroundedAndEveryReturnedRowIsRequired() throws Exception {
        var reader=mock(DocumentTextPort.class);var ai=mock(MaterialFieldAiPort.class);
        var source=new MaterialFieldAiPort.Segment("word/row1","办公设备 申报金额31200 批复参考金额32760");
        when(reader.read(any(),anyString(),anyString(),anyLong(),anyInt(),anyInt())).thenReturn(new DocumentTextPort.Result(List.of(source),"OFFICE_TEXT","a".repeat(64)));
        var facts=List.of(new MaterialFieldAiPort.Fact("subjectName","办公设备",source.locator(),source.text()),new MaterialFieldAiPort.Fact("declaredAmount","31200",source.locator(),"申报金额31200"));
        when(ai.extract(eq("BUDGET"),anyString(),anyString(),anyList())).thenReturn(new MaterialFieldAiPort.Result(List.of(new MaterialFieldAiPort.Row("one",facts)),"model","v1"));
        var parser=new BudgetOfficeMaterialParser(reader,ai);
        assertThat(parser.parse(InputStream.nullInputStream(),"预算.docx").get(0).declaredAmount()).isEqualByComparingTo("31200");
        var incomplete=new MaterialFieldAiPort.Row("two",List.of(facts.get(0)));
        when(ai.extract(eq("BUDGET"),anyString(),anyString(),anyList())).thenReturn(new MaterialFieldAiPort.Result(List.of(new MaterialFieldAiPort.Row("one",facts),incomplete),"model","v1"));
        assertThatThrownBy(()->parser.parse(InputStream.nullInputStream(),"预算.docx")).isInstanceOf(IOException.class).hasMessageContaining("未保存部分结果");
        when(ai.extract(eq("BUDGET"),anyString(),anyString(),anyList())).thenReturn(new MaterialFieldAiPort.Result(List.of(new MaterialFieldAiPort.Row("one",List.of(facts.get(0),new MaterialFieldAiPort.Fact("declaredAmount","99999",source.locator(),source.text())))),"model","v1"));
        assertThatThrownBy(()->parser.parse(InputStream.nullInputStream(),"预算.docx")).isInstanceOf(IOException.class).hasMessageContaining("原文依据");
    }
}
