package com.shitulelv.aicollab.connector.application.view;

import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Read models for the connector module; never exposes secrets. */
public final class ConnectorViews {
    private ConnectorViews() {}

    public record ConnectionView(UUID id, String name, String appId, String status,
                                 OffsetDateTime lastVerifiedAt, String lastError, OffsetDateTime createdAt) {
    }

    public record SyncJobView(UUID id, String triggerType, String status, OffsetDateTime startedAt,
                              OffsetDateTime finishedAt, int scanned, int updated, int skipped,
                              int failed, int removed, String errorSummary) {
    }

    public record SourceView(UUID id, UUID projectId, UUID connectionId, String connectionName,
                             String folderToken, String name, String status,
                             OffsetDateTime authorizationDeclaredAt, OffsetDateTime lastVerifiedAt,
                             OffsetDateTime lastSyncedAt, long documentCount, SyncJobView lastJob) {
    }

    public record ExternalDocumentView(UUID id, String externalKey, String title, String sourceUrl,
                                       String status, UUID documentId, String documentStatus) {
    }

    public record SourceList(List<SourceView> sources) {
    }

    public record SourceDocuments(List<ExternalDocumentView> documents) {
    }

    public record SyncAccepted(UUID jobId, String status) {
    }

    public static SyncJobView job(ConnectorRows.JobRow row) {
        return new SyncJobView(row.id, row.triggerType, row.status, row.startedAt, row.finishedAt,
                row.scannedCount, row.updatedCount, row.skippedCount, row.failedCount,
                row.removedCount, row.errorSummary);
    }
}
