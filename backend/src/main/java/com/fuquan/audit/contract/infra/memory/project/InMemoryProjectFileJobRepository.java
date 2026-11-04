package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.port.ProjectFileJobRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileJob;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Explicit local/test seam. Formal profiles use DM8. */
@Repository @Profile("local")
public class InMemoryProjectFileJobRepository implements ProjectFileJobRepository {
    private final Map<UUID, ProjectFileJob> jobs = new LinkedHashMap<>();
    private final Map<UUID, List<ProjectFileJob.Attempt>> attempts = new HashMap<>();
    public synchronized void insert(ProjectFileJob job) {
        if (jobs.containsKey(job.id()) || jobs.values().stream().anyMatch(j -> j.fileId().equals(job.fileId()))) {
            throw new IllegalStateException("file processing identity already exists");
        }
        jobs.put(job.id(), job);
    }
    public synchronized Optional<ProjectFileJob> find(UUID id) { return Optional.ofNullable(jobs.get(id)); }
    public synchronized Optional<ProjectFileJob> byFile(UUID projectId, UUID fileId) {
        return jobs.values().stream().filter(j -> j.projectId().equals(projectId) && j.fileId().equals(fileId)).findFirst();
    }
    public synchronized List<ProjectFileJob> byProject(UUID projectId) {
        return jobs.values().stream().filter(j -> j.projectId().equals(projectId)).toList();
    }
    private boolean claimable(ProjectFileJob j, Instant now) {
        return j != null && ("QUEUED".equals(j.status())
                || ("PROCESSING".equals(j.status()) && !j.leaseUntil().isAfter(now)));
    }
    private boolean owned(ProjectFileJob j, String token, Instant now) {
        return j != null && "PROCESSING".equals(j.status()) && Objects.equals(j.leaseToken(), token)
                && j.leaseUntil().isAfter(now);
    }
    public synchronized List<ProjectFileJob> pending(Instant now, int limit) {
        return jobs.values().stream().filter(j -> claimable(j, now))
                .sorted(Comparator.comparing(ProjectFileJob::createdAt).thenComparing(j -> j.id().toString()))
                .limit(limit).toList();
    }
    public synchronized List<ProjectFileJob.Attempt> attempts(UUID id) {
        return List.copyOf(attempts.getOrDefault(id, List.of()));
    }
    public synchronized boolean claim(UUID id, String token, Instant now, Instant until) {
        var current = jobs.get(id);
        if (!claimable(current, now) || token == null || !until.isAfter(now)) return false;
        if ("PROCESSING".equals(current.status())) closeAttempt(current, "EXPIRED", "LEASE_EXPIRED", now);
        var next = current.claim(token, now, until);
        jobs.put(id, next);
        attempts.computeIfAbsent(id, unused -> new ArrayList<>()).add(new ProjectFileJob.Attempt(
                id, next.attemptNo(), token, "PROCESSING", null, now, null));
        return true;
    }
    public synchronized boolean renew(UUID id, String token, Instant now, Instant until) {
        var current = jobs.get(id);
        if (!owned(current, token, now) || !until.isAfter(now)) return false;
        jobs.put(id, current.renew(until, now)); return true;
    }
    public synchronized boolean finish(UUID id, String token, String status, String reason, UUID output, Instant now) {
        var current=jobs.get(id);
        if (!Set.of("SUCCEEDED","FAILED","UNKNOWN","QUEUED").contains(status) || !owned(current, token, now)) return false;
        closeAttempt(current, "QUEUED".equals(status)?"DEFERRED":status, reason, now);
        jobs.put(id, current.finish(status, reason, output, now)); return true;
    }
    private void closeAttempt(ProjectFileJob current, String status, String reason, Instant now) {
        var list = attempts.get(current.id());
        if (list == null || list.isEmpty()) throw new IllegalStateException("attempt identity missing");
        var old = list.get(list.size()-1);
        list.set(list.size()-1, new ProjectFileJob.Attempt(old.jobId(),old.attemptNo(),old.leaseToken(),status,
                reason,old.startedAt(),now));
    }
    public synchronized boolean retry(UUID projectId, UUID id, Instant now) {
        var current=jobs.get(id);
        if(current == null || !current.projectId().equals(projectId) || !"FAILED".equals(current.status())) return false;
        jobs.put(id,current.finish("QUEUED",null,null,now));return true;
    }
}
