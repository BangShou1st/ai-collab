package com.shitulelv.aicollab.internal.ai.credential;

import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProfile;
import com.shitulelv.aicollab.infrastructure.ai.capability.CapabilityProvider;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankCapabilityResolver;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankSettings;
import com.shitulelv.aicollab.infrastructure.ai.capability.ResolvedRerankConfiguration;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfig;
import com.shitulelv.aicollab.infrastructure.ai.embedding.SystemEmbeddingConfigRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfile;
import com.shitulelv.aicollab.infrastructure.ai.model.profile.ModelProfileRepository;
import com.shitulelv.aicollab.retrieval.rerank.RerankExecutionPolicy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 非聊天用途的租借路由（P2 契约 §12.2 P1-R4/P1-R5、§12.3 R1-4）。
 *
 * <ul>
 *   <li>{@code EMBEDDING}：系统 Embedding 配置（全站一份，与 {@code ProjectEmbeddingGateway} 同源）。
 *       protocol ∈ {@code OPENAI_EMBEDDINGS / GEMINI_EMBEDDINGS}；
 *       {@code params = {dimensions: int|null, batchSize: int}}，OLLAMA 的 dimensions 一律为 null；</li>
 *   <li>{@code RERANK}：{@link RerankCapabilityResolver}（项目绑定 → 全局绑定）。
 *       protocol 固定 {@code LLAMACPP_RERANK}；{@code params = {maxCandidates, timeoutMs}}，取
 *       {@link RerankExecutionPolicy#inlineSettings} 的现行值；未配置凭据时 apiKey 为 null。</li>
 * </ul>
 * 解析不到可用配置一律 {@link CredentialLeaseException#unavailable}（503，retryable），原因标签只进日志。
 */
@Component
public class NonChatLeaseRouter {

    static final String PROTOCOL_OPENAI_EMBEDDINGS = "OPENAI_EMBEDDINGS";
    static final String PROTOCOL_GEMINI_EMBEDDINGS = "GEMINI_EMBEDDINGS";
    static final String PROTOCOL_LLAMACPP_RERANK = "LLAMACPP_RERANK";
    private static final Set<String> EMBEDDING_PROVIDERS = Set.of("OPENAI_COMPATIBLE", "OLLAMA", "GEMINI");
    private static final int GEMINI_BATCH_LIMIT = 100;
    private static final int GEMINI_DEFAULT_BATCH = 32;

    private final SystemEmbeddingConfigRepository embeddingConfigs;
    private final EmbeddingEndpointPolicy embeddingEndpoints;
    private final RerankCapabilityResolver rerankResolver;
    private final ModelProfileRepository modelProfiles;
    private final ModelSecretCipher secrets;

    public NonChatLeaseRouter(SystemEmbeddingConfigRepository embeddingConfigs,
            EmbeddingEndpointPolicy embeddingEndpoints, RerankCapabilityResolver rerankResolver,
            ModelProfileRepository modelProfiles, ModelSecretCipher secrets) {
        this.embeddingConfigs = Objects.requireNonNull(embeddingConfigs, "embeddingConfigs");
        this.embeddingEndpoints = Objects.requireNonNull(embeddingEndpoints, "embeddingEndpoints");
        this.rerankResolver = Objects.requireNonNull(rerankResolver, "rerankResolver");
        this.modelProfiles = Objects.requireNonNull(modelProfiles, "modelProfiles");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /** 路由结果。{@code apiKey} 可为 null（OLLAMA、无鉴权 reranker）；{@link #toString()} 不含明文。 */
    public record Route(UUID providerId, String providerType, String protocol, String baseUrl, String apiPath,
            String model, String apiKey, Map<String, Object> params, List<String> capabilities) {

        @Override
        public String toString() {
            return "Route[protocol=" + protocol + ", model=" + model + ", apiKey=***]";
        }
    }

    public Route route(LeasePurpose purpose, UUID projectId) {
        return switch (purpose) {
            case EMBEDDING -> embedding();
            case RERANK -> rerank(projectId);
            default -> throw CredentialLeaseException.unavailable("purpose_not_routable");
        };
    }

    Route embedding() {
        SystemEmbeddingConfig config = embeddingConfigs.findActive()
                .orElseThrow(() -> CredentialLeaseException.unavailable("embedding_unconfigured"));
        if (!config.enabled()) {
            throw CredentialLeaseException.unavailable("embedding_disabled");
        }
        String provider = config.provider();
        if (provider == null || !EMBEDDING_PROVIDERS.contains(provider)) {
            throw CredentialLeaseException.unavailable("embedding_provider_unsupported");
        }
        String baseUrl = config.baseUrl() == null ? "" : config.baseUrl().strip();
        String apiPath = config.apiPath() == null ? "" : config.apiPath().strip();
        try {
            embeddingEndpoints.require(provider, baseUrl, apiPath);
        } catch (RuntimeException invalidEndpoint) {
            throw CredentialLeaseException.unavailable("embedding_endpoint_invalid");
        }
        boolean ollama = "OLLAMA".equals(provider);
        String apiKey = decryptOptional(config.encryptedApiKey());
        if (apiKey == null && !ollama) {
            throw CredentialLeaseException.unavailable("credential_missing");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dimensions", !ollama && config.dimensions() > 0 ? config.dimensions() : null);
        params.put("batchSize", effectiveBatchSize(provider, config.batchSize()));
        String protocol = "GEMINI".equals(provider) ? PROTOCOL_GEMINI_EMBEDDINGS : PROTOCOL_OPENAI_EMBEDDINGS;
        return new Route(config.id(), provider, protocol, baseUrl, apiPath, config.modelName(), apiKey,
                params, List.of("EMBEDDING"));
    }

    /** 与 {@code ProjectEmbeddingGateway.embedWithConfig} 的分批规则一致。 */
    static int effectiveBatchSize(String provider, int configured) {
        if ("GEMINI".equals(provider)) {
            return Math.min(configured <= 0 ? GEMINI_DEFAULT_BATCH : configured, GEMINI_BATCH_LIMIT);
        }
        if (configured < 1) {
            throw CredentialLeaseException.unavailable(CredentialLeaseException.REASON_PROVIDER_PARAMS_INVALID);
        }
        return configured;
    }

    Route rerank(UUID projectId) {
        RerankCapabilityResolver.Resolution resolution = rerankResolver.resolve(projectId);
        if (resolution == null || !resolution.available()) {
            throw CredentialLeaseException.unavailable("rerank_unresolved");
        }
        ResolvedRerankConfiguration resolved = resolution.configuration();
        CapabilityProvider provider = resolved.provider();
        CapabilityProfile profile = resolved.profile();
        if (!PROTOCOL_LLAMACPP_RERANK.equals(profile.protocol())) {
            throw CredentialLeaseException.unavailable("rerank_protocol_unsupported");
        }
        String baseUrl = provider.baseUrl() == null ? "" : provider.baseUrl().strip();
        if (!(baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))) {
            throw CredentialLeaseException.unavailable("provider_base_url_invalid");
        }
        String apiPath = provider.apiPath() == null ? "" : provider.apiPath().strip();
        if (apiPath.isEmpty()) {
            throw CredentialLeaseException.unavailable("provider_api_path_missing");
        }
        String apiKey = null;
        if (provider.requiresAuth()) {
            // P1-R5：Python 只按 Authorization: Bearer 发送凭据；其他鉴权方式无法如实转交，拒绝而不是降级为无鉴权。
            String authType = provider.authType().toUpperCase(Locale.ROOT);
            if (!"BEARER".equals(authType)) {
                throw CredentialLeaseException.unavailable("rerank_auth_type_unsupported");
            }
            apiKey = decryptOptional(provider.encryptedApiKey());
            if (apiKey == null) {
                throw CredentialLeaseException.unavailable("credential_missing");
            }
        }
        ModelProfile model = modelProfiles.find(profile.modelProfileId())
                .orElseThrow(() -> CredentialLeaseException.unavailable("rerank_model_profile_missing"));
        RerankSettings inline = RerankExecutionPolicy.inlineSettings(resolved.settings());
        if (inline.maxCandidates() < 1 || inline.timeoutMs() < 1) {
            throw CredentialLeaseException.unavailable(CredentialLeaseException.REASON_PROVIDER_PARAMS_INVALID);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("maxCandidates", inline.maxCandidates());
        params.put("timeoutMs", inline.timeoutMs());
        String providerType = provider.providerType() == null ? PROTOCOL_LLAMACPP_RERANK : provider.providerType().name();
        return new Route(provider.id(), providerType, PROTOCOL_LLAMACPP_RERANK, baseUrl, apiPath,
                model.modelName(), apiKey, params, List.of("RERANK"));
    }

    private String decryptOptional(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        String plain;
        try {
            plain = secrets.decrypt(encrypted);
        } catch (RuntimeException decryptFailure) {
            // 不带上异常文本：解密失败的消息可能含密文片段。
            throw CredentialLeaseException.unavailable("credential_unreadable");
        }
        return plain == null || plain.isBlank() ? null : plain;
    }
}
