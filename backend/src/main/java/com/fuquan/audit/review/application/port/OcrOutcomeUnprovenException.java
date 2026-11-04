package com.fuquan.audit.review.application.port;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;

/** A response/transport failure does not prove the remote OCR outcome. */
public final class OcrOutcomeUnprovenException extends BusinessException {
    public OcrOutcomeUnprovenException(Throwable cause) {
        super(BusinessErrorCode.EXTERNAL_SERVICE_ERROR,"OCR outcome unproven",cause);
    }
}
