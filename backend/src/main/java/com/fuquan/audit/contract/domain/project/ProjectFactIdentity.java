package com.fuquan.audit.contract.domain.project;

import java.math.BigDecimal;
import java.util.Objects;

/** Semantic equality for immutable replay facts; generated IDs/timestamps are not fact inputs. */
public final class ProjectFactIdentity {
    private ProjectFactIdentity() {
    }

    public static boolean sameCandidate(ContractCandidate left, ContractCandidate right) {
        return left.projectId().equals(right.projectId())
                && left.sourceContractFileId().equals(right.sourceContractFileId())
                && Objects.equals(left.sourceParseResultId(), right.sourceParseResultId())
                && Objects.equals(left.sourceRoleDecisionId(), right.sourceRoleDecisionId())
                && Objects.equals(left.sourceIdentityDecisionId(), right.sourceIdentityDecisionId())
                && left.sourceAuthoritySource() == right.sourceAuthoritySource()
                && Objects.equals(left.sourceFactFingerprint(), right.sourceFactFingerprint())
                && sameCandidateContent(left, right);
    }

    public static boolean sameCandidateContent(ContractCandidate left, ContractCandidate right) {
        return left.projectId().equals(right.projectId())
                && left.sourceContractFileId().equals(right.sourceContractFileId())
                && Objects.equals(left.contractNo(), right.contractNo())
                && Objects.equals(left.contractName(), right.contractName())
                && Objects.equals(left.contractType(), right.contractType())
                && Objects.equals(left.partyA(), right.partyA())
                && Objects.equals(left.partyB(), right.partyB())
                && Objects.equals(left.partyBCreditCode(), right.partyBCreditCode())
                && sameAmount(left.contractAmount(), right.contractAmount())
                && Objects.equals(left.amountInWords(), right.amountInWords())
                && sameAmount(left.awardAmount(), right.awardAmount())
                && Objects.equals(left.awardSupplier(), right.awardSupplier())
                && Objects.equals(left.awardSupplierCreditCode(), right.awardSupplierCreditCode())
                && sameLines(left.lineItems(), right.lineItems());
    }

    public static boolean sameExtraction(ExtractionResult left, ExtractionResult right) {
        return left.projectId().equals(right.projectId())
                && left.sourceFileId().equals(right.sourceFileId())
                && Objects.equals(left.parseResultId(), right.parseResultId())
                && Objects.equals(left.candidateId(), right.candidateId())
                && left.extractionKey().equals(right.extractionKey())
                && left.extractionVersion().equals(right.extractionVersion())
                && left.factType().equals(right.factType())
                && Objects.equals(left.groupKey(), right.groupKey())
                && Objects.equals(left.valueText(), right.valueText())
                && Objects.equals(left.normalizedValue(), right.normalizedValue())
                && sameAmount(left.valueAmount(), right.valueAmount())
                && left.sourceText().equals(right.sourceText())
                && Objects.equals(left.pageNumber(), right.pageNumber())
                && Objects.equals(left.paragraphIndex(), right.paragraphIndex())
                && left.sourceLocator().equals(right.sourceLocator());
    }

    private static boolean sameLines(java.util.List<ContractCandidateLineItem> left,
                                     java.util.List<ContractCandidateLineItem> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            ContractCandidateLineItem a = left.get(index);
            ContractCandidateLineItem b = right.get(index);
            if (!Objects.equals(a.itemKey(), b.itemKey()) || !Objects.equals(a.itemName(), b.itemName())
                    || !sameAmount(a.quantity(), b.quantity()) || !sameAmount(a.unitPrice(), b.unitPrice())
                    || !sameAmount(a.declaredAmount(), b.declaredAmount()) || a.sortNo() != b.sortNo()) return false;
        }
        return true;
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
}
