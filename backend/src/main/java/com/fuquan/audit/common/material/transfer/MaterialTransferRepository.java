package com.fuquan.audit.common.material.transfer;
import static com.fuquan.audit.common.material.transfer.MaterialTransfer.*;
import java.time.Instant;
import java.util.*;
public interface MaterialTransferRepository {
    Optional<Session> find(UUID id);
    void insert(Session session);
    List<Part> parts(UUID id);
    void insertPart(UUID id,Part part);
    boolean claim(UUID id,String token,Instant now,Instant until);
    boolean renew(UUID id,String token,Instant until);
    boolean complete(UUID id,String token,String json);
    void release(UUID id,String token);
}
