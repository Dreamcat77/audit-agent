package com.fuquan.audit.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort;
import com.fuquan.audit.integration.ai.fake.FakeAiReviewAdapter;
import com.fuquan.audit.integration.ai.fastapi.FastApiAiReviewAdapter;
import com.fuquan.audit.integration.http.FastApiHttpClientConfiguration;
import com.fuquan.audit.integration.ocr.fake.FakeOcrAdapter;
import com.fuquan.audit.integration.ocr.fastapi.FastApiOcrAdapter;
import com.fuquan.audit.review.application.port.OcrPort;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;
import org.slf4j.LoggerFactory;

class AiOcrAdapterProfileWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    FastApiHttpClientConfiguration.class,
                    FastApiOcrAdapter.class,
                    FastApiAiReviewAdapter.class,
                    FakeOcrAdapter.class,
                    FakeAiReviewAdapter.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withPropertyValues(
                    "audit.ocr.base-url=http://127.0.0.1:18001",
                    "audit.ai.base-url=http://127.0.0.1:18002");

    @Test
    void localUsesOnlyFakeAdapters() {
        assertAdapters("local", FakeOcrAdapter.class, FakeAiReviewAdapter.class);
    }

    @Test
    void localDevUsesOnlyFakeAdapters() {
        assertAdapters("local-dev", FakeOcrAdapter.class, FakeAiReviewAdapter.class);
    }

    @Test
    void localDevWithLocalAiOverlayUsesOnlyRealAdapters() {
        assertAdapters("local-dev,local-ai", FastApiOcrAdapter.class, FastApiAiReviewAdapter.class);
    }

    @Test
    void deployedProfilesUseOnlyRealAdapters() {
        assertAdapters("dev29", FastApiOcrAdapter.class, FastApiAiReviewAdapter.class);
        assertAdapters("customer", FastApiOcrAdapter.class, FastApiAiReviewAdapter.class);
    }

    @Test
    void localAiYamlIsAMinimalOverlayWithDemoSafeExternalizedTimeouts() throws IOException {
        ClassPathResource resource = new ClassPathResource("application-local-ai.yml");
        assertThat(resource.exists()).isTrue();

        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("local-ai", resource);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0)).isInstanceOf(EnumerablePropertySource.class);
        EnumerablePropertySource<?> source = (EnumerablePropertySource<?>) sources.get(0);
        assertThat(source.getPropertyNames()).containsExactlyInAnyOrder(
                "audit.ai.base-url",
                "audit.ai.connect-timeout-ms",
                "audit.ai.read-timeout-ms",
                "audit.ocr.base-url",
                "audit.ocr.connect-timeout-ms",
                "audit.ocr.read-timeout-ms");
        assertThat(source.getProperty("audit.ai.base-url")).isEqualTo("${AUDIT_AI_BASE_URL}");
        assertThat(source.getProperty("audit.ai.connect-timeout-ms"))
                .isEqualTo("${AUDIT_AI_CONNECT_TIMEOUT_MS:5000}");
        assertThat(source.getProperty("audit.ai.read-timeout-ms"))
                .isEqualTo("${AUDIT_AI_READ_TIMEOUT_MS:960000}");
        assertThat(source.getProperty("audit.ocr.base-url")).isEqualTo("${AUDIT_OCR_BASE_URL}");
        assertThat(source.getProperty("audit.ocr.connect-timeout-ms"))
                .isEqualTo("${AUDIT_OCR_CONNECT_TIMEOUT_MS:5000}");
        assertThat(source.getProperty("audit.ocr.read-timeout-ms"))
                .isEqualTo("${AUDIT_OCR_READ_TIMEOUT_MS:1900000}");
    }

    @Test
    void applicationYamlKeepsAiAndOcrOuterTimeoutDefaultsAlignedWithRuntimeBudget() throws IOException {
        ClassPathResource resource = new ClassPathResource("application.yml");
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", resource);
        assertThat(sources).hasSize(1);
        EnumerablePropertySource<?> source = (EnumerablePropertySource<?>) sources.get(0);

        assertThat(source.getProperty("audit.ai.connect-timeout-ms"))
                .isEqualTo("${AUDIT_AI_CONNECT_TIMEOUT_MS:5000}");
        assertThat(source.getProperty("audit.ai.read-timeout-ms"))
                .isEqualTo("${AUDIT_AI_READ_TIMEOUT_MS:960000}");
        assertThat(source.getProperty("audit.ocr.connect-timeout-ms"))
                .isEqualTo("${AUDIT_OCR_CONNECT_TIMEOUT_MS:5000}");
        assertThat(source.getProperty("audit.ocr.read-timeout-ms"))
                .isEqualTo("${AUDIT_OCR_READ_TIMEOUT_MS:1900000}");
    }

    @Test
    void realAdapterStartupLogsOnlyResolvedTimeoutValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(FastApiHttpClientConfiguration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            contextRunner
                    .withInitializer(context -> context.getEnvironment()
                            .setActiveProfiles("local-dev", "local-ai"))
                    .run(context -> assertThat(context).hasNotFailed());

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .contains(
                            "FastAPI OCR client configured connectTimeoutMs=5000 readTimeoutMs=1900000",
                            "FastAPI AI client configured connectTimeoutMs=5000 readTimeoutMs=960000")
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain("127.0.0.1", "baseUrl", "password", "apiKey", "token"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void assertAdapters(String profiles, Class<?> ocrType, Class<?> aiType) {
        contextRunner
                .withInitializer(context -> context.getEnvironment()
                        .setActiveProfiles(profiles.split(",")))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OcrPort.class);
                    assertThat(context).hasSingleBean(ContractTextAnalysisPort.class);
                    assertThat(context).hasSingleBean(BudgetSemanticAnalysisPort.class);
                    assertThat(context.getBean(OcrPort.class)).isExactlyInstanceOf(ocrType);
                    assertThat(context.getBean(ContractTextAnalysisPort.class)).isExactlyInstanceOf(aiType);
                    assertThat(context.getBean(BudgetSemanticAnalysisPort.class))
                            .isSameAs(context.getBean(ContractTextAnalysisPort.class));
                });
    }
}
