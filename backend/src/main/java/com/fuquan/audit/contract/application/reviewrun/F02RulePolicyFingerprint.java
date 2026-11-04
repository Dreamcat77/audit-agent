package com.fuquan.audit.contract.application.reviewrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Execution-authority version, not a timestamp or database row-order hash. */
@Component
public class F02RulePolicyFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String PREFIX = "f02-policy-v2:";
    public static final String FROZEN_SOURCE_POLICY = "f02-frozen-evidence-v3";

    public String fingerprint(List<ContractRuleConfiguration> configurations) {
        List<ContractRuleConfiguration> enabled = enabledSorted(configurations);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("enginePolicySchemaVersion", 2);
        manifest.put("frozenSourcePolicy", FROZEN_SOURCE_POLICY);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (var configuration : enabled) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("ruleCode", configuration.ruleCode());
            rule.put("enabled", configuration.enabled());
            rule.put("failLevel", configuration.failLevel().name());
            rule.put("blockSign", configuration.blockSign());
            rule.put("versionNo", configuration.versionNo());
            rules.add(rule);
        }
        manifest.put("rules", rules);
        try {
            return PREFIX + ContractSnapshotCanonicalizer.sha256(JSON.writeValueAsString(manifest));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("F02 policy serialization failed", failure);
        }
    }

    public List<ContractRuleConfiguration> enabledSorted(List<ContractRuleConfiguration> configurations) {
        if (configurations == null) throw conflict();
        var enabled = configurations.stream().filter(ContractRuleConfiguration::enabled)
                .sorted(Comparator.comparing(ContractRuleConfiguration::ruleCode)).toList();
        if (enabled.isEmpty()) throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                "no F02 rule is enabled");
        Set<String> codes = new HashSet<>();
        for (var rule : enabled) if (!codes.add(rule.ruleCode())) throw conflict();
        return enabled;
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT, "F02 rule policy is ambiguous");
    }
}
