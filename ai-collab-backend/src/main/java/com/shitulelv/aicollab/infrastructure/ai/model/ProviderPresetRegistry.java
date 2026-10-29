package com.shitulelv.aicollab.infrastructure.ai.model;

import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server-owned trust source for provider presets. Frontend must never submit baseUrl/apiPath for presets.
 *
 * <p>Zen 上游的 base、推理路径与发现路径不再由应用自行硬编码，而是直接取共享库
 * {@link OpenCodeZenProtocolPolicy} 的已验证常量：推理与模型发现都以
 * {@code INFERENCE_BASE_URL} 为 base，推理走 {@code /chat/completions}、发现走 {@code /models}。
 * 旧版 {@code /zen/v1} 只可用于发现、绝不可用于推理，因此不再作为可选上游。
 * 用户请求、持久化的 preset baseUrl、HTTP Header 或模型响应都不能覆盖这里的取值。</p>
 */
@Component
public class ProviderPresetRegistry {
    public record PresetPolicy(ProviderPresetCode code, String displayName, ModelProviderType protocol,
            String baseUrl, String completionPath, String modelsPath, String userAgent, boolean directNoProxy,
            java.util.EnumSet<ModelCapability> capabilities, double defaultTemperature, int defaultMaxOutputTokens) {}

    /** 唯一获批的 Zen 上游 base：共享库声明的推理 base（{@code https://opencode.ai/inference/v1}）。 */
    static final String ZEN_INFERENCE_BASE = OpenCodeZenProtocolPolicy.INFERENCE_BASE_URL;
    /** 白名单：只有这个经批准的 HTTPS Zen base 可以被选为上游。 */
    static final Set<String> APPROVED_ZEN_BASES = Set.of(ZEN_INFERENCE_BASE);

    /**
     * Kilo Gateway 上游 base：取自共享库 {@code KiloGatewayProperties} 的默认值，
     * 不在应用侧重复声明该地址。实际 HTTP 由共享库客户端按 {@code kilo.gateway.base-url} 发起。
     */
    static final String KILO_GATEWAY_BASE =
            new com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties().getBaseUrl();
    /** Kilo 网关的 OpenAI 兼容 wire 路径（与共享库客户端使用的后缀一致）。 */
    static final String KILO_CHAT_COMPLETIONS_PATH = "/chat/completions";
    static final String KILO_MODELS_PATH = "/models";

    private static final java.util.EnumSet<ModelCapability> ZEN_FREE_CAPABILITIES = java.util.EnumSet.of(
            ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.STRUCTURED_OUTPUT,
            ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE);

    /** Kilo Gateway 免费模型能力与 Zen 免费预设一致：Chat/流式/结构化/原生工具/用量。 */
    private static final java.util.EnumSet<ModelCapability> KILO_FREE_CAPABILITIES = java.util.EnumSet.of(
            ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.STRUCTURED_OUTPUT,
            ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE);

    private final String zenBaseUrl;
    private final String zenUserAgent;
    private final Map<ProviderPresetCode, PresetPolicy> policies;

    /** 默认上游：共享库的推理 base。 */
    public ProviderPresetRegistry() {
        this(ZEN_INFERENCE_BASE, OpenCodeZenProtocolPolicy.USER_AGENT);
    }

    public ProviderPresetRegistry(String zenUpstream) {
        this(zenUpstream, OpenCodeZenProtocolPolicy.USER_AGENT);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderPresetRegistry(
            @Value("${zen.upstream:" + ZEN_INFERENCE_BASE + "}") String zenUpstream,
            // v1.6.1 起 USER_AGENT 由 capture 组合（非编译期常量），注解默认值留空并在运行时回退共享库值。
            @Value("${opencode.zen.user-agent:}") String zenUserAgent) {
        this.zenBaseUrl = approveZenUpstream(zenUpstream);
        this.zenUserAgent = zenUserAgent == null || zenUserAgent.isBlank()
                ? OpenCodeZenProtocolPolicy.USER_AGENT : zenUserAgent;
        this.policies = Map.of(
                ProviderPresetCode.OPENCODE_ZEN_FREE, zenFree(this.zenBaseUrl, this.zenUserAgent),
                ProviderPresetCode.KILO_GATEWAY_FREE, kiloFree());
    }

    private static PresetPolicy zenFree(String baseUrl, String userAgent) {
        return new PresetPolicy(ProviderPresetCode.OPENCODE_ZEN_FREE, "OpenCode Zen Free",
                ModelProviderType.OPENAI_COMPATIBLE, baseUrl, OpenCodeZenProtocolPolicy.CHAT_COMPLETIONS_PATH,
                OpenCodeZenProtocolPolicy.MODELS_PATH, userAgent, true, ZEN_FREE_CAPABILITIES, 0.2, 4000);
    }

    /** Kilo Gateway 免费预设：base/路径取自共享库契约，无应用侧身份头（免费模型匿名调用）。 */
    private static PresetPolicy kiloFree() {
        return new PresetPolicy(ProviderPresetCode.KILO_GATEWAY_FREE, "Kilo Gateway Free",
                ModelProviderType.OPENAI_COMPATIBLE, KILO_GATEWAY_BASE, KILO_CHAT_COMPLETIONS_PATH,
                KILO_MODELS_PATH, null, true, KILO_FREE_CAPABILITIES, 0.2, 4000);
    }

    /** 校验并归一化服务端配置的 Zen 上游地址；不在白名单内时明确失败。 */
    static String approveZenUpstream(String candidate) {
        String normalized = normalize(candidate);
        if (!APPROVED_ZEN_BASES.contains(normalized)) {
            throw new IllegalStateException("zen.upstream 必须是白名单内的 HTTPS Zen 推理 base，当前值：" + normalized);
        }
        return normalized;
    }

    private static String normalize(String candidate) {
        String normalized = candidate == null ? "" : candidate.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public PresetPolicy require(ProviderPresetCode code) {
        PresetPolicy p = policies.get(code);
        if (p == null) throw new IllegalArgumentException("unknown preset: " + code);
        return new PresetPolicy(p.code(), p.displayName(), p.protocol(), p.baseUrl(), p.completionPath(),
                p.modelsPath(), p.userAgent(), p.directNoProxy(), java.util.EnumSet.copyOf(p.capabilities()),
                p.defaultTemperature(), p.defaultMaxOutputTokens());
    }

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
