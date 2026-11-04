package com.fuquan.audit.security.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordStorageSafetyTest {

    @Test
    void delegatingEncoderUsesBcryptAndAccountStringRedactsHash() {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        String runtimePassword = UUID.randomUUID().toString();
        String hash = encoder.encode(runtimePassword);
        Instant now = Instant.now();
        UserAccount account = new UserAccount(
                UUID.randomUUID().toString(),
                "hash-test-user",
                hash,
                "Hash Test",
                UserAccountStatus.ACTIVE,
                0,
                null,
                null,
                now,
                now,
                now,
                0,
                Set.of("AUDITOR"));

        assertThat(hash).startsWith("{bcrypt}");
        assertThat(encoder.matches(runtimePassword, hash)).isTrue();
        assertThat(account.toString()).contains("passwordHash=***").doesNotContain(hash);
    }
}
