package com.shitulelv.aicollab.connector.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConnectorProperties {
    /** 0 disables scheduled polling; manual sync always stays available. */
    @Value("${connector.sync.poll-interval-ms:1800000}")
    private long pollIntervalMs;

    public long pollIntervalMs() {
        return pollIntervalMs;
    }

    public boolean pollingEnabled() {
        return pollIntervalMs > 0;
    }
}
