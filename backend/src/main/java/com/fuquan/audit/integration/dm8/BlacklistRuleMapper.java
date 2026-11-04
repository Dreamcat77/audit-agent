package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BlacklistRuleMapper {

    List<BlacklistRulePersistenceRow> findAllActive();

    List<BlacklistRulePersistenceRow> findAll();

    BlacklistRulePersistenceRow findById(@Param("id") String id);

    void insert(BlacklistRulePersistenceRow row);

    void update(BlacklistRulePersistenceRow row);

    void deleteById(@Param("id") String id);
}
