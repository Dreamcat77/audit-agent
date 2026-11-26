package com.fuquan.audit.common.privacy;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Local adapters do not invoke external models. No customer names are seeded. */
@Repository
@Profile("local")
public class LocalClientIdentityRepository implements ClientIdentityRepository {
    @Override public List<String> enabledNames() { return List.of(); }
    @Override public boolean required() { return false; }
}
