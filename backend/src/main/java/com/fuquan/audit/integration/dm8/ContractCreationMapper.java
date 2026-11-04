package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractCreationMapper {
    int insertContract(@Param("row") ContractPersistenceRow row);
    int insertLineItem(@Param("row") ContractLineItemPersistenceRow row);
    int insertAwardNotice(@Param("row") ContractAwardNoticePersistenceRow row);
}
