package com.fuquan.audit.contract.application.reviewrun.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.FindingSourceProjection;
import com.fuquan.audit.contract.application.FindingSourceProjectionService;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.FrozenEvidenceGrounder;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrozenF02ResultMapperTest {
    private static final Instant NOW = Instant.parse("2026-09-23T09:00:00Z");

    @Test void oldFrozenExecutionCannotCreateNewEvidenceEvenDuringRecoveryWithEmptyConfigurations() {
        var sources = mock(FindingSourceProjectionService.class);
        var fixture = fixture(sources, true);
        when(sources.projectFrozen(fixture.input(), fixture.check()))
                .thenReturn(FindingSourceProjection.unavailable("SOURCE_NOT_FOUND"));
        var mapper = new FrozenF02ResultMapper(sources, new FrozenEvidenceGrounder(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> mapper.map(fixture.input(), fixture.context(), fixture.taskId(), List.of(fixture.check())))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
    }

    @Test void supplierWitnessesRequireTrustedFrozenRolesAndCannotUseTheOldValidatorEntry() {
        var sources = mock(FindingSourceProjectionService.class);
        var f = fixture(sources, false, true);
        var check = new AuditCheckResult(f.check().id(), f.taskId(), F02RuleCodes.AWARD_SUPPLIER_MATCH,
                null, CheckStatus.FAIL, RiskLevel.YELLOW, false, "Supplier-A", "Supplier-B", "supplier mismatch", 1, NOW);
        when(sources.projectFrozen(f.input(), check)).thenReturn(FindingSourceProjection.available("CROSS_FILE", List.of(
                new FindingSourceProjection.Source("CONTRACT", f.run().projectId(), f.contractMaterial().fileId(),
                        "contract.txt", null, null, "legacy", "849000.00"),
                new FindingSourceProjection.Source("CONTRACT", f.run().projectId(), f.contractMaterial().fileId(),
                        "contract.txt", null, null, "legacy", f.contractMaterial().canonicalText()),
                new FindingSourceProjection.Source("AWARD_NOTICE", f.run().projectId(), f.awardMaterial().fileId(),
                        "award.txt", null, null, "legacy", "876000.00"))));
        var result = new FrozenF02ResultMapper(sources, new FrozenEvidenceGrounder(), Clock.fixed(NOW, ZoneOffset.UTC))
                .map(f.input(), f.context(), f.taskId(), List.of(check));
        assertThat(result.evidence()).hasSize(3).extracting(ContractReviewEvidence::validationPolicyVersion)
                .containsOnly("f02-frozen-evidence-v3");
        var materials = f.input().materials().stream().map(m -> durableMaterial(f, m, m.effectiveRole())).toList();
        assertThatCode(() -> FrozenF02ResultMapper.requireValidProjection(f.context().step(), f.taskId(),
                List.of(check), result.findings(), result.evidence(), materials)).doesNotThrowAnyException();
        assertThatThrownBy(() -> FrozenF02ResultMapper.requireValidProjection(f.context().step(), f.taskId(),
                List.of(check), result.findings(), result.evidence())).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        var wrongRoles = f.input().materials().stream().map(m -> durableMaterial(f, m, DocumentRole.CONTRACT_BODY)).toList();
        assertThatThrownBy(() -> FrozenF02ResultMapper.requireValidProjection(f.context().step(), f.taskId(),
                List.of(check), result.findings(), result.evidence(), wrongRoles)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        for (var mutation : java.util.Map.<String, Object>of(
                "runId", UUID.randomUUID(), "stepId", UUID.randomUUID(), "attemptId", UUID.randomUUID(),
                "evidenceRole", "SUPPORT", "fileSha256", "f".repeat(64), "parseResultId", UUID.randomUUID(),
                "canonicalTextSha256", "f".repeat(64), "validationPolicyVersion", "f02-frozen-evidence-v2").entrySet()) {
            var corrupted = new java.util.ArrayList<>(result.evidence());
            corrupted.set(0, copyRecord(corrupted.get(0), mutation.getKey(), mutation.getValue()));
            assertThatThrownBy(() -> FrozenF02ResultMapper.requireValidProjection(f.context().step(), f.taskId(),
                    List.of(check), result.findings(), corrupted, materials)).as(mutation.getKey())
                    .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        }
        var unscoped = new java.util.ArrayList<>(result.findings());
        unscoped.set(0, copyRecord(unscoped.get(0), "attentionCode", "SOURCE_UNAVAILABLE:ANY_ATTENTION"));
        assertThatThrownBy(() -> FrozenF02ResultMapper.requireValidProjection(f.context().step(), f.taskId(),
                List.of(check), unscoped, result.evidence(), materials)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        var oldPolicyEvidence = result.evidence().stream()
                .map(value -> copyRecord(value, "validationPolicyVersion", "f02-frozen-evidence-v2")).toList();
        assertThatThrownBy(() -> FrozenF02ResultMapper.requireValidProjection(f.context().step(), f.taskId(),
                List.of(check), result.findings(), oldPolicyEvidence, materials)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        assertThat(result.findings()).singleElement().satisfies(value -> assertThat(value.statusCode()).isEqualTo("FAIL"));
    }

    @Test void creditCodeFirstSupplierProofKeepsTheOriginalV2ExactTwoContract() {
        var sources = mock(FindingSourceProjectionService.class);
        var f = fixture(sources);
        var check = copyRecord(f.check(), "ruleCode", F02RuleCodes.AWARD_SUPPLIER_MATCH);
        when(sources.projectFrozen(f.input(), check)).thenReturn(FindingSourceProjection.available("CROSS_FILE", List.of(
                new FindingSourceProjection.Source("CONTRACT", f.run().projectId(), f.contractMaterial().fileId(),
                        "contract.txt", null, null, "legacy", "849000.00"),
                new FindingSourceProjection.Source("AWARD_NOTICE", f.run().projectId(), f.awardMaterial().fileId(),
                        "award.txt", null, null, "legacy", "876000.00"))));
        var result = new FrozenF02ResultMapper(sources, new FrozenEvidenceGrounder(), Clock.fixed(NOW, ZoneOffset.UTC))
                .map(f.input(), f.context(), f.taskId(), List.of(check));
        assertThat(result.evidence()).extracting(ContractReviewEvidence::validationPolicyVersion)
                .containsOnly("f02-frozen-evidence-v2");
        assertThatCode(() -> FrozenF02ResultMapper.requireValidProjection(f.context().step(), f.taskId(),
                List.of(check), result.findings(), result.evidence())).doesNotThrowAnyException();
    }

    @Test void rejectsMismatchedFrozenInputAndLateCompletedStepBeforeSourceProjection() {
        var sources = mock(FindingSourceProjectionService.class);
        var f = fixture(sources);
        var mapper = new FrozenF02ResultMapper(sources, new FrozenEvidenceGrounder(), Clock.fixed(NOW, ZoneOffset.UTC));
        for (var mutation : java.util.Map.<String, Object>of(
                "projectId", UUID.randomUUID(), "sourceCandidateId", UUID.randomUUID(), "inputSnapshotIdentity", "f".repeat(64),
                "requestedAt", NOW.minusSeconds(1), "ruleVersion", "f02-policy-v1:" + "e".repeat(64)).entrySet()) {
            var wrongInput = new FrozenReviewInput(copyRecord(f.run(), mutation.getKey(), mutation.getValue()),
                    f.input().frozenContract(), f.input().materials());
            assertThatThrownBy(() -> mapper.map(wrongInput, f.context(), f.taskId(), List.of(f.check())))
                    .as(mutation.getKey()).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        }
        var boundRun = copyRecord(f.run(), "sourceCandidateId", UUID.randomUUID());
        var boundContext = new ReviewExecutionContext(boundRun, f.context().step(), f.context().startedAttempt(),
                f.context().actor(), f.context().metadata(), List.of(), value -> { });
        var wrongConfirmation = new FrozenReviewInput(copyRecord(boundRun, "sourceConfirmationId", UUID.randomUUID()),
                f.input().frozenContract(), f.input().materials());
        assertThatThrownBy(() -> mapper.map(wrongConfirmation, boundContext, f.taskId(), List.of(f.check())))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        var completed = copyRecord(f.context().step(), "status", ContractReviewStepStatus.COMPLETED);
        var late = new ReviewExecutionContext(f.run(), completed, f.context().startedAttempt(),
                f.context().actor(), f.context().metadata(), List.of(), value -> { });
        assertThatThrownBy(() -> mapper.map(f.input(), late, f.taskId(), List.of(f.check())))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> T copyRecord(T source, String field, Object replacement) {
        try {
            var parts = source.getClass().getRecordComponents();
            Class<?>[] types = new Class<?>[parts.length];
            Object[] values = new Object[parts.length];
            for (int index = 0; index < parts.length; index++) {
                types[index] = parts[index].getType();
                values[index] = parts[index].getName().equals(field) ? replacement : parts[index].getAccessor().invoke(source);
            }
            return (T) source.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static ContractReviewRunMaterial durableMaterial(Fixture f, FrozenReviewMaterial m, DocumentRole role) {
        return new ContractReviewRunMaterial(m.runMaterialId(), f.run().runId(), m.ordinal(), f.run().projectId(),
                m.fileId(), m.fileSha256(), m.parseResultId(), m.parseVersion(), m.canonicalTextSha256(), role,
                m.roleDecisionId(), m.identityDecisionId(), m.sourceRoleDispositionId(), "actor", NOW);
    }

    @Test void mapsOneAuditCheckResultToOneStableFindingAndExactFrozenEvidence() {
        var projection = mock(FindingSourceProjectionService.class);
        var fixture = fixture(projection);
        when(projection.projectFrozen(fixture.input(), fixture.check())).thenReturn(
                FindingSourceProjection.available("CROSS_FILE", List.of(
                        new FindingSourceProjection.Source("CONTRACT", fixture.run().projectId(),
                                fixture.contractMaterial().fileId(), "contract.pdf", 1, 0,
                                "pdf/page:1", "合同金额 849000.00"),
                        new FindingSourceProjection.Source("AWARD_NOTICE", fixture.run().projectId(),
                                fixture.awardMaterial().fileId(), "award.pdf", 1, 0,
                                "pdf/page:1", "中标金额 876000.00"))));
        var mapper = new FrozenF02ResultMapper(projection, new FrozenEvidenceGrounder(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var first = mapper.map(fixture.input(), fixture.context(), fixture.taskId(), List.of(fixture.check()));
        var replay = mapper.map(fixture.input(), fixture.context(), fixture.taskId(), List.of(fixture.check()));

        assertThat(first.outputResourceType()).isEqualTo(FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE);
        assertThat(first.outputResourceId()).isEqualTo(fixture.taskId());
        assertThat(first.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.findingId()).isEqualTo(FrozenF02ResultMapper.findingId(fixture.check().id()));
            assertThat(finding.findingSchemaVersion()).isEqualTo(2);
            assertThat(finding.category()).isEqualTo(F02RuleCodes.AWARD_AMOUNT_MATCH);
            assertThat(finding.statusCode()).isEqualTo("FAIL");
            assertThat(finding.summary()).isEqualTo(fixture.check().detail());
        });
        assertThat(first.evidence()).hasSize(2)
                .allSatisfy(item -> {
                    assertThat(item.locatorAvailable()).isTrue();
                    assertThat(item.validationStatus()).isEqualTo("VALIDATED");
                    assertThat(item.pageNumber()).as("unverified source page must not be published").isNull();
                    assertThat(item.paragraphIndex()).as("unverified paragraph must not be published").isNull();
                    assertThat(item.parseResultId()).isIn(
                            fixture.contractMaterial().parseResultId(), fixture.awardMaterial().parseResultId());
                });
        assertThat(replay.findings()).extracting(value -> value.findingId())
                .containsExactlyElementsOf(first.findings().stream().map(value -> value.findingId()).toList());
        assertThat(replay.evidence()).extracting(value -> value.evidenceId())
                .containsExactlyElementsOf(first.evidence().stream().map(value -> value.evidenceId()).toList());
    }

    @Test void conclusiveCheckWithoutReliableSourceUsesExplicitV2CarrierWithoutFabricatingEvidence() {
        var projection = mock(FindingSourceProjectionService.class);
        var fixture = fixture(projection);
        when(projection.projectFrozen(fixture.input(), fixture.check()))
                .thenReturn(FindingSourceProjection.unavailable("SOURCE_NOT_FOUND"));
        var mapper = new FrozenF02ResultMapper(projection, new FrozenEvidenceGrounder(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = mapper.map(fixture.input(), fixture.context(), fixture.taskId(), List.of(fixture.check()));

        assertThat(result.outputResourceType()).isEqualTo(FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE);
        assertThat(result.findings()).singleElement().satisfies(value -> {
            assertThat(value.statusCode()).isEqualTo("FAIL");
            assertThat(value.attentionCode()).isEqualTo("SOURCE_UNAVAILABLE:SOURCE_NOT_FOUND");
            assertThat(value.findingSchemaVersion()).isEqualTo(FrozenF02ResultMapper.FINDING_SCHEMA_VERSION);
        });
        assertThat(result.evidence()).isEmpty();
    }

    @Test void notEvaluatedCheckMayHonestlyRecordUnavailableSourceWithoutFabricatingEvidence() {
        var projection = mock(FindingSourceProjectionService.class);
        var fixture = fixture(projection);
        var unavailable = new AuditCheckResult(fixture.check().id(), fixture.taskId(),
                F02RuleCodes.AWARD_AMOUNT_MATCH, null, CheckStatus.NOT_EVALUATED, RiskLevel.YELLOW,
                false, null, null, "Award amount is missing", 1, NOW);
        when(projection.projectFrozen(fixture.input(), unavailable))
                .thenReturn(FindingSourceProjection.unavailable("SOURCE_NOT_FOUND"));
        var mapper = new FrozenF02ResultMapper(projection, new FrozenEvidenceGrounder(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = mapper.map(fixture.input(), fixture.context(), fixture.taskId(), List.of(unavailable));

        assertThat(result.findings()).singleElement()
                .satisfies(value -> assertThat(value.attentionCode())
                        .isEqualTo("SOURCE_UNAVAILABLE:SOURCE_NOT_FOUND"));
        assertThat(result.evidence()).isEmpty();
    }

    @Test void exactFrozenLocatorSurvivesRepeatedExcerptWithoutFallingBackToFirstMatch() {
        var projection = mock(FindingSourceProjectionService.class);
        var fixture = fixture(projection);
        String repeatedText = "合同金额 849000.00；复核金额 849000.00";
        FrozenReviewMaterial original = fixture.contractMaterial();
        FrozenReviewMaterial repeated = new FrozenReviewMaterial(original.runMaterialId(), original.ordinal(),
                original.fileId(), original.fileSha256(), original.parseResultId(), original.parseVersion(),
                ContractSnapshotCanonicalizer.sha256(repeatedText), repeatedText, original.effectiveRole(),
                original.roleDecisionId(), original.identityDecisionId(), original.sourceRoleDispositionId(),
                original.originalFilename());
        FrozenReviewInput input = new FrozenReviewInput(fixture.run(), fixture.input().frozenContract(),
                List.of(repeated, fixture.awardMaterial()));
        int start = repeatedText.indexOf("849000.00");
        String exactLocator = "parse-result:%s:canonical-codepoint:%d-%d".formatted(
                repeated.parseResultId(), start, start + "849000.00".length());
        when(projection.projectFrozen(input, fixture.check())).thenReturn(
                FindingSourceProjection.available("CROSS_FILE", List.of(
                        new FindingSourceProjection.Source("CONTRACT", fixture.run().projectId(),
                                repeated.fileId(), "contract.pdf", null, null,
                                exactLocator, "849000.00"),
                        new FindingSourceProjection.Source("AWARD_NOTICE", fixture.run().projectId(),
                                fixture.awardMaterial().fileId(), "award.pdf", null, null,
                                "legacy", "876000.00"))));

        var result = new FrozenF02ResultMapper(projection, new FrozenEvidenceGrounder(),
                Clock.fixed(NOW, ZoneOffset.UTC))
                .map(input, fixture.context(), fixture.taskId(), List.of(fixture.check()));

        assertThat(result.evidence()).hasSize(2);
        assertThat(result.evidence().get(0).locatorAvailable()).isTrue();
        assertThat(result.evidence().get(0).startOffset()).isEqualTo(start);
        assertThat(result.evidence().get(0).endOffset()).isEqualTo(start + "849000.00".length());
    }

    @Test void unavailableComparisonSideDoesNotEraseTheOtherReliableFrozenSource() {
        var projection = mock(FindingSourceProjectionService.class);
        var fixture = fixture(projection);
        int start = fixture.contractMaterial().canonicalText().indexOf("849000.00");
        String exactLocator = "parse-result:%s:canonical-codepoint:%d-%d".formatted(
                fixture.contractMaterial().parseResultId(), start, start + "849000.00".length());
        when(projection.projectFrozen(fixture.input(), fixture.check())).thenReturn(
                FindingSourceProjection.available("CROSS_FILE", List.of(
                        new FindingSourceProjection.Source("CONTRACT", fixture.run().projectId(),
                                fixture.contractMaterial().fileId(), "contract.pdf", null, null,
                                exactLocator, "849000.00"),
                        new FindingSourceProjection.Source("AWARD_NOTICE", fixture.run().projectId(),
                                fixture.awardMaterial().fileId(), "award.pdf", null, null,
                                "legacy", "missing award excerpt"))));

        var result = new FrozenF02ResultMapper(projection, new FrozenEvidenceGrounder(),
                Clock.fixed(NOW, ZoneOffset.UTC))
                .map(fixture.input(), fixture.context(), fixture.taskId(), List.of(fixture.check()));

        assertThat(result.evidence()).singleElement()
                .satisfies(value -> assertThat(value.fileId()).isEqualTo(fixture.contractMaterial().fileId()));
        assertThat(result.findings()).singleElement()
                .satisfies(value -> assertThat(value.attentionCode())
                        .isEqualTo("SOURCE_UNAVAILABLE:PARTIAL_LOCATOR_UNAVAILABLE"));
    }

    @Test void amountWordsProjectionAcceptsBothNumericAndUppercaseFrozenSources() {
        var projection = mock(FindingSourceProjectionService.class);
        var fixture = fixture(projection);
        String text = "合同金额 849000.00，大写人民币捌拾肆万玖仟元整";
        FrozenReviewMaterial material = material(fixture.run(), 1, DocumentRole.CONTRACT_BODY, text);
        FrozenReviewInput input = new FrozenReviewInput(fixture.run(), fixture.input().frozenContract(),
                List.of(material));
        var check = new AuditCheckResult(UUID.randomUUID(), fixture.taskId(),
                F02RuleCodes.AMOUNT_WORDS_MATCH, null, CheckStatus.PASS, RiskLevel.GREEN,
                false, "849000.00", "捌拾肆万玖仟元整", "合同金额大小写一致", 1, NOW);
        when(projection.projectFrozen(input, check)).thenReturn(
                FindingSourceProjection.available("INTERNAL", List.of(
                        new FindingSourceProjection.Source("CONTRACT", fixture.run().projectId(),
                                material.fileId(), "contract.pdf", null, null, "legacy", "849000.00"),
                        new FindingSourceProjection.Source("CONTRACT", fixture.run().projectId(),
                                material.fileId(), "contract.pdf", null, null, "legacy", "捌拾肆万玖仟元整"))));
        var mapper = new FrozenF02ResultMapper(projection, new FrozenEvidenceGrounder(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = mapper.map(input, fixture.context(), fixture.taskId(), List.of(check));

        assertThat(result.evidence()).hasSize(2);
        assertThatCode(() -> FrozenF02ResultMapper.requireValidProjection(
                fixture.context().step(), fixture.taskId(), List.of(check),
                result.findings(), result.evidence())).doesNotThrowAnyException();
    }

    @Test void manualValueDifferenceRetainsOriginalSourcesWithoutChangingArithmeticTruth() {
        var projection = mock(FindingSourceProjectionService.class);
        var fixture = fixture(projection);
        when(projection.projectFrozen(fixture.input(), fixture.check())).thenReturn(
                new FindingSourceProjection(false, "CONFIRMED_VALUE_DIFFERS_FROM_SOURCE", "CROSS_FILE", List.of(
                        new FindingSourceProjection.Source("CONTRACT", fixture.run().projectId(),
                                fixture.contractMaterial().fileId(), "contract.pdf", null, null, "legacy", "849000.00"),
                        new FindingSourceProjection.Source("AWARD_NOTICE", fixture.run().projectId(),
                                fixture.awardMaterial().fileId(), "award.pdf", null, null, "legacy", "876000.00"))));
        var result = new FrozenF02ResultMapper(projection, new FrozenEvidenceGrounder(), Clock.fixed(NOW, ZoneOffset.UTC))
                .map(fixture.input(), fixture.context(), fixture.taskId(), List.of(fixture.check()));
        assertThat(result.evidence()).hasSize(2);
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.statusCode()).isEqualTo("FAIL");
            assertThat(finding.summary()).isEqualTo(fixture.check().detail());
            assertThat(finding.attentionCode()).isEqualTo("SOURCE_UNAVAILABLE:CONFIRMED_VALUE_DIFFERS_FROM_SOURCE");
            assertThat(finding.recommendation()).contains("冻结合同", "849000.00", "876000.00", "原文值");
        });
        assertThatCode(() -> FrozenF02ResultMapper.requireValidProjection(fixture.context().step(), fixture.taskId(),
                List.of(fixture.check()), result.findings(), result.evidence())).doesNotThrowAnyException();
    }

    @Test void reopensHistoricalV1EvidenceWithoutRecomputingOrReplacingIt() {
        var projection = mock(FindingSourceProjectionService.class);
        var fixture = fixture(projection);
        when(projection.projectFrozen(fixture.input(), fixture.check())).thenReturn(
                FindingSourceProjection.available("CROSS_FILE", List.of(
                        new FindingSourceProjection.Source("CONTRACT", fixture.run().projectId(),
                                fixture.contractMaterial().fileId(), "contract.pdf", null, null, "legacy", "849000.00"),
                        new FindingSourceProjection.Source("AWARD_NOTICE", fixture.run().projectId(),
                                fixture.awardMaterial().fileId(), "award.pdf", null, null, "legacy", "876000.00"))));
        var result = new FrozenF02ResultMapper(projection, new FrozenEvidenceGrounder(), Clock.fixed(NOW, ZoneOffset.UTC))
                .map(fixture.input(), fixture.context(), fixture.taskId(), List.of(fixture.check()));
        var historical = result.evidence().stream().map(value -> new ContractReviewEvidence(value.evidenceId(),
                value.findingId(), value.runId(), value.stepId(), value.attemptId(), value.ordinal(), value.fileId(),
                value.fileSha256(), value.parseResultId(), value.parseVersion(), value.canonicalTextSha256(),
                value.evidenceRole(), value.validationStatus(), "f02-frozen-evidence-v1", value.pageNumber(),
                value.paragraphIndex(), value.tableIndex(), value.rowIndex(), value.cellIndex(), value.segmentIndex(),
                value.locatorAvailable(), value.startOffset(), value.endOffset(), value.excerptSha256(),
                value.boundedExcerpt(), value.locatorSchemaVersion(), value.createdAt(), value.versionNo())).toList();
        assertThatCode(() -> FrozenF02ResultMapper.requireValidProjection(fixture.context().step(), fixture.taskId(),
                List.of(fixture.check()), result.findings(), historical)).doesNotThrowAnyException();
        assertThat(historical).extracting(ContractReviewEvidence::validationPolicyVersion)
                .containsOnly("f02-frozen-evidence-v1");
    }

    private static Fixture fixture(FindingSourceProjectionService ignored) {
        return fixture(ignored, false);
    }

    private static Fixture fixture(FindingSourceProjectionService ignored, boolean legacy) {
        return fixture(ignored, legacy, false);
    }

    private static Fixture fixture(FindingSourceProjectionService ignored, boolean legacy, boolean supplierNames) {
        var contract = LocalContractFixtures.contracts().stream()
                .filter(value -> value.id().equals(LocalContractFixtures.FAIL_CONTRACT_ID)).findFirst().orElseThrow();
        if (supplierNames) {
            var award = copyRecord(contract.awardNotice(), "winningBidderCreditCode", null);
            contract = copyRecord(copyRecord(contract, "partyBCreditCode", null), "awardNotice", award);
        }
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        var run = new ContractReviewRun(runId, UUID.randomUUID(), contract.id(), contract.versionNo(),
                null, null, 1, "{}", ContractSnapshotCanonicalizer.sha256("{}"), "profile-v1",
                legacy ? "rule-v1" : "f02-policy-v2:" + "e".repeat(64), "model-v1", "a".repeat(64), "actor", ContractReviewRunStatus.RUNNING,
                NOW, NOW, null, null, null, null, 1);
        var step = new ContractReviewStep(UUID.randomUUID(), runId, ContractReviewStepType.F02_DETERMINISTIC,
                true, ContractReviewStepStatus.RUNNING, run.ruleVersion(), 1, null, null, null,
                NOW, null, 1);
        var attempt = new ContractReviewStepAttempt(UUID.randomUUID(), runId, step.stepId(), 1,
                "b".repeat(64), ContractReviewStepStatus.RUNNING, "worker", NOW.plusSeconds(30), NOW,
                ReviewStepExternalCallState.NOT_STARTED, null, null, null, null, NOW, null,
                null, null, null, 1);
        var contractMaterial = material(run, 1, DocumentRole.CONTRACT_BODY, "合同金额 849000.00");
        var awardMaterial = material(run, 2, DocumentRole.AWARD_NOTICE, "中标金额 876000.00");
        var input = new FrozenReviewInput(run, contract, List.of(contractMaterial, awardMaterial));
        var context = new ReviewExecutionContext(run, step, attempt,
                new CurrentUser("actor", "auditor", "Auditor", Set.of()),
                new ProjectRequestMetadata("request", "trace", null), List.of(), value -> { });
        var check = new AuditCheckResult(UUID.randomUUID(), taskId, F02RuleCodes.AWARD_AMOUNT_MATCH,
                null, CheckStatus.FAIL, RiskLevel.YELLOW, false, "849000.00", "876000.00",
                "合同金额与中标金额不一致", 1, NOW);
        return new Fixture(taskId, run, input, context, check, contractMaterial, awardMaterial);
    }

    private static FrozenReviewMaterial material(ContractReviewRun run, int ordinal,
            DocumentRole role, String text) {
        return new FrozenReviewMaterial(UUID.randomUUID(), ordinal, UUID.randomUUID(), "c".repeat(64),
                UUID.randomUUID(), 1, ContractSnapshotCanonicalizer.sha256(text), text, role,
                UUID.randomUUID(), UUID.randomUUID(), null);
    }

    private record Fixture(UUID taskId, ContractReviewRun run, FrozenReviewInput input,
            ReviewExecutionContext context, AuditCheckResult check,
            FrozenReviewMaterial contractMaterial, FrozenReviewMaterial awardMaterial) { }
}
