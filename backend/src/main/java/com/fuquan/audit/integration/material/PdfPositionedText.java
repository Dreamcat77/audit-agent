package com.fuquan.audit.integration.material;
import com.fuquan.audit.common.material.visual.VisualIndex.*;
import java.io.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.text.*;

/** Same PDFTextStripper text policy, retaining renderer-space line rectangles. */
final class PdfPositionedText extends PDFTextStripper {
    record Run(String text,List<Point> polygon) {}
    private final List<Run> runs=new ArrayList<>();
    private double width,height;private int rotation;
    PdfPositionedText() throws IOException {super();}
    List<Region> regions(String text){
        var result=new ArrayList<Region>();int cursor=0;
        for(var run:runs){
            if(run.text().isBlank())continue;
            int at=text.indexOf(run.text(),cursor);
            if(at<0)continue;
            result.add(new Region(at,at+run.text().length(),run.polygon(),1));cursor=at+run.text().length();
        }
        return result;
    }
    @Override protected void startPage(PDPage page)throws IOException {
        runs.clear();int angle=Math.floorMod(page.getRotation(),360);rotation=angle;var crop=page.getCropBox();
        width=angle==90||angle==270?crop.getHeight():crop.getWidth();height=angle==90||angle==270?crop.getWidth():crop.getHeight();super.startPage(page);
    }
    @Override protected void writeString(String text,List<TextPosition> positions)throws IOException {
        double left=Double.POSITIVE_INFINITY,top=left,right=0,bottom=0;
        for(var p:positions){
            double a=Math.toRadians(rotation-p.getDir()),dx=Math.cos(a),dy=Math.sin(a);
            double w=p.getWidthDirAdj(),h=p.getHeightDir();
            if(!Double.isFinite(w)||!Double.isFinite(h)||w<=0||h<=0)continue;
            for(double along:new double[]{0,w})for(double up:new double[]{-h*.25,h*1.35}){
                double x=p.getX()+along*dx+up*dy,y=p.getY()+along*dy-up*dx;
                left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);
            }
        }
        if(width>0&&height>0&&left<right&&top<bottom&&left>=-2&&top>=-2&&right<=width+2&&bottom<=height+2){
            double x1=Math.max(0,left/width),y1=Math.max(0,top/height),x2=Math.min(1,right/width),y2=Math.min(1,bottom/height);
            runs.add(new Run(text,List.of(new Point(x1,y1),new Point(x2,y1),new Point(x2,y2),new Point(x1,y2))));
        }
        super.writeString(text,positions);
    }
}
