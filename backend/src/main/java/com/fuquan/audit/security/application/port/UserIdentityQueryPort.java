package com.fuquan.audit.security.application.port;

import java.util.Optional;

/** Narrow read boundary for resolving a current, non-durable user display name. */
public interface UserIdentityQueryPort {

    Optional<String> findDisplayNameByUserId(String userId);
}
