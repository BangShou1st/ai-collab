package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpConnectionRequest;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpConnectionView;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpProviderAdmissionRequest;
import com.shitulelv.aicollab.agent.infrastructure.mcp.api.McpProviderAdmissionView;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class McpAdministrationService {
    private final ProjectAccessGuard access;
    private final McpRepository repository;
    private final McpClientFactory clients;
    private final McpConnectionManager manager;
    private final ModelSecretCipher secrets;
    private final McpEndpointPolicy endpoints;
    private final ObjectMapper json;
    private final AuditService audit;

    public McpAdministrationService(ProjectAccessGuard access,
            McpRepository repository, McpClientFactory clients, McpConnectionManager manager,
            ModelSecretCipher secrets, McpEndpointPolicy endpoints, ObjectMapper json, AuditService audit) {
        this.access = access; this.repository = repository;
        this.clients = clients; this.manager = manager; this.secrets = secrets;
        this.endpoints = endpoints; this.json = json; this.audit = audit;
    }

    public List<McpConnectionView> list(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return repository.listByProject(projectId).stream().map(McpConnectionView::from).toList();
    }

    @Transactional
    public McpConnectionView create(UUID projectId, McpConnectionRequest request, UUID userId) {
        access.requireAdmin(projectId, userId); validate(request, true);
        McpConnection value = toConnection(UUID.randomUUID(), projectId, request,
                encrypt(request.credential(), request.authType()), userId);
        McpConnectionView created = McpConnectionView.from(repository.create(value));
        audit(projectId, userId, "AGENT_MCP_CONNECTION_CREATED", created.id(), created.code());
        return created;
    }

    @Transactional
    public McpConnectionView update(UUID projectId, UUID id, McpConnectionRequest request, UUID userId) {
        access.requireAdmin(projectId, userId); validate(request, false);
        McpConnection existing = require(id);
        if (!existing.projectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_NOT_FOUND);
        }
        String encrypted = request.credential() == null || request.credential().isBlank()
                ? existing.credentialCiphertext() : encrypt(request.credential(), request.authType());
        McpConnection value = toConnection(id, projectId, request, encrypted, existing.createdBy());
        if (!repository.update(value, request.version())) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        repository.revokeAdmissionOnConnectionChange(id);
        manager.invalidate(projectId, id);
        audit(projectId, userId, "AGENT_MCP_CONNECTION_UPDATED", id, request.code());
        return McpConnectionView.from(require(id));
    }

    public McpConnectionView test(UUID projectId, UUID id, UUID userId) {
        access.requireAdmin(projectId, userId); McpConnection connection = require(id);
        if (!connection.projectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_NOT_FOUND);
        }
        try (McpClientFacade client = clients.create(connection)) {
            McpClientFacade.ServerInfo info = client.initialize();
            repository.health(id, "HEALTHY", "已连接 " + info.name());
        } catch (RuntimeException exception) {
            repository.health(id, "UNHEALTHY", "连接失败"); throw exception;
        }
        return McpConnectionView.from(require(id));
    }

    public McpConnectionView discover(UUID projectId, UUID id, UUID userId) {
        access.requireAdmin(projectId, userId); McpConnection connection = require(id);
        if (!connection.projectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_NOT_FOUND);
        }
        try (McpClientFacade client = clients.create(connection)) {
            client.initialize();
            List<McpClientFacade.DiscoveredTool> tools = client.listTools().stream()
                    .sorted(Comparator.comparing(McpClientFacade.DiscoveredTool::name)).toList();
            validateDiscoveredTools(tools);
            List<McpClientFacade.DiscoveredResource> resources;
            try { resources = client.listResources().stream()
                    .sorted(Comparator.comparing(McpClientFacade.DiscoveredResource::uri)).toList(); }
            catch (BusinessException unsupported) { resources = List.of(); }
            ArrayNode toolJson = json.valueToTree(tools);
            ArrayNode resourceJson = json.valueToTree(resources);
            String hash = sha256(json.writeValueAsBytes(toolJson));
            boolean changed = connection.confirmedSchemaHash() != null
                    && !connection.confirmedSchemaHash().equals(hash);
            manager.invalidate(projectId, id);
            McpConnectionView discovered = McpConnectionView.from(repository.discovered(id,
                    json.writeValueAsString(toolJson), json.writeValueAsString(resourceJson), hash, changed));
            audit(projectId, userId, changed ? "AGENT_MCP_SCHEMA_CHANGED" : "AGENT_MCP_CONNECTION_DISCOVERED",
                    id, connection.code());
            return discovered;
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE); }
    }

    @Transactional
    public McpConnectionView setEnabled(UUID projectId, UUID id, int version, boolean enabled, UUID userId) {
        access.requireAdmin(projectId, userId); McpConnection connection = require(id);
        if (!connection.projectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_NOT_FOUND);
        }
        if (enabled && !repository.findAdmission(id).filter(McpProviderAdmission::active).isPresent()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "MCP Provider 未完成只读准入");
        }
        if (!repository.setEnabled(id, version, enabled)) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        manager.invalidate(projectId, id);
        audit(projectId, userId, enabled ? "AGENT_MCP_CONNECTION_ENABLED" : "AGENT_MCP_CONNECTION_DISABLED",
                id, require(id).code());
        return McpConnectionView.from(require(id));
    }

    public McpProviderAdmissionView admission(UUID projectId, UUID id, UUID userId) {
        access.requireAdmin(projectId, userId);
        requireProjectConnection(projectId, id);
        return repository.findAdmission(id).map(McpProviderAdmissionView::from)
                .orElseGet(() -> McpProviderAdmissionView.empty(id));
    }

    @Transactional
    public McpProviderAdmissionView saveAdmission(UUID projectId, UUID id,
            McpProviderAdmissionRequest request, UUID userId) {
        access.requireAdmin(projectId, userId);
        McpConnection connection = requireProjectConnection(projectId, id);
        if (!request.readOnlyEndpoint()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Provider 必须声明只读端点");
        }
        if (!repository.findAdmission(id).map(value -> value.version())
                .map(version -> version == request.version())
                .orElse(request.version() == 0)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        McpProviderType type = providerType(connection);
        McpProviderAdmission draft = new McpProviderAdmission(id, type,
                request.providerOwner().strip(), request.dataScope().strip(),
                request.retentionPolicy().strip(), request.termsUrl().strip(),
                request.leastPrivilegeScope().strip(), true, McpAdmissionStatus.DRAFT,
                null, null, null, null, request.version(), null, null);
        McpProviderAdmissionView saved = repository.saveAdmissionDraft(draft, request.version())
                .map(McpProviderAdmissionView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERSION_CONFLICT));
        manager.invalidate(projectId, id);
        audit(projectId, userId, "AGENT_MCP_PROVIDER_ADMISSION_DRAFTED", id, connection.code());
        return saved;
    }

    @Transactional
    public McpProviderAdmissionView approveAdmission(UUID projectId, UUID id,
            int version, UUID userId) {
        access.requireAdmin(projectId, userId);
        McpConnection connection = requireProjectConnection(projectId, id);
        McpProviderAdmission current = repository.findAdmission(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Provider 准入信息尚未填写"));
        if (current.version() != version) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        if (!current.metadataComplete() || !current.activeForReview()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Provider 准入信息不完整");
        }
        if (connection.schemaHash() == null || !hasAllowlistedReadOnlyTool(connection)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Provider 需先发现并确认至少一个只读白名单工具");
        }
        McpProviderAdmissionView approved = repository.approveAdmission(id, version, userId)
                .map(McpProviderAdmissionView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERSION_CONFLICT));
        manager.invalidate(projectId, id);
        audit(projectId, userId, "AGENT_MCP_PROVIDER_ADMISSION_APPROVED", id, connection.code());
        return approved;
    }

    @Transactional
    public McpProviderAdmissionView revokeAdmission(UUID projectId, UUID id,
            int version, UUID userId) {
        access.requireAdmin(projectId, userId);
        McpConnection connection = requireProjectConnection(projectId, id);
        McpProviderAdmissionView revoked = repository.revokeAdmission(id, version, userId)
                .map(McpProviderAdmissionView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERSION_CONFLICT));
        repository.disable(id);
        manager.invalidate(projectId, id);
        audit(projectId, userId, "AGENT_MCP_PROVIDER_ADMISSION_REVOKED", id, connection.code());
        return revoked;
    }

    private void validate(McpConnectionRequest request, boolean creating) {
        if (request.transport() == McpTransport.STDIO)
            throw new BusinessException(ErrorCode.AGENT_MCP_ENDPOINT_FORBIDDEN, "STDIO MCP 默认禁用");
        try { endpoints.validate(URI.create(request.endpoint())); }
        catch (RuntimeException exception) {
            if (exception instanceof BusinessException business) throw business;
            throw new BusinessException(ErrorCode.AGENT_MCP_ENDPOINT_FORBIDDEN);
        }
        if (request.authType() != McpAuthType.NONE && creating
                && (request.credential() == null || request.credential().isBlank()))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "MCP 凭据不能为空");
        if (request.authType() == McpAuthType.NONE && request.credential() != null
                && !request.credential().isBlank())
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "无认证连接不能保存凭据");
    }

    private String encrypt(String credential, McpAuthType auth) {
        return auth == McpAuthType.NONE ? null : secrets.encrypt(credential);
    }

    private McpConnection toConnection(UUID id, UUID projectId, McpConnectionRequest request,
                                       String credential, UUID creator) {
        try {
            return new McpConnection(id, projectId, request.code().strip(), request.name().strip(),
                    request.transport(), request.endpoint().strip(), null, request.authType(), credential,
                    credential == null ? null : 1, request.timeoutMs(), request.maxResultBytes(),
                    json.writeValueAsString(safeStrings(request.toolAllowlist())),
                    json.writeValueAsString(safeStrings(request.resourceAllowlist())),
                    "[]", "[]", null, null, false, null, null, null,
                    creator, 0, null, null);
        } catch (Exception exception) { throw new BusinessException(ErrorCode.VALIDATION_ERROR); }
    }

    private McpConnection require(UUID id) {
        return repository.find(id).orElseThrow(() ->
                new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_NOT_FOUND));
    }

    private McpConnection requireProjectConnection(UUID projectId, UUID id) {
        McpConnection connection = require(id);
        if (!connection.projectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.AGENT_MCP_CONNECTION_NOT_FOUND);
        }
        return connection;
    }

    private McpProviderType providerType(McpConnection connection) {
        URI endpoint = URI.create(connection.endpoint());
        endpoints.validate(endpoint);
        String host = endpoint.getHost() == null ? "" : endpoint.getHost()
                .replace("[", "").replace("]", "").toLowerCase(java.util.Locale.ROOT);
        return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equals(host)
                ? McpProviderType.LOCAL_FIXTURE : McpProviderType.EXTERNAL_READ_ONLY;
    }

    private boolean hasAllowlistedReadOnlyTool(McpConnection connection) {
        try {
            Set<String> allowed = new HashSet<>();
            for (JsonNode value : json.readTree(connection.toolAllowlistJson())) {
                if (value.isTextual()) {
                    allowed.add(value.textValue());
                }
            }
            for (JsonNode tool : json.readTree(connection.discoveredToolsJson())) {
                if (tool.isObject() && allowed.contains(tool.path("name").asText())
                        && tool.path("annotations").path("readOnlyHint").asBoolean(false)) {
                    return true;
                }
            }
            return false;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static void validateDiscoveredTools(List<McpClientFacade.DiscoveredTool> tools) {
        Set<String> serverNames = new HashSet<>();
        Set<String> exposedNames = new HashSet<>();
        for (McpClientFacade.DiscoveredTool tool : tools) {
            if (tool.name() == null || tool.name().isBlank() || !serverNames.add(tool.name())
                    || !exposedNames.add(tool.name().replaceAll("[^a-zA-Z0-9_]", "_")
                            .toLowerCase(java.util.Locale.ROOT)))
                throw new BusinessException(ErrorCode.AGENT_MCP_SCHEMA_CHANGED,
                        "MCP 工具名称为空、重复或规范化后冲突");
        }
    }

    private static List<String> safeStrings(List<String> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull)
                .map(String::strip).filter(value -> !value.isBlank()).distinct().sorted().toList();
    }

    private static String sha256(byte[] input) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    private void audit(UUID projectId, UUID userId, String action, UUID entityId, String code) {
        if (audit != null) audit.write(projectId, userId, action, "AGENT_MCP", entityId,
                java.util.Map.of("code", code));
    }
}
