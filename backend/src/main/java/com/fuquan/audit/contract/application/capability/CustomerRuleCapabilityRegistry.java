package com.fuquan.audit.contract.application.capability;

import static com.fuquan.audit.contract.application.capability.ReviewCapabilityDescriptor.*;

import com.fuquan.audit.contract.application.reviewrun.ContractReviewExecutionPolicyResolver;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Closed, immutable catalog. Registration cannot create steps, execute rules or activate a provider. */
public final class CustomerRuleCapabilityRegistry {
    private static final List<Code> CURRENT = List.of(Code.MATERIAL_VALIDATION, Code.F02_DETERMINISTIC,
            Code.SEMANTIC_AI, Code.BREACH, Code.TERM);
    private final ReviewProfileDescriptor profile = new ReviewProfileDescriptor(
            ContractReviewExecutionPolicyResolver.REVIEW_PROFILE_VERSION, CURRENT, List.of());
    private final List<ReviewCapabilityDescriptor> descriptors;

    public CustomerRuleCapabilityRegistry() {
        var values = new ArrayList<ReviewCapabilityDescriptor>();
        values.add(fixed(Code.MATERIAL_VALIDATION, "材料校验", ExecutionKind.SNAPSHOT_VALIDATION,
                List.of(Input.FROZEN_MATERIAL_SNAPSHOT), OutputSchema.RUN_MATERIAL_SNAPSHOT));
        values.add(fixed(Code.F02_DETERMINISTIC, "合同确定性审核", ExecutionKind.DETERMINISTIC,
                List.of(Input.FROZEN_CONTRACT_SNAPSHOT), OutputSchema.F02_AUDIT_TASK));
        values.add(fixed(Code.SEMANTIC_AI, "合同语义辅助", ExecutionKind.SEMANTIC_ASSISTANCE,
                List.of(Input.FROZEN_TEXT_EVIDENCE), OutputSchema.REVIEW_FINDING_EVIDENCE));
        values.add(fixed(Code.BREACH, "违约条款分析", ExecutionKind.CLAUSE_ANALYSIS,
                List.of(Input.FROZEN_TEXT_EVIDENCE), OutputSchema.REVIEW_FINDING_EVIDENCE));
        values.add(fixed(Code.TERM, "期限条款分析", ExecutionKind.CLAUSE_ANALYSIS,
                List.of(Input.FROZEN_TEXT_EVIDENCE), OutputSchema.REVIEW_FINDING_EVIDENCE));
        for (var code : List.of(Code.F09, Code.F10, Code.F11, Code.F12, Code.F13, Code.F14, Code.F15)) {
            values.add(new ReviewCapabilityDescriptor(code, "待配置审核能力 " + code,
                    ExecutionKind.UNCONFIGURED, List.of(), null, PolicyRequirement.CUSTOMER_APPROVED_VERSION,
                    null, CapabilityAvailability.NOT_CONFIGURED));
        }
        descriptors = List.copyOf(values);
    }

    public ReviewProfileDescriptor currentProfile() { return profile; }
    public List<ReviewCapabilityDescriptor> descriptors() { return descriptors; }
    public ReviewCapabilityDescriptor find(Code code) {
        Objects.requireNonNull(code);
        return descriptors.stream().filter(value -> value.code() == code).findFirst().orElseThrow();
    }

    private static ReviewCapabilityDescriptor fixed(Code code, String name, ExecutionKind kind,
            List<Input> inputs, OutputSchema output) {
        return new ReviewCapabilityDescriptor(code, name, kind, inputs, output,
                PolicyRequirement.SERVER_FROZEN_VERSION, ContractReviewStepType.valueOf(code.name()),
                CapabilityAvailability.ENABLED);
    }
}
