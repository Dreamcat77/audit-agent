package com.fuquan.audit.contract.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.ContractCandidateSupportReplacementException;
import com.fuquan.audit.contract.application.project.ContractCandidateSupportReplacementException.Classification;
import com.fuquan.audit.contract.application.project.ContractCandidateSupportReplacementService;
import com.fuquan.audit.contract.application.project.ContractCandidateSupportReplacementTransactionCoordinator;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.application.port.FileStoragePort;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.application.port.StoredFileInfo;
import com.fuquan.audit.contract.application.port.StoredFileInspection;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ContractCandidateSupportAuthorityApiIntegrationTest {
    private static final String PATH = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements";
    private static final Instant NOW = Instant.parse("2026-09-20T03:00:00Z");
    private static final String ACTOR = "support-api-user";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectRepository projects;
    @SpyBean ProjectFileRepository files;
    @SpyBean ExtractionResultRepository extractions;
    @SpyBean FileStoragePort storage;
    @SpyBean ProjectFileParseResultRepository parses;
    @Autowired ProjectFileRoleDecisionRepository roles;
    @Autowired ProjectFileIdentityDecisionRepository identities;
    @Autowired ProjectFileIdentityAssessmentRepository assessments;
    @SpyBean ContractCandidateRepository candidates;
    @Autowired ContractCandidateSupportAuthorityRepository supports;
    @Autowired ContractCandidateAssembler assembler;
    @SpyBean ContractCandidateSupportReplacementTransactionCoordinator transactions;
    @SpyBean InMemoryIdempotencyAdapter idempotency;

    @Test
    void candidateDetailSeparatesVisiblePrimaryAwardFromExactSupportAggregation() throws Exception {
        Fixture f = fixtureWithFacts();
        Source support = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        List<ExtractionResult> supportFacts = facts(support, null, "98", "SUPPORT", "Support line");
        supportFacts.forEach(extractions::insertIfAbsent);
        Source unrelated = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        facts(unrelated, null, "77", "UNASSIGNED", "Other line").forEach(extractions::insertIfAbsent);
        send(f, key(), body(0, List.of(support.file().id())), "AUDITOR").andExpect(status().isOk());
        var stored = candidates.findByProjectIdAndId(f.projectId(), f.candidateId()).orElseThrow();
        var association = supports.findCurrentByProjectIdAndCandidateId(f.projectId(), f.candidateId()).get(0);
        clearInvocations(files, extractions);

        JsonNode detail = detail(f);

        assertThat(detail.path("authorityMode").asText()).isEqualTo("EXPLICIT_SUPPORT_AUTHORITY");
        assertThat(detail.path("supportAuthorityVersionNo").asLong()).isEqualTo(1);
        assertThat(detail.path("supportAuthorityFingerprint").asText()).isEqualTo(stored.supportAuthorityFingerprint());
        assertThat(detail.path("candidate").path("awardAmount").decimalValue()).isEqualByComparingTo("98");
        assertThat(detail.path("fieldStates").path("AWARD_AMOUNT").asText()).isEqualTo("CONFIRMED");
        assertThat(detail.path("fieldStates").path("CONTRACT_NO").asText()).isEqualTo("CONFIRMED");
        assertThat(detail.path("lineItemFieldStates").path("line-1").path("LINE_ITEM_NAME").asText())
                .isEqualTo("CONFIRMED");
        assertThat(detail.path("candidate").path("contractNo").asText()).isEqualTo("PRIMARY");
        assertThat(detail.path("candidate").path("lineItems").get(0).path("itemName").asText()).isEqualTo("Primary line");
        assertThat(detail.path("supportAuthorities").size()).isEqualTo(1);
        JsonNode projection = detail.path("supportAuthorities").get(0);
        assertThat(projection.path("supportAuthorityId").asText()).isEqualTo(association.supportAuthorityId().toString());
        assertThat(projection.path("sourceFileId").asText()).isEqualTo(support.file().id().toString());
        assertThat(projection.path("parseResultId").asText()).isEqualTo(support.parseId().toString());
        assertThat(projection.path("roleDecisionId").asText()).isEqualTo(support.roleId().toString());
        assertThat(projection.path("identityDecisionId").asText()).isEqualTo(support.identityId().toString());
        assertThat(projection.path("originalFilename").asText()).isEqualTo(support.file().originalFilename());
        assertThat(projection.path("current").asBoolean()).isTrue();
        assertThat(projection.path("safeReasonCode").isNull()).isTrue();
        assertThat(projection.path("eligibleAwardFacts").size()).isEqualTo(1);
        assertThat(projection.path("eligibleAwardFacts").get(0).path("id").asText()).isEqualTo(supportFacts.get(0).id().toString());
        assertThat(detail.path("evidence")).hasSize(6);
        assertThat(detail.path("evidence").findValuesAsText("sourceFileId"))
                .containsOnly(f.primary().file().id().toString(), support.file().id().toString());
        assertThat(detail.path("evidence").findValuesAsText("normalizedValue"))
                .contains("100", "98", "SUPPORT", "Support line");
        assertThat(projection.toString()).doesNotContain("synthetic private text", "storageKey", "canonicalText", "parsedText");
        assertThat(detail(f)).isEqualTo(detail);
        verify(files, never()).findByProjectId(any());
        verify(extractions, never()).findByProjectId(any());
    }

    @ParameterizedTest
    @CsvSource({"99,CONFLICT", "98.00,CONFIRMED"})
    void onlyAssignedSupportValuesDetermineAwardConflictsInStableFileOrder(String secondAmount, String state) throws Exception {
        Fixture f = fixtureWithFacts();
        Source first = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        Source second = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        facts(first, null, "98", "FIRST", "First support line").forEach(extractions::insertIfAbsent);
        facts(second, null, secondAmount, "SECOND", "Second support line").forEach(extractions::insertIfAbsent);
        send(f, key(), body(0, List.of(second.file().id(), first.file().id())), "AUDITOR").andExpect(status().isOk());

        JsonNode detail = detail(f);

        assertThat(detail.path("authorityMode").asText()).isEqualTo("EXPLICIT_SUPPORT_AUTHORITY");
        assertThat(detail.path("fieldStates").path("AWARD_AMOUNT").asText()).isEqualTo(state);
        if (state.equals("CONFLICT")) assertThat(detail.path("candidate").path("awardAmount").isNull()).isTrue();
        else assertThat(detail.path("candidate").path("awardAmount").decimalValue()).isEqualByComparingTo("98");
        assertThat(detail.path("supportAuthorities").findValuesAsText("sourceFileId").stream().distinct().toList())
                .containsExactlyElementsOf(List.of(first.file().id().toString(), second.file().id().toString())
                        .stream().sorted().toList());
        assertThat(detail.path("fieldStates").path("CONTRACT_NO").asText()).isEqualTo("CONFIRMED");
        assertThat(detail.path("lineItemFieldStates").size()).isEqualTo(1);
        assertThat(detail.path("lineItemFieldStates").path("line-1").path("LINE_ITEM_NAME").asText()).isEqualTo("CONFIRMED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"parse", "role", "identity", "project", "facts", "missing-file", "missing-exact-parse", "wrong-exact-parse"})
    void staleAssociationStaysVisibleWithoutCurrentAuthorityOrPrimaryAwardFallback(String change) throws Exception {
        Fixture f = fixtureWithFacts();
        Source support = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        facts(support, null, "98", "SUPPORT", "Support line").forEach(extractions::insertIfAbsent);
        send(f, key(), body(0, List.of(support.file().id())), "AUDITOR").andExpect(status().isOk());
        switch (change) {
            case "parse" -> parses.insertIfAbsent(parse(support.file(), UUID.randomUUID(), 2));
            case "role" -> roles.insertIfAbsent(new ProjectFileRoleDecision(UUID.randomUUID(), f.projectId(), support.file().id(),
                    support.file().sha256(), support.parseId(), 1, UUID.randomUUID(), DocumentRole.AWARD_NOTICE,
                    RoleDecisionStatus.CONFIRMED, ACTOR, NOW.plusSeconds(1), support.roleId(), null, 2));
            case "identity" -> identities.insertIfAbsent(new ProjectFileIdentityDecision(UUID.randomUUID(), f.projectId(), 1,
                    support.file().id(), support.file().sha256(), support.parseId(), 1, UUID.randomUUID(),
                    ProjectIdentityResolution.CONFIRM_SAME_PROJECT, ACTOR, NOW.plusSeconds(1), support.identityId(), null, 2));
            case "project" -> {
                Project project = projects.findById(f.projectId()).orElseThrow();
                projects.compareAndSet(project, project.confirmMetadata(1, "Changed synthetic project", "SYN-2", null, NOW.plusSeconds(1)));
            }
            case "facts" -> extractions.insertIfAbsent(new ExtractionResult(UUID.randomUUID(), f.projectId(), support.file().id(),
                    support.parseId(), null, sha(UUID.randomUUID().toString()), "test-v1", "AWARD_AMOUNT", null,
                    "99", "99", new java.math.BigDecimal("99"), "new synthetic excerpt", 1, 1, "page:1", NOW));
            case "missing-file" -> doReturn(Optional.empty()).when(files).findByProjectIdAndId(f.projectId(), support.file().id());
            case "missing-exact-parse" -> doReturn(Optional.empty()).when(parses).findById(support.parseId());
            case "wrong-exact-parse" -> doReturn(Optional.of(parse(f.primary().file(), support.parseId(), 1)))
                    .when(parses).findById(support.parseId());
            default -> throw new AssertionError(change);
        }

        JsonNode detail = detail(f);

        assertThat(detail.path("authorityMode").asText()).isEqualTo("EXPLICIT_SUPPORT_AUTHORITY");
        assertThat(detail.path("supportAuthorities").size()).isEqualTo(1);
        JsonNode projection = detail.path("supportAuthorities").get(0);
        assertThat(projection.path("sourceFileId").asText()).isEqualTo(support.file().id().toString());
        assertThat(projection.path("parseResultId").asText()).isEqualTo(support.parseId().toString());
        assertThat(projection.path("current").asBoolean()).isFalse();
        assertThat(projection.path("safeReasonCode").asText()).isEqualTo("SUPPORT_AUTHORITY_NOT_CURRENT");
        assertThat(projection.path("eligibleAwardFacts")).isEmpty();
        assertThat(detail.path("fieldStates").path("AWARD_AMOUNT").asText()).isEqualTo("MISSING");
        assertThat(detail.path("candidate").path("awardAmount").decimalValue()).isEqualByComparingTo("98");
        assertThat(detail.path("lineItemFieldStates").path("line-1").path("LINE_ITEM_NAME").asText()).isEqualTo("CONFIRMED");
        if (change.equals("parse")) {
            assertThat(detail.path("evidence").findValuesAsText("parseResultId"))
                    .containsOnly(f.primary().parseId().toString(), support.parseId().toString());
            assertThat(detail.path("evidence")).hasSize(6);
        }
        if (change.contains("exact-parse") || change.equals("missing-file")) {
            assertThat(detail.path("evidence").findValuesAsText("sourceFileId"))
                    .containsOnly(f.primary().file().id().toString());
        }
    }

    @Test
    void clearingSupportRestoresExactPrimaryEvidenceAndFieldStates() throws Exception {
        Fixture f = fixtureWithFacts();
        JsonNode original = detail(f);
        Source support = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        facts(support, null, "98", "SUPPORT", "Support line").forEach(extractions::insertIfAbsent);
        send(f, key(), body(0, List.of(support.file().id())), "AUDITOR").andExpect(status().isOk());
        send(f, key(), body(1, List.of()), "AUDITOR").andExpect(status().isOk());

        JsonNode cleared = detail(f);

        assertThat(cleared.path("authorityMode").asText()).isEqualTo("PRIMARY_ONLY_COMPAT");
        assertThat(cleared.path("supportAuthorityVersionNo").asLong()).isEqualTo(2);
        assertThat(cleared.path("supportAuthorities")).isEmpty();
        assertThat(cleared.path("evidence")).isEqualTo(original.path("evidence"));
        assertThat(cleared.path("fieldStates")).isEqualTo(original.path("fieldStates"));
        assertThat(cleared.path("lineItemFieldStates")).isEqualTo(original.path("lineItemFieldStates"));
        assertThat(cleared.path("candidate").path("awardAmount").decimalValue()).isEqualByComparingTo("100");
    }

    @Test
    void missingExplicitCohortCannotSilentlyBecomePrimaryOnly() throws Exception {
        Fixture f = fixtureWithFacts();
        Source support = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        facts(support, null, "98", "SUPPORT", "Support line").forEach(extractions::insertIfAbsent);
        send(f, key(), body(0, List.of(support.file().id())), "AUDITOR").andExpect(status().isOk());
        supports.deleteCurrentByProjectIdAndCandidateId(f.projectId(), f.candidateId());

        generic(mvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                f.projectId(), f.candidateId()).with(authentication(auth("AUDITOR")))), 409);
    }

    @Test
    void evidenceChangingAfterValidationCannotGainAggregationAuthority() throws Exception {
        Fixture f = fixtureWithFacts();
        Source support = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        List<ExtractionResult> original = facts(support, null, "98", "SUPPORT", "Support line");
        original.forEach(extractions::insertIfAbsent);
        send(f, key(), body(0, List.of(support.file().id())), "AUDITOR").andExpect(status().isOk());
        List<ExtractionResult> changed = new ArrayList<>(original);
        changed.add(new ExtractionResult(UUID.randomUUID(), f.projectId(), support.file().id(), support.parseId(), null,
                sha(UUID.randomUUID().toString()), "test-v1", "AWARD_AMOUNT", null, "99", "99",
                new java.math.BigDecimal("99"), "later synthetic excerpt", 1, 1, "page:1", NOW));
        doReturn(original, changed).when(extractions)
                .findBySourceFileIdAndParseResultId(f.projectId(), support.file().id(), support.parseId());

        JsonNode detail = detail(f);

        assertThat(detail.path("supportAuthorities").get(0).path("current").asBoolean()).isFalse();
        assertThat(detail.path("supportAuthorities").get(0).path("eligibleAwardFacts")).isEmpty();
        assertThat(detail.path("fieldStates").path("AWARD_AMOUNT").asText()).isEqualTo("MISSING");
        assertThat(detail.path("candidate").path("awardAmount").decimalValue()).isEqualByComparingTo("98");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void oneSupportRereadDriftMakesTheCompleteConflictingCohortNonAuthoritative(int driftedIndex) throws Exception {
        Fixture f = fixtureWithFacts();
        List<Source> ordered = java.util.stream.Stream.of(source(f.projectId(), DocumentRole.AWARD_NOTICE),
                        source(f.projectId(), DocumentRole.AWARD_NOTICE))
                .sorted(java.util.Comparator.comparing(source -> source.file().id().toString())).toList();
        Source drifted = ordered.get(driftedIndex), unchanged = ordered.get(1 - driftedIndex);
        List<ExtractionResult> original = facts(drifted, null, "98", "SUPPORT-A", "Support A line");
        original.forEach(extractions::insertIfAbsent);
        facts(unchanged, null, "99", "SUPPORT-B", "Support B line").forEach(extractions::insertIfAbsent);
        send(f, key(), body(0, ordered.stream().map(source -> source.file().id()).toList()), "AUDITOR")
                .andExpect(status().isOk());
        JsonNode before = detail(f);
        assertThat(before.path("fieldStates").path("AWARD_AMOUNT").asText()).isEqualTo("CONFLICT");
        assertThat(before.path("supportAuthorities")).allSatisfy(row -> assertThat(row.path("current").asBoolean()).isTrue());
        List<ExtractionResult> changed = new ArrayList<>(original);
        changed.add(new ExtractionResult(UUID.randomUUID(), f.projectId(), drifted.file().id(), drifted.parseId(), null,
                sha(UUID.randomUUID().toString()), "test-v1", "AWARD_AMOUNT", null, "97", "97",
                new java.math.BigDecimal("97"), "later synthetic cohort excerpt", 1, 1, "page:1", NOW));
        doReturn(original, changed).when(extractions)
                .findBySourceFileIdAndParseResultId(f.projectId(), drifted.file().id(), drifted.parseId());

        JsonNode detail = detail(f);

        assertThat(detail.path("fieldStates").path("AWARD_AMOUNT").asText()).isEqualTo("MISSING");
        assertThat(detail.path("candidate").path("awardAmount").isNull()).isTrue();
        assertThat(detail.path("authorityMode").asText()).isEqualTo("EXPLICIT_SUPPORT_AUTHORITY");
        assertThat(detail.path("supportAuthorities")).hasSize(2).allSatisfy(row -> {
            assertThat(row.path("current").asBoolean()).isFalse();
            assertThat(row.path("safeReasonCode").asText()).isEqualTo("SUPPORT_AUTHORITY_NOT_CURRENT");
            assertThat(row.path("eligibleAwardFacts")).isEmpty();
        });
        assertThat(detail.path("evidence")).hasSize(10);
        assertThat(detail.path("evidence").findValuesAsText("sourceFileId"))
                .contains(f.primary().file().id().toString(), drifted.file().id().toString(), unchanged.file().id().toString());
        assertThat(detail.path("evidence").findValuesAsText("normalizedValue")).contains("100", "98", "99", "97");
    }

    @ParameterizedTest
    @ValueSource(strings = {"business", "infrastructure"})
    void unknownSupportReadFailurePropagatesInsteadOfReturningAStaleSuccess(String failureType) throws Exception {
        Fixture f = fixtureWithFacts();
        Source support = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        facts(support, null, "98", "SUPPORT", "Support line").forEach(extractions::insertIfAbsent);
        send(f, key(), body(0, List.of(support.file().id())), "AUDITOR").andExpect(status().isOk());
        RuntimeException failure = failureType.equals("business")
                ? new BusinessException(BusinessErrorCode.INTERNAL_ERROR, "synthetic support read failure")
                : new IllegalStateException("synthetic infrastructure failure");
        doThrow(failure).when(extractions)
                .findBySourceFileIdAndParseResultId(f.projectId(), support.file().id(), support.parseId());

        generic(mvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                f.projectId(), f.candidateId()).with(authentication(auth("AUDITOR")))), 500);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "AUDITOR", "LEGAL"})
    void authorizedReplacementAndCanonicalReplayReturnTheSameDurableCohort(String role) throws Exception {
        Fixture f = fixture();
        Source support = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        String key = key();
        var first = response(send(f, key, body(0, List.of(support.file().id())), role).andExpect(status().isOk()));
        JsonNode data = first.get("data");
        assertThat(fieldNames(data)).containsExactlyInAnyOrder("candidateId", "candidateVersion",
                "supportAuthorityVersion", "authorityMode", "supportAssociations", "replayed");
        assertThat(data.path("candidateId").asText()).isEqualTo(f.candidateId().toString());
        assertThat(data.path("candidateVersion").asLong()).isEqualTo(1);
        assertThat(data.path("supportAuthorityVersion").asLong()).isEqualTo(1);
        assertThat(data.path("authorityMode").asText()).isEqualTo("EXPLICIT_SUPPORT_AUTHORITY");
        assertThat(data.path("replayed").asBoolean()).isFalse();
        JsonNode row = data.path("supportAssociations").get(0);
        assertThat(row.path("sourceFileId").asText()).isEqualTo(support.file().id().toString());
        assertThat(row.path("parseResultId").asText()).isEqualTo(support.parseId().toString());
        assertThat(row.path("roleDecisionId").asText()).isEqualTo(support.roleId().toString());
        assertThat(row.path("identityDecisionId").asText()).isEqualTo(support.identityId().toString());
        assertThat(row.path("effectiveRole").asText()).isEqualTo("AWARD_NOTICE");
        assertThat(row.path("identityResolution").asText()).isEqualTo("CONFIRM_SAME_PROJECT");
        assertThat(row.path("createdBy").asText()).isEqualTo(ACTOR);
        assertThat(first.toString()).doesNotContain("synthetic private text", "private.pdf", "storageKey");

        JsonNode replay = response(send(f, key, body(0, List.of(support.file().id(), support.file().id())), role)
                .andExpect(status().isOk())).get("data");
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("supportAssociations")).isEqualTo(data.path("supportAssociations"));
        assertThat(replay.path("candidateVersion").asLong()).isEqualTo(1);
        ContractCandidate current = candidates.findByProjectIdAndId(f.projectId(), f.candidateId()).orElseThrow();
        assertThat(current.versionNo()).isEqualTo(1);
        assertThat(current.sourceContractFileId()).isEqualTo(f.primary().file().id());
        assertThat(current.sourceParseResultId()).isEqualTo(f.primary().parseId());
    }

    @Test
    void requestRecordSerializesOnlyUserOperationFields() throws Exception {
        UUID id = UUID.randomUUID();
        var request = ProjectController.SupportAuthorityReplacementRequest.from(json.readTree(body(3, List.of(id))));
        JsonNode serialized = json.valueToTree(request);
        assertThat(fieldNames(serialized)).containsExactlyInAnyOrder("expectedCandidateVersion", "supportFileIds");
        assertThat(serialized.path("expectedCandidateVersion").asLong()).isEqualTo(3);
        assertThat(serialized.path("supportFileIds").get(0).asText()).isEqualTo(id.toString());
    }

    @Test
    void inProgressDurableRecordIsUncertainWithoutAnotherMutation() throws Exception {
        Fixture f = fixture();
        String key = key();
        send(f, key, body(0, List.of()), "AUDITOR").andExpect(status().isOk());
        String scope = "CONTRACT_CANDIDATE_SUPPORT_REPLACE";
        IdempotencyRecord completed = idempotency.findByScopeAndKey(scope, key).orElseThrow();
        IdempotencyRecord inProgress = new IdempotencyRecord(completed.id(), scope, key, completed.actorId(),
                completed.resourceType(), completed.resourceId(), completed.requestFingerprint(), IdempotencyStatus.IN_PROGRESS,
                completed.resultResourceType(), completed.resultResourceId(), completed.firstRequestId(), completed.traceId(),
                completed.createdAt(), null, 0);
        doReturn(Optional.of(inProgress)).when(idempotency).findByScopeAndKey(scope, key);
        classified(send(f, key, body(0, List.of()), "AUDITOR"), 409, "OUTCOME_UNCERTAIN",
                "candidate support replacement is in progress");
        assertThat(candidates.findByProjectIdAndId(f.projectId(), f.candidateId()).orElseThrow().versionNo()).isEqualTo(1);
    }

    @Test
    void missingSuccessfulResultIsUncertainNotDeterministicNotFound() throws Exception {
        Fixture f = fixture();
        String key = key();
        send(f, key, body(0, List.of()), "AUDITOR").andExpect(status().isOk());
        doReturn(Optional.empty()).when(candidates).findByProjectIdAndId(f.projectId(), f.candidateId());
        classified(send(f, key, body(0, List.of()), "AUDITOR"), 404, "OUTCOME_UNCERTAIN", "contract candidate not found");
    }

    @Test
    void emptyReplacementRestoresPrimaryOnlyMode() throws Exception {
        Fixture f = fixture();
        Source support = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        send(f, key(), body(0, List.of(support.file().id())), "AUDITOR").andExpect(status().isOk());
        JsonNode data = response(send(f, key(), body(1, List.of()), "AUDITOR").andExpect(status().isOk())).get("data");
        assertThat(data.path("authorityMode").asText()).isEqualTo("PRIMARY_ONLY_COMPAT");
        assertThat(data.path("candidateVersion").asLong()).isEqualTo(2);
        assertThat(data.path("supportAuthorityVersion").asLong()).isEqualTo(2);
        assertThat(data.path("supportAssociations")).isEmpty();
        assertThat(supports.findCurrentByProjectIdAndCandidateId(f.projectId(), f.candidateId())).isEmpty();
    }

    @Test
    void requiresAuthenticationAllowedRoleAndCsrf() throws Exception {
        Fixture f = fixture();
        mvc.perform(request(f, key(), body(0, List.of())).with(csrf())).andExpect(status().isUnauthorized());
        send(f, key(), body(0, List.of()), "VIEWER").andExpect(status().isForbidden());
        mvc.perform(request(f, key(), body(0, List.of())).with(authentication(auth("AUDITOR"))))
                .andExpect(status().isForbidden());
        assertThat(candidates.findByProjectIdAndId(f.projectId(), f.candidateId()).orElseThrow().versionNo()).isZero();
    }

    @Test
    void requiresIdempotencyKeyAndRejectsMalformedPathIds() throws Exception {
        Fixture f = fixture();
        generic(send(f, null, body(0, List.of()), "AUDITOR"), 400);
        generic(mvc.perform(post(PATH, "not-a-uuid", f.candidateId()).header("Idempotency-Key", key())
                .contentType("application/json").content(body(0, List.of()))
                .with(csrf()).with(authentication(auth("AUDITOR")))), 400);
        generic(mvc.perform(post(PATH, f.projectId(), "not-a-uuid").header("Idempotency-Key", key())
                .contentType("application/json").content(body(0, List.of()))
                .with(csrf()).with(authentication(auth("AUDITOR")))), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad key", "bad/key", "bad+key", "秘密"})
    void rejectsUnsafeKey(String key) throws Exception {
        generic(send(fixture(), key, body(0, List.of()), "AUDITOR"), 400);
    }

    @Test
    void keyLengthIsBoundedAt128Characters() throws Exception {
        Fixture f = fixture();
        generic(send(f, "k".repeat(129), body(0, List.of()), "AUDITOR"), 400);
        send(f, "k".repeat(92) + UUID.randomUUID(), body(0, List.of()), "AUDITOR").andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null", "{}", "{\"expectedCandidateVersion\":0}",
            "{\"supportFileIds\":[]}", "{\"expectedCandidateVersion\":null,\"supportFileIds\":[]}",
            "{\"expectedCandidateVersion\":-1,\"supportFileIds\":[]}",
            "{\"expectedCandidateVersion\":0.5,\"supportFileIds\":[]}",
            "{\"expectedCandidateVersion\":\"0\",\"supportFileIds\":[]}",
            "{\"expectedCandidateVersion\":true,\"supportFileIds\":[]}",
            "{\"expectedCandidateVersion\":9223372036854775808,\"supportFileIds\":[]}",
            "{\"expectedCandidateVersion\":9223372036854775807,\"supportFileIds\":[]}",
            "{\"expectedCandidateVersion\":0,\"supportFileIds\":null}",
            "{\"expectedCandidateVersion\":0,\"supportFileIds\":{}}",
            "{\"expectedCandidateVersion\":0,\"supportFileIds\":\"uuid\"}",
            "{\"expectedCandidateVersion\":0,\"supportFileIds\":[null]}",
            "{\"expectedCandidateVersion\":0,\"supportFileIds\":[1]}",
            "{\"expectedCandidateVersion\":0,\"supportFileIds\":[{}]}",
            "{\"expectedCandidateVersion\":0,\"supportFileIds\":[\"bad-uuid\"]}",
            "{\"expectedCandidateVersion\":0,\"supportFileIds\":[\"1-1-1-1-1\"]}"})
    void rejectsMalformedBodyWithoutTypedOutcome(String body) throws Exception {
        generic(send(fixture(), key(), body, "AUDITOR"), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"parseResultId", "roleDecisionId", "identityDecisionId", "effectiveRole",
            "supportAuthorityFingerprint", "projectId", "candidateId", "createdBy", "unknown"})
    void rejectsEveryExtraField(String field) throws Exception {
        var body = json.createObjectNode().put("expectedCandidateVersion", 0).put(field, "untrusted");
        body.putArray("supportFileIds");
        generic(send(fixture(), key(), body.toString(), "AUDITOR"), 400);
    }

    @Test
    void fortyDistinctIdsRejectBeforeAuthorityLookup() throws Exception {
        Fixture f = fixture();
        List<UUID> ids = IntStream.range(0, 40).mapToObj(i -> UUID.randomUUID()).toList();
        JsonNode error = generic(send(f, key(), body(0, ids), "AUDITOR"), 400);
        assertThat(error.path("message").asText()).isEqualTo(ContractCandidateSupportReplacementService.SUPPORT_FILE_COUNT_LIMIT_MESSAGE);
        assertThat(candidates.findByProjectIdAndId(f.projectId(), f.candidateId()).orElseThrow().versionNo()).isZero();
    }

    @Test
    void seventyEightRawIdsCanonicalizeToThirtyNineDistinctSupports() throws Exception {
        Fixture f = fixture();
        List<UUID> ids = IntStream.range(0, 39).mapToObj(i -> source(f.projectId(), DocumentRole.AWARD_NOTICE).file().id()).toList();
        List<String> raw = new ArrayList<>();
        ids.forEach(id -> { raw.add(id.toString()); raw.add(id.toString().toUpperCase(java.util.Locale.ROOT)); });
        String body = json.writeValueAsString(Map.of("expectedCandidateVersion", 0, "supportFileIds", raw));
        JsonNode data = response(send(f, key(), body, "AUDITOR").andExpect(status().isOk())).get("data");
        assertThat(data.path("supportAssociations")).hasSize(39);
        assertThat(supports.findCurrentByProjectIdAndCandidateId(f.projectId(), f.candidateId())).hasSize(39);
    }

    @Test
    void foreignProjectCandidateAndFileRemainScopedNotFound() throws Exception {
        Fixture f = fixture(), foreign = fixture();
        classified(send(new Fixture(foreign.projectId(), f.candidateId(), f.primary()), key(), body(0, List.of()), "AUDITOR"),
                404, "REQUEST_REJECTED", "contract candidate not found");
        Source foreignSupport = source(foreign.projectId(), DocumentRole.AWARD_NOTICE);
        JsonNode error = classified(send(f, key(), body(0, List.of(foreignSupport.file().id())), "AUDITOR"),
                404, "REQUEST_REJECTED", "candidate authority file not found");
        assertThat(error.toString()).doesNotContain(foreign.projectId().toString(), "private.pdf");
    }

    @Test
    void staleVersionAndPromotedCandidateAreRequestRejected() throws Exception {
        Fixture f = fixture();
        classified(send(f, key(), body(1, List.of()), "AUDITOR"), 409, "REQUEST_REJECTED",
                "candidate support replacement version conflict");
        candidates.promoteIfVersionMatches(f.projectId(), f.candidateId(), 0, UUID.randomUUID(), ACTOR, NOW);
        classified(send(f, key(), body(1, List.of()), "AUDITOR"), 409, "REQUEST_REJECTED",
                "candidate support replacement version conflict");
    }

    @Test
    void staleSupportAndPrimaryAuthorityAreRequestRejected() throws Exception {
        Fixture f = fixture();
        Source support = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        parses.insertIfAbsent(parse(support.file(), UUID.randomUUID(), 2));
        classified(send(f, key(), body(0, List.of(support.file().id())), "AUDITOR"), 409, "REQUEST_REJECTED", null);
        parses.insertIfAbsent(parse(f.primary().file(), UUID.randomUUID(), 2));
        classified(send(f, key(), body(0, List.of()), "AUDITOR"), 409, "REQUEST_REJECTED", null);
    }

    @Test
    void changedCommandFingerprintAndCorruptCohortAreIntegrityConflicts() throws Exception {
        Fixture f = fixture();
        Source support = source(f.projectId(), DocumentRole.AWARD_NOTICE);
        String key = key();
        String body = body(0, List.of(support.file().id()));
        send(f, key, body, "AUDITOR").andExpect(status().isOk());
        classified(send(f, key, body(0, List.of()), "AUDITOR"), 409, "INTEGRITY_CONFLICT", null);
        supports.deleteCurrentByProjectIdAndCandidateId(f.projectId(), f.candidateId());
        classified(send(f, key, body, "AUDITOR"), 409, "INTEGRITY_CONFLICT", null);
    }

    @Test
    void laterMutationSupersedesOriginalResultWithoutRestoringIt() throws Exception {
        Fixture f = fixture();
        String key = key();
        send(f, key, body(0, List.of()), "AUDITOR").andExpect(status().isOk());
        send(f, key(), body(1, List.of()), "AUDITOR").andExpect(status().isOk());
        classified(send(f, key, body(0, List.of()), "AUDITOR"), 409, "RESULT_SUPERSEDED", null);
        assertThat(candidates.findByProjectIdAndId(f.projectId(), f.candidateId()).orElseThrow().versionNo()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"CONFLICT,OUTCOME_UNCERTAIN,in progress", "INTERNAL_ERROR,OUTCOME_UNCERTAIN,result unproven",
            "NOT_FOUND,REQUEST_REJECTED,not found", "BUSINESS_VALIDATION_FAILED,REQUEST_REJECTED,rejected",
            "CONFLICT,RESULT_SUPERSEDED,superseded", "CONFLICT,INTEGRITY_CONFLICT,corrupt"})
    void typedFailurePreservesOriginalStatusCodeMessageAndOnlyClassification(BusinessErrorCode code,
            Classification classification, String message) throws Exception {
        // Only the fault boundary is substituted; MVC, authentication, parsing and service validation remain real.
        doThrow(new ContractCandidateSupportReplacementException(code, message, classification))
                .when(transactions).replace(any(), any());
        classified(send(fixture(), key(), body(0, List.of()), "AUDITOR"), code.httpStatus().value(), classification.name(), message);
    }

    @Test
    void unclassifiedProxyFailureIsGenericAndDoesNotInventRejection() throws Exception {
        doThrow(new IllegalStateException("synthetic private proxy failure")).when(transactions).replace(any(), any());
        JsonNode result = generic(send(fixture(), key(), body(0, List.of()), "AUDITOR"), 500);
        assertThat(result.path("message").asText()).isEqualTo("internal server error");
        assertThat(result.toString()).doesNotContain("private proxy");
    }

    @Test
    void unclassifiedBusinessFailureAndUnrelatedProjectEndpointRetainGlobalEnvelope() throws Exception {
        doThrow(new BusinessException(BusinessErrorCode.CONFLICT, "generic business failure"))
                .when(transactions).replace(any(), any());
        JsonNode result = generic(send(fixture(), key(), body(0, List.of()), "AUDITOR"), 409);
        assertThat(result.path("message").asText()).isEqualTo("generic business failure");
        generic(mvc.perform(get("/api/v1/projects/{projectId}", UUID.randomUUID())
                .with(authentication(auth("AUDITOR")))), 404);
    }

    private ResultActions send(Fixture f, String key, String body, String role) throws Exception {
        return mvc.perform(request(f, key, body).with(csrf()).with(authentication(auth(role))));
    }

    private MockHttpServletRequestBuilder request(Fixture f, String key, String body) {
        var request = post(PATH, f.projectId(), f.candidateId()).contentType("application/json").content(body)
                .header("X-Request-Id", "support-api-request");
        if (key != null) request.header("Idempotency-Key", key);
        return request;
    }

    private JsonNode response(ResultActions result) throws Exception {
        var response = result.andReturn().getResponse();
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        return json.readTree(response.getContentAsString());
    }

    private JsonNode generic(ResultActions result, int status) throws Exception {
        JsonNode body = response(result.andExpect(status().is(status)));
        assertThat(body.path("code").asInt()).isEqualTo(status);
        assertThat(body.get("data").isNull()).isTrue();
        return body;
    }

    private JsonNode classified(ResultActions result, int status, String classification, String message) throws Exception {
        JsonNode body = response(result.andExpect(status().is(status)));
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("code", "message", "data");
        assertThat(body.path("code").asInt()).isEqualTo(status);
        if (message != null) assertThat(body.path("message").asText()).isEqualTo(message);
        assertThat(fieldNames(body.get("data"))).containsExactly("classification");
        assertThat(body.path("data").path("classification").asText()).isEqualTo(classification);
        return body;
    }

    private String body(long version, List<UUID> ids) throws Exception {
        return json.writeValueAsString(Map.of("expectedCandidateVersion", version, "supportFileIds", ids));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private Fixture fixture() {
        UUID projectId = UUID.randomUUID(), candidateId = UUID.randomUUID();
        projects.insert(new Project(projectId, "Synthetic support API project", "SYN-SUPPORT", null, ACTOR, 1, NOW, NOW));
        Source primary = source(projectId, DocumentRole.CONTRACT_BODY);
        candidates.insertIfAbsent(assembler.assemble(candidateId, projectId, primary.file(), primary.parseId(),
                primary.roleId(), primary.identityId(), "a".repeat(64), List.of()));
        return new Fixture(projectId, candidateId, primary);
    }

    private JsonNode detail(Fixture f) throws Exception {
        return response(mvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}",
                f.projectId(), f.candidateId()).with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk())).path("data");
    }

    private Fixture fixtureWithFacts() {
        UUID projectId = UUID.randomUUID(), candidateId = UUID.randomUUID();
        projects.insert(new Project(projectId, "Synthetic detail project", "SYN-DETAIL", null, ACTOR, 1, NOW, NOW));
        Source primary = source(projectId, DocumentRole.CONTRACT_BODY);
        List<ExtractionResult> facts = facts(primary, candidateId, "100", "PRIMARY", "Primary line");
        doReturn(StoredFileInspection.present(new StoredFileInfo(primary.file().sizeBytes(), primary.file().sha256())))
                .when(storage).inspect(new StorageKey(primary.file().storageKey()));
        candidates.insertIfAbsent(assembler.assemble(candidateId, projectId, primary.file(), primary.parseId(),
                primary.roleId(), primary.identityId(), "a".repeat(64), facts));
        facts.forEach(extractions::insertIfAbsent);
        return new Fixture(projectId, candidateId, primary);
    }

    private List<ExtractionResult> facts(Source source, UUID candidateId, String award, String contractNo, String line) {
        return java.util.stream.IntStream.range(0, 3).mapToObj(index -> new ExtractionResult(UUID.randomUUID(),
                source.file().projectId(), source.file().id(), source.parseId(), candidateId,
                sha(source.file().id() + ":" + index), "test-v1",
                List.of("AWARD_AMOUNT", "CONTRACT_NO", "LINE_ITEM_NAME").get(index), index == 2 ? "line-1" : null,
                List.of(award, contractNo, line).get(index), List.of(award, contractNo, line).get(index),
                index == 0 ? new java.math.BigDecimal(award) : null,
                "bounded synthetic excerpt", 1, index + 1, "page:1/paragraph:" + (index + 1), NOW)).toList();
    }

    private Source source(UUID projectId, DocumentRole role) {
        UUID id = UUID.randomUUID(), parseId = UUID.randomUUID(), roleId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID(), assessmentId = UUID.randomUUID();
        ProjectFile file = new ProjectFile(id, projectId, null, "private.pdf", "private.pdf", "application/pdf",
                role == DocumentRole.CONTRACT_BODY ? ProjectFileCategory.CONTRACT_BODY : ProjectFileCategory.AWARD_NOTICE,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED, sha("storage-" + id), sha(id.toString()),
                100, "synthetic private text", null, NOW, NOW);
        files.insert(file);
        parses.insertIfAbsent(parse(file, parseId, 1));
        roles.insertIfAbsent(new ProjectFileRoleDecision(roleId, projectId, id, file.sha256(), parseId, 1,
                assessmentId, role, RoleDecisionStatus.CONFIRMED, ACTOR, NOW, null, null, 1));
        assessments.insertIfAbsent(new ProjectFileIdentityAssessment(assessmentId, projectId, 1, id, file.sha256(),
                parseId, 1, ProjectIdentityAssessmentSource.DETERMINISTIC, "test-v1", "human-v1",
                ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT, "e".repeat(64), null, NOW, NOW, 0));
        identities.insertIfAbsent(new ProjectFileIdentityDecision(identityId, projectId, 1, id, file.sha256(), parseId, 1,
                assessmentId, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, ACTOR, NOW, null, null, 1));
        return new Source(file, parseId, roleId, identityId);
    }

    private ProjectFileParseResult parse(ProjectFile file, UUID parseId, int version) {
        return new ProjectFileParseResult(parseId, file.projectId(), file.id(), file.sha256(), version,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT,
                "TEST", null, "synthetic private text", sha("synthetic private text"), 1, 1L, null, null, NOW, NOW, 0);
    }

    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static String key() { return "support-api-" + UUID.randomUUID(); }
    private static Authentication auth(String role) {
        return UsernamePasswordAuthenticationToken.authenticated(new CurrentUser(ACTOR, "support.api", "Support API", Set.of(role)),
                null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private record Fixture(UUID projectId, UUID candidateId, Source primary) { }
    private record Source(ProjectFile file, UUID parseId, UUID roleId, UUID identityId) { }
}
