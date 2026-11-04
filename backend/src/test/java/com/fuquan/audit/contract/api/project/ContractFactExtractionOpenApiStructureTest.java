package com.fuquan.audit.contract.api.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractFactExtractionOpenApiStructureTest {
    @Test
    void documentsReadOnlyLatestAttemptWithCandidatesRejectionsAndModelCalls() throws Exception {
        String yaml = Files.readString(Path.of("..", "docs", "openapi.yaml"));

        assertThat(yaml).contains(
                "/api/v1/projects/{projectId}/files/{fileId}/contract-fact-extractions/latest:",
                "ApiResponseContractFactExtractionProjection:",
                "ContractFactExtractionProjection:",
                "ContractFactExtractionRejection:",
                "ContractModelCallAudit:",
                "currentParseResultId:", "currentParseVersion:",
                "candidates:", "rejections:", "modelCalls:",
                "batchStatus:", "safeReason:");
    }
}
