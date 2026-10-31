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
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorOAuthRepository;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentVisibilityRepository;
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
    private final ConnectorOAuthRepository grants;
    private final ConnectorOAuthTokenService userTokens;
    private final DocumentVisibilityRepository documentVisibility;
    private final ConnectorConnectionService connectionService;

    public ConnectorSourceService(ConnectorSourceMapper sources, ConnectorConnectionMapper connections,
                                  ConnectorSyncJobMapper jobs, ExternalDocumentMapper externalDocs,
                                  FeishuClient feishu,
                                  com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher secrets,
                                  ProjectAccessGuard accessGuard, ProjectWriteGuard writeGuard,
                                  AuditService audit, ConnectorSyncService syncService,
                                  ConnectorOAuthRepository grants, ConnectorOAuthTokenService userTokens,
                                  DocumentVisibilityRepository documentVisibility,
                                  ConnectorConnectionService connectionService) {
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
        this.grants = grants;
        this.userTokens = userTokens;
        this.documentVisibility = documentVisibility;
        this.connectionService = connectionService;
    }

    public SourceView create(UUID projectId, UUID userId, UUID connectionId, String folderToken,
                             String name, boolean authorizationConfirmed, UUID userAuthorizationId) {
        accessGuard.requireAdmin(projectId, userId);
        writeGuard.requireWritable(projectId);
        if (!authorizationConfirmed) {
            throw new BusinessException(ErrorCode.CONNECTOR_AUTH_DECLARATION_REQUIRED);
        }
        if (folderToken == null || folderToken.isBlank() || name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件夹 Token 与名称不能为空");
        }
        String trimmedToken = folderToken.trim();
        // 兼容旧客户端的原始 folderToken；同时拒绝明显的非法输入，真实读取权限仍由飞书接口验证。
        if (!trimmedToken.matches("[A-Za-z0-9_-]{6,128}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "文件夹 Token 格式不正确，请粘贴飞书文件夹链接或输入原始 folderToken");
        }
        ConnectorRows.ConnectionRow connection = connectionService.requireAvailableForProject(projectId, connectionId);
        String token;
        try {
            if (userAuthorizationId != null) {
                var grant = grants.find(projectId, userAuthorizationId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTOR_REAUTH_REQUIRED));
                if (!grant.userId().equals(userId) || !grant.connectionId().equals(connectionId)) {
                    throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
                }
                token = userTokens.accessToken(projectId, userAuthorizationId);
            } else {
                token = feishu.tenantAccessToken(connection.appId, secrets.decrypt(connection.appSecretCipher));
            }
        } catch (FeishuApiException exception) {
            feishu.invalidateToken(connection.appId);
            if (exception.invalidApplicationCredentials()) {
                connections.updateStatus(connectionId, "INVALID", exception.getMessage(), null);
            }
            throw new BusinessException(ErrorCode.CONNECTOR_CREDENTIAL_INVALID, exception.getMessage());
        }
        try {
            feishu.listFolderDocx(token, trimmedToken);
        } catch (FeishuApiException exception) {
            if (exception.forbidden()) {
                throw new BusinessException(ErrorCode.CONNECTOR_FOLDER_NOT_ACCESSIBLE,
                        "应用无法读取该文件夹：" + exception.getMessage());
            }
            throw new BusinessException(ErrorCode.CONNECTOR_FOLDER_NOT_ACCESSIBLE,
                    "访问飞书失败：" + exception.getMessage());
        }
        feishu.folderPermissionMembers(token, trimmedToken);
        UUID id = UUID.randomUUID();
        try {
            sources.insert(id, projectId, connectionId, trimmedToken, name.trim(), "ACTIVE",
                    userId, OffsetDateTime.now(), userAuthorizationId);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
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
                .filter(row -> row.documentId != null && documentVisibility.readable(projectId, row.documentId))
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
        boolean visible = "ACTIVE".equals(row.status) && (row.userAuthorizationId == null ||
                grants.find(row.projectId, row.userAuthorizationId).filter(grant -> "ACTIVE".equals(grant.status())
                        && grant.accessExpiresAt() != null && grant.accessExpiresAt().isAfter(java.time.Instant.now())).isPresent());
        long count = externalDocs.listBySource(row.id).stream()
                .filter(doc -> visible && "ACTIVE".equals(doc.status)).count();
        List<ConnectorRows.JobRow> recent = jobs.listRecent(row.id, 1);
        ConnectorViews.SyncJobView lastJob = recent.isEmpty() ? null : ConnectorViews.job(recent.getFirst());
        return new SourceView(row.id, row.projectId, row.connectionId,
                connection == null ? null : connection.name, visible ? row.folderToken : null, row.name, visible ? row.status : "BLOCKED",
                row.authorizationDeclaredAt, row.lastVerifiedAt, row.lastSyncedAt, count, lastJob, row.userAuthorizationId);
    }
}
