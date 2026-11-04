package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.contract.infra.memory.reviewrun.InMemoryContractReviewRunRepositories;
import com.fuquan.audit.security.application.AuthContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrozenReviewInputLoaderTest {
    private static final UUID PROJECT_ID = UUID.fromString("12000000-0000-0000-0000-000000000001");
    private static final UUID CONTRACT_ID = UUID.fromString("12000000-0000-0000-0000-000000000002");
    private static final UUID FILE_ID = UUID.fromString("12000000-0000-0000-0000-000000000003");
    private static final UUID RUN_ID = UUID.fromString("12000000-0000-0000-0000-000000000004");
    private static final UUID PARSE1_ID = UUID.fromString("12000000-0000-0000-0000-000000000005");
    private static final UUID PARSE2_ID = UUID.fromString("12000000-0000-0000-0000-000000000006");
    private static final UUID ROLE_ID = UUID.fromString("12000000-0000-0000-0000-000000000007");
    private static final UUID IDENTITY_ID = UUID.fromString("12000000-0000-0000-0000-000000000008");
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String FILE_SHA = "a".repeat(64);

    @Test
    void loadsExactFrozenParseAndRoleNotLaterLatestParseOrDecision() {
        String frozenText = "旧合同正文。";
        var parses = new InMemoryProjectFileParseResultRepository();
        parses.insertIfAbsent(parse(PARSE1_ID, PROJECT_ID, FILE_ID, 1, frozenText));
        parses.insertIfAbsent(parse(PARSE2_ID, PROJECT_ID, FILE_ID, 2, "后来重新解析的正文。"));
        assertThat(parses.findLatestByFileId(FILE_ID).orElseThrow().parseResultId()).isEqualTo(PARSE2_ID);
        var stored = storedRun(ContractSnapshotCanonicalizer.sha256(frozenText));
        FrozenReviewInput input = loader(stored, parses).load(PROJECT_ID, CONTRACT_ID, RUN_ID);
        assertThat(input.frozenContract().id()).isEqualTo(CONTRACT_ID);
        assertThat(input.materials()).hasSize(1);
        assertThat(input.materials().get(0).parseResultId()).isEqualTo(PARSE1_ID);
        assertThat(input.materials().get(0).canonicalText()).isEqualTo(frozenText);
        assertThat(input.materials().get(0).effectiveRole()).isEqualTo(DocumentRole.CONTRACT_BODY);
        assertThat(input.materials().get(0).roleDecisionId()).isEqualTo(ROLE_ID);
        assertThat(input.materials().get(0).originalFilename()).isEqualTo("冻结合同.docx");
    }

    @Test
    void rejectsExactParseWithWrongProjectOwnership() {
        String text = "冻结文本。";
        var stored = storedRun(ContractSnapshotCanonicalizer.sha256(text));
        ProjectFileParseResultRepository parses = mock(ProjectFileParseResultRepository.class);
        when(parses.findById(PARSE1_ID)).thenReturn(Optional.of(parse(PARSE1_ID,
                UUID.randomUUID(), FILE_ID, 1, text)));
        assertConflict(loader(stored, parses));
    }

    @Test
    void rejectsExactParseHashMismatchWithoutFallbackToCurrentFileProjection() {
        String text = "冻结文本。";
        var stored = storedRun("b".repeat(64));
        ProjectFileParseResultRepository parses = mock(ProjectFileParseResultRepository.class);
        when(parses.findById(PARSE1_ID)).thenReturn(Optional.of(parse(PARSE1_ID,
                PROJECT_ID, FILE_ID, 1, text)));
        assertConflict(loader(stored, parses));
    }

    private static void assertConflict(FrozenReviewInputLoader loader) {
        assertThatThrownBy(() -> loader.load(PROJECT_ID, CONTRACT_ID, RUN_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
    }

    private static FrozenReviewInputLoader loader(InMemoryContractReviewRunRepositories store,
            ProjectFileParseResultRepository parses) {
        var canonicalizer = new ContractSnapshotCanonicalizer();
        var runs = new ContractReviewRunService(mock(AuthContext.class),
                mock(ContractReviewRunCreateTransactionCoordinator.class), store, store, store, store,
                new ReviewRunSnapshotIdentityFactory());
        ProjectFileRepository files = mock(ProjectFileRepository.class);
        com.fuquan.audit.contract.domain.project.ProjectFile file =
                mock(com.fuquan.audit.contract.domain.project.ProjectFile.class);
        when(file.id()).thenReturn(FILE_ID);
        when(file.projectId()).thenReturn(PROJECT_ID);
        when(file.sha256()).thenReturn(FILE_SHA);
        when(file.originalFilename()).thenReturn("冻结合同.docx");
        when(files.findByProjectIdAndId(PROJECT_ID, FILE_ID)).thenReturn(Optional.of(file));
        return new FrozenReviewInputLoader(runs, parses, files,
                new ContractSnapshotDecoder(canonicalizer));
    }

    private static InMemoryContractReviewRunRepositories storedRun(String textSha) {
        var store = new InMemoryContractReviewRunRepositories();
        var canonicalizer = new ContractSnapshotCanonicalizer();
        var contract = new Contract(CONTRACT_ID, "C-1", "合同", "GOODS", "甲方", "乙方", null,
                Money.of(new BigDecimal("100.00")), null, "READY_FOR_REVIEW", 0,
                NOW, NOW, List.of(), null);
        var snapshot = canonicalizer.canonicalize(contract);
        var material = new ContractReviewRunMaterial(UUID.randomUUID(), RUN_ID, 1, PROJECT_ID, FILE_ID,
                FILE_SHA, PARSE1_ID, 1, textSha, DocumentRole.CONTRACT_BODY, ROLE_ID,
                IDENTITY_ID, null, "actor-1", NOW);
        String identity = new ReviewRunSnapshotIdentityFactory().identity(PROJECT_ID, CONTRACT_ID, 0,
                snapshot.sha256(), null, null, List.of(material), "profile-v1", "rule-v1", "model-v1");
        var run = new ContractReviewRun(RUN_ID, PROJECT_ID, CONTRACT_ID, 0, null, null, 1,
                snapshot.canonicalJson(), snapshot.sha256(), "profile-v1", "rule-v1", "model-v1",
                identity, "actor-1", ContractReviewRunStatus.PENDING, NOW,
                null, null, null, null, null, 0);
        store.insert(run);
        store.insert(material);
        var manifest = new ReviewRunManifestFactory().create(RUN_ID, NOW);
        manifest.steps().forEach(store::insert);
        manifest.attempts().forEach(store::insert);
        return store;
    }

    private static ProjectFileParseResult parse(UUID parseId, UUID projectId, UUID fileId,
            int version, String text) {
        return new ProjectFileParseResult(parseId, projectId, fileId, FILE_SHA, version,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                ProjectFileParseMethod.PDF_TEXT, "PDFBox", "3", text,
                ContractSnapshotCanonicalizer.sha256(text), 1, 1L, null, null,
                NOW, NOW, 0);
    }
}
