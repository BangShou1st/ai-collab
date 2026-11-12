package com.shitulelv.aicollab.infrastructure.ai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gemini thinking 的受控覆盖（默认关闭，独立于 D1 的 OpenAI 兼容扩展参数）。
 *
 * <p>用途：{@code gemini-3.5-flash-lite} 等模型在"声明 THINKING、不发 thinkingConfig"时会
 * 走默认思考，3s 门禁会被思考延迟污染；而 {@code thinkingBudget=0} 在该模型上会 400。
 * 本配置允许运维显式指定 {@code budget}（0–32768）或 {@code level}
 * （{@code minimal/low/medium/high}），用于延迟门禁的公平测量。</p>
 *
 * <p>优先级：</p>
 * <ol>
 *   <li>per-model（{@code models} 的 key 为 modelName，去首尾空白后转小写）覆盖全局默认；</li>
 *   <li>同一层内 {@code budget} 与 {@code level} 同时存在时 {@code budget} 优先，并 warn；</li>
 *   <li>都未配置或全部非法时返回 {@code null}，适配器保持现有语义
 *       （声明 THINKING → 不发；未声明 → {@code thinkingBudget=0}）。</li>
 * </ol>
 *
 * <p>非法值策略：绑定后的构造阶段直接忽略并 warn（不阻止启动），与"配置错误不应阻止启动"
 * 的既有约定一致。{@code enabled=false} 时任何值都不生效。</p>
 *
 * <p>环境变量（避免 map key 含 {@code /}、{@code .} 的绑定问题）：</p>
 *
 * <pre>{@code
 * AI_GEMINI_THINKING_ENABLED=true
 * AI_GEMINI_THINKING_BUDGET=1
 * AI_GEMINI_THINKING_LEVEL=minimal
 * }</pre>
 */
@ConfigurationProperties("ai.gemini-thinking")
public record GeminiThinkingProperties(
        @DefaultValue("false") boolean enabled,
        Integer budget,
        String level,
        Map<String, ModelOverride> models) {

    private static final Logger log = LoggerFactory.getLogger(GeminiThinkingProperties.class);

    static final int MIN_BUDGET = 0;
    static final int MAX_BUDGET = 32768;
    static final Set<String> LEVELS = Set.of("minimal", "low", "medium", "high");

    /** per-model 覆盖项；构造时已归一，最多只有一个字段非空。 */
    public record ModelOverride(Integer budget, String level) {
    }

    /** 解析结果；budget 与 level 最多一个非空。 */
    public record ThinkingSetting(Integer budget, String level) {
    }

    public GeminiThinkingProperties {
        budget = validBudget(budget, "全局");
        level = validLevel(level, "全局");
        if (budget != null && level != null) {
            log.warn("ai.gemini-thinking 同时配置 budget 与 level，budget 优先，忽略 level");
            level = null;
        }
        models = normalizeModels(models);
    }

    /** 未启用时的安全默认值：供不经过 Spring 的构造点使用。 */
    public static GeminiThinkingProperties disabled() {
        return new GeminiThinkingProperties(false, null, null, Map.of());
    }

    /**
     * 解析该模型最终生效的 thinking 设置；{@code null} 表示不覆盖、沿用适配器现有语义。
     */
    public ThinkingSetting resolve(String modelName) {
        if (!enabled) {
            return null;
        }
        String key = normalizeModelName(modelName);
        ModelOverride override = key == null ? null : models.get(key);
        if (override != null) {
            return override.budget() != null
                    ? new ThinkingSetting(override.budget(), null)
                    : new ThinkingSetting(null, override.level());
        }
        if (budget != null) {
            return new ThinkingSetting(budget, null);
        }
        if (level != null) {
            return new ThinkingSetting(null, level);
        }
        return null;
    }

    private static Map<String, ModelOverride> normalizeModels(Map<String, ModelOverride> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, ModelOverride> normalized = new LinkedHashMap<>();
        raw.forEach((rawKey, rawOverride) -> {
            String key = normalizeModelName(rawKey);
            if (key == null || rawOverride == null) {
                log.warn("ai.gemini-thinking.models 存在空 modelName 条目，已忽略");
                return;
            }
            Integer overrideBudget = validBudget(rawOverride.budget(), "模型 " + key);
            String overrideLevel = validLevel(rawOverride.level(), "模型 " + key);
            if (overrideBudget != null && overrideLevel != null) {
                log.warn("ai.gemini-thinking.models[{}] 同时配置 budget 与 level，budget 优先，忽略 level", key);
                overrideLevel = null;
            }
            if (overrideBudget != null) {
                normalized.put(key, new ModelOverride(overrideBudget, null));
            } else if (overrideLevel != null) {
                normalized.put(key, new ModelOverride(null, overrideLevel));
            } else {
                log.warn("ai.gemini-thinking.models[{}] 没有有效的 budget/level，回退全局默认", key);
            }
        });
        return Map.copyOf(normalized);
    }

    private static String normalizeModelName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static Integer validBudget(Integer value, String scope) {
        if (value == null) {
            return null;
        }
        if (value < MIN_BUDGET || value > MAX_BUDGET) {
            log.warn("ai.gemini-thinking {} budget 超出 {}..{}，已忽略", scope, MIN_BUDGET, MAX_BUDGET);
            return null;
        }
        return value;
    }

    private static String validLevel(String value, String scope) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!LEVELS.contains(normalized)) {
            log.warn("ai.gemini-thinking {} level 不在 minimal/low/medium/high 内，已忽略", scope);
            return null;
        }
        return normalized;
    }
}
