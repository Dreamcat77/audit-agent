package com.fuquan.audit.common.material.visual;

import java.util.*;

/** Derived coordinates tied to an immutable source and its exact canonical text. No copied source text. */
public record VisualIndex(String documentId,String fileSha256,String textSha256,List<Anchor> anchors) {
    public VisualIndex {
        if(documentId==null||documentId.isBlank()||documentId.length()>200||!hash(fileSha256)||!hash(textSha256))throw new IllegalArgumentException("invalid visual identity");
        anchors=List.copyOf(anchors);if(anchors.size()>100000)throw new IllegalArgumentException("visual index too large");
    }
    public static boolean hash(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    public record Point(double x,double y) {
        public Point {if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1)throw new IllegalArgumentException("invalid visual point");}
    }
    public record Region(int start,int end,List<Point> polygon,double confidence,String verification) {
        public Region(int start,int end,List<Point> polygon,double confidence){this(start,end,polygon,confidence,"NOT_RECHECKED");}
        public Region {
            verification=verification==null?"NOT_RECHECKED":verification;
            polygon=List.copyOf(polygon);
            if(start<0||end<=start||polygon.size()!=4||!Double.isFinite(confidence)||confidence<0||confidence>1||!Set.of("NOT_RECHECKED","MATCHED","UNCERTAIN").contains(verification))throw new IllegalArgumentException("invalid visual region");
        }
    }
    public record Anchor(int pageNumber,String method,int start,int end,List<Point> polygon,double confidence,String verification) {
        public Anchor(int pageNumber,String method,int start,int end,List<Point> polygon,double confidence){this(pageNumber,method,start,end,polygon,confidence,"NOT_RECHECKED");}
        public Anchor {
            verification=verification==null?"NOT_RECHECKED":verification;
            new Region(start,end,polygon,confidence,verification);polygon=List.copyOf(polygon);
            if(pageNumber<1||pageNumber>1024||!Set.of("NATIVE","OCR").contains(method))throw new IllegalArgumentException("invalid visual anchor");
        }
    }
    public record Block(String locator,int pageNumber,String textSha256,int textLength,String method,List<Region> regions) {
        public Block {
            regions=List.copyOf(regions);
            if(locator==null||locator.length()>300||pageNumber<1||pageNumber>1024||!hash(textSha256)||textLength<1||regions.size()>10000
                    ||!Set.of("NATIVE","OCR").contains(method)||regions.stream().anyMatch(r->r.end()>textLength))throw new IllegalArgumentException("invalid visual block");
        }
    }
}
