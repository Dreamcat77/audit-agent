package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractReviewRunAuthorityLockStructureTest {
    @Test
    void runCreateUsesShortWriteTransactionAndLocksMutableRowsBeforeFreeze() throws Exception {
        String resolver = source("contract/application/reviewrun/ReviewRunReadinessResolver.java");
        String coordinator = source("contract/application/reviewrun/ContractReviewRunCreateTransactionCoordinator.java");
        String contractXml = xml("ContractMapper.xml");
        String associationXml = xml("ProjectContractMapper.xml");
        assertThat(coordinator).contains("@Transactional").doesNotContain("FastAPI", "OCR", "@Async");
        assertThat(resolver).contains("projects.findByIdForUpdate(projectId)",
                "associations.findByContractIdForUpdate(contractId)",
                "contracts.findByIdForUpdate(contractId)",
                "candidates.findByProjectIdAndIdForUpdate(projectId, association.candidateId())",
                "files.findByProjectIdAndIdForUpdate(projectId, fileId)");
        assertThat(resolver.indexOf("files.findByProjectIdAndIdForUpdate(projectId, fileId)"))
                .isLessThan(resolver.indexOf("projects.findByIdForUpdate(projectId)"));
        assertThat(contractXml).contains("id=\"findByIdForUpdate\"", "FROM CONTRACT WHERE ID = #{id} FOR UPDATE");
        assertThat(associationXml).contains("id=\"findByContractIdForUpdate\"",
                "WHERE CONTRACT_ID = #{contractId} FOR UPDATE");
    }
    private static String source(String path) throws Exception {
        return Files.readString(Path.of("src", "main", "java", "com", "fuquan", "audit", path));
    }
    private static String xml(String filename) throws Exception {
        return Files.readString(Path.of("src", "main", "resources", "mappers", "dm8", filename))
                .replaceAll("\\s+", " ");
    }
}
