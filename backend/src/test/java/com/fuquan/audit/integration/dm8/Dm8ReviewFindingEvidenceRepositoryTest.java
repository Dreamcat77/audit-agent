package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison;
import com.fuquan.audit.contract.domain.reviewrun.ReviewFindingSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class Dm8ReviewFindingEvidenceRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void mapsTypedFindingAndExactParseEvidenceIntoAppendOnlyRowsAndReopensThem() {
        var mapper = mock(ContractReviewResultsMapper.class);
        var adapter = new Dm8ContractReviewResultsRepositories(mapper);
        var finding = finding();
        var evidence = evidence(finding);
        when(mapper.insertFinding(any())).thenReturn(1);
        when(mapper.insertEvidence(any())).thenReturn(1);
        adapter.insert(finding);
        adapter.insert(evidence);
        var findingRow = ArgumentCaptor.forClass(ContractReviewFindingPersistenceRow.class);
        var evidenceRow = ArgumentCaptor.forClass(ContractReviewEvidencePersistenceRow.class);
        verify(mapper).insertFinding(findingRow.capture());
        verify(mapper).insertEvidence(evidenceRow.capture());
        assertThat(findingRow.getValue().statusCode()).isNull();
        assertThat(findingRow.getValue().attentionCode()).isEqualTo("REVIEW");
        assertThat(findingRow.getValue().riskLevel()).isNull();
        assertThat(findingRow.getValue().modelProvider()).isNull();
        assertThat(evidenceRow.getValue().parseResultId()).isEqualTo(evidence.parseResultId().toString());
        assertThat(evidenceRow.getValue().canonicalTextSha256()).isEqualTo(evidence.canonicalTextSha256());
        assertThat(evidenceRow.getValue().locatorAvailable()).isEqualTo(0);
        assertThat(evidenceRow.getValue().startOffset()).isNull();
        assertThat(evidenceRow.getValue().excerptSha256()).isEqualTo(evidence.excerptSha256());
        when(mapper.findFindingsByAttemptId(eq(finding.attemptId().toString())))
                .thenReturn(java.util.List.of(findingRow.getValue()));
        when(mapper.findEvidenceByAttemptId(eq(finding.attemptId().toString())))
                .thenReturn(java.util.List.of(evidenceRow.getValue()));
        when(mapper.findFindingsByRunId(eq(finding.runId().toString())))
                .thenReturn(java.util.List.of(findingRow.getValue()));
        when(mapper.findEvidenceByRunId(eq(finding.runId().toString())))
                .thenReturn(java.util.List.of(evidenceRow.getValue()));
        assertThat(adapter.findFindingsByAttemptId(finding.attemptId())).containsExactly(finding);
        assertThat(adapter.findEvidenceByAttemptId(finding.attemptId())).containsExactly(evidence);
        assertThat(adapter.findFindingsByRunId(finding.runId())).containsExactly(finding);
        assertThat(adapter.findEvidenceByRunId(finding.runId())).containsExactly(evidence);
    }

    @Test
    void insertCannotPretendDurabilityWhenMapperChangedZeroRows() {
        var adapter = new Dm8ContractReviewResultsRepositories(mock(ContractReviewResultsMapper.class));
        assertThatThrownBy(() -> adapter.insert(finding())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void persistsAndReopensTypedBilateralValuesWithExactEvidenceIdentity() {
        var mapper = mock(ContractReviewResultsMapper.class);
        var adapter = new Dm8ContractReviewResultsRepositories(mapper);
        UUID comparisonId = UUID.randomUUID();
        UUID findingId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID contractFileId = UUID.randomUUID();
        UUID tenderFileId = UUID.randomUUID();
        UUID contractEvidenceId = UUID.randomUUID();
        UUID secondContractEvidenceId = UUID.randomUUID();
        var comparison = new ContractReviewClauseComparison(comparisonId, findingId, runId,
                stepId, attemptId, "BREACH:SUPPLIER_DELAY", "BREACH", "CONFLICT", 2,
                java.util.List.of(
                        new ContractReviewClauseComparison.Value(UUID.randomUUID(), comparisonId, runId,
                                "CONTRACT_BODY:" + contractFileId, contractFileId, "CONFLICT",
                                "SUPPLIER_DELAY", "PARTY_B", "CONTRACT_AMOUNT",
                                new BigDecimal("0.0010"), "PERCENT", "PER_DAY",
                                null, new BigDecimal("0.1000"), new BigDecimal("1000.00"),
                                "10 days", null, null, null, null, null, null,
                                java.util.List.of(contractEvidenceId, secondContractEvidenceId)),
                        new ContractReviewClauseComparison.Value(UUID.randomUUID(), comparisonId, runId,
                                "TENDER_DOCUMENT:" + tenderFileId, tenderFileId, "NOT_EXTRACTED",
                                "SUPPLIER_DELAY", null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null,
                                java.util.List.of())));
        when(mapper.insertClauseComparison(any())).thenReturn(1);
        when(mapper.insertClauseValue(any())).thenReturn(1);
        when(mapper.insertClauseValueEvidence(any(), any())).thenReturn(1);

        adapter.insert(comparison);

        var comparisonRow = ArgumentCaptor.forClass(ContractReviewClauseComparisonPersistenceRow.class);
        var valueRow = ArgumentCaptor.forClass(ContractReviewClauseValuePersistenceRow.class);
        verify(mapper).insertClauseComparison(comparisonRow.capture());
        verify(mapper, org.mockito.Mockito.times(2)).insertClauseValue(valueRow.capture());
        verify(mapper).insertClauseValueEvidence(valueRow.getAllValues().get(0).valueId(),
                contractEvidenceId.toString());
        verify(mapper).insertClauseValueEvidence(valueRow.getAllValues().get(0).valueId(),
                secondContractEvidenceId.toString());
        assertThat(valueRow.getAllValues()).extracting(ContractReviewClauseValuePersistenceRow::state)
                .containsExactly("CONFLICT", "NOT_EXTRACTED");

        var rows = valueRow.getAllValues();
        var persistedKnown = rows.get(0);
        var reopenedKnown = new ContractReviewClauseValuePersistenceRow(persistedKnown.valueId(),
                persistedKnown.comparisonId(), persistedKnown.runId(), persistedKnown.sideKey(), persistedKnown.sourceFileId(),
                persistedKnown.state(), persistedKnown.scenarioKey(), persistedKnown.responsibleParty(),
                persistedKnown.calculationBasis(), persistedKnown.rateValue(), persistedKnown.rateUnit(),
                persistedKnown.frequency(), persistedKnown.periodValue(), persistedKnown.capRate(),
                persistedKnown.capAmount(), persistedKnown.terminationThreshold(), persistedKnown.dateType(),
                persistedKnown.normalizedDate(), persistedKnown.termStart(), persistedKnown.durationValue(),
                persistedKnown.effectiveCondition(), persistedKnown.terminationCondition(),
                contractEvidenceId.toString());
        var reopenedSecondEvidence = new ContractReviewClauseValuePersistenceRow(
                persistedKnown.valueId(), persistedKnown.comparisonId(), persistedKnown.runId(),
                persistedKnown.sideKey(), persistedKnown.sourceFileId(), persistedKnown.state(),
                persistedKnown.scenarioKey(), persistedKnown.responsibleParty(),
                persistedKnown.calculationBasis(), persistedKnown.rateValue(), persistedKnown.rateUnit(),
                persistedKnown.frequency(), persistedKnown.periodValue(), persistedKnown.capRate(),
                persistedKnown.capAmount(), persistedKnown.terminationThreshold(), persistedKnown.dateType(),
                persistedKnown.normalizedDate(), persistedKnown.termStart(), persistedKnown.durationValue(),
                persistedKnown.effectiveCondition(), persistedKnown.terminationCondition(),
                secondContractEvidenceId.toString());
        when(mapper.findClauseComparisonsByAttemptId(attemptId.toString()))
                .thenReturn(java.util.List.of(comparisonRow.getValue()));
        when(mapper.findClauseValues(comparisonId.toString()))
                .thenReturn(java.util.List.of(reopenedKnown, reopenedSecondEvidence, rows.get(1)));

        assertThat(adapter.findByAttemptId(attemptId)).containsExactly(comparison);
    }

    @Test
    void mapperUsesOnlyTypedInsertAndDurableReadQueries() throws Exception {
        String xml = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ContractReviewResultsMapper.xml")).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        assertThat(xml).contains("INSERT INTO CONTRACT_REVIEW_FINDING",
                "INSERT INTO CONTRACT_REVIEW_EVIDENCE", "SELECT", "WHERE ATTEMPT_ID=#{ATTEMPTID}",
                "WHERE RUN_ID=#{RUNID}", "INSERT INTO CONTRACT_REVIEW_CLAUSE_COMPARISON",
                "INSERT INTO CONTRACT_REVIEW_CLAUSE_VALUE",
                "INSERT INTO CONTRACT_REVIEW_CLAUSE_VALUE_EVIDENCE",
                "LEFT JOIN CONTRACT_REVIEW_CLAUSE_VALUE_EVIDENCE");
        assertThat(xml).doesNotContain("RAW_MODEL_BODY", "RAW_PROMPT", "UPDATE CONTRACT_REVIEW_FINDING",
                "UPDATE CONTRACT_REVIEW_EVIDENCE");
    }

    private static ContractReviewFinding finding() {
        return new ContractReviewFinding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "SEMANTIC_HINT", "CONTRACT_STRUCTURE", null, "REVIEW", null,
                "核对合同结构", null, 1, "semantic-v1", ReviewFindingSource.MODEL_ASSISTED,
                null, "typed-model", null, null, "schema-v1", NOW, 0);
    }

    private static ContractReviewEvidence evidence(ContractReviewFinding finding) {
        return new ContractReviewEvidence(UUID.randomUUID(), finding.findingId(), finding.runId(),
                finding.stepId(), finding.attemptId(), 1, UUID.randomUUID(), "b".repeat(64),
                UUID.randomUUID(), 2, "c".repeat(64), "SUPPORT", "LOCATOR_UNAVAILABLE",
                "ground-v1", null, null, null, null, null, null, false, null, null,
                "d".repeat(64), "候选文本", 1, NOW, 0);
    }
}
