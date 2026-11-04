package com.fuquan.audit.contract.application.clause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.project.ProjectApplicationService;
import com.fuquan.audit.contract.application.project.ProjectParsedTextReadService;
import com.fuquan.audit.contract.application.project.SelectedProjectMaterialReadService;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.infra.memory.PromotionContractFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractClauseMaterialLoaderTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void loadsOnlyTheSelectedEligibleProjectFilesInCallerOrder() {
        Fixture fixture = fixture();

        ContractClauseMaterialLoader.Source loaded = fixture.loader.load(fixture.projectId,
                fixture.contract.id(), List.of(fixture.award.id(), fixture.contractFile.id()));

        assertThat(loaded.materials()).extracting(ContractClauseMaterial::fileId)
                .containsExactly(fixture.award.id(), fixture.contractFile.id());
        assertThat(loaded.materials()).extracting(ContractClauseMaterial::category)
                .containsExactly(ProjectFileCategory.AWARD_NOTICE, ProjectFileCategory.CONTRACT_BODY);
        assertThat(loaded.materials()).extracting(ContractClauseMaterial::fileId)
                .doesNotContain(fixture.unselected.id());
    }

    @Test
    void rejectsForeignAndPackageSelectionsThroughTheSharedBoundary() {
        Fixture fixture = fixture();
        for (UUID invalid : List.of(UUID.randomUUID(), fixture.packageFile.id())) {
            assertThatThrownBy(() -> fixture.loader.load(fixture.projectId, fixture.contract.id(), List.of(invalid)))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.errorCode()).isEqualTo(BusinessErrorCode.BUSINESS_VALIDATION_FAILED));
        }
    }

    private static Fixture fixture() {
        UUID projectId = UUID.randomUUID();
        Contract contract = PromotionContractFixtures.contract(UUID.randomUUID(), "CLAUSE-SOURCE");
        UUID candidateId = UUID.randomUUID();
        ProjectFile contractFile = file(projectId, ProjectFileCategory.CONTRACT_BODY, "contract.docx", "合同条款");
        ProjectFile award = file(projectId, ProjectFileCategory.AWARD_NOTICE, "award.docx", "中标条款");
        ProjectFile unselected = file(projectId, ProjectFileCategory.TENDER_DOCUMENT, "tender.docx", "采购条款");
        ProjectFile packageFile = new ProjectFile(UUID.randomUUID(), projectId, null, null, "package.zip",
                "application/zip", ProjectFileCategory.PACKAGE, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.NOT_APPLICABLE, "d".repeat(64), "e".repeat(64), 1,
                null, null, NOW, NOW);
        ContractRepository contracts = mock(ContractRepository.class);
        ProjectContractQueryPort associations = mock(ProjectContractQueryPort.class);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        ProjectParsedTextReadService parsedTexts = mock(ProjectParsedTextReadService.class);
        when(contracts.findById(contract.id())).thenReturn(Optional.of(contract));
        when(associations.findByContractId(contract.id())).thenReturn(Optional.of(
                new ProjectContract(projectId, contract.id(), candidateId, "actor", NOW)));
        when(projects.files(projectId)).thenReturn(List.of(contractFile, award, unselected, packageFile));
        when(parsedTexts.read(any(ProjectFile.class))).thenAnswer(call -> call.<ProjectFile>getArgument(0).parsedText());
        SelectedProjectMaterialReadService selected = new SelectedProjectMaterialReadService(projects, parsedTexts);
        return new Fixture(projectId, contract, contractFile, award, unselected, packageFile,
                new ContractClauseMaterialLoader(contracts, associations, selected));
    }

    private static ProjectFile file(UUID projectId, ProjectFileCategory category, String name, String text) {
        return new ProjectFile(UUID.randomUUID(), projectId, null, null, name,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", category,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                "f".repeat(64), text.length(), text, null, NOW, NOW);
    }

    private record Fixture(UUID projectId, Contract contract, ProjectFile contractFile,
                           ProjectFile award, ProjectFile unselected, ProjectFile packageFile,
                           ContractClauseMaterialLoader loader) {
    }
}
