package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileParseProvenance;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectVersionConflictException;
import com.fuquan.audit.contract.infra.memory.PromotionContractFixtures;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectContractAdapter;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void historicalCandidateDetailKeepsPrimaryFactsAndExposesEmptyPrimaryOnlySupportState() throws Exception {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        ProjectApplicationService service = new ProjectApplicationService(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(), CLOCK);
        Project project = service.createBlank("actor-string");
        ProjectFile source = file(project.id(), null, "legacy-contract.pdf", ProjectFileCategory.CONTRACT_BODY,
                ProjectFileParseStatus.PARSED, "legacy", ProjectFileParseProvenance.none(), "a");
        repositories.projectFileRepository().insert(source);
        UUID parseId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        var candidate = com.fuquan.audit.contract.domain.project.ContractCandidate.draftWithAuthority(
                UUID.randomUUID(), project.id(), source.id(), parseId, roleId, identityId,
                com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                "f".repeat(64), "C-LEGACY", "Legacy Contract", "GOODS",
                "Buyer", "Supplier", null, new BigDecimal("100.00"), null,
                new BigDecimal("98.00"), "Award Supplier", "91350000LEGACY", List.of(), NOW);
        repositories.contractCandidateRepository().insertIfAbsent(candidate);
        ExtractionResult primaryAward = new ExtractionResult(UUID.randomUUID(), project.id(), source.id(), parseId,
                candidate.id(), "1".repeat(64), "test-v1", "AWARD_AMOUNT", null, "98", "98",
                new BigDecimal("98.00"), "synthetic award excerpt", 1, 1, "page:1", NOW);
        ExtractionResult primaryLine = new ExtractionResult(UUID.randomUUID(), project.id(), source.id(), parseId,
                candidate.id(), "2".repeat(64), "test-v1", "LINE_ITEM_NAME", "line-1", "Item", "Item",
                null, "synthetic line excerpt", 1, 2, "page:1/paragraph:2", NOW);
        repositories.extractionResultRepository().insertIfAbsent(primaryAward);
        repositories.extractionResultRepository().insertIfAbsent(primaryLine);

        Object detail = service.candidate(project.id(), candidate.id());

        assertThat(value(detail, "candidate")).isEqualTo(candidate);
        assertThat(value(detail, "sourceParseResultId")).isEqualTo(parseId);
        assertThat(value(detail, "sourceRoleDecisionId")).isEqualTo(roleId);
        assertThat(value(detail, "sourceIdentityDecisionId")).isEqualTo(identityId);
        assertThat(value(detail, "sourceFactFingerprint")).isEqualTo("f".repeat(64));
        assertThat((List<?>) value(detail, "evidence")).isEqualTo(
                repositories.extractionResultRepository().findByCandidateId(project.id(), candidate.id()));
        assertThat(value(detail, "fieldStates")).isEqualTo(java.util.Map.ofEntries(
                java.util.Map.entry("PROJECT_NAME", "MISSING"), java.util.Map.entry("PROJECT_NO", "MISSING"),
                java.util.Map.entry("CONTRACT_NO", "MISSING"), java.util.Map.entry("CONTRACT_NAME", "MISSING"),
                java.util.Map.entry("CONTRACT_TYPE", "MISSING"),
                java.util.Map.entry("PARTY_A", "MISSING"), java.util.Map.entry("PARTY_B", "MISSING"),
                java.util.Map.entry("SUPPLIER_NAME", "MISSING"), java.util.Map.entry("CREDIT_CODE", "MISSING"),
                java.util.Map.entry("AMOUNT_NUMERIC", "MISSING"), java.util.Map.entry("AMOUNT_UPPERCASE", "MISSING"),
                java.util.Map.entry("AWARD_AMOUNT", "CONFIRMED"), java.util.Map.entry("AWARD_SUPPLIER", "MISSING"),
                java.util.Map.entry("AWARD_SUPPLIER_CREDIT_CODE", "MISSING")));
        assertThat(value(detail, "lineItemFieldStates")).isEqualTo(java.util.Map.of("line-1", java.util.Map.of(
                "LINE_ITEM_KEY", "MISSING", "LINE_ITEM_NAME", "CONFIRMED", "LINE_ITEM_QUANTITY", "MISSING",
                "LINE_ITEM_UNIT_PRICE", "MISSING", "LINE_ITEM_DECLARED_AMOUNT", "MISSING")));
        assertThat(detail.getClass().getRecordComponents()).extracting(component -> component.getName())
                .contains("authorityMode", "supportAuthorityVersionNo", "supportAuthorityFingerprint",
                        "supportAuthorities");
        Object authorityMode = value(detail, "authorityMode");
        assertThat(authorityMode).isNotNull();
        assertThat(authorityMode.toString()).isEqualTo("PRIMARY_ONLY_COMPAT");
        assertThat(value(detail, "supportAuthorityVersionNo")).isNull();
        assertThat(value(detail, "supportAuthorityFingerprint")).isNull();
        assertThat((List<?>) value(detail, "supportAuthorities")).isEmpty();
    }

    @Test
    void createListDetailAndMetadataCasFormOneVerticalSlice() {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        ProjectApplicationService service = new ProjectApplicationService(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(),
                CLOCK);

        Project created = service.createBlank("actor-string");
        assertThat(service.list(1, 20, null).items()).isEmpty();
        assertThat(service.detail(created.id()).project()).isEqualTo(created);

        Project updated = service.confirmMetadata(created.id(), 0, "Alpha", "P-1", null);
        assertThat(updated.versionNo()).isEqualTo(1);
        assertThat(service.list(1, 20, null).items()).containsExactly(updated);
        assertThatThrownBy(() -> service.confirmMetadata(created.id(), 0, "stale", null, null))
                .isInstanceOf(ProjectVersionConflictException.class);
    }

    @Test
    void packageBackedDraftMovesToFormalProjectsWithTheSameProjectAndFilesAfterMetadataConfirmation() {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        ProjectApplicationService service = new ProjectApplicationService(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(), CLOCK);
        Project draftProject = service.createBlank("actor-string");
        ProjectFile packageFile = file(draftProject.id(), null, "intake.zip", ProjectFileCategory.PACKAGE,
                ProjectFileParseStatus.NOT_APPLICABLE, null, ProjectFileParseProvenance.none(), "a");
        ProjectFile ocrFile = file(draftProject.id(), packageFile.id(), "合同扫描件.png",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileParseStatus.PARSED, "项目名称：数据中心项目",
                new ProjectFileParseProvenance(ProjectFileParseMethod.OCR, "PaddleOCR", new BigDecimal("0.9800"),
                        1, 1200L), "b");
        repositories.projectFileRepository().insert(packageFile);
        repositories.projectFileRepository().insert(ocrFile);
        repositories.extractionResultRepository().insertIfAbsent(extraction(
                draftProject.id(), ocrFile.id(), "PROJECT_NAME", "数据中心项目", "c"));
        repositories.extractionResultRepository().insertIfAbsent(extraction(
                draftProject.id(), ocrFile.id(), "PROJECT_NO", "CG-2026-002", "d"));

        ProjectApplicationService.IntakeDraftPage drafts = service.listIntakeDrafts(1, 20);
        assertThat(drafts.total()).isEqualTo(1);
        assertThat(drafts.items()).singleElement().satisfies(draft -> {
            assertThat(draft.projectId()).isEqualTo(draftProject.id());
            assertThat(draft.versionNo()).isZero();
            assertThat(draft.packageFilename()).isEqualTo("intake.zip");
            assertThat(draft.parsedFileCount()).isEqualTo(1);
            assertThat(draft.ocrFileCount()).isEqualTo(1);
            assertThat(draft.candidateProjectName()).isEqualTo("数据中心项目");
            assertThat(draft.candidateProjectNo()).isEqualTo("CG-2026-002");
        });
        List<UUID> fileIdsBeforeConfirmation = service.files(draftProject.id()).stream()
                .map(ProjectFile::id).toList();

        Project confirmed = service.confirmMetadata(
                draftProject.id(), 0, "数据中心项目", "CG-2026-002", "办公设备");

        assertThat(confirmed.id()).isEqualTo(draftProject.id());
        assertThat(service.listIntakeDrafts(1, 20).items()).isEmpty();
        assertThat(service.list(1, 20, null).items()).containsExactly(confirmed);
        assertThat(service.files(confirmed.id())).extracting(ProjectFile::id)
                .containsExactlyElementsOf(fileIdsBeforeConfirmation);
    }

    @Test
    void preparationDraftsRediscoverOnlyUnconfirmedProjectsWithOrdinaryFiles() {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        ProjectApplicationService service = new ProjectApplicationService(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(), CLOCK);
        Project ordinary = Project.blank(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "actor", NOW.plusSeconds(2));
        Project older = Project.blank(UUID.fromString("00000000-0000-0000-0000-000000000009"),
                "actor", NOW.plusSeconds(1));
        Project sameTimeHigherId = Project.blank(UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "actor", NOW.plusSeconds(2));
        Project packageBacked = Project.blank(UUID.fromString("00000000-0000-0000-0000-000000000004"),
                "actor", NOW.plusSeconds(3));
        Project empty = Project.blank(UUID.fromString("00000000-0000-0000-0000-000000000005"),
                "actor", NOW.plusSeconds(4));
        Project confirmed = Project.blank(UUID.fromString("00000000-0000-0000-0000-000000000006"),
                "actor", NOW.plusSeconds(5)).confirmMetadata(0, "Confirmed", "P-6", null, NOW.plusSeconds(6));
        for (Project project : List.of(ordinary, older, sameTimeHigherId, packageBacked, empty, confirmed)) {
            repositories.projectRepository().insert(project);
        }
        repositories.projectFileRepository().insert(file(ordinary.id(), null, "ordinary-a.pdf",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileParseStatus.PARSED, "a",
                ProjectFileParseProvenance.none(), "1"));
        repositories.projectFileRepository().insert(file(ordinary.id(), null, "ordinary-b.pdf",
                ProjectFileCategory.AWARD_NOTICE, ProjectFileParseStatus.PARSED, "b",
                ProjectFileParseProvenance.none(), "2"));
        repositories.projectFileRepository().insert(file(older.id(), null, "older.pdf",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileParseStatus.PARSED, "c",
                ProjectFileParseProvenance.none(), "3"));
        repositories.projectFileRepository().insert(file(sameTimeHigherId.id(), null, "same-time.pdf",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileParseStatus.PARSED, "d",
                ProjectFileParseProvenance.none(), "4"));
        repositories.projectFileRepository().insert(file(packageBacked.id(), null, "package.zip",
                ProjectFileCategory.PACKAGE, ProjectFileParseStatus.NOT_APPLICABLE, null,
                ProjectFileParseProvenance.none(), "5"));
        repositories.projectFileRepository().insert(file(confirmed.id(), null, "confirmed.pdf",
                ProjectFileCategory.CONTRACT_BODY, ProjectFileParseStatus.PARSED, "e",
                ProjectFileParseProvenance.none(), "6"));

        ProjectApplicationService.PreparationDraftPage firstPage = service.listPreparationDrafts(1, 2);
        ProjectApplicationService.PreparationDraftPage secondPage = service.listPreparationDrafts(2, 2);

        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(firstPage.items()).extracting(ProjectApplicationService.PreparationDraft::projectId)
                .containsExactly(sameTimeHigherId.id(), ordinary.id());
        assertThat(firstPage.items()).extracting(ProjectApplicationService.PreparationDraft::uploadedFileCount)
                .containsExactly(1L, 2L);
        assertThat(secondPage.items()).extracting(ProjectApplicationService.PreparationDraft::projectId)
                .containsExactly(older.id());
        assertThat(service.listIntakeDrafts(1, 20).items())
                .extracting(ProjectApplicationService.IntakeDraft::projectId)
                .containsExactly(packageBacked.id());
        assertThat(service.list(1, 20, null).items()).containsExactly(confirmed);
        assertThat(service.detail(ordinary.id()).files()).hasSize(2);
    }

    @Test
    void conflictingNormalizedProjectNumbersProduceNoMetadataSuggestionRegardlessOfOrder() {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        ProjectApplicationService service = new ProjectApplicationService(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(), CLOCK);
        Project firstOrder = service.createBlank("actor-string");
        Project reverseOrder = service.createBlank("actor-string");
        repositories.projectFileRepository().insert(file(firstOrder.id(), null, "first.zip",
                ProjectFileCategory.PACKAGE, ProjectFileParseStatus.NOT_APPLICABLE, null,
                ProjectFileParseProvenance.none(), "e"));
        repositories.projectFileRepository().insert(file(reverseOrder.id(), null, "reverse.zip",
                ProjectFileCategory.PACKAGE, ProjectFileParseStatus.NOT_APPLICABLE, null,
                ProjectFileParseProvenance.none(), "f"));
        UUID firstFile = UUID.randomUUID();
        UUID reverseFile = UUID.randomUUID();
        repositories.extractionResultRepository().insertIfAbsent(extraction(
                firstOrder.id(), firstFile, "PROJECT_NO", "SCIT-GZ-ZF-2026-0017", "1"));
        repositories.extractionResultRepository().insertIfAbsent(extraction(
                firstOrder.id(), firstFile, "PROJECT_NO", "SCIT-GZ-ZF-2026-0007", "2"));
        repositories.extractionResultRepository().insertIfAbsent(extraction(
                reverseOrder.id(), reverseFile, "PROJECT_NO", "SCIT-GZ-ZF-2026-0007", "3"));
        repositories.extractionResultRepository().insertIfAbsent(extraction(
                reverseOrder.id(), reverseFile, "PROJECT_NO", "SCIT-GZ-ZF-2026-0017", "4"));

        assertThat(service.listIntakeDrafts(1, 20).items())
                .filteredOn(draft -> draft.projectId().equals(firstOrder.id())
                        || draft.projectId().equals(reverseOrder.id()))
                .allSatisfy(draft -> assertThat(draft.candidateProjectNo()).isNull());
    }

    @Test
    void projectDetailProjectsFormalContractFactsInAssociationOrderAndFailsClosedWhenMissing() throws Exception {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        Project project = Project.blank(UUID.randomUUID(), "actor", NOW);
        repositories.projectRepository().insert(project);
        InMemoryProjectContractAdapter associations = new InMemoryProjectContractAdapter();
        Contract first = PromotionContractFixtures.contract(UUID.randomUUID(), "FORMAL-1");
        Contract second = PromotionContractFixtures.contract(UUID.randomUUID(), "FORMAL-2");
        UUID firstCandidate = UUID.randomUUID();
        UUID secondCandidate = UUID.randomUUID();
        associations.insert(new ProjectContract(project.id(), second.id(), secondCandidate, "actor", NOW.plusSeconds(1)));
        associations.insert(new ProjectContract(project.id(), first.id(), firstCandidate, "actor", NOW));
        ContractRepository contracts = id -> Optional.ofNullable(
                id.equals(first.id()) ? first : id.equals(second.id()) ? second : null);
        var constructor = java.util.Arrays.stream(ProjectApplicationService.class.getConstructors())
                .filter(candidate -> candidate.getParameterCount() == 7)
                .findFirst();
        assertThat(constructor).as("Project detail must receive the existing read-only ContractRepository")
                .isPresent();
        ProjectApplicationService service = (ProjectApplicationService) constructor.orElseThrow().newInstance(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(),
                associations, contracts, CLOCK);

        List<?> summaries = service.detail(project.id()).associatedContracts();
        assertThat(value(summaries.get(0), "contractId")).isEqualTo(first.id());
        assertThat(value(summaries.get(1), "contractId")).isEqualTo(second.id());
        Object summary = summaries.get(0);
        assertThat(value(summary, "projectId")).isEqualTo(project.id());
        assertThat(value(summary, "candidateId")).isEqualTo(firstCandidate);
        assertThat(value(summary, "contractNo")).isEqualTo("FORMAL-1");
        assertThat(value(summary, "contractName")).isEqualTo(first.contractName());
        assertThat(value(summary, "contractType")).isEqualTo(first.contractType());
        assertThat(value(summary, "partyA")).isEqualTo(first.partyA());
        assertThat(value(summary, "partyB")).isEqualTo(first.partyB());
        assertThat(value(summary, "partyBCreditCode")).isEqualTo(first.partyBCreditCode());
        assertThat((java.math.BigDecimal) value(summary, "contractAmount")).isEqualByComparingTo("100.00");
        assertThat(value(summary, "amountInWords")).isEqualTo(first.amountInWords());
        assertThat(value(summary, "status")).isEqualTo("READY_FOR_REVIEW");
        assertThat(value(summary, "versionNo")).isEqualTo(0L);

        ProjectApplicationService broken = (ProjectApplicationService) constructor.orElseThrow().newInstance(
                repositories.projectRepository(), repositories.projectFileRepository(),
                repositories.contractCandidateRepository(), repositories.extractionResultRepository(),
                associations, (ContractRepository) id -> Optional.empty(), CLOCK);
        assertThatThrownBy(() -> broken.detail(project.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
    }

    private static Object value(Object target, String accessor) throws Exception {
        return target.getClass().getMethod(accessor).invoke(target);
    }

    private static ProjectFile file(UUID projectId, UUID parentId, String filename,
                                    ProjectFileCategory category, ProjectFileParseStatus parseStatus,
                                    String parsedText, ProjectFileParseProvenance provenance, String identity) {
        return new ProjectFile(UUID.randomUUID(), projectId, parentId,
                parentId == null ? null : filename, filename,
                category == ProjectFileCategory.PACKAGE ? "application/zip" : "image/png",
                category, ProjectFileIngestionStatus.AVAILABLE, parseStatus,
                identity.repeat(64), identity.repeat(64), 10L, parsedText, null, NOW, NOW, provenance);
    }

    private static ExtractionResult extraction(UUID projectId, UUID fileId, String type,
                                               String value, String identity) {
        return new ExtractionResult(UUID.randomUUID(), projectId, fileId, null, identity.repeat(64),
                "project-extraction-v1", type, null, value, value, null,
                type + "：" + value, null, 1, "document/fact:" + type, NOW);
    }
}
