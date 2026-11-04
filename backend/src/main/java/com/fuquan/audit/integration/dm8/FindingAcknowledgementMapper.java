package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FindingAcknowledgementMapper {

    int insert(FindingAcknowledgementPersistenceRow row);

    FindingAcknowledgementPersistenceRow findByCheckResultId(
            @Param("checkResultId") String checkResultId);

    List<FindingAcknowledgementPersistenceRow> findByTaskId(@Param("taskId") String taskId);
}
