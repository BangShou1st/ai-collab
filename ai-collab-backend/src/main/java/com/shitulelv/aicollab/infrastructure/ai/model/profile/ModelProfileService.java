package com.shitulelv.aicollab.infrastructure.ai.model.profile;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.api.ModelProfileRequest;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.api.ModelProfileView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 全局模型窗口档案的维护与解析。
 *
 * <p>身份键为 {@code provider_type + 归一化 base_url + 归一化 api_path + 小写 model_name} 的
 * SHA-256，避免同一模型在不同网关下共用窗口；管理员只能通过本服务写入，运行时只读。</p>
 */
@Service
public class ModelProfileService {
    private final ModelProfileRepository repository;

    public ModelProfileService(ModelProfileRepository repository) {
        this.repository = repository;
    }

    public List<ModelProfileView> list() {
        return repository.list().stream().map(ModelProfileView::from).toList();
    }

    @Transactional
    public ModelProfileView create(UUID adminId, ModelProfileRequest request) {
        String key = profileKey(request.providerType().name(), request.baseUrl(), request.apiPath(),
                request.modelName());
        if (repository.findByKey(key).isPresent()) {
            throw new BusinessException(ErrorCode.MODEL_PROFILE_EXISTS);
        }
        OffsetDateTime verifiedAt = request.source().verified() ? OffsetDateTime.now() : null;
        UUID verifiedBy = request.source().verified() ? adminId : null;
        try {
            return ModelProfileView.from(repository.create(adminId, key, request.providerType().name(),
                    request.baseUrl().strip(), request.apiPath().strip(), request.modelName().strip(),
                    request.contextWindowTokens(), request.maxOutputTokens(), request.supportsUsage(),
                    trimToNull(request.tokenizerKind()), trimToNull(request.pricingVersion()),
                    request.source().name(), verifiedAt, verifiedBy));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.MODEL_PROFILE_EXISTS);
        }
    }

    @Transactional
    public ModelProfileView update(UUID adminId, UUID id, ModelProfileRequest request) {
        require(id);
        String key = profileKey(request.providerType().name(), request.baseUrl(), request.apiPath(),
                request.modelName());
        repository.findByKey(key)
                .filter(existing -> !existing.id().equals(id))
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.MODEL_PROFILE_EXISTS);
                });
        OffsetDateTime verifiedAt = request.source().verified() ? OffsetDateTime.now() : null;
        UUID verifiedBy = request.source().verified() ? adminId : null;
        try {
            if (!repository.update(id, adminId, key, request.providerType().name(),
                    request.baseUrl().strip(), request.apiPath().strip(), request.modelName().strip(),
                    request.contextWindowTokens(), request.maxOutputTokens(), request.supportsUsage(),
                    trimToNull(request.tokenizerKind()), trimToNull(request.pricingVersion()),
                    request.source().name(), verifiedAt, verifiedBy)) {
                throw new BusinessException(ErrorCode.MODEL_PROFILE_NOT_FOUND);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.MODEL_PROFILE_EXISTS);
        }
        return ModelProfileView.from(repository.find(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_PROFILE_NOT_FOUND)));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.delete(id)) {
            throw new BusinessException(ErrorCode.MODEL_PROFILE_NOT_FOUND);
        }
    }

    /** 按实际出站身份精确解析档案；任一部分缺失时不猜测。 */
    public Optional<ModelProfile> resolve(String providerType, String baseUrl, String apiPath, String modelName) {
        if (isBlank(providerType) || isBlank(baseUrl) || isBlank(apiPath) || isBlank(modelName)) {
            return Optional.empty();
        }
        return repository.findByKey(profileKey(providerType, baseUrl, apiPath, modelName));
    }

    /** 归一化后的稳定档案键：大小写、尾部斜杠与路径写法差异不产生重复档案。 */
    public static String profileKey(String providerType, String baseUrl, String apiPath, String modelName) {
        String normalized = String.join("|",
                providerType == null ? "" : providerType.strip().toUpperCase(Locale.ROOT),
                normalizeEndpoint(baseUrl),
                normalizePath(apiPath),
                modelName == null ? "" : modelName.strip().toLowerCase(Locale.ROOT));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String normalizeEndpoint(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizePath(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "";
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.strip();
    }

    private ModelProfile require(UUID id) {
        return repository.find(id).orElseThrow(() -> new BusinessException(ErrorCode.MODEL_PROFILE_NOT_FOUND));
    }
}
