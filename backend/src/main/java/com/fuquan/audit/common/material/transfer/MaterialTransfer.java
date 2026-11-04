package com.fuquan.audit.common.material.transfer;

import java.time.Instant;
import java.util.*;

public final class MaterialTransfer {
    private MaterialTransfer() {}
    public static final int CHUNK_BYTES=4*1024*1024;
    public static final long MAX_BYTES=500L*1024*1024;
    public record Spec(String fileName,String contentType,long size,String sha256,List<String> partHashes,
            UUID projectId,String budgetYear,String departmentCode,UUID batchId) {
        public Spec {partHashes=partHashes==null?null:List.copyOf(partHashes);}
    }
    public record Session(UUID id,String actor,String kind,UUID operation,Spec spec,String status,
            String token,Instant leaseUntil,String resultJson,Instant createdAt) {}
    public record Part(int index,long size,String sha256,String storageKey) {}
    public record Progress(UUID operation,String status,int chunkBytes,List<Integer> received,long receivedBytes) {}
}
