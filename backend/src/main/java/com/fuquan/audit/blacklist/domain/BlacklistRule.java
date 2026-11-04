package com.fuquan.audit.blacklist.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F03 报警规则配置。
 */
public record BlacklistRule(
        UUID id,
        String ruleName,
        String ruleType,
        String blacklistTypes,
        String alertLevel,
        boolean autoResolve,
        boolean active,
        Instant createdTime,
        Instant updatedTime) {

    public Set<String> typeSet() {
        if (blacklistTypes == null || blacklistTypes.isBlank()) return Set.of();
        return Arrays.stream(blacklistTypes.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean appliesTo(String blacklistType) {
        return typeSet().isEmpty() || typeSet().contains(blacklistType);
    }
}
