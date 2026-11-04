package com.fuquan.audit.common.observability;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FlowDebugLogger {

    static final String SUPPRESSED_WARNING =
            "flow debug is enabled but DEBUG logger level is disabled; flow events will be suppressed";
    private static final Logger DEFAULT_LOGGER = LoggerFactory.getLogger(FlowDebugLogger.class);
    private static final Pattern SAFE_AI_IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");
    private final ObservabilityProperties properties;
    private final Logger log;

    @Autowired
    public FlowDebugLogger(ObservabilityProperties properties) {
        this(properties, DEFAULT_LOGGER);
    }

    FlowDebugLogger(ObservabilityProperties properties, Logger log) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.log = Objects.requireNonNull(log, "logger must not be null");
        if (properties.isFlowDebugEnabled() && !log.isDebugEnabled()) {
            log.warn(SUPPRESSED_WARNING);
        }
    }

    public boolean isEnabled() {
        return properties.isFlowDebugEnabled() && log.isDebugEnabled();
    }

    public void log(String event, String module, String layer, String operation) {
        log(event, module, layer, operation, Map.of());
    }

    public void log(
            String event,
            String module,
            String layer,
            String operation,
            Map<String, ?> additionalFields) {
        if (!isEnabled()) {
            return;
        }
        LoggingEventBuilder builder = log.atDebug()
                .addKeyValue("event", Objects.requireNonNull(event, "event must not be null"))
                .addKeyValue("module", Objects.requireNonNull(module, "module must not be null"))
                .addKeyValue("layer", Objects.requireNonNull(layer, "layer must not be null"))
                .addKeyValue("operation", Objects.requireNonNull(operation, "operation must not be null"));
        additionalFields.forEach(builder::addKeyValue);
        String fields = additionalFields.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(" "));
        builder.log(
                "{} module={} layer={} operation={} {}",
                event,
                module,
                layer,
                operation,
                fields);
    }

    public void logAiAnalysis(
            String event,
            String module,
            String operation,
            String resourceId,
            String outcome,
            String model,
            long durationMs) {
        logAiAnalysis(event, module, operation, resourceId, outcome, model, durationMs, 0, 0);
    }

    public void logAiAnalysis(
            String event,
            String module,
            String operation,
            String resourceId,
            String outcome,
            String model,
            long durationMs,
            int selectedFileCount,
            int checkCount) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be non-negative");
        }
        if (selectedFileCount < 0 || checkCount < 0) {
            throw new IllegalArgumentException("AI counts must be non-negative");
        }
        log(safeAiIdentifier(event, "event"),
                safeAiIdentifier(module, "module"),
                "APPLICATION",
                safeAiIdentifier(operation, "operation"),
                Map.of(
                        "resourceId", safeAiIdentifier(resourceId, "resourceId"),
                        "outcome", safeAiIdentifier(outcome, "outcome"),
                        "model", safeAiIdentifier(model, "model"),
                        "durationMs", durationMs,
                        "selectedFileCount", selectedFileCount,
                        "checkCount", checkCount));
    }

    private static String safeAiIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!SAFE_AI_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a safe identifier");
        }
        return value;
    }
}
