package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 自定义 Provider 接入档案：前端表单默认值与能力候选的唯一事实源。
 *
 * <p>历史问题：用户级表单与项目级表单各自硬编码默认端点且互不一致（用户级选 Gemini
 * 仍是 OpenAI 端点）。此处集中定义后经 {@code GET /api/v1/user/ai-providers/profiles}
 * 暴露，前端不再维护第二份默认表。</p>
 *
 * <p>每个档案有唯一 {@code code}：常用兼容网关（如魔搭 ModelScope）与协议档案
 * （OPENAI_COMPATIBLE）一一可选，用户不需要自己拼端点。档案只描述「协议事实」
 * （默认端点、可勾选能力、是否支持模型发现），不承载预设信任边界——
 * Zen/Kilo 预设仍由 {@code ProviderPresetRegistry} 服务端持有；
 * 也不预先断言具体模型支持哪些能力（真实能力以探针为准）。</p>
 */
public final class ProviderProfileCatalog {
    private ProviderProfileCatalog() {
    }

    /**
     * 单个接入档案。
     *
     * @param code                  档案唯一代码（前端服务商选择器使用；协议档案与 ModelProviderType 同名）
     * @param providerType          实际协议类型（ModelScope 等兼容网关即 OPENAI_COMPATIBLE）
     * @param label                 展示名
     * @param defaultBaseUrl        官方默认 Base URL（仅作表单初始值，用户可改）
     * @param defaultApiPath        官方默认 API Path；Gemini 携带 {@code {model}} 占位符
     * @param apiKeyRequired        该协议官方端点是否必须 API Key（兼容网关可能免鉴权，前端仅作提示）
     * @param modelDiscovery        是否支持模型列表发现（GET models 端点）
     * @param candidateCapabilities 用户可勾选声明的能力候选（不含 USAGE：用量记账由系统隐含使用）
     * @param apiPathHint           API Path 的填写说明（含占位符语义）
     */
    public record ProviderProfile(
            String code,
            ModelProviderType providerType,
            String label,
            String defaultBaseUrl,
            String defaultApiPath,
            boolean apiKeyRequired,
            boolean modelDiscovery,
            List<ModelCapability> candidateCapabilities,
            String apiPathHint) {
    }

    private static final Map<String, ProviderProfile> PROFILES = build();

    private static Map<String, ProviderProfile> build() {
        Map<String, ProviderProfile> profiles = new LinkedHashMap<>();
        profiles.put("OPENAI_COMPATIBLE", new ProviderProfile(
                "OPENAI_COMPATIBLE", ModelProviderType.OPENAI_COMPATIBLE, "OpenAI 兼容",
                "https://api.openai.com", "/v1/chat/completions",
                true, true,
                List.of(ModelCapability.CHAT, ModelCapability.STREAMING,
                        ModelCapability.STRUCTURED_OUTPUT, ModelCapability.NATIVE_TOOLS),
                "推理端点路径，通常为 /v1/chat/completions；模型发现自动使用同前缀的 /models"));
        profiles.put("MODELSCOPE", new ProviderProfile(
                "MODELSCOPE", ModelProviderType.OPENAI_COMPATIBLE, "魔搭 ModelScope",
                "https://api-inference.modelscope.cn", "/v1/chat/completions",
                true, true,
                List.of(ModelCapability.CHAT, ModelCapability.STREAMING,
                        ModelCapability.STRUCTURED_OUTPUT, ModelCapability.NATIVE_TOOLS),
                "官方 OpenAI 兼容路径 /v1/chat/completions；Base URL 无需再带 /v1（默认已按此拆分，避免 /v1/v1 重复）"));
        profiles.put("ANTHROPIC", new ProviderProfile(
                "ANTHROPIC", ModelProviderType.ANTHROPIC, "Anthropic",
                "https://api.anthropic.com", "/v1/messages",
                true, true,
                List.of(ModelCapability.CHAT, ModelCapability.STREAMING,
                        ModelCapability.NATIVE_TOOLS),
                "推理端点路径，官方为 /v1/messages；模型发现自动使用同前缀的 /models"));
        profiles.put("GEMINI", new ProviderProfile(
                "GEMINI", ModelProviderType.GEMINI, "Google Gemini",
                "https://generativelanguage.googleapis.com", "/v1beta/models/{model}:generateContent",
                true, true,
                List.of(ModelCapability.CHAT, ModelCapability.STREAMING,
                        ModelCapability.NATIVE_TOOLS),
                "官方路径为 /v1beta/models/{model}:generateContent，{model} 会替换为模型 ID；流式自动切换 :streamGenerateContent"));
        return Map.copyOf(profiles);
    }

    public static List<ProviderProfile> all() {
        return List.copyOf(PROFILES.values());
    }

    public static Optional<ProviderProfile> ofCode(String code) {
        return Optional.ofNullable(PROFILES.get(code));
    }

    /** 按协议类型取主档案（协议档案 code 与类型同名）；兼容旧调用方。 */
    public static Optional<ProviderProfile> of(ModelProviderType providerType) {
        return ofCode(providerType.name());
    }
}
