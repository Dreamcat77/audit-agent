package com.fuquan.audit.contract.application.project.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PdfTextUsefulnessTest {

    private final PdfTextUsefulness usefulness = new PdfTextUsefulness();

    @Test
    void acceptsUsefulChineseAndLatinText() {
        assertThat(usefulness.isUseful("建设工程施工合同，承包范围与工期约定明确。\n签署日期：2026-08-31"))
                .isTrue();
        assertThat(usefulness.isUseful("Contract No C-2026-01\nDelivery period: 30 days"))
                .isTrue();
    }

    @Test
    void rejectsBlankControlOnlyAndObviouslyLowInformationOutput() {
        assertThat(usefulness.isUseful(null)).isFalse();
        assertThat(usefulness.isUseful("  \r\n\t\u0000\u0001  ")).isFalse();
        assertThat(usefulness.isUseful("--- /// ...")).isFalse();
        assertThat(usefulness.isUseful("A-1")).isFalse();
    }
}
