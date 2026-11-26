package com.fuquan.audit.procurement.autocase;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CaseReferenceBoundaryTest {
    @Test void prohibitionMentioningHistoryCannotHideFollowingTenderPagesOrCurrentQuote(){
        var doc=mock(AuditCase.Document.class);var pages=List.of(new Segment("pdf/page:1/part:1","招标文件\n本项目预算2200000元。\n不得以历史业绩金额作为本次报价。"),
                new Segment("pdf/page:2/part:1","投标单位须为本地企业，必须使用指定品牌。"),
                new Segment("pdf/page:3/part:1","投标报价表\n投标报价总额2300000元。\n历史业绩：其他项目合同金额3800000元。"),
                new Segment("pdf/page:4/part:1","采购要求\n验收合格后15日内付款。"));when(doc.segments()).thenReturn(pages);
        var index=new ProcurementMaterialSemantics.Index(doc);
        for(var page:pages)assertThat(index.currentSegment(page.locator(),0)).isTrue();
        assertThat(index.current(pages.get(2).locator(),"投标报价总额2300000元。","2300000元","CURRENT_PROJECT")).isTrue();
        assertThat(index.current(pages.get(2).locator(),"历史业绩：其他项目合同金额3800000元。","3800000元",null)).isFalse();
    }
    @Test void realHistoryChapterStillExcludesPortfolioUntilCurrentQuoteHeading(){
        var doc=mock(AuditCase.Document.class);var a=new Segment("page:1","类似项目业绩情况表\n旧项目合同金额9000000元");var b=new Segment("page:2","旧项目延续内容");var c=new Segment("page:3","投标报价表\n本次投标报价总额120000元");when(doc.segments()).thenReturn(List.of(a,b,c));
        var index=new ProcurementMaterialSemantics.Index(doc);
        assertThat(index.currentSegment(a.locator(),a.text().indexOf("9000000"))).isFalse();assertThat(index.currentSegment(b.locator(),0)).isFalse();
        assertThat(index.currentSegment(c.locator(),c.text().indexOf("120000"))).isTrue();
    }
}
