package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProjectFileParseResultRepository implements ProjectFileParseResultRepository {
    private final ProjectFileParseResultMapper mapper;

    public Dm8ProjectFileParseResultRepository(ProjectFileParseResultMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProjectFileParseResult insertIfAbsent(ProjectFileParseResult result) {
        try {
            mapper.insert(row(result));
            return result;
        } catch (DuplicateKeyException conflict) {
            ProjectFileParseResultPersistenceRow raced = mapper.findByFileIdAndParseVersion(
                    result.fileId().toString(), result.parseVersion());
            if (raced == null) throw conflict;
            return requireSame(result, domain(raced));
        }
    }

    @Override public Optional<ProjectFileParseResult> findById(UUID parseResultId) {
        return Optional.ofNullable(mapper.findById(parseResultId.toString())).map(Dm8ProjectFileParseResultRepository::domain);
    }
    @Override public Optional<ProjectFileParseResult> findByFileIdAndParseVersion(UUID fileId, int parseVersion) {
        return Optional.ofNullable(mapper.findByFileIdAndParseVersion(fileId.toString(), parseVersion)).map(Dm8ProjectFileParseResultRepository::domain);
    }
    @Override public Optional<ProjectFileParseResult> findLatestByFileId(UUID fileId) {
        return Optional.ofNullable(mapper.findLatestByFileId(fileId.toString())).map(Dm8ProjectFileParseResultRepository::domain);
    }

    private static ProjectFileParseResult requireSame(ProjectFileParseResult expected, ProjectFileParseResult actual) {
        if (!sameImmutableBusinessIdentity(expected, actual)) {
            throw new IllegalStateException("immutable parse result identity conflict");
        }
        return actual;
    }

    private static boolean sameImmutableBusinessIdentity(ProjectFileParseResult a, ProjectFileParseResult b) {
        return a.projectId().equals(b.projectId()) && a.fileId().equals(b.fileId())
                && a.fileSha256().equals(b.fileSha256()) && a.parseVersion() == b.parseVersion()
                && a.parseOrigin() == b.parseOrigin() && a.parseStatus() == b.parseStatus()
                && a.parseMethod() == b.parseMethod() && a.engine().equals(b.engine())
                && Objects.equals(a.engineVersion(), b.engineVersion())
                && Objects.equals(a.canonicalText(), b.canonicalText())
                && Objects.equals(a.canonicalTextSha256(), b.canonicalTextSha256())
                && Objects.equals(a.pageCount(), b.pageCount()) && Objects.equals(a.durationMs(), b.durationMs())
                && sameDecimal(a.confidence(), b.confidence())
                && Objects.equals(a.supersedesParseResultId(), b.supersedesParseResultId())
                && a.createdAt().equals(b.createdAt()) && a.completedAt().equals(b.completedAt())
                && a.versionNo() == b.versionNo();
    }

    private static boolean sameDecimal(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static ProjectFileParseResultPersistenceRow row(ProjectFileParseResult value) {
        return new ProjectFileParseResultPersistenceRow(value.parseResultId().toString(), value.projectId().toString(),
                value.fileId().toString(), value.fileSha256(), value.parseVersion(), value.parseOrigin().name(),
                value.parseStatus().name(), value.parseMethod().name(), value.engine(), value.engineVersion(),
                value.canonicalText(), value.canonicalTextSha256(), value.pageCount(), value.durationMs(), value.confidence(),
                text(value.supersedesParseResultId()), time(value.createdAt()), time(value.completedAt()), value.versionNo());
    }

    private static ProjectFileParseResult domain(ProjectFileParseResultPersistenceRow value) {
        return new ProjectFileParseResult(UUID.fromString(value.parseResultId()), UUID.fromString(value.projectId()),
                UUID.fromString(value.fileId()), value.fileSha256(), value.parseVersion(),
                ProjectFileParseOrigin.valueOf(value.parseOrigin()), ProjectFileParseStatus.valueOf(value.parseStatus()),
                ProjectFileParseMethod.valueOf(value.parseMethod()), value.engine(), value.engineVersion(), value.canonicalText(),
                value.canonicalTextSha256(), value.pageCount(), value.durationMs(), value.confidence(),
                uuid(value.supersedesParseResultId()), value.createdAt().toInstant(ZoneOffset.UTC),
                value.completedAt().toInstant(ZoneOffset.UTC), Math.toIntExact(value.versionNo()));
    }

    private static String text(UUID value) { return value == null ? null : value.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static LocalDateTime time(java.time.Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
