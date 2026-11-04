package com.fuquan.audit.contract.application.capability;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.util.List;
import java.util.Objects;

/** Descriptive contract only: contains neither executable rules nor customer business decisions. */
public record ReviewCapabilityDescriptor(
        Code code, String displayName, ExecutionKind executionKind, List<Input> inputs,
        OutputSchema outputSchema, PolicyRequirement policyRequirement,
        ContractReviewStepType stepType, CapabilityAvailability availability) {
    public enum Code {
        MATERIAL_VALIDATION, F02_DETERMINISTIC, SEMANTIC_AI, BREACH, TERM,
        F09, F10, F11, F12, F13, F14, F15;

        public boolean extension() { return name().matches("F(09|1[0-5])"); }
    }
    public enum ExecutionKind { SNAPSHOT_VALIDATION, DETERMINISTIC, SEMANTIC_ASSISTANCE, CLAUSE_ANALYSIS, UNCONFIGURED }
    public enum Input { FROZEN_MATERIAL_SNAPSHOT, FROZEN_CONTRACT_SNAPSHOT, FROZEN_TEXT_EVIDENCE }
    public enum OutputSchema { RUN_MATERIAL_SNAPSHOT, F02_AUDIT_TASK, REVIEW_FINDING_EVIDENCE }
    public enum PolicyRequirement { SERVER_FROZEN_VERSION, CUSTOMER_APPROVED_VERSION }

    public ReviewCapabilityDescriptor {
        Objects.requireNonNull(code);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(executionKind);
        Objects.requireNonNull(policyRequirement);
        Objects.requireNonNull(availability);
        inputs = List.copyOf(inputs);
        if (displayName.isBlank()) throw new IllegalArgumentException("blank capability display name");
        if (code.extension()) {
            if (!displayName.equals("待配置审核能力 " + code)
                    || executionKind != ExecutionKind.UNCONFIGURED || !inputs.isEmpty()
                    || outputSchema != null || stepType != null
                    || policyRequirement != PolicyRequirement.CUSTOMER_APPROVED_VERSION
                    || availability != CapabilityAvailability.NOT_CONFIGURED)
                throw new IllegalArgumentException("extension has no approved execution contract");
        } else if (stepType == null || !stepType.name().equals(code.name())
                || executionKind == ExecutionKind.UNCONFIGURED || inputs.isEmpty() || outputSchema == null
                || policyRequirement != PolicyRequirement.SERVER_FROZEN_VERSION) {
            throw new IllegalArgumentException("invalid fixed capability binding");
        }
    }
}
