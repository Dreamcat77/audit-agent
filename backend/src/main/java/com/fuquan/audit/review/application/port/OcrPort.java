package com.fuquan.audit.review.application.port;

/**
 * Java 应用调用独立 OCR 服务的出站端口。
 *
 * <p>Python/FastAPI 及原生 OCR 依赖位于外部服务，由 integration HTTP 适配器隔离，不嵌入 Java 进程；
 * F02 结构化确定性审查不依赖该端口。</p>
 */
public interface OcrPort {

    /**
     * 请求外部 OCR 服务识别一个文档。
     *
     * @param request 包含文档身份、媒体类型和可重新打开文件来源的请求
     * @return 外部服务的文档及分页识别结果
     */
    OcrResult recognize(OcrRequest request);
}
