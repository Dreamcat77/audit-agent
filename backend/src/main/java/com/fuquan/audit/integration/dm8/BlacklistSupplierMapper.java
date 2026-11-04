package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BlacklistSupplierMapper {

    List<BlacklistSupplierPersistenceRow> findAllActive();

    List<BlacklistSupplierPersistenceRow> findPage(
            @Param("keyword") String keyword,
            @Param("blacklistType") String blacklistType,
            @Param("active") Boolean active,
            @Param("startRow") int startRow,
            @Param("endRow") int endRow);

    long count(
            @Param("keyword") String keyword,
            @Param("blacklistType") String blacklistType,
            @Param("active") Boolean active);

    BlacklistSupplierPersistenceRow findById(@Param("id") String id);

    void insert(BlacklistSupplierPersistenceRow row);

    void update(BlacklistSupplierPersistenceRow row);
}
