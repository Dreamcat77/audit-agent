package com.fuquan.audit.common.application.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OperationAuditPortContractTest {

    @Test
    void exposesAppendOnlySemantics() {
        assertThat(Arrays.stream(OperationAuditPort.class.getDeclaredMethods())
                .map(method -> method.getName())
                .toList())
                .containsExactly("append")
                .noneMatch(name -> name.toLowerCase().contains("update")
                        || name.toLowerCase().contains("delete"));
    }
}
