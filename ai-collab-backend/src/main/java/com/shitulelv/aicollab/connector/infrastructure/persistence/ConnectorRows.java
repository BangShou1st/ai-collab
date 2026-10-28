package com.shitulelv.aicollab.connector.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Plain row carriers for MyBatis annotation mappers. */
public final class ConnectorRows {
    private ConnectorRows() {}

    public static class ConnectionRow {
        public UUID id;
        public String provider;
        public String name;
        public String appId;
        public String appSecretCipher;
        public String status;
        public String lastError;
        public OffsetDateTime lastVerifiedAt;
        public UUID createdBy;
        public OffsetDateTime createdAt;
    }

    public static class SourceRow {
        public UUID id;
        public UUID projectId;
        public UUID connectionId;
        public String folderToken;
        public String name;
        public String status;
        public UUID authorizationDeclaredBy;
        public OffsetDateTime authorizationDeclaredAt;
        public OffsetDateTime lastVerifiedAt;
        public OffsetDateTime lastSyncedAt;
        public OffsetDateTime createdAt;
    }

    public static class ExternalDocRow {
        public UUID id;
        public UUID sourceId;
        public UUID projectId;
        public String externalKey;
        public String docType;
        public String title;
        public String sourceUrl;
        public Long latestModifyTime;
        public String contentHash;
        public UUID documentId;
        public String status;
        public String documentStatus;
    }

    public static class JobRow {
        public UUID id;
        public UUID sourceId;
        public String triggerType;
        public String status;
        public OffsetDateTime startedAt;
        public OffsetDateTime finishedAt;
        public int scannedCount;
        public int updatedCount;
        public int skippedCount;
        public int failedCount;
        public int removedCount;
        public String errorSummary;
    }
}
