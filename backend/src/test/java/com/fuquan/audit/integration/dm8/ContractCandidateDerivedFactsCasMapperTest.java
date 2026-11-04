package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateDerivedFacts;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.domain.project.ContractCandidateLineItem;
import java.math.BigDecimal;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContractCandidateDerivedFactsCasMapperTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID CANDIDATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-20T02:00:00Z");

    @Test
    void mapperGuardsDraftVersionAndLeavesPrimaryAuthorityColumnsOutsideDerivedFactUpdate() throws IOException {
        String xml = new String(getClass().getClassLoader()
                .getResourceAsStream("mappers/dm8/ContractCandidateMapper.xml").readAllBytes());
        String update = xml.substring(xml.indexOf("<update id=\"replaceDraftDerivedFactsIfVersionMatches\">"),
                xml.indexOf("</update>", xml.indexOf("<update id=\"replaceDraftDerivedFactsIfVersionMatches\">")));

        assertThat(update).contains("PROJECT_ID=#{projectId}", "ID=#{candidateId}",
                "PROMOTION_STATE='DRAFT'", "VERSION_NO=#{expectedVersion}",
                "CONTRACT_NO=#{facts.contractNo}", "CONTRACT_NAME=#{facts.contractName}",
                "CONTRACT_TYPE=#{facts.contractType}", "PARTY_A=#{facts.partyA}",
                "PARTY_B=#{facts.partyB}", "PARTY_B_CREDIT_CODE=#{facts.partyBCreditCode}",
                "CONTRACT_AMOUNT=#{facts.contractAmount}", "AMOUNT_IN_WORDS=#{facts.amountInWords}",
                "AWARD_AMOUNT=#{facts.awardAmount}", "AWARD_SUPPLIER=#{facts.awardSupplier}",
                "AWARD_SUPPLIER_CREDIT_CODE=#{facts.awardSupplierCreditCode}",
                "SUPPORT_AUTHORITY_VERSION_NO=#{supportAuthorityVersionNo}",
                "SUPPORT_AUTHORITY_FINGERPRINT=#{supportAuthorityFingerprint}");
        assertThat(update).doesNotContain("SOURCE_CONTRACT_FILE_ID=", "SOURCE_PARSE_RESULT_ID=",
                "SOURCE_ROLE_DECISION_ID=", "SOURCE_IDENTITY_DECISION_ID=", "SOURCE_AUTHORITY_SOURCE=",
                "SOURCE_FACT_FINGERPRINT=");
        assertThat(xml).contains("<delete id=\"deleteLinesByCandidate\">DELETE FROM CONTRACT_CANDIDATE_LINE_ITEM",
                "WHERE PROJECT_ID=#{projectId} AND CANDIDATE_ID=#{candidateId}");
    }

    @Test
    void guardedUpdateThenReplacesOnlyCandidateLinesAndReloadsExpectedMetadata() {
        ContractCandidateMapper candidateMapper = mock(ContractCandidateMapper.class);
        ContractCandidatePersistenceRow updated = candidateRow(5L, 8L, "b".repeat(64), replacementFacts());
        when(candidateMapper.replaceDraftDerivedFactsIfVersionMatches(eq(PROJECT_ID.toString()),
                eq(CANDIDATE_ID.toString()), eq(4L), any(), eq(8L), eq("b".repeat(64)), any())).thenReturn(1);
        when(candidateMapper.findByProjectAndId(PROJECT_ID.toString(), CANDIDATE_ID.toString())).thenReturn(updated);
        when(candidateMapper.findLinesByCandidate(PROJECT_ID.toString(), CANDIDATE_ID.toString()))
                .thenReturn(replacementFacts().lineItems().stream().map(ContractCandidateDerivedFactsCasMapperTest::lineRow).toList());
        ContractCandidateRepository repository = repository(candidateMapper);

        var result = repository.replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID, CANDIDATE_ID, 4L,
                replacementFacts(), 8L, "b".repeat(64), UPDATED_AT);

        assertThat(result.versionNo()).isEqualTo(5L);
        assertThat(result.supportAuthorityVersionNo()).isEqualTo(8L);
        assertThat(result.supportAuthorityFingerprint()).isEqualTo("b".repeat(64));
        assertThat(result.lineItems()).extracting(ContractCandidateLineItem::itemKey)
                .containsExactly("new-a", "new-b");
        InOrder ordered = inOrder(candidateMapper);
        ordered.verify(candidateMapper).replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID.toString(),
                CANDIDATE_ID.toString(), 4L, replacementFacts(), 8L, "b".repeat(64),
                LocalDateTime.of(2026, 9, 20, 2, 0));
        ordered.verify(candidateMapper).deleteLinesByCandidate(PROJECT_ID.toString(), CANDIDATE_ID.toString());
        ordered.verify(candidateMapper).insertLine(lineRow(replacementFacts().lineItems().get(0)));
        ordered.verify(candidateMapper).insertLine(lineRow(replacementFacts().lineItems().get(1)));
        ordered.verify(candidateMapper).findByProjectAndId(PROJECT_ID.toString(), CANDIDATE_ID.toString());
    }

    @Test
    void failedGuardedUpdateConflictsBeforeDeletingLines() {
        ContractCandidateMapper candidateMapper = mock(ContractCandidateMapper.class);
        when(candidateMapper.replaceDraftDerivedFactsIfVersionMatches(eq(PROJECT_ID.toString()),
                eq(CANDIDATE_ID.toString()), eq(4L), any(), eq(8L), eq("b".repeat(64)), any())).thenReturn(0);

        assertThatThrownBy(() -> repository(candidateMapper).replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 4L, replacementFacts(), 8L, "b".repeat(64), UPDATED_AT))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(BusinessErrorCode.CONFLICT);

        verify(candidateMapper, never()).deleteLinesByCandidate(any(), any());
        verify(candidateMapper, never()).insertLine(any());
    }

    @Test
    void reloadMetadataMismatchIsRejectedAfterTheGuardedWrite() {
        ContractCandidateMapper candidateMapper = mock(ContractCandidateMapper.class);
        when(candidateMapper.replaceDraftDerivedFactsIfVersionMatches(eq(PROJECT_ID.toString()),
                eq(CANDIDATE_ID.toString()), eq(4L), any(), eq(8L), eq("b".repeat(64)), any())).thenReturn(1);
        when(candidateMapper.findByProjectAndId(PROJECT_ID.toString(), CANDIDATE_ID.toString()))
                .thenReturn(candidateRow(5L, 7L, "c".repeat(64), replacementFacts()));
        when(candidateMapper.findLinesByCandidate(PROJECT_ID.toString(), CANDIDATE_ID.toString()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> repository(candidateMapper).replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 4L, replacementFacts(), 8L, "b".repeat(64), UPDATED_AT))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(BusinessErrorCode.CONFLICT);
    }

    @Test
    void rejectsWrongProjectLineBeforeAnyMapperCall() {
        ContractCandidateMapper candidateMapper = mock(ContractCandidateMapper.class);
        ContractCandidateDerivedFacts foreignLines = factsWithLines(List.of(lineOwnedBy("wrong-project", 0,
                UUID.fromString("00000000-0000-0000-0000-000000000209"), CANDIDATE_ID)));

        assertThatThrownBy(() -> repository(candidateMapper).replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 4L, foreignLines, 8L, "b".repeat(64), UPDATED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(candidateMapper);
    }

    @Test
    void rejectsWrongCandidateLineBeforeAnyMapperCall() {
        ContractCandidateMapper candidateMapper = mock(ContractCandidateMapper.class);
        ContractCandidateDerivedFacts foreignLines = factsWithLines(List.of(lineOwnedBy("wrong-candidate", 0,
                PROJECT_ID, UUID.fromString("00000000-0000-0000-0000-000000000210"))));

        assertThatThrownBy(() -> repository(candidateMapper).replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 4L, foreignLines, 8L, "b".repeat(64), UPDATED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(candidateMapper);
    }

    @Test
    void emptyReplacementDeletesOldLinesWithoutInsertingNewOnes() {
        ContractCandidateMapper candidateMapper = mock(ContractCandidateMapper.class);
        ContractCandidateDerivedFacts empty = nullFacts();
        when(candidateMapper.replaceDraftDerivedFactsIfVersionMatches(eq(PROJECT_ID.toString()),
                eq(CANDIDATE_ID.toString()), eq(4L), eq(empty), eq(8L), eq("b".repeat(64)), any())).thenReturn(1);
        when(candidateMapper.findByProjectAndId(PROJECT_ID.toString(), CANDIDATE_ID.toString()))
                .thenReturn(candidateRow(5L, 8L, "b".repeat(64), empty));
        when(candidateMapper.findLinesByCandidate(PROJECT_ID.toString(), CANDIDATE_ID.toString())).thenReturn(List.of());

        var result = repository(candidateMapper).replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 4L, empty, 8L, "b".repeat(64), UPDATED_AT);

        assertThat(result.lineItems()).isEmpty();
        verify(candidateMapper).deleteLinesByCandidate(PROJECT_ID.toString(), CANDIDATE_ID.toString());
        verify(candidateMapper, never()).insertLine(any());
    }

    @Test
    void propagatesLineDeleteFailureForCallerTransactionRollback() {
        ContractCandidateMapper candidateMapper = mock(ContractCandidateMapper.class);
        when(candidateMapper.replaceDraftDerivedFactsIfVersionMatches(eq(PROJECT_ID.toString()),
                eq(CANDIDATE_ID.toString()), eq(4L), any(), eq(8L), eq("b".repeat(64)), any())).thenReturn(1);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("line delete failed");
        when(candidateMapper.deleteLinesByCandidate(PROJECT_ID.toString(), CANDIDATE_ID.toString())).thenThrow(failure);

        assertThatThrownBy(() -> repository(candidateMapper).replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 4L, replacementFacts(), 8L, "b".repeat(64), UPDATED_AT)).isSameAs(failure);
    }

    @Test
    void propagatesLineInsertFailureForCallerTransactionRollback() {
        ContractCandidateMapper candidateMapper = mock(ContractCandidateMapper.class);
        when(candidateMapper.replaceDraftDerivedFactsIfVersionMatches(eq(PROJECT_ID.toString()),
                eq(CANDIDATE_ID.toString()), eq(4L), any(), eq(8L), eq("b".repeat(64)), any())).thenReturn(1);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("line insert failed");
        when(candidateMapper.insertLine(any())).thenThrow(failure);

        assertThatThrownBy(() -> repository(candidateMapper).replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 4L, replacementFacts(), 8L, "b".repeat(64), UPDATED_AT)).isSameAs(failure);
    }

    private static ContractCandidateRepository repository(ContractCandidateMapper candidateMapper) {
        return new Dm8ProjectRepositories(mock(ProjectMapper.class), mock(ProjectFileMapper.class),
                mock(ProjectExtractionResultMapper.class), candidateMapper).contractCandidateRepository();
    }

    private static ContractCandidatePersistenceRow candidateRow(long version, long supportVersion,
            String supportFingerprint, ContractCandidateDerivedFacts facts) {
        return new ContractCandidatePersistenceRow(CANDIDATE_ID.toString(), PROJECT_ID.toString(),
                "00000000-0000-0000-0000-000000000203", "00000000-0000-0000-0000-000000000204",
                "00000000-0000-0000-0000-000000000205", "00000000-0000-0000-0000-000000000206",
                "HUMAN_CONFIRMATION", "a".repeat(64), facts.contractNo(), facts.contractName(), facts.contractType(),
                facts.partyA(), facts.partyB(), facts.partyBCreditCode(), facts.contractAmount(), facts.amountInWords(),
                facts.awardAmount(), facts.awardSupplier(), facts.awardSupplierCreditCode(), "DRAFT", version,
                null, null, null, null, LocalDateTime.of(2026, 9, 20, 0, 0),
                LocalDateTime.of(2026, 9, 20, 2, 0), supportVersion, supportFingerprint);
    }

    private static ContractCandidateDerivedFacts replacementFacts() {
        return new ContractCandidateDerivedFacts("C-NEW", "New Contract", "SERVICE", "Buyer", "Supplier",
                "91350000NEW", new BigDecimal("200.00"), "贰佰元整", new BigDecimal("190.00"),
                "Award Supplier", "91350000AWARD", List.of(line("new-a", 0), line("new-b", 1)));
    }

    private static ContractCandidateDerivedFacts nullFacts() {
        return new ContractCandidateDerivedFacts(null, null, null, null, null, null, null, null, null,
                null, null, List.of());
    }

    private static ContractCandidateDerivedFacts factsWithLines(List<ContractCandidateLineItem> lines) {
        return new ContractCandidateDerivedFacts("C-NEW", "New Contract", "SERVICE", "Buyer", "Supplier",
                "91350000NEW", new BigDecimal("200.00"), "贰佰元整", new BigDecimal("190.00"),
                "Award Supplier", "91350000AWARD", lines);
    }

    private static ContractCandidateLineItem line(String key, int sortNo) {
        return lineOwnedBy(key, sortNo, PROJECT_ID, CANDIDATE_ID);
    }

    private static ContractCandidateLineItem lineOwnedBy(String key, int sortNo, UUID projectId, UUID candidateId) {
        return new ContractCandidateLineItem(UUID.nameUUIDFromBytes(key.getBytes()), projectId, candidateId,
                key, key + " name", new BigDecimal("2"), new BigDecimal("3.00"), new BigDecimal("6.00"), sortNo);
    }

    private static ContractCandidateLineItemPersistenceRow lineRow(ContractCandidateLineItem line) {
        return new ContractCandidateLineItemPersistenceRow(line.id().toString(), line.projectId().toString(),
                line.candidateId().toString(), line.itemKey(), line.itemName(), line.quantity(), line.unitPrice(),
                line.declaredAmount(), line.sortNo());
    }
}
