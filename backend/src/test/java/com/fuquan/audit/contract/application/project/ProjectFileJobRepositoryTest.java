package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.ProjectFileJob;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileJobRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectFileJobRepositoryTest {
    private final Instant now = Instant.parse("2026-10-14T00:00:00Z");
    private final ProjectRequestMetadata metadata = new ProjectRequestMetadata("request", "trace", "127.0.0.1");
    private ProjectFileJob job() {
        return ProjectFileJob.queued(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "actor",
                metadata.requestId(),metadata.traceId(),metadata.sourceIp(),now);
    }

    @Test void acceptanceIdentitySurvivesRediscoveryAndDoesNotCrossProjects() {
        var repository = new InMemoryProjectFileJobRepository();
        var first = job();
        repository.insert(first);
        assertThat(repository.byFile(first.projectId(), first.fileId())).contains(first);
        assertThat(repository.byFile(UUID.randomUUID(), first.fileId())).isEmpty();
        assertThatThrownBy(() -> repository.insert(ProjectFileJob.queued(UUID.randomUUID(), first.fileId(),
                "b".repeat(64), "actor",metadata.requestId(),metadata.traceId(),metadata.sourceIp(),now))).isInstanceOf(RuntimeException.class);
    }

    @Test void staleLeaseOwnerCannotPublishAfterExactJobIsReclaimed() {
        var repository = new InMemoryProjectFileJobRepository();
        var first = job(); repository.insert(first);
        assertThat(repository.claim(first.id(), "owner-one", now, now.plusSeconds(60))).isTrue();
        assertThat(repository.claim(first.id(), "owner-two", now, now.plusSeconds(60))).isFalse();
        var expired = now.plusSeconds(61);
        assertThat(repository.pending(expired, 1)).extracting(ProjectFileJob::id).containsExactly(first.id());
        assertThat(repository.claim(first.id(), "owner-two", expired, expired.plusSeconds(60))).isTrue();
        assertThat(repository.finish(first.id(), "owner-one", "SUCCEEDED", null, null, expired)).isFalse();
        assertThat(repository.finish(first.id(), "owner-two", "SUCCEEDED", null, null, expired)).isTrue();
        assertThat(repository.byFile(first.projectId(), first.fileId()).orElseThrow().attemptNo()).isEqualTo(2);
        assertThat(repository.pending(expired, 10)).isEmpty();
        assertThat(repository.attempts(first.id())).extracting(ProjectFileJob.Attempt::status)
                .containsExactly("EXPIRED", "SUCCEEDED");
    }

    @Test void heartbeatCannotResurrectExpiredLeaseAndUnknownCannotBeRetriedAsFailed() {
        var repository = new InMemoryProjectFileJobRepository();
        var first = job(); repository.insert(first);
        repository.claim(first.id(), "owner", now, now.plusSeconds(60));
        assertThat(repository.renew(first.id(), "owner", now.plusSeconds(61), now.plusSeconds(120))).isFalse();
        assertThat(repository.finish(first.id(), "owner", "SUCCEEDED", null, null, now.plusSeconds(61))).isFalse();
        repository.claim(first.id(), "new-owner", now.plusSeconds(61), now.plusSeconds(180));
        assertThat(repository.finish(first.id(), "new-owner", "UNKNOWN", "STORAGE_UNKNOWN", null,
                now.plusSeconds(62))).isTrue();
        assertThat(repository.retry(first.projectId(), first.id(), now.plusSeconds(63))).isFalse();
        assertThat(repository.pending(now.plusSeconds(999), 10)).isEmpty();
    }

    @Test void ordinaryFailureRetryKeepsJobAndFileIdentity() {
        var repository = new InMemoryProjectFileJobRepository();
        var first=job();repository.insert(first);repository.claim(first.id(), "owner", now, now.plusSeconds(60));
        repository.finish(first.id(), "owner", "FAILED", "PDF_PARSE_FAILED", null, now.plusSeconds(1));
        assertThat(repository.retry(UUID.randomUUID(), first.id(), now.plusSeconds(2))).isFalse();
        assertThat(repository.retry(first.projectId(), first.id(), now.plusSeconds(2))).isTrue();
        assertThat(repository.byFile(first.projectId(), first.fileId()).orElseThrow().id()).isEqualTo(first.id());
    }

    @Test void aNonProcessingStateRejectsEvenOneResidualLeaseField() {
        var first=job();
        assertThatThrownBy(()->new ProjectFileJob(first.id(),first.projectId(),first.fileId(),first.fileSha256(),
                first.actorId(),first.requestId(),first.traceId(),first.sourceIp(),"QUEUED",0,"orphan-token",null,
                null,null,now,now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ProjectFileJob(first.id(),first.projectId(),first.fileId(),first.fileSha256(),
                first.actorId(),first.requestId(),first.traceId(),first.sourceIp(),"UNKNOWN",0,null,now,
                null,null,now,now)).isInstanceOf(IllegalArgumentException.class);
    }
}
