package com.shitulelv.aicollab.infrastructure.ai.model;

/**
 * Zen 模型的原生协议类别。
 *
 * <p>AI Collab 的 Zen 执行路径只适配 OpenAI Chat Completions。Responses / Anthropic Messages
 * 目前没有完整适配，必须在配置或连接测试阶段明确拒绝，绝不能改发到 {@code /chat/completions}。</p>
 */
public enum ZenModelProtocol {
    CHAT("chat/completions"),
    RESPONSES("responses"),
    ANTHROPIC("messages"),
    UNKNOWN("unknown");

    private final String wireName;

    ZenModelProtocol(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * 由可信目录中的 npm 包名推断原生协议。无法判断时返回 {@code null}——
     * 不做猜测，由调用方决定保守回退策略。
     */
    static ZenModelProtocol fromNpmPackage(String npmPackage) {
        if (npmPackage == null || npmPackage.isBlank()) {
            return null;
        }
        String value = npmPackage.strip();
        if (value.contains("anthropic")) {
            return ANTHROPIC;
        }
        if ("@ai-sdk/openai".equals(value)) {
            return RESPONSES;
        }
        if (value.contains("openai-compatible")) {
            return CHAT;
        }
        return null;
    }
}
