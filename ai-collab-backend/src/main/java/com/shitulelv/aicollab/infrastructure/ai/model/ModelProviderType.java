package com.shitulelv.aicollab.infrastructure.ai.model;

public enum ModelProviderType {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GEMINI,
    /** 管理端共享能力 Provider（当前用于本地 rerank 服务），不参与个人 Chat 适配器路由。 */
    LOCAL_CUSTOM;

    public boolean chatCompatible() {
        return this != LOCAL_CUSTOM;
    }
}
