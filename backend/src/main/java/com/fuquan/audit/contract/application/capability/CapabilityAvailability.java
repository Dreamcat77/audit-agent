package com.fuquan.audit.contract.application.capability;

/** Availability is catalog metadata, never a ReviewStep execution status. */
public enum CapabilityAvailability {
    ENABLED, NOT_CONFIGURED, DISABLED_BY_POLICY
}
