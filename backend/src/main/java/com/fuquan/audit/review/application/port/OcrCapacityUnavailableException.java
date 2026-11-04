package com.fuquan.audit.review.application.port;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;

/** Transient admission refusal, not a corrupt document or failed OCR result. */
public class OcrCapacityUnavailableException extends BusinessException {
    public OcrCapacityUnavailableException() {
        super(BusinessErrorCode.EXTERNAL_SERVICE_ERROR,"OCR 正在处理其他材料，请稍后继续");
    }
}
