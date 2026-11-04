package com.fuquan.audit.common.config;

import java.util.Arrays;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ActiveProfileGuard {

    public static final String ERROR_MESSAGE =
            "Exactly one runtime profile must be active: local, local-dev, dev29, customer";
    private static final Set<String> RUNTIME_PROFILES = Set.of("local", "local-dev", "dev29", "customer");

    public ActiveProfileGuard(Environment environment) {
        long activeRuntimeProfiles = Arrays.stream(environment.getActiveProfiles())
                .filter(RUNTIME_PROFILES::contains)
                .distinct()
                .count();
        if (activeRuntimeProfiles != 1) {
            throw new IllegalStateException(ERROR_MESSAGE);
        }
    }
}
