package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.application.view.ConnectorViews;
import com.shitulelv.aicollab.connector.application.view.ConnectorViews.ExternalDocumentView;
import com.shitulelv.aicollab.connector.application.view.ConnectorViews.SourceView;
import com.shitulelv.aicollab.connector.application.view.ConnectorViews.SyncAccepted;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuApiException;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSourceMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSyncJobMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ExternalDocumentMapper;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.ProjectWriteGuard;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ConnectorSourceService {
    private final ConnectorSourceMapper sources;
    private final ConnectorConnectionMapper connections;
    private final ConnectorSyncJobMapper jobs;
    private final ExternalDocumentMapper externalDocs;
    private final FeishuClient feishu;
    private final com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher secrets;
    private final ProjectAccessGuard accessGuard;
    private final ProjectWriteGuard writeGuard;
    private final AuditService audit;
    private final ConnectorSyncService syncService;

    public ConnectorSourceService(ConnectorSourceMapper sources, ConnectorConnectionMapper connections,
                                  ConnectorSyncJobMapper jobs, ExternalDocumentMapper externalDocs,
                                  FeishuClient feishu,
                                  com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher secrets,
                                  ProjectAccessGuard accessGuard, ProjectWriteGuard writeGuard,
                                  AuditService audit, ConnectorSyncService syncService) {
        this.sources = sources;
        this.connections = connections;
        this.jobs = jobs;
        this.externalDocs = externalDocs;
        this.feishu = feishu;
        this.secrets = secrets;
        this.accessGuard = accessGuard;
        this.writeGuard = writeGuard;
        this.audit = audit;
        this.syncService = syncService;
    }

    public SourceView create(UUID projectId, UUID userId, UUID connectionId, String folderToken,
                             String name, boolean authorizationConfirmed) {
        accessGuard.requireAdmin(projectId, userId);
        writeGuard.requireWritable(projectId);
        if (!authorizationConfirmed) {
            throw new BusinessException(ErrorCode.CONNECTOR_AUTH_DECLARATION_REQUIRED);
        }
        if (folderToken == null || folderToken.isBlank() || name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件夹 Token 与名称不能为空");
        }
        ConnectorRows.ConnectionRow connection = connections.find(connectionId);
        if (connection == null || !"ACTIVE".equals(connection.status)) {
            throw new BusinessException(ErrorCode.CONNECTOR_CONNECTION_NOT_FOUND);
        }
        String token;
        try {
            token = feishu.tenantAccessToken(connection.appId, secrets.decrypt(connection.appSecretCipher));
        } catch (FeishuApiException exception) {
            feishu.invalidateToken(connection.appId);
            connections.updateStatus(connectionId, "INVALID", exception.getMessage(), null);
            throw new BusinessException(ErrorCode.CONNECTOR_CREDENTIAL_INVALID, exception.getMessage());
        }
        try {
            feishu.listFolderDocx(token, folderToken.trim());
        } catch (FeishuApiException exception) {
            if (exception.forbidden()) {
                throw new BusinessException(ErrorCode.CONNECTOR_FOLDER_NOT_ACCESSIBLE,
                        "应用无法读取该文件夹：" + exception.getMessage());
            }
            throw new BusinessException(ErrorCode.CONNECTOR_FOLDER_NOT_ACCESSIBLE,
                    "访问飞书失败：" + exception.getMessage());
        }
        feishu.folderPermissionMembers(token, folderToken.trim());
        UUID id = UUID.randomUUID();
        try {
            sources.insert(id, projectId, connectionId, folderToken.trim(), name.trim(), "ACTIVE",
                    userId, OffsetDateTime.now());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "该文件夹已添加为本项目知识源");
        }
        audit.write(projectId, userId, "CONNECTOR_SOURCE_ADDED", "CONNECTOR_SOURCE", id,
                java.util.Map.of("name", name.trim(), "connectionId", connectionId.toString()));
        return view(sources.find(id));
    }

    public List<SourceView> list(UUID projectId, UUID userId) {
        accessGuard.requireMember(projectId, userId);
        return sources.listByProject(projectId).stream().map(this::view).toList();
    }

    public List<ExternalDocumentView> documents(UUID projectId, UUID sourceId, UUID userId) {
        accessGuard.requireMember(projectId, userId);
        requireSourceInProject(projectId, sourceId);
        return externalDocs.listBySource(sourceId).stream()
                .map(row -> new ExternalDocumentView(row.id, row.externalKey, row.title, row.sourceUrl,
                        row.status, row.documentId, row.documentStatus))
                .toList();
    }

    public SyncAccepted triggerSync(UUID projectId, UUID sourceId, UUID userId, String triggerType) {
        accessGuard.requireAdmin(projectId, userId);
        writeGuard.requireWritable(projectId);
        ConnectorRows.SourceRow source = requireSourceInProject(projectId, sourceId);
        UUID jobId = syncService.trigger(sourceId, triggerType);
        return new SyncAccepted(jobId, "RUNNING");
    }

    public ConnectorViews.SyncJobView latestJob(UUID projectId, UUID sourceId, UUID userId) {
        accessGuard.requireMember(projectId, userId);
        requireSourceInProject(projectId, sourceId);
        List<ConnectorRows.JobRow> recent = jobs.listRecent(sourceId, 1);
        return recent.isEmpty() ? null : ConnectorViews.job(recent.getFirst());
    }

    private ConnectorRows.SourceRow requireSourceInProject(UUID projectId, UUID sourceId) {
        ConnectorRows.SourceRow row = sources.find(sourceId);
        if (row == null || !row.projectId.equals(projectId)) {
            throw new BusinessException(ErrorCode.CONNECTOR_SOURCE_NOT_FOUND);
        }
        return row;
    }

    private SourceView view(ConnectorRows.SourceRow row) {
        ConnectorRows.ConnectionRow connection = connections.find(row.connectionId);
        long count = externalDocs.listBySource(row.id).stream()
                .filter(doc -> "ACTIVE".equals(doc.status)).count();
        List<ConnectorRows.JobRow> recent = jobs.listRecent(row.id, 1);
        ConnectorViews.SyncJobView lastJob = recent.isEmpty() ? null : ConnectorViews.job(recent.getFirst());
        return new SourceView(row.id, row.projectId, row.connectionId,
                connection == null ? null : connection.name, row.folderToken, row.name, row.status,
                row.authorizationDeclaredAt, row.lastVerifiedAt, row.lastSyncedAt, count, lastJob);
    }
}
