package com.fuquan.audit.security.application.port;

import com.fuquan.audit.security.domain.UserAccount;
import java.util.Optional;

/** 按业务角色选择审批人的安全查询边界。 */
public interface UserAccountRoleQueryPort {

    Optional<UserAccount> findFirstActiveByRole(String roleCode);

    Optional<UserAccount> findActiveById(String userId);
}
