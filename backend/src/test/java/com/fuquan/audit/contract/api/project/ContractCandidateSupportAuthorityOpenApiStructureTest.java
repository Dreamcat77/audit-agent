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

class ContractCandidateSupportAuthorityOpenApiStructureTest {
    private final JsonNode document;

    ContractCandidateSupportAuthorityOpenApiStructureTest() throws Exception {
        document = new ObjectMapper().valueToTree(new Yaml().load(Files.readString(Path.of("..", "docs", "openapi.yaml"))));
    }

    @Test
    void routeRequiresSafeKeyStrictBodyAndDocumentedResponses() {
        JsonNode post = document.path("paths").path("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements").path("post");
        assertThat(post.isMissingNode()).as("support replacement POST must be documented").isFalse();
        List<JsonNode> parameters = new ArrayList<>();
        post.path("parameters").forEach(value -> parameters.add(resolve(value)));
        JsonNode key = parameters.stream().filter(value -> value.path("name").asText().equals("Idempotency-Key")).findFirst().orElseThrow();
        assertThat(key.path("required").asBoolean()).isTrue();
        assertThat(key.path("in").asText()).isEqualTo("header");
        assertThat(key.path("schema").path("pattern").asText()).isEqualTo("^[A-Za-z0-9._:-]{1,128}$");
        assertThat(parameters.stream().filter(value -> value.path("in").asText().equals("path")))
                .allSatisfy(value -> {
                    assertThat(value.path("required").asBoolean()).isTrue();
                    assertThat(value.path("schema").path("format").asText()).isEqualTo("uuid");
                });
        assertThat(post.path("requestBody").path("required").asBoolean()).isTrue();
        JsonNode request = resolve(post.path("requestBody").path("content").path("application/json").path("schema"));
        assertThat(request.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(names(request.path("properties"))).containsExactlyInAnyOrder("expectedCandidateVersion", "supportFileIds");
        assertThat(strings(request.path("required"))).containsExactlyInAnyOrder("expectedCandidateVersion", "supportFileIds");
        JsonNode version = request.path("properties").path("expectedCandidateVersion");
        assertThat(version.path("type").asText()).isEqualTo("integer");
        assertThat(version.path("format").asText()).isEqualTo("int64");
        assertThat(version.path("minimum").asLong(-1)).isZero();
        JsonNode ids = request.path("properties").path("supportFileIds");
        assertThat(ids.path("type").asText()).isEqualTo("array");
        assertThat(ids.path("items").path("format").asText()).isEqualTo("uuid");
        assertThat(ids.has("maxItems")).as("39 applies after canonical de-duplication, not to the raw array").isFalse();
        assertThat(ids.path("uniqueItems").asBoolean()).isFalse();
        assertThat(ids.path("description").asText()).contains("39", "distinct", "canonical", "PRIMARY_ONLY_COMPAT");
        assertThat(names(post.path("responses"))).contains("200", "400", "401", "403", "404", "409", "500");
    }

    @Test
    void successAndLocalFailureSchemasDescribeExactPublicContract() {
        JsonNode post = document.path("paths").path("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements").path("post");
        JsonNode success = responseSchema(post, "200");
        JsonNode data = resolve(success.path("properties").path("data"));
        assertThat(names(data.path("properties"))).containsExactlyInAnyOrder("candidateId", "candidateVersion", "supportAuthorityVersion",
                "authorityMode", "supportAssociations", "replayed");
        assertThat(strings(data.path("properties").path("authorityMode").path("enum")))
                .containsExactlyInAnyOrder("PRIMARY_ONLY_COMPAT", "EXPLICIT_SUPPORT_AUTHORITY");
        JsonNode association = resolve(data.path("properties").path("supportAssociations").path("items"));
        assertThat(names(association.path("properties"))).containsExactlyInAnyOrder("supportAuthorityId", "projectId", "candidateId",
                "sourceFileId", "fileSha256", "parseResultId", "parseVersion", "projectVersionNo", "effectiveRole", "roleDecisionId",
                "identityResolution", "identityDecisionId", "sourceFactFingerprint", "authorityVersion", "createdBy", "createdAt");
        for (String status : List.of("404", "409")) {
            JsonNode error = responseSchema(post, status);
            JsonNode errorData = resolve(error.path("properties").path("data"));
            assertThat(names(errorData.path("properties"))).containsExactly("classification");
            assertThat(errorData.path("additionalProperties").asBoolean(true)).isFalse();
            assertThat(strings(resolve(errorData.path("properties").path("classification")).path("enum")))
                    .containsExactlyInAnyOrder("OUTCOME_UNCERTAIN", "REQUEST_REJECTED", "RESULT_SUPERSEDED", "INTEGRITY_CONFLICT");
        }
        assertThat(post.path("responses").path("500").path("description").asText()).contains("unclassified", "retain");
        assertThat(names(document.path("components").path("schemas").path("PromotionRequest").path("properties")))
                .containsExactlyInAnyOrder("expectedVersion", "confirmationId");
        assertThat(names(document.path("components").path("schemas").path("ConfirmationRequest").path("properties")))
                .doesNotContain("supportFileIds", "supportAuthorityFingerprint", "classification");
    }

    private JsonNode responseSchema(JsonNode post, String status) {
        JsonNode content = post.path("responses").path(status).path("content");
        assertThat(content.isMissingNode()).as("response %s must declare a schema", status).isFalse();
        return resolve(content.elements().next().path("schema"));
    }
    private JsonNode resolve(JsonNode value) {
        return value.has("$ref") ? document.at(value.path("$ref").asText().substring(1)) : value;
    }
    private static List<String> names(JsonNode value) {
        List<String> names = new ArrayList<>(); value.fieldNames().forEachRemaining(names::add); return names;
    }
    private static List<String> strings(JsonNode value) {
        List<String> values = new ArrayList<>(); value.forEach(item -> values.add(item.asText())); return values;
    }
}
