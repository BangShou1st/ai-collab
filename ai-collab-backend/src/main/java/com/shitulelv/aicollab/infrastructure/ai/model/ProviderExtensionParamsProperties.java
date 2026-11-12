package com.shitulelv.aicollab.infrastructure.ai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容通道的受控 provider 扩展参数（D1，默认关闭）。
 *
 * <p>背景：{@code OpenAiCompatibleModelAdapter.request()} / {@code turnRequest()} 原先只发
 * 固定字段，ModelScope 的 thinking 无法关闭。这里提供"白名单 + 默认关闭"的透传，不开放
 * 任意字段直通：</p>
 *
 * <ul>
 *   <li>仅对 {@link ModelProviderType#OPENAI_COMPATIBLE} 生效；Gemini / Anthropic 行为不变；</li>
 *   <li>只允许 {@code enableThinking}（boolean → {@code enable_thinking}）与
 *       {@code reasoningEffort}（{@code none/low/medium/high} → {@code reasoning_effort}）；
 *       其它字段不存在，配置里的非法取值忽略（debug 日志，不进请求体）；</li>
 *   <li>{@code models} 使用<b>列表</b>而不是 map：{@code providerType|modelName} 这类 key 含
 *       {@code |}、{@code /}、{@code .} 时 Spring 的属性名绑定很脆弱，列表把 providerType 与
 *       modelName 当普通字符串绑定，更稳定也更好运维。匹配时 providerType 大小写不敏感，
 *       modelName 去首尾空白后转小写；请求体里的 {@code model} 仍使用原始配置值；</li>
 *   <li>{@code enabled=false} 或未配置该模型时，请求体与未接入本特性前逐字节一致。</li>
 * </ul>
 *
 * <p>示例（application.yml）：</p>
 *
 * <pre>{@code
 * ai:
 *   provider-extension-params:
 *     enabled: true
 *     models:
 *       - providerType: OPENAI_COMPATIBLE
 *         modelName: "deepseek-ai/DeepSeek-V4.1-Flash-1017"
 *         enableThinking: false
 * }</pre>
 */
@ConfigurationProperties("ai.provider-extension-params")
public class ProviderExtensionParamsProperties {

    private static final Logger log = LoggerFactory.getLogger(ProviderExtensionParamsProperties.class);

    /** 白名单：布尔型开关（如 ModelScope 的 enable_thinking）。 */
    static final String ENABLE_THINKING = "enable_thinking";
    /** 白名单：字符串枚举（OpenAI 兼容的 reasoning_effort）。 */
    static final String REASONING_EFFORT = "reasoning_effort";
    private static final Set<String> REASONING_EFFORT_VALUES = Set.of("none", "low", "medium", "high");

    private boolean enabled;
    private List<ModelExtension> models = List.of();
    /** 由 {@link #setModels(List)} 归一后的查找表：{@code PROVIDER_TYPE|modelname} → 允许字段。 */
    private Map<String, Map<String, Object>> normalized = Map.of();

    public ProviderExtensionParamsProperties() {
    }

    /** 供不依赖 Spring Binder 的代码与测试使用。 */
    public ProviderExtensionParamsProperties(boolean enabled, List<ModelExtension> models) {
        this.enabled = enabled;
        setModels(models);
    }

    /** 未启用时的安全默认值：供不经过 Spring 的构造点使用。 */
    public static ProviderExtensionParamsProperties disabled() {
        return new ProviderExtensionParamsProperties(false, List.of());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<ModelExtension> getModels() {
        return models;
    }

    public void setModels(List<ModelExtension> models) {
        this.models = models == null ? List.of() : List.copyOf(models);
        this.normalized = normalizeModels(this.models);
    }

    /**
     * 返回该模型可下发的扩展参数（已完成白名单与取值校验）。
     *
     * <p>返回空 map 表示"不发任何扩展参数"，请求体保持原样。</p>
     */
    public Map<String, Object> forModel(ModelProviderType providerType, String modelName) {
        if (!enabled || providerType != ModelProviderType.OPENAI_COMPATIBLE) {
            return Map.of();
        }
        String key = normalizeKey(providerType, modelName);
        return key == null ? Map.of() : normalized.getOrDefault(key, Map.of());
    }

    /** 单条模型扩展参数配置；字段名即 YAML 键（也接受 kebab-case 的宽松绑定）。 */
    public record ModelExtension(
            String providerType,
            String modelName,
            Boolean enableThinking,
            String reasoningEffort) {
    }

    /** 统一的查找键：{@code PROVIDER_TYPE|modelname}。 */
    static String normalizeKey(ModelProviderType providerType, String modelName) {
        if (providerType == null || modelName == null || modelName.isBlank()) {
            return null;
        }
        return providerType.name().toUpperCase(Locale.ROOT) + "|"
                + modelName.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Map<String, Object>> normalizeModels(List<ModelExtension> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> normalized = new LinkedHashMap<>();
        for (ModelExtension extension : raw) {
            if (extension == null) {
                continue;
            }
            String key = extensionKey(extension);
            if (key == null) {
                log.debug("忽略 ai.provider-extension-params.models 中缺少 providerType/modelName 的条目");
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>();
            if (extension.enableThinking() != null) {
                params.put(ENABLE_THINKING, extension.enableThinking());
            }
            if (extension.reasoningEffort() != null) {
                String effort = extension.reasoningEffort().trim().toLowerCase(Locale.ROOT);
                if (REASONING_EFFORT_VALUES.contains(effort)) {
                    params.put(REASONING_EFFORT, effort);
                } else {
                    log.debug("忽略 provider 扩展参数 reasoning_effort：取值必须是 none/low/medium/high");
                }
            }
            if (!params.isEmpty()) {
                normalized.put(key, Map.copyOf(params));
            }
        }
        return Map.copyOf(normalized);
    }

    private static String extensionKey(ModelExtension extension) {
        String provider = extension.providerType() == null
                ? "" : extension.providerType().trim().toUpperCase(Locale.ROOT);
        String modelName = extension.modelName() == null ? "" : extension.modelName().trim();
        if (provider.isEmpty() || modelName.isEmpty()) {
            return null;
        }
        return provider + "|" + modelName.toLowerCase(Locale.ROOT);
    }
}
