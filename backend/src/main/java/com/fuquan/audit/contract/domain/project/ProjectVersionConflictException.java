package com.fuquan.audit.contract.domain.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;

public final class ProjectVersionConflictException extends BusinessException {
    public ProjectVersionConflictException() {
        super(BusinessErrorCode.CONFLICT, "project version conflict");
    }
}
