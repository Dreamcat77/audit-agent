package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.application.port.ContractRuleConfigRepository;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Freezes real server-owned execution contracts for new runs, not provider deployment guesses. */
@Component
public class ContractReviewExecutionPolicyResolver {
    public static final String REVIEW_PROFILE_VERSION = "review-profile-five-required-v1";
    public static final String MODEL_POLICY_VERSION =
            "contract-semantic-java-schema-v1-redaction-v1-grounding-v1";
    public static final String DURABLE_WORK_POLICY_VERSION = "contract-semantic-work-v3";
    public static final String DURABLE_MODEL_POLICY_VERSION =
            DURABLE_WORK_POLICY_VERSION + "-redaction-v1-grounding-v1";
    public static final String OBSERVE_MODEL_POLICY_VERSION = DURABLE_MODEL_POLICY_VERSION + "-money-observe-v1";
    public static final String OBSERVE_HIERARCHY_MODEL_POLICY_VERSION = OBSERVE_MODEL_POLICY_VERSION + "-hierarchy-v1";
    public static final String OBSERVE_HIERARCHY_V2_MODEL_POLICY_VERSION = OBSERVE_MODEL_POLICY_VERSION + "-hierarchy-v2";
    public static final String HIERARCHY_PROTOCOL = "contract-bounded-hierarchy-v1";
    public static final String HIERARCHY_V2_PROTOCOL = "contract-bounded-hierarchy-v2";
    /** Historical checkpoints remain readable, but cannot execute through the current work protocol. */
    public static final String HISTORICAL_DURABLE_MODEL_POLICY_VERSION =
            "contract-semantic-work-v2-redaction-v1-grounding-v1";

    public record ResolvedPolicy(String reviewProfileVersion, String ruleVersion,
            String modelPolicyVersion, List<ContractRuleConfiguration> enabledF02Configurations) {
        public ResolvedPolicy {
            Objects.requireNonNull(reviewProfileVersion);
            Objects.requireNonNull(ruleVersion);
            Objects.requireNonNull(modelPolicyVersion);
            enabledF02Configurations = List.copyOf(enabledF02Configurations);
            if (reviewProfileVersion.isBlank() || ruleVersion.isBlank()
                    || modelPolicyVersion.isBlank()) throw new IllegalArgumentException("blank review policy version");
        }
    }

    private final ContractRuleConfigRepository source;
    private final F02RulePolicyFingerprint fingerprint;
    private final boolean semanticCheckpoints;
    private final String moneyMode;
    private final Set<UUID> observeProjects;
    private final String hierarchyVersion;

    public ContractReviewExecutionPolicyResolver(ContractRuleConfigRepository source,
            F02RulePolicyFingerprint fingerprint) {
        this(source,fingerprint,false);
    }

    public ContractReviewExecutionPolicyResolver(ContractRuleConfigRepository source,
            F02RulePolicyFingerprint fingerprint,
            boolean semanticCheckpoints) {
        this(source, fingerprint, semanticCheckpoints, "STRICT", "", new org.springframework.core.env.StandardEnvironment());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ContractReviewExecutionPolicyResolver(ContractRuleConfigRepository source,
            F02RulePolicyFingerprint fingerprint,
            @org.springframework.beans.factory.annotation.Value("${audit.contract.semantic-checkpoints.enabled:false}") boolean semanticCheckpoints,
            @org.springframework.beans.factory.annotation.Value("${AUDIT_CONTRACT_SEMANTIC_MONEY_MODE:STRICT}") String moneyMode,
            @org.springframework.beans.factory.annotation.Value("${AUDIT_CONTRACT_SEMANTIC_OBSERVE_PROJECT_IDS:}") String observeProjects,
            Environment environment) {
        this.source = source;
        this.fingerprint = fingerprint;
        this.semanticCheckpoints=semanticCheckpoints;
        this.moneyMode = com.fuquan.audit.contract.application.ai.ContractMonetaryObservation.requireMode(moneyMode);
        this.hierarchyVersion = environment.getProperty("AUDIT_CONTRACT_SEMANTIC_HIERARCHY_VERSION", "v1");
        if (!Set.of("v1", "v2").contains(hierarchyVersion))
            throw new IllegalArgumentException("unsupported contract semantic hierarchy version");
        this.observeProjects = observeProjects.isBlank() ? Set.of() : Arrays.stream(observeProjects.split(",", -1))
                .map(String::trim).map(value -> {
                    UUID project = UUID.fromString(value);
                    if (!project.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("invalid observe project identity");
                    return project;
                }).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if ("OBSERVE".equals(moneyMode) && (!semanticCheckpoints || this.observeProjects.isEmpty()
                || !environment.acceptsProfiles(Profiles.of("local-dev"))
                || environment.acceptsProfiles(Profiles.of("dev29", "customer"))))
            throw new IllegalArgumentException("contract semantic OBSERVE requires local-dev, explicit projects and durable checkpoints");
    }

    public ResolvedPolicy resolveForNewRun() {
        return resolveForNewRun(null);
    }

    public ResolvedPolicy resolveForNewRun(UUID projectId) {
        var enabled = fingerprint.enabledSorted(source.findEnabledForF02());
        return new ResolvedPolicy(REVIEW_PROFILE_VERSION, fingerprint.fingerprint(enabled),
                semanticCheckpoints ? "OBSERVE".equals(moneyMode) && projectId != null && observeProjects.contains(projectId)
                        ? ("v2".equals(hierarchyVersion) ? OBSERVE_HIERARCHY_V2_MODEL_POLICY_VERSION
                            : OBSERVE_HIERARCHY_MODEL_POLICY_VERSION) : DURABLE_MODEL_POLICY_VERSION : MODEL_POLICY_VERSION, enabled);
    }

    public static boolean isDurableModelPolicy(String policy) {
        return DURABLE_MODEL_POLICY_VERSION.equals(policy) || OBSERVE_MODEL_POLICY_VERSION.equals(policy)
                || OBSERVE_HIERARCHY_MODEL_POLICY_VERSION.equals(policy) || OBSERVE_HIERARCHY_V2_MODEL_POLICY_VERSION.equals(policy);
    }

    public static String moneyModeForPolicy(String policy) {
        return OBSERVE_MODEL_POLICY_VERSION.equals(policy) || OBSERVE_HIERARCHY_MODEL_POLICY_VERSION.equals(policy)
                || OBSERVE_HIERARCHY_V2_MODEL_POLICY_VERSION.equals(policy)
                ? "OBSERVE" : "STRICT";
    }

    public static String hierarchyProtocolForPolicy(String policy) {
        return OBSERVE_HIERARCHY_MODEL_POLICY_VERSION.equals(policy) ? HIERARCHY_PROTOCOL
                : OBSERVE_HIERARCHY_V2_MODEL_POLICY_VERSION.equals(policy) ? HIERARCHY_V2_PROTOCOL : null;
    }

    public ResolvedPolicy resolveCurrentF02() {
        return resolveForNewRun();
    }
}
