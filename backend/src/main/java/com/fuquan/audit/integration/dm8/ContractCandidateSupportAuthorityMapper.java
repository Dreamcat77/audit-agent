package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractCandidateSupportAuthorityMapper {
    List<ContractCandidateSupportAuthorityPersistenceRow> findCurrentByProjectAndCandidate(
            @Param("projectId") String projectId, @Param("candidateId") String candidateId);

    List<ContractCandidateSupportAuthorityPersistenceRow> findCurrentByProjectAndCandidateForUpdate(
            @Param("projectId") String projectId, @Param("candidateId") String candidateId);

    ContractCandidateSupportAuthorityPersistenceRow findByProjectAndCandidateAndSourceFile(
            @Param("projectId") String projectId, @Param("candidateId") String candidateId,
            @Param("sourceFileId") String sourceFileId);

    int deleteCurrentByProjectAndCandidate(@Param("projectId") String projectId,
            @Param("candidateId") String candidateId);

    int insertAll(@Param("rows") List<ContractCandidateSupportAuthorityPersistenceRow> rows);
}
