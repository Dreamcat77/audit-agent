package com.fuquan.audit.contract.application.project.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectIdentityNormalizerTest {
    @Test
    void normalizesWidthWhitespaceAndSeparatorSpacingWithoutDiscardingMeaningfulNumberCharacters() {
        assertThat(ProjectIdentityNormalizer.projectNo("  ＧＺＵ－２０２６－００１  "))
                .isEqualTo("GZU-2026-001");
        assertThat(ProjectIdentityNormalizer.projectNo("GZU - 2026 - 001A"))
                .isEqualTo("GZU-2026-001A");
        assertThat(ProjectIdentityNormalizer.projectNo("GZU/2026/001"))
                .isNotEqualTo(ProjectIdentityNormalizer.projectNo("GZU-2026-001"));
        assertThat(ProjectIdentityNormalizer.projectNo("GZU-2026-001A"))
                .isNotEqualTo(ProjectIdentityNormalizer.projectNo("GZU-2026-001"));
    }

    @Test
    void normalizesNameExactlyWithoutFuzzyAliasOrSuffixGuessing() {
        assertThat(ProjectIdentityNormalizer.projectName("　贵州大学　实验室设备采购项目　"))
                .isEqualTo("贵州大学 实验室设备采购项目");
        assertThat(ProjectIdentityNormalizer.projectName("贵州大学实验室设备采购项目"))
                .isNotEqualTo(ProjectIdentityNormalizer.projectName("贵州大学实验设备采购项目"));
        assertThat(ProjectIdentityNormalizer.projectName("  ")).isNull();
        assertThat(ProjectIdentityNormalizer.projectNo(null)).isNull();
    }
}
