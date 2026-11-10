package com.fuquan.audit.review.application.port;

import java.util.Objects;

/**
 * 独立 OCR 服务返回的单页文本和置信度。
 *
 * @param pageNumber 从 1 开始的页码
 * @param text 该页识别文本
 * @param confidence 0 到 1 之间的页级置信度
 */
public record OcrPage(int pageNumber, String text, double confidence,
        java.util.List<com.fuquan.audit.common.material.visual.VisualIndex.Region> regions) {
    public OcrPage(int pageNumber,String text,double confidence){this(pageNumber,text,confidence,java.util.List.of());}

    /**
     * 校验页码、文本和置信度边界。
     *
     * @throws NullPointerException 文本为空时抛出
     * @throws IllegalArgumentException 页码非正数或置信度越界时抛出
     */
    public OcrPage {
        if (pageNumber <= 0) {
            throw new IllegalArgumentException("pageNumber must be positive");
        }
        Objects.requireNonNull(text, "text must not be null");
        regions=regions==null?java.util.List.of():java.util.List.copyOf(regions);
        if(regions.size()>10000||regions.stream().anyMatch(r->r.end()>text.length()))throw new IllegalArgumentException("invalid OCR regions");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}
