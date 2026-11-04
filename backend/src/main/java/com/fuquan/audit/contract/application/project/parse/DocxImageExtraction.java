package com.fuquan.audit.contract.application.project.parse;

import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.review.application.port.*;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.*;

/** Per-document image evidence; never joins OCR into typed native table cells. */
final class DocxImageExtraction {
    private final ProjectFile file;
    private final OcrPort ocr;
    private final long started;
    private int count;
    private BigDecimal confidence=BigDecimal.ZERO;
    private final Set<String> engines=new LinkedHashSet<>(List.of("APACHE_POI_4.1.2"));
    DocxImageExtraction(ProjectFile file,OcrPort ocr,long started){this.file=file;this.ocr=ocr;this.started=started;}
    void body(IBody body,String locator,List<ParsedProjectDocument.Segment> segments){
        int index=0;
        for(var element:body.getBodyElements()){
            String path=locator+"/element:"+(++index);
            if(element instanceof XWPFParagraph paragraph)paragraph(paragraph,path,segments);
            else if(element instanceof XWPFTable table){
                int rowIndex=0;
                for(var row:table.getRows()){
                    int cellIndex=0;rowIndex++;
                    for(var cell:row.getTableCells())body(cell,path+"/row:"+rowIndex+"/cell:"+(++cellIndex),segments);
                }
            }
        }
    }
    void paragraph(XWPFParagraph paragraph,String locator,List<ParsedProjectDocument.Segment> segments){
        int occurrence=0;
        for(var run:paragraph.getRuns())for(var picture:run.getEmbeddedPictures()){
            if(++count>200)throw new ProjectParseException("DOCX_IMAGE_COUNT_LIMIT");
            budget();
            if(ocr==null)throw new ProjectParseException("DOCX_IMAGE_OCR_UNAVAILABLE");
            var blip=picture.getCTPicture().getBlipFill().getBlip();
            if(blip==null||blip.isSetLink()||picture.getPictureData()==null)
                throw new ProjectParseException("DOCX_IMAGE_SOURCE_UNPROVEN");
            byte[] original=picture.getPictureData().getData();
            if(original.length>50L*1024*1024)throw new ProjectParseException("DOCX_IMAGE_BYTE_LIMIT");
            byte[] payload=png(original);
            OcrResult result;
            try{
                result=ocr.recognize(new OcrRequest(file.id().toString(),"embedded.png","image/png",new OcrDocumentSource(){
                    public InputStream openStream(){return new ByteArrayInputStream(payload);}
                    public long contentLength(){return payload.length;}
                }));
            }catch(OcrCapacityUnavailableException busy){throw busy;}
            catch(OcrOutcomeUnprovenException uncertain){throw uncertain;}
            catch(com.fuquan.audit.common.exception.ExternalServiceException uncertain){throw new OcrOutcomeUnprovenException(uncertain);}
            catch(RuntimeException failure){throw new ProjectParseException("DOCX_IMAGE_OCR_FAILED",failure);}
            budget();
            if(!file.id().toString().equals(result.documentId()))throw new OcrOutcomeUnprovenException(
                    new IllegalStateException("DOCX_IMAGE_RESULT_ID_MISMATCH"));
            if(result.simulated())throw new ProjectParseException("OCR_NOT_AVAILABLE");
            if(result.pages().size()!=1||result.pages().get(0).pageNumber()!=1
                    ||!result.text().equals(result.pages().get(0).text()))
                throw new ProjectParseException("DOCX_IMAGE_COVERAGE_UNPROVEN");
            // Empty recognition can be a logo or decoration. It contributes no
            // fabricated text; the OCR attempt and confidence remain provenance.
            if(!result.text().isBlank())segments.add(new ParsedProjectDocument.Segment(
                    locator+"/image:"+(++occurrence),result.text()));
            else occurrence++;
            confidence=confidence.add(BigDecimal.valueOf(result.confidence()));engines.add(result.engine());
        }
    }
    private byte[] png(byte[] bytes){
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new ProjectParseException("DOCX_IMAGE_FORMAT_UNSUPPORTED");
            var reader=readers.next();
            try{
                reader.setInput(input);
                if(reader.getNumImages(true)!=1)throw new ProjectParseException("DOCX_IMAGE_FRAME_COVERAGE_UNPROVEN");
                if((long)reader.getWidth(0)*reader.getHeight(0)>40_000_000L)throw new ProjectParseException("DOCX_IMAGE_PIXEL_LIMIT");
                var image=reader.read(0);
                try(var output=new ByteArrayOutputStream()){
                    if(!ImageIO.write(image,"png",output))throw new ProjectParseException("DOCX_IMAGE_FORMAT_UNSUPPORTED");
                    if(output.size()>50L*1024*1024)throw new ProjectParseException("DOCX_IMAGE_BYTE_LIMIT");
                    return output.toByteArray();
                }finally{image.flush();}
            }finally{reader.dispose();}
        }catch(IOException failure){throw new ProjectParseException("DOCX_IMAGE_READ_FAILED",failure);}
    }
    ProjectFileParseProvenance provenance(){
        String engine=String.join("+",engines);
        return new ProjectFileParseProvenance(count==0?ProjectFileParseMethod.DOCX_TEXT:ProjectFileParseMethod.OCR,
                engine.substring(0,Math.min(100,engine.length())),
                count==0?null:confidence.divide(BigDecimal.valueOf(count),4,RoundingMode.HALF_UP),null,
                Math.max(0,(System.nanoTime()-started)/1_000_000));
    }
    private void budget(){if(System.nanoTime()-started>1_800_000_000_000L)throw new ProjectParseException("DOCX_PROCESSING_DEADLINE");}
}
