package com.fuquan.audit.contract.application.debug;

import com.fuquan.audit.common.exception.*;

public enum DebugContentLevel {
    METADATA_ONLY, CUSTOMER_POLICY_APPROVED_REDACTED;
    public void requireAvailable() {
        if (this != METADATA_ONLY) throw new BusinessException(BusinessErrorCode.FORBIDDEN,
                "customer debug redaction policy is not approved");
    }
}
