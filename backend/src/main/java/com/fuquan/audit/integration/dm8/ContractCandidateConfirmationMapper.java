package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractCandidateConfirmationMapper {
    int insert(@Param("row") ContractCandidateConfirmationPersistenceRow row);
    ContractCandidateConfirmationPersistenceRow findByProjectAndCandidateAndId(
            @Param("projectId") String projectId, @Param("candidateId") String candidateId,
            @Param("confirmationId") String confirmationId);
    ContractCandidateConfirmationPersistenceRow findLatestByProjectAndCandidate(
            @Param("projectId") String projectId, @Param("candidateId") String candidateId);
}
