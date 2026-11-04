package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The Project version is fenced in the same write transaction as identity authority. */
class ProjectIdentityDm8ProjectLockStructureTest {
    @Test
    void dm8ProjectRepositoryProvidesRowLockToIdentityAndCandidateAuthorityCommands() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/mappers/dm8/ProjectMapper.xml"))
                .replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        assertThat(xml).contains("ID=\"FINDBYIDFORUPDATE\"", "FROM PROJECT WHERE ID = #{ID} FOR UPDATE");
        assertThat(source("contract/application/project/identity/IdentityAssessmentTransactionCoordinator.java"))
                .contains("PROJECTS.FINDBYIDFORUPDATE(PROJECTID)");
        assertThat(source("contract/application/project/identity/IdentityConfirmationTransactionCoordinator.java"))
                .contains("PROJECTS.FINDBYIDFORUPDATE(COMMAND.PROJECTID())");
        assertThat(source("contract/application/project/ContractCandidateMaterializationTransactionCoordinator.java"))
                .contains("PROJECTS.FINDBYIDFORUPDATE(PROJECTID)");
        assertThat(source("contract/application/project/ContractCandidatePromotionTransactionCoordinator.java"))
                .contains("PROJECTS.FINDBYIDFORUPDATE(COMMAND.PROJECTID())");
    }

    private static String source(String suffix) throws Exception {
        return Files.readString(Path.of("src/main/java/com/fuquan/audit", suffix))
                .replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
