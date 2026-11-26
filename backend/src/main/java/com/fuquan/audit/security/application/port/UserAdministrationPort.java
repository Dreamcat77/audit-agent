package com.fuquan.audit.security.application.port;

import com.fuquan.audit.security.domain.UserAccount;
import java.util.List;

/** Account provisioning. Passwords cross this boundary only as encoded hashes. */
public interface UserAdministrationPort {
    void create(UserAccount account);
    UserAccount findById(String id);
    void update(UserAccount account);
    List<UserAccount> list(int offset, int limit);
    long count();
}
