package com.fuquan.audit.integration.material;

import java.awt.geom.Point2D;
import java.io.IOException;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

/** Counts displayed image area, including form XObjects; small logos do not force OCR. */
final class PdfImageCoverage extends PDFGraphicsStreamEngine {
    private double largest;
    private Point2D point=new Point2D.Float();
    private PdfImageCoverage(PDPage page){super(page);}
    static boolean hasLargeImage(PDPage page) throws IOException {
        var engine=new PdfImageCoverage(page);engine.processPage(page);
        return engine.largest>=page.getCropBox().getWidth()*page.getCropBox().getHeight()*0.02d;
    }
    @Override public void drawImage(PDImage image) {
        var m=getGraphicsState().getCurrentTransformationMatrix();
        largest=Math.max(largest,Math.abs(m.getScaleX()*m.getScaleY()-m.getShearX()*m.getShearY()));
    }
    @Override public void appendRectangle(Point2D a,Point2D b,Point2D c,Point2D d){}
    @Override public void clip(int rule){}
    @Override public void moveTo(float x,float y){point=new Point2D.Float(x,y);}
    @Override public void lineTo(float x,float y){point=new Point2D.Float(x,y);}
    @Override public void curveTo(float a,float b,float c,float d,float e,float f){point=new Point2D.Float(e,f);}
    @Override public Point2D getCurrentPoint(){return point;}
    @Override public void closePath(){}
    @Override public void endPath(){}
    @Override public void strokePath(){}
    @Override public void fillPath(int rule){}
    @Override public void fillAndStrokePath(int rule){}
    @Override public void shadingFill(COSName name){}
}
