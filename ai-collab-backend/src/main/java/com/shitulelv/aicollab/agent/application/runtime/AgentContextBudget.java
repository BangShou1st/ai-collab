package com.shitulelv.aicollab.agent.application.runtime;

import java.util.Map;

/**
 * 单次模型请求输入预算计算。
 *
 * <p>可用输入 = min(模型窗口 - 输出预留 - 安全余量, 运行剩余输入预算, 应用单次上限)。
 * 模型窗口未知时不引入额外收紧（保守应用上限），仅标记估算；
 * 字符→token 换算沿用 chars/3 兼容估算，真实 usage 用于校准而非承诺精确。</p>
 */
public final class AgentContextBudget {

    /** 约束来源：单次请求预算被哪一项绑定，超限时用于区分原因。 */
    public static final String BINDING_RUN_INPUT_BUDGET = "RUN_INPUT_BUDGET";
    public static final String BINDING_PER_REQUEST_CAP = "PER_REQUEST_CAP";
    public static final String BINDING_MODEL_WINDOW = "MODEL_WINDOW";

    /** 模型窗口解析结果。windowTokens 为 null 表示窗口未知。 */
    public record ModelWindow(Integer windowTokens, String basis, boolean estimated) {
        public static ModelWindow unknown() {
            return new ModelWindow(null, "UNKNOWN", true);
        }
    }

    /** 单次请求可用输入预算。 */
    public record Budget(int availableInputTokens, String binding, boolean windowEstimated) {
    }

    private AgentContextBudget() {
    }

    /**
     * 按"提供商类型:模型名"或"模型名"匹配窗口覆盖；未命中返回未知窗口。
     * 运行所用配置经 AgentModelConfigurationStore 固定（快照），此处只做键匹配。
     */
    public static ModelWindow resolveWindow(String providerType, String modelName, Map<String, Integer> overrides) {
        if (overrides == null || overrides.isEmpty() || modelName == null || modelName.isBlank()) {
            return ModelWindow.unknown();
        }
        if (providerType != null && !providerType.isBlank()) {
            Integer typed = overrides.get(providerType + ":" + modelName);
            if (typed != null && typed > 0) return new ModelWindow(typed, providerType + ":" + modelName, false);
        }
        Integer plain = overrides.get(modelName);
        if (plain != null && plain > 0) return new ModelWindow(plain, modelName, false);
        return ModelWindow.unknown();
    }

    public static Budget perRequest(AgentContextProperties properties, ModelWindow window, int remainingRunInputTokens) {
        int available = Math.min(remainingRunInputTokens, properties.perRequestInputCap());
        String binding = remainingRunInputTokens <= properties.perRequestInputCap()
                ? BINDING_RUN_INPUT_BUDGET
                : BINDING_PER_REQUEST_CAP;
        boolean windowEstimated = true;
        if (window != null && window.windowTokens() != null) {
            windowEstimated = false;
            int windowAvailable = window.windowTokens() - properties.outputReserveTokens() - properties.safetyMarginTokens();
            if (windowAvailable < available) {
                available = windowAvailable;
                binding = BINDING_MODEL_WINDOW;
            }
        }
        if (available < 0) available = 0;
        return new Budget(available, binding, windowEstimated);
    }
}
