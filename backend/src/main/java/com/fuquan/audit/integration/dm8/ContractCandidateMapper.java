package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.extraction.ContractCandidateDerivedFacts;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractCandidateMapper {
    int insert(@Param("row") ContractCandidatePersistenceRow row);
    int insertLine(@Param("row") ContractCandidateLineItemPersistenceRow row);
    ContractCandidatePersistenceRow findByProjectAndId(@Param("projectId") String projectId, @Param("id") String id);
    ContractCandidatePersistenceRow findByProjectAndIdForUpdate(@Param("projectId") String projectId, @Param("id") String id);
    ContractCandidatePersistenceRow findBySource(@Param("projectId") String projectId, @Param("sourceId") String sourceId);
    ContractCandidatePersistenceRow findBySourceForUpdate(@Param("projectId") String projectId, @Param("sourceId") String sourceId);
    List<ContractCandidatePersistenceRow> findByProject(@Param("projectId") String projectId);
    List<ContractCandidateLineItemPersistenceRow> findLinesByCandidate(
            @Param("projectId") String projectId, @Param("candidateId") String candidateId);
    int deleteLinesByCandidate(@Param("projectId") String projectId, @Param("candidateId") String candidateId);
    int replaceDraftDerivedFactsIfVersionMatches(@Param("projectId") String projectId,
            @Param("candidateId") String candidateId, @Param("expectedVersion") long expectedVersion,
            @Param("facts") ContractCandidateDerivedFacts facts,
            @Param("supportAuthorityVersionNo") long supportAuthorityVersionNo,
            @Param("supportAuthorityFingerprint") String supportAuthorityFingerprint,
            @Param("updatedAt") java.time.LocalDateTime updatedAt);
    int promoteIfVersionMatches(@Param("projectId") String projectId, @Param("candidateId") String candidateId,
            @Param("expectedVersion") long expectedVersion, @Param("contractId") String contractId,
            @Param("promotedBy") String promotedBy, @Param("promotedAt") java.time.LocalDateTime promotedAt,
            @Param("updatedAt") java.time.LocalDateTime updatedAt);
    int promoteWithConfirmationIfVersionMatches(@Param("projectId") String projectId,
            @Param("candidateId") String candidateId, @Param("expectedVersion") long expectedVersion,
            @Param("contractId") String contractId, @Param("confirmationId") String confirmationId,
            @Param("promotedBy") String promotedBy, @Param("promotedAt") java.time.LocalDateTime promotedAt,
            @Param("updatedAt") java.time.LocalDateTime updatedAt);
    int confirmDraftIfVersionMatches(@Param("projectId") String projectId,
            @Param("candidateId") String candidateId, @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") java.time.LocalDateTime updatedAt);
    int reauthorizeDraftIfVersionMatches(@Param("projectId") String projectId,
            @Param("candidateId") String candidateId, @Param("expectedVersion") long expectedVersion,
            @Param("parseResultId") String parseResultId, @Param("roleDecisionId") String roleDecisionId,
            @Param("identityDecisionId") String identityDecisionId,
            @Param("authoritySource") String authoritySource,
            @Param("factFingerprint") String factFingerprint,
            @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
