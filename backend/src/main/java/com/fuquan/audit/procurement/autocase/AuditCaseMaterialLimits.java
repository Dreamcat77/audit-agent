package com.fuquan.audit.procurement.autocase;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The same byte limit governs admission and reading; text/page limits remain explicit failures. */
@Component
public record AuditCaseMaterialLimits(long maxBytes, int maxPages, int maxChars) {
    public static AuditCaseMaterialLimits defaults() { return new AuditCaseMaterialLimits(524288000L,512,768000); }
    public AuditCaseMaterialLimits(
            @Value("${audit.procurement.material.max-bytes:524288000}") long maxBytes,
            @Value("${audit.procurement.material.max-pages:512}") int maxPages,
            @Value("${audit.procurement.material.max-chars:768000}") int maxChars) {
        if(maxBytes<=0||maxPages<=0||maxChars<=0)throw new IllegalArgumentException("positive material limits required");
        this.maxBytes=maxBytes;this.maxPages=maxPages;this.maxChars=maxChars;
    }
}
