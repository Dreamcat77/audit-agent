package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractMapper {

    ContractPersistenceRow findById(@Param("id") String id);

    ContractPersistenceRow findByIdForUpdate(@Param("id") String id);

    List<ContractLineItemPersistenceRow> findLineItems(@Param("contractId") String contractId);

    ContractAwardNoticePersistenceRow findAwardNotice(@Param("contractId") String contractId);

    List<ContractPersistenceRow> findSummaryPage(
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("endRow") long endRow);

    long countSummaries(
            @Param("status") String status,
            @Param("keyword") String keyword);
}
