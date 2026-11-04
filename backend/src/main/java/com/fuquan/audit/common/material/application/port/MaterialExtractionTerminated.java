package com.fuquan.audit.common.material.application.port;

/** Local processing has ended without a publishable result; provider outcome may remain unknown. */
public final class MaterialExtractionTerminated extends IllegalStateException {
    private final String reasonCode;
    public MaterialExtractionTerminated(String reasonCode) {
        super("material extraction stage=EXECUTION reasonCode="+reasonCode);
        if (!java.util.Set.of("MATERIAL_CALL_TIMEOUT", "MATERIAL_TIME_LIMIT", "MATERIAL_QUEUE_TIMEOUT").contains(reasonCode))
            throw new IllegalArgumentException("invalid termination reason");
        this.reasonCode=reasonCode;
    }
    public String reasonCode(){return reasonCode;}
}
