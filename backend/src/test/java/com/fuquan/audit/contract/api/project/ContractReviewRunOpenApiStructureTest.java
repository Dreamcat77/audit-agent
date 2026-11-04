package com.fuquan.audit.contract.api.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractReviewRunOpenApiStructureTest {
    @Test
    void specificationDefinesDurableRunAndStepExecuteReadContracts() throws Exception {
        String yaml = Files.readString(Path.of("..", "docs", "openapi.yaml"));
        assertThat(yaml).contains(
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/latest:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}/diagnostics:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}/execute:",
                "'202':", "CreateReviewRunRequest:", "ReviewRunResponse:",
                "ApiResponseReviewRunCreateConflict:", "ReviewRunCreateConflict:",
                "OUTCOME_UNCERTAIN", "READINESS_REJECTED", "INTEGRITY_CONFLICT",
                "ReviewRunMaterialResponse:", "ReviewStepResponse:", "ReviewStepAttemptResponse:",
                "ApiResponseReviewStepOutputResponse:", "ReviewStepOutputResponse:",
                "ApiResponseReviewStepDiagnostics:", "ReviewStepDiagnostics:",
                "ReviewStepModelCallDiagnostic:", "failureStage:", "failureReasonCode:",
                "ReviewFindingResponse:", "ReviewEvidenceResponse:",
                "expectedContractVersion:", "materialFileIds:", "inputSnapshotIdentity:",
                "sourceConfirmationId:", "externalCallState:", "findings:", "evidence:",
                "auditTask:", "auditCheckResults:", "findingCheckResultRelations:");
        assertThat(yaml).contains(
                "executionMode:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-sessions:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-sessions/{sessionId}/materials:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}/retries:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/findings/{findingId}/acknowledgements:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/reports:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/reports/{reportId}/artifact:",
                "  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/debug-bundles:",
                "AUDITSESSION:", "X-CSRF-TOKEN", "Idempotency-Key", "SYSTEM_ADMIN / AUDITOR");
        assertThat(yaml).doesNotContain("attemptNo=2", "/worker/claims", "/provider-callbacks");
        String diagnostics = yaml.substring(
                yaml.indexOf("  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}/diagnostics:"),
                yaml.indexOf("  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}/execute:"));
        assertThat(diagnostics).contains("SYSTEM_ADMIN / AUDITOR", "read-only", "'200':")
                .doesNotContain("prompt:", "rawResponse:", "documentText:", "responseBody:");
        String latest = yaml.substring(
                yaml.indexOf("  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/latest:"),
                yaml.indexOf("  /api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}:"));
        assertThat(latest).contains("    get:", "'200':", "ApiResponseReviewRunResponse",
                "'401':", "'403':", "'404':", "'409':")
                .doesNotContain("requestBody:", "Idempotency-Key");
    }
}
