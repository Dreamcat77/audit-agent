package com.fuquan.audit.review.application.port;

import java.util.Objects;

/**
 * 发送给独立 OCR 服务的不可变文档请求。
 *
 * <p>文件内容由可重新打开的流提供，不在 Java 堆中物化为整文件字节数组。</p>
 */
public final class OcrRequest {

    private final String documentId;
    private final String fileName;
    private final String contentType;
    private final OcrDocumentSource source;

    /**
     * 创建并校验 OCR 文档请求。
     *
     * @param documentId 文档业务标识
     * @param fileName 原始文件名
     * @param contentType 文件媒体类型
     * @param source 可重新打开且长度为正的文件来源
     * @throws NullPointerException 任一必填字段为空时抛出
     * @throws IllegalArgumentException 文本字段为空白或文件内容为空时抛出
     */
    public OcrRequest(String documentId, String fileName, String contentType, OcrDocumentSource source) {
        this.documentId = requireNotBlank(documentId, "documentId");
        this.fileName = requireNotBlank(fileName, "fileName");
        this.contentType = requireNotBlank(contentType, "contentType");
        this.source = Objects.requireNonNull(source, "source must not be null");
        if (source.contentLength() <= 0) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
    }

    /**
     * 返回文档业务标识。
     *
     * @return 文档业务标识
     */
    public String documentId() {
        return documentId;
    }

    /**
     * 返回传给 OCR 服务的原始文件名。
     *
     * @return 原始文件名
     */
    public String fileName() {
        return fileName;
    }

    /**
     * 返回文件媒体类型。
     *
     * @return 文件媒体类型
     */
    public String contentType() {
        return contentType;
    }

    /**
     * 返回可重新打开的持久文件来源。
     *
     * @return 可重新打开的文件来源
     */
    public OcrDocumentSource source() {
        return source;
    }

    /**
     * 校验 OCR 请求中的必填文本并原样返回。
     *
     * @param value 待校验文本
     * @param fieldName 用于异常信息的字段名
     * @return 通过非空白校验的原文本
     * @throws NullPointerException 文本为 {@code null} 时抛出
     * @throws IllegalArgumentException 文本为空白时抛出
     */
    private static String requireNotBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
