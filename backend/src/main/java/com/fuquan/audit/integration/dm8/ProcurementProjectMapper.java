package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcurementProjectMapper {
    List<ProcurementProjectPersistenceRow> findPage(
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("startRow") int startRow,
            @Param("endRow") int endRow);

    long count(@Param("status") String status, @Param("keyword") String keyword);

    ProcurementProjectPersistenceRow findById(@Param("id") String id);

    int insertLegacyMaterialLink(@Param("id") String id, @Param("materialId") String materialId);
    int insertMaterialLink(@Param("id") String id, @Param("materialId") String materialId,
                           @Param("materialOrder") int materialOrder, @Param("primary") int primary);
    String lockParsedMaterial(@Param("materialId") String materialId);
    long countMaterialProjects(@Param("materialId") String materialId);

    int insert(@Param("id") String id,
               @Param("sourceProjectId") String sourceProjectId,
               @Param("projectCode") String projectCode,
               @Param("projectName") String projectName,
               @Param("departmentName") String departmentName,
               @Param("procurementType") String procurementType,
               @Param("budgetAmount") java.math.BigDecimal budgetAmount,
               @Param("subjectCode") String subjectCode,
               @Param("subjectName") String subjectName,
               @Param("procurementMethod") String procurementMethod,
               @Param("requirementDescription") String requirementDescription,
               @Param("applicantName") String applicantName,
               @Param("attachmentName") String attachmentName);

    int update(@Param("id") String id,
               @Param("projectName") String projectName,
               @Param("departmentName") String departmentName,
               @Param("procurementType") String procurementType,
               @Param("budgetAmount") java.math.BigDecimal budgetAmount,
               @Param("subjectCode") String subjectCode,
               @Param("subjectName") String subjectName,
               @Param("procurementMethod") String procurementMethod,
               @Param("requirementDescription") String requirementDescription,
               @Param("applicantName") String applicantName,
               @Param("attachmentName") String attachmentName);

    int softDelete(@Param("id") String id);

    int updateStatus(@Param("id") String id, @Param("status") String status);
}
