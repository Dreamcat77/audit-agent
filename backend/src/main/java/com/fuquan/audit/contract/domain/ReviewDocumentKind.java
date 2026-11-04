package com.fuquan.audit.contract.domain;

import java.util.UUID;

/** 合同审核工作台当前支持的五种材料类型。 */
public enum ReviewDocumentKind {
    CONTRACT_BODY(true),
    BIDDING_DOCUMENT(true),
    TENDER_DOCUMENT(false),
    AWARD_NOTICE(true),
    OTHER(false);

    private final boolean singleton;

    ReviewDocumentKind(boolean singleton) {
        this.singleton = singleton;
    }

    public boolean singleton() {
        return singleton;
    }

    /** Slot key 始终由服务端决定。 */
    public String newSlotKey(UUID documentId) {
        return singleton ? name() : name() + ":" + documentId;
    }
}
