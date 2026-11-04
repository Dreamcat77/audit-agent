package com.fuquan.audit.contract.infra.memory.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

class InMemoryProjectFileParseResultRepositoryTest {
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();

    @Test
    void insertsReadsAndReturnsLatestParseVersion() {
        InMemoryProjectFileParseResultRepository repository = new InMemoryProjectFileParseResultRepository();
        ProjectFileParseResult first = result(FILE, 1, "first");
        ProjectFileParseResult second = result(FILE, 2, "second");
        repository.insertIfAbsent(second);
        repository.insertIfAbsent(first);

        assertThat(repository.findById(first.parseResultId())).contains(first);
        assertThat(repository.findByFileIdAndParseVersion(FILE, 1)).contains(first);
        assertThat(repository.findLatestByFileId(FILE)).contains(second);
    }

    @Test
    void replaysSameImmutableIdentityAndReturnsStoredInstance() {
        InMemoryProjectFileParseResultRepository repository = new InMemoryProjectFileParseResultRepository();
        ProjectFileParseResult stored = result(FILE, 1, "same");
        ProjectFileParseResult replay = copyWithId(stored, UUID.randomUUID());
        repository.insertIfAbsent(stored);

        assertThat(repository.insertIfAbsent(replay)).isSameAs(stored);
    }

    @Test
    void rejectsConflictingImmutableIdentityForSameFileAndVersion() {
        InMemoryProjectFileParseResultRepository repository = new InMemoryProjectFileParseResultRepository();
        ProjectFileParseResult stored = result(FILE, 1, "same");
        ProjectFileParseResult conflicting = copyWithText(stored, "different");
        repository.insertIfAbsent(stored);

        assertThatThrownBy(() -> repository.insertIfAbsent(conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable parse result identity conflict");
    }

    @Test
    void concurrentInsertIfAbsentPublishesExactlyOneDurableIdentity() throws Exception {
        InMemoryProjectFileParseResultRepository repository = new InMemoryProjectFileParseResultRepository();
        int count = 32;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<ProjectFileParseResult>> futures = java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        ProjectFileParseResult returned = repository.insertIfAbsent(result(FILE, 3, "concurrent", UUID.randomUUID()));
                        assertThat(repository.findById(returned.parseResultId())).containsSame(returned);
                        return returned;
                    })).toList();
            start.countDown();
            List<ProjectFileParseResult> returned = futures.stream().map(this::get).toList();
            assertThat(returned).allSatisfy(value -> assertThat(value).isEqualTo(returned.get(0)));
            assertThat(returned.stream().map(ProjectFileParseResult::parseResultId).distinct()).hasSize(1);
            assertThat(repository.findByFileIdAndParseVersion(FILE, 3)).contains(returned.get(0));
            returned.forEach(value -> assertThat(repository.findById(value.parseResultId())).contains(value));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void latestUsesPersistenceStringOrderingForSameVersionHighBitUuid() throws Exception {
        ProjectFileParseResult low = result(FILE, 4, "same", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        ProjectFileParseResult high = result(FILE, 4, "same", UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        Comparator<ProjectFileParseResult> order = InMemoryProjectFileParseResultRepository.latestOrder();
        assertThat(order.compare(high, low)).isGreaterThan(0);
    }

    private ProjectFileParseResult get(Future<ProjectFileParseResult> future) {
        try { return future.get(); } catch (Exception e) { throw new AssertionError(e); }
    }

    private static ProjectFileParseResult result(UUID file, int version, String text) {
        return result(file, version, text, UUID.randomUUID());
    }

    private static ProjectFileParseResult result(UUID file, int version, String text, UUID id) {
        Instant created = Instant.parse("2026-09-14T00:00:00Z");
        return new ProjectFileParseResult(id, PROJECT, file, sha("file"), version,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT,
                "PDFBox", "2.0.30", text, sha(text), 2, 50L, new BigDecimal("0.9500"), null,
                created, created.plusSeconds(1), 1);
    }

    private static ProjectFileParseResult copyWithId(ProjectFileParseResult value, UUID id) {
        return new ProjectFileParseResult(id, value.projectId(), value.fileId(), value.fileSha256(), value.parseVersion(), value.parseOrigin(), value.parseStatus(), value.parseMethod(), value.engine(), value.engineVersion(), value.canonicalText(), value.canonicalTextSha256(), value.pageCount(), value.durationMs(), value.confidence(), value.supersedesParseResultId(), value.createdAt(), value.completedAt(), value.versionNo());
    }

    private static ProjectFileParseResult copyWithText(ProjectFileParseResult value, String text) {
        return new ProjectFileParseResult(value.parseResultId(), value.projectId(), value.fileId(), value.fileSha256(), value.parseVersion(), value.parseOrigin(), value.parseStatus(), value.parseMethod(), value.engine(), value.engineVersion(), text, sha(text), value.pageCount(), value.durationMs(), value.confidence(), value.supersedesParseResultId(), value.createdAt(), value.completedAt(), value.versionNo());
    }

    private static String sha(String input) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
