package com.fuquan.audit.common.privacy;

import java.util.List;

/** Deployment-owned single-client names and explicitly approved aliases. */
public interface ClientIdentityRepository {
    List<String> enabledNames();
    default boolean required() { return true; }
}
