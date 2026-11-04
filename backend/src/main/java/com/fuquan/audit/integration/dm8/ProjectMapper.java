package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectMapper {
    int insert(@Param("row") ProjectPersistenceRow row);
    ProjectPersistenceRow findById(@Param("id") String id);
    ProjectPersistenceRow findByIdForUpdate(@Param("id") String id);
    List<ProjectPersistenceRow> findPage(@Param("keyword") String keyword,
                                         @Param("offset") long offset, @Param("endRow") long endRow);
    long count(@Param("keyword") String keyword);
    List<ProjectPersistenceRow> findIntakeDraftPage(@Param("offset") long offset,
                                                     @Param("endRow") long endRow);
    long countIntakeDrafts();
    List<ProjectPreparationDraftPersistenceRow> findPreparationDraftPage(
            @Param("offset") long offset, @Param("endRow") long endRow);
    long countPreparationDrafts();
    int compareAndSet(@Param("row") ProjectPersistenceRow row, @Param("expectedVersion") long expectedVersion);
}
