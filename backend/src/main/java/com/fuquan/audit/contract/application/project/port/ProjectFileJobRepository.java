package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.project.ProjectFileJob;
import java.time.Instant;
import java.util.*;

public interface ProjectFileJobRepository {
    void insert(ProjectFileJob job);
    Optional<ProjectFileJob> find(UUID id);
    Optional<ProjectFileJob> byFile(UUID projectId, UUID fileId);
    List<ProjectFileJob> byProject(UUID projectId);
    List<ProjectFileJob> pending(Instant now, int limit);
    List<ProjectFileJob.Attempt> attempts(UUID jobId);
    boolean claim(UUID id, String token, Instant now, Instant until);
    boolean renew(UUID id, String token, Instant now, Instant until);
    default void requireLease(UUID id,String token,Instant now) {
        var j=find(id).orElseThrow();
        if(!"PROCESSING".equals(j.status())||!Objects.equals(token,j.leaseToken())||!j.leaseUntil().isAfter(now)) {
            throw new IllegalStateException("processing lease lost");
        }
    }
    /** CAS must occur inside the same transaction that publishes immutable parse/file facts. */
    boolean finish(UUID id, String token, String status, String reason, UUID parseResultId, Instant now);
    boolean retry(UUID projectId, UUID id, Instant now);
}
