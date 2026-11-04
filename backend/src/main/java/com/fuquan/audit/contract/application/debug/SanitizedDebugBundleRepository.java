package com.fuquan.audit.contract.application.debug;
import java.util.*;
public interface SanitizedDebugBundleRepository {
    void insert(SanitizedDebugBundle bundle);
    Optional<SanitizedDebugBundle> findById(UUID id);
    List<SanitizedDebugBundle> findByRunId(UUID runId);
    SanitizedDebugBundle transition(SanitizedDebugBundle expected,SanitizedDebugBundle next);
    /** Local adapter only; DM8 rolls back the surrounding transaction. */
    default void rollbackUncommittedInsert(SanitizedDebugBundle bundle) { }
}
