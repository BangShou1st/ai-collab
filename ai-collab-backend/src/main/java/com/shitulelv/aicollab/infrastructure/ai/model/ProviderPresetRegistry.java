package com.shitulelv.aicollab.infrastructure.ai.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server-owned trust source for provider presets. Frontend must never submit baseUrl/apiPath for presets.
 *
 * <p>Zen / Console 上游地址只在这里决定，并且必须命中代码白名单：默认使用新版官方文档
 * （{@code https://opencode.ai/v2/docs/console/models/}）列出的 {@code /inference/v1}；
 * 旧版文档的 {@code /zen/v1} 保留为可显式选择的兼容选项。
 * 用户请求、持久化的 preset baseUrl、HTTP Header 或模型响应都不能覆盖这里的取值。</p>
 */
@Component
public class ProviderPresetRegistry {
    public record PresetPolicy(ProviderPresetCode code, String displayName, ModelProviderType protocol,
            String baseUrl, String completionPath, String modelsPath, String userAgent, boolean directNoProxy,
            java.util.EnumSet<ModelCapability> capabilities, double defaultTemperature, int defaultMaxOutputTokens) {}

    /** 新版官方文档的默认上游。 */
    static final String ZEN_DEFAULT_BASE = "https://opencode.ai/inference/v1";
    /** 旧版官方文档的兼容上游；需服务端显式配置启用。 */
    static final String ZEN_LEGACY_BASE = "https://opencode.ai/zen/v1";
    /** 白名单：只有这些经批准的 HTTPS Zen 地址可以被选为上游。 */
    static final Set<String> APPROVED_ZEN_BASES = Set.of(ZEN_DEFAULT_BASE, ZEN_LEGACY_BASE);

    private static final java.util.EnumSet<ModelCapability> ZEN_FREE_CAPABILITIES = java.util.EnumSet.of(
            ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.STRUCTURED_OUTPUT,
            ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE);
    // Free reasoning models emit long reasoning traces before content; the default budget must cover both.
    private static final String ZEN_USER_AGENT =
            "opencode/1.18.31 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14";

    private final String zenBaseUrl;
    private final Map<ProviderPresetCode, PresetPolicy> policies;

    /** 默认上游：新版官方文档列出的 {@code /inference/v1}。 */
    public ProviderPresetRegistry() {
        this(ZEN_DEFAULT_BASE);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderPresetRegistry(@Value("${zen.upstream:https://opencode.ai/inference/v1}") String zenUpstream) {
        this.zenBaseUrl = approveZenUpstream(zenUpstream);
        this.policies = Map.of(ProviderPresetCode.OPENCODE_ZEN_FREE, zenFree(this.zenBaseUrl));
    }

    private static PresetPolicy zenFree(String baseUrl) {
        return new PresetPolicy(ProviderPresetCode.OPENCODE_ZEN_FREE, "OpenCode Zen Free",
                ModelProviderType.OPENAI_COMPATIBLE, baseUrl, "/chat/completions", "/models",
                ZEN_USER_AGENT, true, ZEN_FREE_CAPABILITIES, 0.2, 4000);
    }

    /** 校验并归一化服务端配置的 Zen 上游地址；不在白名单内时明确失败。 */
    static String approveZenUpstream(String candidate) {
        String normalized = candidate == null ? "" : candidate.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!APPROVED_ZEN_BASES.contains(normalized)) {
            throw new IllegalStateException("zen.upstream 必须是白名单内的 HTTPS Zen 地址，当前值：" + normalized);
        }
        return normalized;
    }

    /** 当前生效的 Zen 上游地址。 */
    public String zenBaseUrl() {
        return zenBaseUrl;
    }

    public Set<String> approvedBaseUrls() {
        return APPROVED_ZEN_BASES;
    }

    public PresetPolicy require(ProviderPresetCode code) {
        PresetPolicy p = policies.get(code);
        if (p == null) throw new IllegalArgumentException("unknown preset: " + code);
        return new PresetPolicy(p.code(), p.displayName(), p.protocol(), p.baseUrl(), p.completionPath(),
                p.modelsPath(), p.userAgent(), p.directNoProxy(), java.util.EnumSet.copyOf(p.capabilities()),
                p.defaultTemperature(), p.defaultMaxOutputTokens());
    }

    public List<PresetPolicy> all() { return List.copyOf(policies.values()); }
    public String completionEndpoint(PresetPolicy p) { return join(p.baseUrl(), p.completionPath()); }
    public String modelsEndpoint(PresetPolicy p) { return join(p.baseUrl(), p.modelsPath()); }
    private static String join(String base, String path) {
        String b = base.strip();
        String q = path.strip();
        if (b.endsWith("/") && q.startsWith("/")) return b + q.substring(1);
        if (!b.endsWith("/") && !q.startsWith("/")) return b + "/" + q;
        return b + q;
    }
}
