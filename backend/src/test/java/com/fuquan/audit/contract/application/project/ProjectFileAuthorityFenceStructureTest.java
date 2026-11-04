package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProjectFileAuthorityFenceStructureTest {
    @Test
    void bothWritersLockTheSameDurableFileBeforeReadingLatestDecision() throws Exception {
        String materialization = Files.readString(Path.of("src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateMaterializationTransactionCoordinator.java"));
        String materializationService = Files.readString(Path.of("src/main/java/com/fuquan/audit/contract/application/project/ContractCandidateMaterializationService.java"));
        String confirmation = Files.readString(Path.of("src/main/java/com/fuquan/audit/contract/application/project/role/RoleConfirmationTransactionCoordinator.java"));
        String confirmationService = Files.readString(Path.of("src/main/java/com/fuquan/audit/contract/application/project/role/RoleConfirmationService.java"));
        String promotion = Files.readString(Path.of("src/main/java/com/fuquan/audit/contract/application/project/ContractCandidatePromotionTransactionCoordinator.java"));
        String promotionService = Files.readString(Path.of("src/main/java/com/fuquan/audit/contract/application/project/ContractCandidatePromotionService.java"));
        String mapper = Files.readString(Path.of("src/main/resources/mappers/dm8/ProjectFileMapper.xml"));
        assertThat(materialization).contains("files.findByProjectIdAndIdForUpdate(projectId, fileId)");
        assertThat(confirmation).contains("files.findByProjectIdAndIdForUpdate(command.projectId(), command.fileId())");
        assertThat(promotion).contains(".distinct().sorted(Comparator.comparing(UUID::toString))")
                .contains("withFileFences(command.projectId(), fileIds")
                .contains("files.withFileAuthorityFence(projectId, fileIds.get(index)");
        String promotionLocks = promotion.substring(promotion.indexOf("private ContractCandidate requireProjectAndCandidate"),
                promotion.indexOf("private <T> T withFileFences"));
        assertThat(promotionLocks).contains("for (UUID fileId : fileIds)")
                .contains("files.findByProjectIdAndIdForUpdate(command.projectId(), fileId)");
        assertThat(promotionLocks.indexOf("files.findByProjectIdAndIdForUpdate"))
                .isLessThan(promotionLocks.indexOf("projects.findByIdForUpdate"));
        assertThat(promotionLocks.indexOf("projects.findByIdForUpdate"))
                .isLessThan(promotionLocks.indexOf("candidates.findByProjectIdAndIdForUpdate"));
        assertThat(materializationService).contains("files.withFileAuthorityFence(");
        assertThat(confirmationService).contains("files.withFileAuthorityFence(");
        assertThat(promotionService).doesNotContain("withFileAuthorityFence(");
        assertThat(mapper).contains("id=\"findByProjectIdAndIdForUpdate\"")
                .contains("FROM PROJECT_FILE WHERE PROJECT_ID=#{projectId} AND ID=#{id} FOR UPDATE");
    }
}
