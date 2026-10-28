package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Low-frequency snapshot diff for every ACTIVE source; polling disabled at interval <= 0. */
@Component
public class ConnectorSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(ConnectorSyncScheduler.class);
    private final ConnectorSourceMapper sources;
    private final ConnectorSyncService syncService;
    private final ConnectorProperties properties;

    public ConnectorSyncScheduler(ConnectorSourceMapper sources, ConnectorSyncService syncService,
                                  ConnectorProperties properties) {
        this.sources = sources;
        this.syncService = syncService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${connector.sync.poll-interval-ms:1800000}",
               initialDelayString = "${connector.sync.poll-initial-delay-ms:120000}")
    public void pollSources() {
        if (!properties.pollingEnabled()) return;
        List<ConnectorRows.SourceRow> active = sources.listActive();
        for (ConnectorRows.SourceRow source : active) {
            try {
                syncService.trigger(source.id, "SCHEDULED");
            } catch (BusinessException exception) {
                if (exception.getErrorCode() != ErrorCode.CONNECTOR_SYNC_ALREADY_RUNNING) {
                    log.warn("定时同步触发失败，sourceId={}，原因={}", source.id, exception.getMessage());
                }
            }
        }
    }
}
