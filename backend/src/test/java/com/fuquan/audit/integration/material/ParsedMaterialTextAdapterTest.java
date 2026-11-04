package com.fuquan.audit.integration.material;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.material.application.port.*;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParsedMaterialTextAdapterTest {
    @Test void legacyConsumersReceiveExactLocatorsHashAndTypedTablesWithOneParse() throws Exception {
        var parser=mock(MaterialParsePort.class);
        var cell=new MaterialParsePort.Cell("excel/sheet:1/row:3/col:2",3,2,"100","NUMERIC",null);
        var table=new MaterialParsePort.Table("excel/sheet:1",List.of(cell),false);
        when(parser.parse(any())).thenReturn(new MaterialParsePort.ParseResult("doc","a".repeat(64),"application/pdf",
                MaterialParsePort.ParseStatus.PARSED,"100","b".repeat(64),List.of(),
                List.of(new MaterialParsePort.Paragraph("excel/sheet:1/row:3/part:1","100")),List.of(table),List.of(cell),
                List.of(),MaterialParsePort.PARSER_VERSION,Map.of(),List.of(),null));
        var result=new ParsedMaterialTextAdapter(parser).read(new ByteArrayInputStream(new byte[]{1}),"a.xlsx","doc",1024,10,1000);
        assertThat(result.segments()).containsExactly(new MaterialFieldAiPort.Segment("excel/sheet:1/row:3/part:1","100"));
        assertThat(result.tables()).containsExactly(table);
        assertThat(result.sha256()).isEqualTo("a".repeat(64));
        verify(parser,times(1)).parse(any());
    }
}
