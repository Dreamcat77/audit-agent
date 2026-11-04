package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.integration.http.FastApiHttpClientConfiguration;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

class ContractReviewWorkerConfigurationTest {

    @Test
    void defaultWorkerTimeoutCoversTheBoundedMultiCallSemanticWindow() {
        var configuredValues = Arrays.stream(ContractReviewWorkerConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("contractReviewWorker"))
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .map(parameter -> parameter.getAnnotation(Value.class))
                .filter(annotation -> annotation != null)
                .map(Value::value)
                .toList();

        String workerTimeout = configuredValues.stream()
                .filter(value -> value.startsWith("${audit.contract.review-worker.timeout:"))
                .findFirst()
                .orElseThrow();
        String aiReadTimeout = Arrays.stream(FastApiHttpClientConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("aiRestClient"))
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .map(parameter -> parameter.getAnnotation(Value.class))
                .filter(annotation -> annotation != null)
                .map(Value::value)
                .filter(value -> value.startsWith("${audit.ai.read-timeout-ms:"))
                .findFirst()
                .orElseThrow();

        Duration worker = Duration.parse(defaultValue(workerTimeout));
        Duration backendAiRead = Duration.ofMillis(Long.parseLong(defaultValue(aiReadTimeout)));

        assertThat(worker).isEqualTo(Duration.ofMinutes(20));
        assertThat(backendAiRead).isEqualTo(Duration.ofMinutes(16));
        assertThat(backendAiRead).isLessThan(worker);
    }

    private static String defaultValue(String placeholder) {
        return placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1);
    }
}
