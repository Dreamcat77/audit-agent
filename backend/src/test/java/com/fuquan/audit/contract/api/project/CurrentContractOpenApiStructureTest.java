package com.fuquan.audit.contract.api.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class CurrentContractOpenApiStructureTest {
    private final JsonNode document;

    CurrentContractOpenApiStructureTest() throws Exception {
        document = new ObjectMapper().valueToTree(
                new Yaml().load(Files.readString(Path.of("..", "docs", "openapi.yaml"))));
    }

    @Test
    void candidateDetailAndCandidateExposeCurrentPublicProvenance() {
        JsonNode detail = schema("CandidateDetailResponse");
        assertThat(names(detail.path("properties"))).contains(
                "sourceParseResultId", "sourceRoleDecisionId", "sourceIdentityDecisionId",
                "candidateAuthoritySource", "sourceFactFingerprint", "promotionProvenanceSource",
                "authorityMode", "supportAuthorityVersionNo", "supportAuthorityFingerprint",
                "supportAuthorities");
        assertThat(names(schema("ContractCandidate").path("properties"))).contains(
                "sourceParseResultId", "sourceRoleDecisionId", "sourceIdentityDecisionId",
                "sourceAuthoritySource", "sourceFactFingerprint", "promotedConfirmationId");
    }

    @Test
    void confirmationDocumentsActualBusinessRequiredFieldsAndConditionalFacts() {
        JsonNode request = schema("ConfirmationRequest");
        assertThat(names(request.path("properties"))).containsExactlyInAnyOrder(
                "expectedVersion", "contractNo", "contractName", "contractType", "partyA",
                "partyB", "partyBCreditCode", "contractAmount", "amountInWords",
                "lineItems", "awardNotice");
        assertThat(strings(request.path("required"))).containsExactlyInAnyOrder(
                "expectedVersion", "contractNo", "contractName", "contractType",
                "partyA", "partyB", "contractAmount");
        assertThat(request.path("properties").path("lineItems").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/PromotionLineItemRequest");
        assertThat(request.path("properties").path("awardNotice").path("$ref").asText())
                .isEqualTo("#/components/schemas/PromotionAwardRequest");
        JsonNode line = schema("PromotionLineItemRequest");
        assertThat(names(line.path("properties"))).contains("basis", "quantity", "unitPrice", "declaredAmount");
        assertThat(strings(line.path("required"))).contains("itemKey", "itemName", "declaredAmount")
                .doesNotContain("quantity", "unitPrice");
        assertThat(strings(schema("PromotionAwardRequest").path("required"))).contains(
                "noticeNo", "projectName", "winningBidder", "winningAmount", "winningDate");
    }

    @Test
    void currentContractLifecycleAndCloseoutRoutesRemainDocumented() {
        JsonNode paths = document.path("paths");
        String candidate = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}";
        String run = "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs";
        assertThat(paths.path(candidate + "/support-authority-replacements").has("post")).isTrue();
        assertThat(paths.path(candidate + "/confirmations").has("post")).isTrue();
        assertThat(paths.path(candidate + "/promote").has("post")).isTrue();
        assertThat(paths.path(run).has("post")).isTrue();
        assertThat(paths.path(run + "/latest").has("get")).isTrue();
        assertThat(paths.path(run + "/{runId}").has("get")).isTrue();
        assertThat(paths.path(run + "/{runId}/steps/{stepId}").has("get")).isTrue();
        assertThat(paths.path(run + "/{runId}/findings/{findingId}/acknowledgements").has("post")).isTrue();
        assertThat(paths.path(run + "/{runId}/reports").has("post")).isTrue();
        assertThat(paths.path(run + "/{runId}/debug-bundles").has("post")).isTrue();
        assertThat(paths.path("/api/v1/projects/{projectId}/files/{fileId}/role-assessments").has("post"))
                .isTrue();
        assertThat(paths.path("/api/v1/projects/{projectId}/files/{fileId}/identity-assessments")
                .has("post")).isTrue();
    }

    private JsonNode schema(String name) {
        JsonNode schema = document.path("components").path("schemas").path(name);
        assertThat(schema.isMissingNode()).as("missing schema %s", name).isFalse();
        return schema;
    }

    private static List<String> names(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.fieldNames().forEachRemaining(values::add);
        return values;
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }
}
