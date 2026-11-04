package com.fuquan.audit.security.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.util.SerializationUtils;

class CurrentUserTest {

    @Test
    void principalCanBeSerializedForSpringSessionRedis() {
        CurrentUser user = new CurrentUser(
                "user-1", "auditor", "Audit User", Set.of("AUDITOR", "LEGAL"));

        byte[] serialized = SerializationUtils.serialize(user);
        Object restored = SerializationUtils.deserialize(serialized);

        assertThat(restored).isEqualTo(user);
    }
}
