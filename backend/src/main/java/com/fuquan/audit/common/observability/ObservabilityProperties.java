package com.fuquan.audit.common.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("audit.observability")
public class ObservabilityProperties {

    private boolean flowDebugEnabled;

    public boolean isFlowDebugEnabled() {
        return flowDebugEnabled;
    }

    public void setFlowDebugEnabled(boolean flowDebugEnabled) {
        this.flowDebugEnabled = flowDebugEnabled;
    }
}
