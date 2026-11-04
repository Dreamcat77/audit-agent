package com.fuquan.audit.contract.application.debug;

import java.util.Optional;

/** Export projection only: implementations may retain, alias/salt, or remove hashes, never change domain IDs. */
@FunctionalInterface
public interface DebugHashPolicy {
    Optional<String> exportHash(String hash);
    /** Internal authorized diagnostics only; does not approve export to a customer/external site. */
    static DebugHashPolicy internal() { return Optional::of; }
}
