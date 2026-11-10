package com.fuquan.audit.integration.material;

import com.fuquan.audit.common.material.visual.*;
import com.fuquan.audit.common.material.visual.VisualIndex.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import java.io.*;
import java.util.*;
import java.awt.image.BufferedImage;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class OriginalVisualEvidenceTest {
    static final String SHA="a".repeat(64);
    static final List<Point> BOX=List.of(new Point(.1,.1),new Point(.8,.1),new Point(.8,.2),new Point(.1,.2));
    static class Store implements VisualIndexStore {
        VisualIndex value;
        public void save(VisualIndex v){value=v;}
        public Optional<VisualIndex> find(String id,String sha,String text){return value!=null&&value.documentId().equals(id)&&value.fileSha256().equals(sha)&&value.textSha256().equals(text)?Optional.of(value):Optional.empty();}
    }
    @Test void splitUnicodeAndCrossBoundarySpansMapToFrozenCanonicalText(){
        String first="甲".repeat(3998)+"😀",second="金额100元";String raw=first+second;
        var segments=List.of(new Segment("pdf/page:1/text/part:1",first),new Segment("pdf/page:1/text/part:2",second));
        var block=new Block("pdf/page:1/text",1,MaterialVisualEvidence.digest(raw),raw.length(),"NATIVE",List.of(new Region(3998,4004,BOX,1)));
        var index=VisualIndexBuilder.build("d",SHA,segments,List.of(block));
        assertThat(index.anchors()).singleElement().satisfies(a->{assertThat(a.start()).isEqualTo(3998);assertThat(a.end()).isEqualTo(4005);});
        var store=new Store();store.save(index);var visual=new MaterialVisualEvidence(store);
        String canonical=first+"\n"+second;
        assertThat(visual.locate("d",SHA,canonical,3998,4005,null).status()).isEqualTo("LOCATED");
        assertThat(visual.locate("d",SHA,canonical,3998,4006,1).status()).isEqualTo("PAGE_ONLY");
        assertThat(visual.locate("d","b".repeat(64),canonical,3998,4005,null).status()).isEqualTo("BROWSE");
    }
    @Test void repeatedAmountsKeepTheirOwnOffsetsAndWhitespaceGapsAreAllowed(){
        String text="100元\n100元";var store=new Store();
        store.save(new VisualIndex("d",SHA,MaterialVisualEvidence.digest(text),List.of(new Anchor(1,"OCR",0,4,BOX,.9),new Anchor(2,"OCR",5,9,BOX,.9,"UNCERTAIN"))));
        var visual=new MaterialVisualEvidence(store);
        assertThat(visual.locate("d",SHA,text,5,9,null).pages()).containsExactly(2);
        assertThat(visual.locate("d",SHA,text,0,9,null).pages()).containsExactly(1,2);
        assertThat(visual.uncertain("d",SHA,text,0,4)).isFalse();assertThat(visual.uncertain("d",SHA,text,5,9)).isTrue();
        assertThat(visual.uncertain("d",SHA,text,5,1000)).isFalse();
        assertThatThrownBy(()->visual.locate("d",SHA,text,-1,4,null)).isInstanceOf(RuntimeException.class);
    }
    @Test void staleOrPartlyMappedIndexCannotProducePreciseHighlight(){
        String text="金额100元";var store=new Store();
        store.save(new VisualIndex("d",SHA,MaterialVisualEvidence.digest(text),List.of(new Anchor(1,"OCR",0,2,BOX,.9),new Anchor(1,"OCR",3,6,BOX,.9))));
        var visual=new MaterialVisualEvidence(store);
        assertThat(visual.locate("d",SHA,text,0,6,1).status()).isEqualTo("PAGE_ONLY");
        store.save(new VisualIndex("d",SHA,MaterialVisualEvidence.digest(text),List.of(new Anchor(1,"OCR",0,200,BOX,.9))));
        assertThat(visual.locate("d",SHA,text,0,6,null).anchors()).isEmpty();
    }
    @Test void manyFactsReuseOneFrozenCoordinateReadAndDoNotConfuseRepeatedQuotes(){
        var store=org.mockito.Mockito.mock(VisualIndexStore.class);String text="金额100元\n金额100元";
        org.mockito.Mockito.when(store.find("d",SHA,MaterialVisualEvidence.digest(text))).thenReturn(Optional.of(new VisualIndex("d",SHA,MaterialVisualEvidence.digest(text),List.of(new Anchor(2,"OCR",7,13,BOX,.7,"UNCERTAIN")))));
        var quality=new MaterialVisualEvidence(store).quotes("d",SHA,List.of(new Segment("pdf/page:1/part:1","金额100元"),new Segment("pdf/page:2/part:1","金额100元")));
        for(int i=0;i<2000;i++){
            assertThat(quality.uncertain("pdf/page:1/part:1","金额100元")).isFalse();
            assertThat(quality.uncertain("pdf/page:2/part:1","金额100元")).isTrue();
        }
        org.mockito.Mockito.verify(store,org.mockito.Mockito.times(1)).find("d",SHA,MaterialVisualEvidence.digest(text));
    }
    @ParameterizedTest @ValueSource(ints={0,90,180,270})
    void nativeHighlightsContainActualRenderedInkAfterCropAndRotation(int rotation)throws Exception {
        try(var doc=new PDDocument()){
            var page=new PDPage(new PDRectangle(450,650));page.setCropBox(new PDRectangle(25,30,390,560));page.setRotation(rotation);doc.addPage(page);
            try(var stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,16);stream.newLineAtOffset(75,440);stream.showText("Amount 12345.67");stream.endText();}
            var stripper=new PdfPositionedText();stripper.setSortByPosition(true);String text=stripper.getText(doc);var regions=stripper.regions(text);
            assertThat(regions).isNotEmpty();BufferedImage image=new PDFRenderer(doc).renderImage(0,2);
            var boxes=regions.stream().map(r->{double minX=r.polygon().stream().mapToDouble(Point::x).min().orElseThrow(),maxX=r.polygon().stream().mapToDouble(Point::x).max().orElseThrow(),minY=r.polygon().stream().mapToDouble(Point::y).min().orElseThrow(),maxY=r.polygon().stream().mapToDouble(Point::y).max().orElseThrow();return new double[]{minX*image.getWidth()-2,minY*image.getHeight()-2,maxX*image.getWidth()+2,maxY*image.getHeight()+2};}).toList();
            int ink=0,covered=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)&0xffffff)<0x888888){ink++;for(var box:boxes)if(x>=box[0]&&x<=box[2]&&y>=box[1]&&y<=box[3]){covered++;break;}}
            assertThat(ink).isGreaterThan(500);assertThat((double)covered/ink).as("rotation %s ink coverage",rotation).isGreaterThan(.98);
        }
    }
    @Test void rendererUsesActualOriginalBytesAndRejectsSourceChange()throws Exception {
        byte[] pdf;try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){doc.addPage(new PDPage());doc.addPage(new PDPage());doc.save(out);pdf=out.toByteArray();}
        var renderer=new OriginalPageRenderer();var image=renderer.render(new ByteArrayInputStream(pdf),pdf.length,MaterialVisualEvidence.digest(pdf),2);
        assertThat(image.pageCount()).isEqualTo(2);assertThat(image.png()).startsWith((byte)137,(byte)80,(byte)78,(byte)71);
        assertThatThrownBy(()->renderer.render(new ByteArrayInputStream(pdf),pdf.length,SHA,1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->renderer.render(new ByteArrayInputStream(pdf),pdf.length,MaterialVisualEvidence.digest(pdf),3)).isInstanceOf(RuntimeException.class);
        assertThat(renderer.render(new ByteArrayInputStream(pdf),pdf.length,MaterialVisualEvidence.digest(pdf),1).pageCount()).isEqualTo(2);
    }
    @Test void legacyWordPreviewReadsNativeOriginalAndRejectsWrongHash()throws Exception {
        byte[] bytes;try(var in=getClass().getResourceAsStream("/project-file-fixtures/preview-table.doc")){bytes=in.readAllBytes();}
        var renderer=new OriginalPageRenderer();String sha=MaterialVisualEvidence.digest(bytes);
        String html=renderer.legacyWord(new ByteArrayInputStream(bytes),bytes.length,sha);
        assertThat(html).contains("<html", "<body", "<p", "<table");
        assertThatThrownBy(()->renderer.legacyWord(new ByteArrayInputStream(bytes),bytes.length,SHA)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->renderer.legacyWord(new ByteArrayInputStream(bytes),bytes.length+1,sha)).isInstanceOf(RuntimeException.class);
        assertThat(renderer.legacyWord(new ByteArrayInputStream(bytes),bytes.length,sha)).isEqualTo(html);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7,8})
    void exifTransformsKeepAllFourCornerColors(int orientation){
        var source=new BufferedImage(20,30,BufferedImage.TYPE_INT_RGB);source.setRGB(0,0,0xff0000);source.setRGB(19,0,0x00ff00);source.setRGB(0,29,0x0000ff);source.setRGB(19,29,0xffffff);
        var result=OriginalPageRenderer.orient(source,orientation);
        assertThat(result.getWidth()).isEqualTo(orientation>=5?30:20);assertThat(result.getHeight()).isEqualTo(orientation>=5?20:30);
        assertThat(List.of(result.getRGB(0,0)&0xffffff,result.getRGB(result.getWidth()-1,0)&0xffffff,result.getRGB(0,result.getHeight()-1)&0xffffff,result.getRGB(result.getWidth()-1,result.getHeight()-1)&0xffffff)).containsExactlyInAnyOrder(0xff0000,0x00ff00,0x0000ff,0xffffff);
    }
}
