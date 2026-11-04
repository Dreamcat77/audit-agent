package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectContractMapper {
    int insert(@Param("row") ProjectContractPersistenceRow row);
    List<ProjectContractPersistenceRow> findByProjectId(@Param("projectId") String projectId);
    ProjectContractPersistenceRow findByContractId(@Param("contractId") String contractId);
    ProjectContractPersistenceRow findByContractIdForUpdate(@Param("contractId") String contractId);
    ProjectContractPersistenceRow findByCandidateId(@Param("candidateId") String candidateId);
}
