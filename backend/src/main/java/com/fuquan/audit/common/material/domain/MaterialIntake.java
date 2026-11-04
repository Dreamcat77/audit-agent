package com.fuquan.audit.common.material.domain;

import java.time.Instant;
import java.util.UUID;

public record MaterialIntake(UUID id, String actorId, String operationKey, String fileName,
        String mediaType, long fileSize, String sha256, String storageKey, String status,
        String leaseToken, Instant leaseUntil, MaterialIntakeFields fields, String parseMethod,
        String errorCode, Instant createdAt, Instant updatedAt) {
    public boolean timedOut(){return errorCode!=null&&java.util.Set.of("MATERIAL_CALL_TIMEOUT","MATERIAL_TIME_LIMIT","MATERIAL_QUEUE_TIMEOUT").contains(errorCode.replace("_CLEANED",""));}
    public boolean active(Instant now) {
        return "PROCESSING".equals(status) && leaseUntil.isAfter(now);
    }
    public MaterialIntake claim(String token, Instant until, Instant now) {
        return new MaterialIntake(id, actorId, operationKey, fileName, mediaType, fileSize,
                sha256, storageKey, "PROCESSING", token, until, null, null, null, createdAt, now);
    }
    public MaterialIntake finish(String state, MaterialIntakeFields values,
            String method, String error, Instant now) {
        return new MaterialIntake(id, actorId, operationKey, fileName, mediaType, fileSize,
                sha256, storageKey, state, leaseToken, leaseUntil, values, method, error, createdAt, now);
    }
}
