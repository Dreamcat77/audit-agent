package com.fuquan.audit.contract.application.project;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionKeyFactoryV2Test {

    private static final UUID SOURCE_FILE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID PARSE_RESULT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Test
    void createsTheLengthPrefixedV2GoldenKeyInItsSpecifiedComponentOrder() {
        String key = new ExtractionKeyFactoryV2().create(SOURCE_FILE_ID, PARSE_RESULT_ID,
                "project-extraction-v2", "CONTRACT_NO", null, "page:1/occurrence:1");

        assertThat(key).isEqualTo("a9a5422a601ee11979f1319ffc4f50cc0fbba36b40221c364f326830125a774b");
    }

    @Test
    void bindsEveryV2ComponentAndDistinguishesTheNullGroupKeySentinel() {
        ExtractionKeyFactoryV2 factory = new ExtractionKeyFactoryV2();
        String key = factory.create(SOURCE_FILE_ID, PARSE_RESULT_ID, "project-extraction-v2",
                "CONTRACT_NO", null, "page:1/occurrence:1");

        assertThat(factory.create(UUID.fromString("20000000-0000-0000-0000-000000000004"), PARSE_RESULT_ID,
                "project-extraction-v2", "CONTRACT_NO", null, "page:1/occurrence:1")).isNotEqualTo(key);
        assertThat(factory.create(SOURCE_FILE_ID, UUID.fromString("30000000-0000-0000-0000-000000000005"),
                "project-extraction-v2", "CONTRACT_NO", null, "page:1/occurrence:1")).isNotEqualTo(key);
        assertThat(factory.create(SOURCE_FILE_ID, PARSE_RESULT_ID, "project-extraction-v3",
                "CONTRACT_NO", null, "page:1/occurrence:1")).isNotEqualTo(key);
        assertThat(factory.create(SOURCE_FILE_ID, PARSE_RESULT_ID, "project-extraction-v2",
                "PROJECT_NO", null, "page:1/occurrence:1")).isNotEqualTo(key);
        assertThat(factory.create(SOURCE_FILE_ID, PARSE_RESULT_ID, "project-extraction-v2",
                "CONTRACT_NO", "", "page:1/occurrence:1")).isNotEqualTo(key);
        assertThat(factory.create(SOURCE_FILE_ID, PARSE_RESULT_ID, "project-extraction-v2",
                "CONTRACT_NO", null, "page:1/occurrence:2")).isNotEqualTo(key);
    }
}
