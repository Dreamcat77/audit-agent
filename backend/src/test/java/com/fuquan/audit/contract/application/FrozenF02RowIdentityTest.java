package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.application.reviewrun.FrozenEvidenceGrounder;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Catches itemKey/groupKey conflation and proof borrowing across rows or extraction cohorts. */
class FrozenF02RowIdentityTest {
    private static final Instant NOW = Instant.parse("2026-10-16T02:00:00Z");

    @Test void resolvesTwelveExplicitKeysWithDifferentOpaqueGroupsAndSameNames() {
        Fixture f = new Fixture(12);
        for (int row = 1; row <= 12; row++) {
            var projection = f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, key(row)));
            assertThat(projection.available()).as("row %s reason %s", row, projection.reason()).isTrue();
            assertThat(projection.sources()).extracting(FindingSourceProjection.Source::excerpt)
                    .containsExactly(key(row) + " quantity 2", key(row) + " unit price 7", key(row) + " amount 14");
        }
        var sum = f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_SUM_MATCH, null));
        assertThat(sum.available()).isTrue();
        assertThat(sum.sources()).hasSize(13);
    }

    @Test void preservesAllFortyEightRowsInSumRatherThanUsingOnlyTheDeclaredTotal() {
        Fixture f = new Fixture(48);
        var sum = f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_SUM_MATCH, null));
        assertThat(sum.available()).isTrue();
        assertThat(sum.sources()).hasSize(49);
        assertThat(sum.sources()).extracting(FindingSourceProjection.Source::excerpt)
                .contains("MX-001 amount 14", "MX-048 amount 14");
    }

    @Test void mapsEveryOperandAndAllFortyEightSumRowsToFrozenCanonicalOffsets() {
        Fixture f = new Fixture(48);
        f.confirmed = true;
        var source = f.materials.get(0);
        String canonical = String.join("\n", f.facts.stream().map(ExtractionResult::sourceText).toList());
        f.materials.set(0, new FrozenReviewMaterial(source.runMaterialId(), 1, source.fileId(), source.fileSha256(),
                source.parseResultId(), 1, ContractSnapshotCanonicalizer.sha256(canonical), canonical,
                source.effectiveRole(), source.roleDecisionId(), source.identityDecisionId(), null));
        var input = f.input();
        var step = new ContractReviewStep(UUID.randomUUID(), f.run.runId(), ContractReviewStepType.F02_DETERMINISTIC,
                true, ContractReviewStepStatus.RUNNING, f.run.ruleVersion(), 1, null, null, null, NOW, null, 1);
        var attempt = new ContractReviewStepAttempt(UUID.randomUUID(), f.run.runId(), step.stepId(), 1,
                "e".repeat(64), ContractReviewStepStatus.RUNNING, "worker", NOW.plusSeconds(60), NOW,
                ReviewStepExternalCallState.NOT_STARTED, null, null, null, null, NOW, null, null, null, null, 1);
        var context = new ReviewExecutionContext(input.run(), step, attempt,
                new CurrentUser("actor", "actor", "Actor", Set.of()), new ProjectRequestMetadata("r", "t", null),
                List.of(), value -> { });
        UUID taskId = UUID.randomUUID();
        List<AuditCheckResult> checks = new ArrayList<>();
        for (int row = 1; row <= 48; row++) checks.add(f.check(taskId, F02RuleCodes.LINE_ITEM_ARITHMETIC, key(row)));
        checks.add(f.check(taskId, F02RuleCodes.LINE_SUM_MATCH, null));

        var result = new FrozenF02ResultMapper(f.service(), new FrozenEvidenceGrounder(), Clock.fixed(NOW, ZoneOffset.UTC))
                .map(input, context, taskId, checks);

        assertThat(result.findings()).hasSize(49).allSatisfy(value -> assertThat(value.attentionCode()).isNull());
        assertThat(result.evidence()).hasSize(193).allSatisfy(value -> {
            assertThat(value.locatorAvailable()).isTrue();
            assertThat(value.parseResultId()).isEqualTo(source.parseResultId());
            assertThat(canonical.substring(value.startOffset(), value.endOffset())).isEqualTo(value.boundedExcerpt());
        });
        FrozenF02ResultMapper.requireValidProjection(step, taskId, checks, result.findings(), result.evidence());
    }

    @Test void refusesDuplicateExplicitKeysAcrossFilesBeforeMatchingAmounts() {
        Fixture f = new Fixture(1);
        FrozenReviewMaterial other = material(2, DocumentRole.CONTRACT_BODY);
        f.materials.add(other);
        f.addRow(other, "other-group", "MX-001", "3", "8", "24", NOW.minusSeconds(1), null, "v1");
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).reason())
                .isEqualTo("SOURCE_AMBIGUOUS");
    }

    @Test void refusesDuplicateExplicitKeysInDifferentGroupsOfTheSameFile() {
        Fixture f = new Fixture(1);
        f.addRow(f.materials.get(0), "other-group", "MX-001", "2", "7", "14", NOW.minusSeconds(1), null, "v1");
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).reason())
                .isEqualTo("SOURCE_AMBIGUOUS");
    }

    @Test void doesNotFallBackToMatchingGroupNameWhenExplicitKeyIsAbsent() {
        Fixture f = new Fixture(1);
        f.facts.removeIf(value -> value.factType().equals("LINE_ITEM_KEY"));
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).reason())
                .isEqualTo("SOURCE_ROW_KEY_NOT_FOUND");
    }

    @Test void doesNotBorrowMissingOperandFromAnotherCandidateExtraction() {
        Fixture f = new Fixture(1);
        f.facts.removeIf(value -> value.factType().equals("LINE_ITEM_UNIT_PRICE"));
        f.facts.add(f.fact(f.materials.get(0), "opaque-1", "LINE_ITEM_UNIT_PRICE", "7", new BigDecimal("7"),
                "MX-001 unit price 7", NOW.minusSeconds(1), UUID.randomUUID(), "v1"));
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).reason())
                .isEqualTo("SOURCE_NOT_FOUND");
    }

    @Test void doesNotBorrowMissingOperandFromAnotherExtractionVersion() {
        Fixture f = new Fixture(1);
        f.facts.removeIf(value -> value.factType().equals("LINE_ITEM_UNIT_PRICE"));
        f.facts.add(f.fact(f.materials.get(0), "opaque-1", "LINE_ITEM_UNIT_PRICE", "7", new BigDecimal("7"),
                "MX-001 unit price 7", NOW.minusSeconds(1), null, "different-extraction-v2"));
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).reason())
                .isEqualTo("SOURCE_NOT_FOUND");
    }

    @Test void refusesConflictingTypedValuesEvenWhenOneConvenientlyMatchesTheConfirmedValue() {
        Fixture f = new Fixture(1);
        f.facts.add(f.fact(f.materials.get(0), "opaque-1", "LINE_ITEM_UNIT_PRICE", "8", new BigDecimal("8"),
                "MX-001 unit price 7", NOW.minusSeconds(1), null, "v1"));
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).reason())
                .isEqualTo("SOURCE_AMBIGUOUS");
    }

    @Test void collapsesEquivalentDecimalScalesWithoutInventingAnAmbiguousOperand() {
        Fixture f = new Fixture(1);
        f.facts.add(f.fact(f.materials.get(0), "opaque-1", "LINE_ITEM_UNIT_PRICE", "7.00", new BigDecimal("7.00"),
                "MX-001 unit price 7", NOW.minusSeconds(1), null, "v1"));
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).available()).isTrue();
    }

    @Test void cannotChooseAnEarlierExtractionCohortWhenItsIdenticalKeyAnchorHasConflictingOperands() {
        Fixture f = new Fixture(1);
        f.addRow(f.materials.get(0), "opaque-1", "MX-001", "3", "8", "24", NOW.minusMillis(500), null, "v2");
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).reason())
                .isEqualTo("SOURCE_AMBIGUOUS");
    }

    @Test void anExplicitFrozenConfirmationPreventsBorrowingADuplicateKeyFromAnotherSelectedFile() {
        Fixture f = new Fixture(1);
        f.confirmed = true;
        var other = material(2, DocumentRole.CONTRACT_BODY);
        f.materials.add(other);
        f.addRow(other, "opaque-other", "MX-001", "2", "7", "14", NOW.minusSeconds(2), null, "v1");
        var result = f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001"));
        assertThat(result.available()).isTrue();
        assertThat(result.sources()).extracting(FindingSourceProjection.Source::fileId).containsOnly(f.materials.get(0).fileId());
    }

    @Test void keepsManualCorrectionDistinctFromOriginalTypedValuesEvenIfTheProductMatches() {
        Fixture f = new Fixture(1);
        f.facts.removeIf(value -> value.factType().equals("LINE_ITEM_QUANTITY") || value.factType().equals("LINE_ITEM_UNIT_PRICE"));
        f.facts.add(f.fact(f.materials.get(0), "opaque-1", "LINE_ITEM_QUANTITY", "7", new BigDecimal("7"),
                "MX-001 quantity 7", NOW.minusSeconds(1), null, "v1"));
        f.facts.add(f.fact(f.materials.get(0), "opaque-1", "LINE_ITEM_UNIT_PRICE", "2", new BigDecimal("2"),
                "MX-001 unit price 2", NOW.minusSeconds(1), null, "v1"));
        var result = f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001"));
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo("CONFIRMED_VALUE_DIFFERS_FROM_SOURCE");
        assertThat(result.sources()).extracting(FindingSourceProjection.Source::excerpt)
                .containsExactly("MX-001 quantity 7", "MX-001 unit price 2", "MX-001 amount 14");
    }

    @Test void ignoresNewRowsPublishedAfterFreezeEvenWhenTheirExplicitKeysAreIdentical() {
        Fixture f = new Fixture(1);
        f.addRow(f.materials.get(0), "late-group", "MX-001", "2", "7", "14", NOW.plusSeconds(1), null, "v1");
        var result = f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001"));
        assertThat(result.available()).isTrue();
        assertThat(result.sources()).hasSize(3);
    }

    @Test void doesNotResolveFromAwardRoleOrFromAnotherParseOrProject() {
        Fixture f = new Fixture(1);
        f.materials.set(0, new FrozenReviewMaterial(UUID.randomUUID(), 1, f.materials.get(0).fileId(), "b".repeat(64),
                f.materials.get(0).parseResultId(), 1, "c".repeat(64), "text", DocumentRole.AWARD_NOTICE,
                UUID.randomUUID(), UUID.randomUUID(), null));
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).reason())
                .isEqualTo("SOURCE_ROW_KEY_NOT_FOUND");
    }

    @Test void ignoresFactsPublishedAfterHumanConfirmationButBeforeRunFreeze() {
        Fixture f = new Fixture(1);
        f.confirmed = true;
        f.addRow(f.materials.get(0), "late-group", "MX-001", "2", "7", "14", NOW.minusMillis(500), null, "v1");
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).available()).isTrue();
    }

    @Test void rejectsFrozenConfirmationWhoseParseOrDecisionDoesNotMatchFrozenMaterial() {
        Fixture f = new Fixture(1);
        f.confirmed = true;
        f.wrongConfirmationParse = true;
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).reason())
                .isEqualTo("FROZEN_CONFIRMATION_UNVERIFIED");
    }

    @Test void refusesKeyOrTypedFactsFromAnotherCandidateEvenIfGroupAndValuesMatch() {
        Fixture f = new Fixture(1);
        f.confirmed = true;
        f.foreignCandidate = true;
        assertThat(f.service().projectFrozen(f.input(), f.check(F02RuleCodes.LINE_ITEM_ARITHMETIC, "MX-001")).reason())
                .isEqualTo("SOURCE_ROW_KEY_NOT_FOUND");
    }

    private static String key(int row) { return "MX-%03d".formatted(row); }
    private static FrozenReviewMaterial material(int ordinal, DocumentRole role) {
        return new FrozenReviewMaterial(UUID.randomUUID(), ordinal, UUID.randomUUID(), "b".repeat(64),
                UUID.randomUUID(), 1, "c".repeat(64), "text", role, UUID.randomUUID(), UUID.randomUUID(), null);
    }

    private static final class Fixture {
        final UUID projectId = UUID.randomUUID(), contractId = UUID.randomUUID();
        final UUID candidateId = UUID.randomUUID(), confirmationId = UUID.randomUUID();
        boolean confirmed, wrongConfirmationParse, foreignCandidate;
        final List<FrozenReviewMaterial> materials = new ArrayList<>();
        final List<ExtractionResult> facts = new ArrayList<>();
        final Contract contract;
        final ContractReviewRun run;
        Fixture(int rows) {
            var primary = material(1, DocumentRole.CONTRACT_BODY);
            materials.add(primary);
            List<ContractLineItem> lines = new ArrayList<>();
            for (int row = 1; row <= rows; row++) {
                lines.add(new ContractLineItem(UUID.randomUUID(), contractId, key(row), "same name", new BigDecimal("2"),
                        new BigDecimal("7"), Money.of(new BigDecimal("14")), row));
                addRow(primary, "opaque-" + row, key(row), "2", "7", "14", NOW.minusSeconds(1), null, "v1");
            }
            contract = new Contract(contractId, "C-1", "Contract", "SERVICE", "A", "B", null,
                    Money.of(new BigDecimal(rows * 14)), "words", "DRAFT", 0, NOW, NOW, lines, null);
            facts.add(fact(primary, null, "AMOUNT_NUMERIC", Integer.toString(rows * 14), new BigDecimal(rows * 14),
                    "declared total " + rows * 14, NOW.minusSeconds(1), null, "v1"));
            run = new ContractReviewRun(UUID.randomUUID(), projectId, contractId, 0, null, null, 2,
                    "{}", ContractSnapshotCanonicalizer.sha256("{}"), "profile", "f02-policy-v2:" + "e".repeat(64), "models", "a".repeat(64),
                    "actor", ContractReviewRunStatus.RUNNING, NOW, NOW, null, null, null, null, 1);
        }
        void addRow(FrozenReviewMaterial material, String group, String key, String quantity, String unit, String amount,
                Instant at, UUID candidateId, String version) {
            facts.add(fact(material, group, "LINE_ITEM_KEY", key, null, key, at, candidateId, version));
            facts.add(fact(material, group, "LINE_ITEM_NAME", "same name", null, "same name", at, candidateId, version));
            facts.add(fact(material, group, "LINE_ITEM_QUANTITY", quantity, new BigDecimal(quantity), key + " quantity " + quantity, at, candidateId, version));
            facts.add(fact(material, group, "LINE_ITEM_UNIT_PRICE", unit, new BigDecimal(unit), key + " unit price " + unit, at, candidateId, version));
            facts.add(fact(material, group, "LINE_ITEM_DECLARED_AMOUNT", amount, new BigDecimal(amount), key + " amount " + amount, at, candidateId, version));
        }
        ExtractionResult fact(FrozenReviewMaterial material, String group, String type, String text, BigDecimal amount,
                String excerpt, Instant at, UUID candidateId, String version) {
            return new ExtractionResult(UUID.randomUUID(), projectId, material.fileId(), material.parseResultId(), candidateId,
                    "d".repeat(64), version, type, group, text, text, amount, excerpt, 1, 1,
                    "row:" + group + "/fact:" + type, at);
        }
        FrozenReviewInput input() {
            var value = confirmed ? new ContractReviewRun(run.runId(), projectId, contractId, 0, candidateId, confirmationId,
                    2, "{}", run.contractSnapshotSha256(), "profile", run.ruleVersion(), "models", run.inputSnapshotIdentity(),
                    "actor", run.status(), NOW, NOW, null, null, null, null, 1) : run;
            return new FrozenReviewInput(value, contract, materials);
        }
        AuditCheckResult check(String rule, String key) {
            return check(UUID.randomUUID(), rule, key);
        }
        AuditCheckResult check(UUID taskId, String rule, String key) {
            return new AuditCheckResult(UUID.randomUUID(), taskId, rule, key, CheckStatus.PASS,
                    RiskLevel.GREEN, false, "14", "14", "confirmed arithmetic passed", 1, NOW);
        }
        FindingSourceProjectionService service() {
            var repository = mock(ExtractionResultRepository.class);
            var confirmations = mock(ContractCandidateConfirmationRepository.class);
            if (confirmed) {
                var source = materials.get(0);
                when(confirmations.findByProjectAndCandidateAndId(projectId, candidateId, confirmationId))
                        .thenReturn(Optional.of(new ContractCandidateConfirmation(confirmationId, projectId, candidateId,
                                0, 1, source.fileId(), wrongConfirmationParse ? UUID.randomUUID() : source.parseResultId(),
                                source.roleDecisionId(), source.identityDecisionId(), 2, "a".repeat(64), "{}", "actor",
                                NOW.minusSeconds(1), null, NOW.minusSeconds(1))));
            }
            for (var material : materials) {
                when(repository.findBySourceFileIdAndParseResultId(projectId, material.fileId(), material.parseResultId()))
                        .thenReturn(facts.stream().filter(value -> value.sourceFileId().equals(material.fileId())
                                && value.parseResultId().equals(material.parseResultId()))
                                .map(value -> confirmed && value.candidateId() == null ? value.bindCandidate(value.parseResultId(),
                                        foreignCandidate ? UUID.randomUUID() : candidateId) : value).toList());
            }
            return new FindingSourceProjectionService(mock(ProjectContractQueryPort.class), mock(ContractCandidateRepository.class),
                    mock(ProjectFileRepository.class), repository, null, null, confirmations);
        }
    }
}
