package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UserAccountMapperXmlTest {

    @Test
    void failedCountIsIncrementedAtomicallyAndPasswordHashIsNeverUpdatedByLogin() throws Exception {
        String xml;
        try (var stream = getClass().getResourceAsStream("/mappers/dm8/UserAccountMapper.xml")) {
            assertThat(stream).isNotNull();
            xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(xml).contains("FAILED_LOGIN_COUNT = FAILED_LOGIN_COUNT + 1")
                .contains("VERSION_NO = VERSION_NO + 1")
                .contains("FAILED_LOGIN_COUNT = 0")
                .doesNotContain("SET PASSWORD_HASH")
                .doesNotContainPattern("DELETE\\s+FROM\\s+SYS_USER(?!_)");
    }

    @Test
    void findByIdUsesOnlyTheExistingSysUserRowContract() throws Exception {
        String xml;
        try (var stream = getClass().getResourceAsStream("/mappers/dm8/UserAccountMapper.xml")) {
            assertThat(stream).isNotNull();
            xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(xml).contains("<select id=\"findById\" resultMap=\"userAccountRow\">")
                .contains("FROM SYS_USER")
                .contains("WHERE ID = #{id}")
                .contains("REAL_NAME")
                .doesNotContain("USER_IDENTITY")
                .doesNotContain("USER_DISPLAY_NAME");
    }
}
