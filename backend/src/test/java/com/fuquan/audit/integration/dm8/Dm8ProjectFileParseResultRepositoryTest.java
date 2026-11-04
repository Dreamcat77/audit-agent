package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class Dm8ProjectFileParseResultRepositoryTest {
    @Mock private ProjectFileParseResultMapper mapper;

    @Test
    void insertsNewImmutableParseResult() {
        ProjectFileParseResult result = result();
        Dm8ProjectFileParseResultRepository repository = new Dm8ProjectFileParseResultRepository(mapper);

        assertThat(repository.insertIfAbsent(result)).isSameAs(result);
        verify(mapper).insert(any(ProjectFileParseResultPersistenceRow.class));
    }

    @Test
    void reloadsAndReturnsExactSameFactAfterDuplicateInsertRace() {
        ProjectFileParseResult result = result();
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.findByFileIdAndParseVersion(result.fileId().toString(), result.parseVersion())).thenReturn(row(result));
        Dm8ProjectFileParseResultRepository repository = new Dm8ProjectFileParseResultRepository(mapper);

        assertThat(repository.insertIfAbsent(result)).isEqualTo(result);
        verify(mapper).findByFileIdAndParseVersion(result.fileId().toString(), result.parseVersion());
    }

    @Test
    void returnsConcurrentWinnerWithDifferentGeneratedParseResultIdAfterDuplicateInsertRace() {
        ProjectFileParseResult attempted = result();
        ProjectFileParseResult winner = new ProjectFileParseResult(UUID.randomUUID(), attempted.projectId(), attempted.fileId(),
                attempted.fileSha256(), attempted.parseVersion(), attempted.parseOrigin(), attempted.parseStatus(),
                attempted.parseMethod(), attempted.engine(), attempted.engineVersion(), attempted.canonicalText(),
                attempted.canonicalTextSha256(), attempted.pageCount(), attempted.durationMs(), attempted.confidence(),
                attempted.supersedesParseResultId(), attempted.createdAt(), attempted.completedAt(), attempted.versionNo());
        assertThat(winner.parseResultId()).isNotEqualTo(attempted.parseResultId());
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.findByFileIdAndParseVersion(attempted.fileId().toString(), attempted.parseVersion())).thenReturn(row(winner));
        Dm8ProjectFileParseResultRepository repository = new Dm8ProjectFileParseResultRepository(mapper);

        assertThat(repository.insertIfAbsent(attempted)).isEqualTo(winner);
    }

    @Test
    void failsClosedWhenDuplicateRaceHasDifferentImmutableBusinessIdentity() {
        ProjectFileParseResult result = result();
        ProjectFileParseResult different = new ProjectFileParseResult(result.parseResultId(), result.projectId(), result.fileId(),
                result.fileSha256(), result.parseVersion(), result.parseOrigin(), result.parseStatus(), result.parseMethod(),
                result.engine(), result.engineVersion(), "different", sha("different"), result.pageCount(), result.durationMs(),
                result.confidence(), result.supersedesParseResultId(), result.createdAt(), result.completedAt(), result.versionNo());
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.findByFileIdAndParseVersion(result.fileId().toString(), result.parseVersion())).thenReturn(row(different));
        Dm8ProjectFileParseResultRepository repository = new Dm8ProjectFileParseResultRepository(mapper);

        assertThatThrownBy(() -> repository.insertIfAbsent(result)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable parse result identity conflict");
    }

    @Test
    void propagatesDuplicateKeyWhenRaceRowCannotBeReloaded() {
        ProjectFileParseResult result = result();
        DuplicateKeyException duplicate = new DuplicateKeyException("duplicate");
        when(mapper.insert(any())).thenThrow(duplicate);
        Dm8ProjectFileParseResultRepository repository = new Dm8ProjectFileParseResultRepository(mapper);

        assertThatThrownBy(() -> repository.insertIfAbsent(result)).isSameAs(duplicate);
    }

    @Test
    void propagatesNonDuplicateInsertDataAccessFailure() {
        ProjectFileParseResult result = result();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        when(mapper.insert(any())).thenThrow(failure);
        Dm8ProjectFileParseResultRepository repository = new Dm8ProjectFileParseResultRepository(mapper);

        assertThatThrownBy(() -> repository.insertIfAbsent(result)).isSameAs(failure);
    }

    @Test
    void propagatesReloadDataAccessFailureAfterDuplicateKey() {
        ProjectFileParseResult result = result();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.findByFileIdAndParseVersion(result.fileId().toString(), result.parseVersion())).thenThrow(failure);
        Dm8ProjectFileParseResultRepository repository = new Dm8ProjectFileParseResultRepository(mapper);

        assertThatThrownBy(() -> repository.insertIfAbsent(result)).isSameAs(failure);
    }

    @Test
    void findsByIdFileVersionAndLatestUsingCanonicalMapping() {
        ProjectFileParseResult result = result();
        when(mapper.findById(result.parseResultId().toString())).thenReturn(row(result));
        when(mapper.findByFileIdAndParseVersion(result.fileId().toString(), result.parseVersion())).thenReturn(row(result));
        when(mapper.findLatestByFileId(result.fileId().toString())).thenReturn(row(result));
        Dm8ProjectFileParseResultRepository repository = new Dm8ProjectFileParseResultRepository(mapper);

        assertThat(repository.findById(result.parseResultId())).contains(result);
        assertThat(repository.findByFileIdAndParseVersion(result.fileId(), result.parseVersion())).contains(result);
        assertThat(repository.findLatestByFileId(result.fileId())).contains(result);
    }

    private static ProjectFileParseResult result() {
        Instant created = Instant.parse("2026-09-14T00:00:00Z");
        String text = "canonical text";
        return new ProjectFileParseResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sha("file"), 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT,
                "PDFBox", "2.0.30", text, sha(text), 2, 50L, new BigDecimal("0.9500"), null, created, created.plusSeconds(1), 1);
    }

    private static ProjectFileParseResultPersistenceRow row(ProjectFileParseResult v) {
        return new ProjectFileParseResultPersistenceRow(v.parseResultId().toString(), v.projectId().toString(), v.fileId().toString(),
                v.fileSha256(), v.parseVersion(), v.parseOrigin().name(), v.parseStatus().name(), v.parseMethod().name(),
                v.engine(), v.engineVersion(), v.canonicalText(), v.canonicalTextSha256(), v.pageCount(), v.durationMs(),
                v.confidence(), v.supersedesParseResultId() == null ? null : v.supersedesParseResultId().toString(),
                LocalDateTime.ofInstant(v.createdAt(), java.time.ZoneOffset.UTC), LocalDateTime.ofInstant(v.completedAt(), java.time.ZoneOffset.UTC), v.versionNo());
    }

    private static String sha(String input) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
