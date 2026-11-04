package com.fuquan.audit.review.application.port;

import java.util.List;
import java.util.Objects;

/**
 * 独立 OCR 服务返回的文档级识别结果。
 *
 * @param documentId 对应请求的文档标识
 * @param text 合并后的全文文本
 * @param confidence 0 到 1 之间的文档级置信度
 * @param pages 分页识别结果
 * @param simulated 是否为模拟结果
 * @param engine OCR 引擎安全标识
 * @param durationMs 下游识别耗时（毫秒）
 */
public record OcrResult(
        String documentId,
        String text,
        double confidence,
        List<OcrPage> pages,
        boolean simulated,
        String engine,
        long durationMs) {

    /**
     * 校验 OCR 结果字段并建立分页结果不可变快照。
     *
     * @throws NullPointerException 文档标识、文本或分页列表为空时抛出
     * @throws IllegalArgumentException 文档级置信度越界时抛出
     */
    public OcrResult {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(text, "text must not be null");
        validateConfidence(confidence);
        pages = List.copyOf(Objects.requireNonNull(pages, "pages must not be null"));
        if (engine == null || engine.isBlank()) {
            throw new IllegalArgumentException("engine must not be blank");
        }
        engine = engine.trim();
        if (engine.length() > 100) {
            throw new IllegalArgumentException("engine must not exceed 100 characters");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative");
        }
    }

    /**
     * 校验 OCR 置信度是 0 到 1 之间的有限值。
     *
     * @param confidence 待校验置信度
     * @throws IllegalArgumentException 置信度非有限值或越界时抛出
     */
    private static void validateConfidence(double confidence) {
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}
