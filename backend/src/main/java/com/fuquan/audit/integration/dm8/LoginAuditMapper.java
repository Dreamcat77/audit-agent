package com.fuquan.audit.integration.dm8;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LoginAuditMapper {

    int insert(LoginAuditPersistenceRow row);
}
