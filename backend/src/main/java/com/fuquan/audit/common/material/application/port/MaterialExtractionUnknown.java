package com.fuquan.audit.common.material.application.port;

/** No automatic replay or clean/failed business conclusion is justified. */
public class MaterialExtractionUnknown extends IllegalStateException {
    private final String reasonCode;
    public MaterialExtractionUnknown(String reason){super("material extraction stage=CHECKPOINT reasonCode="+reason);this.reasonCode=reason;}
    public MaterialExtractionUnknown(String reason,Throwable cause){super("material extraction stage=CHECKPOINT reasonCode="+reason,cause);this.reasonCode=reason;}
    public String reasonCode(){return reasonCode;}
}
