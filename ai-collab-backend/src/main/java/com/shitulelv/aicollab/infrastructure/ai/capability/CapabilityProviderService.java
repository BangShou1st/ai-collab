package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityProviderRequest;
import com.shitulelv.aicollab.infrastructure.ai.capability.api.CapabilityProviderView;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.project.application.service.AuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 管理员维护共享能力 Provider；本地 Provider 只允许 local 网络区域。 */
@Service
public class CapabilityProviderService {

    private final CapabilityProviderRepository repository;
    private final ModelSecretCipher secrets;
    private final AuditService audit;

    public CapabilityProviderService(CapabilityProviderRepository repository,
            ModelSecretCipher secrets, AuditService audit) {
        this.repository = repository;
        this.secrets = secrets;
        this.audit = audit;
    }

    public List<CapabilityProviderView> list() {
        return repository.findAll().stream().map(CapabilityProviderView::from).toList();
    }

    public CapabilityProviderView get(UUID id) {
        return CapabilityProviderView.from(require(id));
    }

    @Transactional
    public CapabilityProviderView create(UUID adminId, CapabilityProviderRequest request) {
        validate(request);
        repository.findByLowerName(request.name().strip()).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_EXISTS);
        });
        String encrypted = resolveEncryptedKey(null, request);
        CapabilityProvider created = repository.create(adminId, UUID.randomUUID(), request.name().strip(),
                request.providerType(), request.protocol().name(), stripTrailingSlash(request.baseUrl()),
                request.apiPath().strip(), request.authType(), request.networkZone(),
                healthPathOrDefault(request.healthPath()), encrypted, request.enabled());
        audit(adminId, "AI_CAPABILITY_PROVIDER_CREATED", created, request.reason());
        return CapabilityProviderView.from(created);
    }

    @Transactional
    public CapabilityProviderView update(UUID adminId, UUID id, CapabilityProviderRequest request) {
        CapabilityProvider existing = require(id);
        validate(request);
        repository.findByLowerName(request.name().strip())
                .filter(found -> !found.id().equals(id))
                .ifPresent(found -> {
                    throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_EXISTS);
                });
        String encrypted = resolveEncryptedKey(existing.encryptedApiKey(), request);
        if (!repository.update(id, adminId, request.name().strip(), request.providerType(),
                request.protocol().name(), stripTrailingSlash(request.baseUrl()), request.apiPath().strip(),
                request.authType(), request.networkZone(), healthPathOrDefault(request.healthPath()),
                encrypted, request.enabled())) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_NOT_FOUND);
        }
        CapabilityProvider updated = require(id);
        audit(adminId, "AI_CAPABILITY_PROVIDER_UPDATED", updated, request.reason());
        return CapabilityProviderView.from(updated);
    }

    @Transactional
    public void delete(UUID adminId, UUID id) {
        CapabilityProvider provider = require(id);
        try {
            if (repository.delete(id) == 0) {
                throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_NOT_FOUND);
            }
        } catch (DataIntegrityViolationException inUse) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_IN_USE);
        }
        audit(adminId, "AI_CAPABILITY_PROVIDER_DELETED", provider, null);
    }

    private CapabilityProvider require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAPABILITY_PROVIDER_NOT_FOUND));
    }

    private String resolveEncryptedKey(String existingEncrypted, CapabilityProviderRequest request) {
        String authType = request.authType().strip().toUpperCase(java.util.Locale.ROOT);
        if ("NONE".equals(authType)) {
            if (request.apiKey() != null && !request.apiKey().isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "authType=NONE 时不允许提交 API Key");
            }
            return null;
        }
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            return secrets.encrypt(request.apiKey().strip());
        }
        if (existingEncrypted != null && !existingEncrypted.isBlank()) {
            return existingEncrypted;
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "该鉴权方式必须提供 API Key");
    }

    private static void validate(CapabilityProviderRequest request) {
        if (request.providerType() != ModelProviderType.LOCAL_CUSTOM) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, "当前能力 Provider 只允许 LOCAL_CUSTOM");
        }
        if (request.protocol() != CapabilityProtocol.LLAMACPP_RERANK) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, "当前能力 Provider 只允许 LLAMACPP_RERANK");
        }
        if (!"local".equals(request.networkZone())) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, "本地 Provider 只能是 local 网络区域");
        }
        validateBaseUrl(request.baseUrl(), request.networkZone());
        validateRelativePath(request.apiPath(), "apiPath");
        String healthPath = healthPathOrDefault(request.healthPath());
        validateRelativePath(healthPath, "healthPath");
    }

    private static void validateBaseUrl(String raw, String networkZone) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, "Base URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(raw.strip());
        } catch (RuntimeException malformed) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, "Base URL 格式无效");
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, "Base URL 必须是带主机的绝对地址");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, "Base URL 只允许 http/https");
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, "Base URL 不允许携带凭据、查询串或片段");
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, "Base URL 必须是服务根地址，路径请填写在 apiPath");
        }
        if ("public".equals(networkZone) && !"https".equals(scheme)) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, "公网 Provider 必须使用 HTTPS");
        }
        if ("local".equals(networkZone) && isPublicIpLiteral(uri.getHost())) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, "local Provider 不允许使用公网 IP 字面量");
        }
    }

    private static void validateRelativePath(String raw, String field) {
        if (raw == null || raw.isBlank() || !raw.strip().startsWith("/")) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, field + " 必须是以 / 开头的相对路径");
        }
        if (raw.strip().contains("?") || raw.strip().contains("#")) {
            throw new BusinessException(ErrorCode.CAPABILITY_PROVIDER_INVALID, field + " 不允许查询串或片段");
        }
    }

    private static boolean isPublicIpLiteral(String host) {
        if (host == null) {
            return false;
        }
        String value = host.strip();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.contains(":")) {
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            return !(lower.equals("::1") || lower.startsWith("fc") || lower.startsWith("fd")
                    || lower.startsWith("fe80:"));
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            if (first == 10 || first == 127) {
                return false;
            }
            if (first == 172 && second >= 16 && second <= 31) {
                return false;
            }
            if (first == 192 && second == 168) {
                return false;
            }
            if (first == 169 && second == 254) {
                return false;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return false;
            }
            return true;
        } catch (NumberFormatException notIp) {
            return false;
        }
    }

    private static String stripTrailingSlash(String value) {
        String result = value.strip();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String healthPathOrDefault(String value) {
        return value == null || value.isBlank() ? "/health" : value.strip();
    }

    private void audit(UUID adminId, String action, CapabilityProvider provider, String reason) {
        if (audit == null) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("providerId", provider.id().toString());
        detail.put("name", provider.name());
        detail.put("protocol", provider.protocol());
        detail.put("networkZone", provider.networkZone());
        detail.put("enabled", provider.enabled());
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason.strip());
        }
        audit.write(null, adminId, action, "AI_CAPABILITY_PROVIDER", provider.id(), detail);
    }
}
