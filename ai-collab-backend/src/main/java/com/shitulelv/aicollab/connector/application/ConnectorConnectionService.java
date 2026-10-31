package com.shitulelv.aicollab.connector.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.connector.application.view.ConnectorViews.ConnectionView;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuApiException;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows.ConnectionRow;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSourceMapper;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorEventCredentialRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ConnectorConnectionService {
    private final ConnectorConnectionMapper connections;
    private final ConnectorSourceMapper sources;
    private final FeishuClient feishu;
    private final ModelSecretCipher secrets;
    private final UserService users;
    private final ConnectorEventCredentialRepository eventCredentials;
    private final ConnectorVisibilityService visibility;
    private final ProjectAccessGuard access;

    public ConnectorConnectionService(ConnectorConnectionMapper connections, ConnectorSourceMapper sources,
                                      FeishuClient feishu, ModelSecretCipher secrets, UserService users,
                                      ConnectorEventCredentialRepository eventCredentials, ConnectorVisibilityService visibility,
                                      ProjectAccessGuard access) {
        this.connections = connections;
        this.sources = sources;
        this.feishu = feishu;
        this.secrets = secrets;
        this.users = users;
        this.eventCredentials = eventCredentials;
        this.visibility = visibility;
        this.access = access;
    }

    /**
     * projectId 为空时创建系统级连接（仅系统管理员，全租户项目可用）；
     * 否则创建项目级连接（项目 Owner/Admin），仅归属项目可见可用。凭据一律加密只写不读。
     */
    public ConnectionView create(UUID userId, UUID projectId, String name, String appId, String appSecret) {
        String visibility;
        UUID ownerProjectId;
        if (projectId == null) {
            users.requireSystemAdmin(userId);
            visibility = "SYSTEM";
            ownerProjectId = null;
        } else {
            access.requireAdmin(projectId, userId);
            visibility = "PROJECT";
            ownerProjectId = projectId;
        }
        if (name == null || name.isBlank() || appId == null || appId.isBlank()
                || appSecret == null || appSecret.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "连接名称、App ID 与 App Secret 不能为空");
        }
        // checkConnection: only accept credentials that can actually fetch a token.
        try {
            feishu.tenantAccessToken(appId.trim(), appSecret.trim());
        } catch (FeishuApiException exception) {
            throw new BusinessException(ErrorCode.CONNECTOR_CREDENTIAL_INVALID, exception.getMessage());
        }
        UUID id = UUID.randomUUID();
        try {
            connections.insert(id, "feishu", name.trim(), appId.trim(), secrets.encrypt(appSecret.trim()),
                    "ACTIVE", OffsetDateTime.now(), userId, visibility, ownerProjectId);
        } catch (DataIntegrityViolationException exception) {
            // (provider, app_id) 全局唯一：同一飞书应用只能有一个连接，无论作用域。
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "该 App ID 已存在连接，同一飞书应用不能重复创建");
        }
        return view(connections.find(id));
    }

    public List<ConnectionView> list(UUID userId) {
        users.requireSystemAdmin(userId);
        return connections.listSystem().stream().map(ConnectorConnectionService::view).toList();
    }

    @Transactional
    public void configureEvents(UUID userId, UUID connectionId, String encryptKey, String verificationToken) {
        ConnectionRow row = require(connectionId);
        requireManageable(userId, row);
        if (encryptKey == null || encryptKey.length() < 16 || verificationToken == null || verificationToken.length() < 16) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        eventCredentials.save(connectionId, secrets.encrypt(encryptKey), secrets.encrypt(verificationToken))
                .forEach(grant -> visibility.blockAuthorization(grant.projectId(), grant.authorizationId(), false));
    }

    public ConnectionView verify(UUID userId, UUID connectionId) {
        ConnectionRow row = require(connectionId);
        requireManageable(userId, row);
        try {
            feishu.tenantAccessToken(row.appId, secrets.decrypt(row.appSecretCipher));
            connections.updateStatus(connectionId, "ACTIVE", null, OffsetDateTime.now());
        } catch (FeishuApiException exception) {
            feishu.invalidateToken(row.appId);
            if (exception.invalidApplicationCredentials()) {
                connections.updateStatus(connectionId, "INVALID", exception.getMessage(), null);
            }
            throw new BusinessException(ErrorCode.CONNECTOR_CREDENTIAL_INVALID, exception.getMessage());
        }
        return view(connections.find(connectionId));
    }

    public void delete(UUID userId, UUID connectionId) {
        ConnectionRow row = require(connectionId);
        requireManageable(userId, row);
        if (sources.countByConnection(connectionId) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "该连接仍被知识源引用，请先删除对应知识源");
        }
        connections.delete(connectionId);
    }

    ConnectionRow require(UUID id) {
        ConnectionRow row = connections.find(id);
        if (row == null) throw new BusinessException(ErrorCode.CONNECTOR_CONNECTION_NOT_FOUND);
        return row;
    }

    private void requireManageable(UUID userId, ConnectionRow row) {
        if ("PROJECT".equals(row.visibility)) {
            access.requireAdmin(row.ownerProjectId, userId);
        } else {
            users.requireSystemAdmin(userId);
        }
    }

    private static ConnectionView view(ConnectionRow row) {
        return new ConnectionView(row.id, row.name, row.appId, row.status,
                row.lastVerifiedAt, row.lastError, row.createdAt);
    }
}
