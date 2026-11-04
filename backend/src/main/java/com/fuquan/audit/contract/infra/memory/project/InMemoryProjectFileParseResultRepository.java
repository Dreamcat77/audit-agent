package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Local-process adapter for parse facts; identity is the file/version pair. */
@Repository
@Profile("local")
public class InMemoryProjectFileParseResultRepository implements ProjectFileParseResultRepository {
    private final ConcurrentMap<Identity, ProjectFileParseResult> byIdentity = new ConcurrentHashMap<>();

    @Override
    public ProjectFileParseResult insertIfAbsent(ProjectFileParseResult result) {
        Identity identity = new Identity(result.fileId(), result.parseVersion());
        ProjectFileParseResult existing = byIdentity.putIfAbsent(identity, result);
        if (existing == null) {
            return result;
        }
        if (!sameImmutableBusinessIdentity(existing, result)) {
            throw new IllegalStateException("immutable parse result identity conflict");
        }
        return existing;
    }

    @Override
    public Optional<ProjectFileParseResult> findById(UUID parseResultId) {
        return byIdentity.values().stream()
                .filter(value -> value.parseResultId().equals(parseResultId)).findFirst();
    }

    @Override
    public Optional<ProjectFileParseResult> findByFileIdAndParseVersion(UUID fileId, int parseVersion) {
        return Optional.ofNullable(byIdentity.get(new Identity(fileId, parseVersion)));
    }

    @Override
    public Optional<ProjectFileParseResult> findLatestByFileId(UUID fileId) {
        return byIdentity.values().stream()
                .filter(value -> value.fileId().equals(fileId))
                .max(latestOrder());
    }

    static Comparator<ProjectFileParseResult> latestOrder() {
        return Comparator.comparingInt(ProjectFileParseResult::parseVersion)
                .thenComparing(value -> value.parseResultId().toString());
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

    private record Identity(UUID fileId, int parseVersion) { }
}
