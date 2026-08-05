package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;

/**
 * Provider 原生 Tool Calling 适配器接口。
 * 与旧 ModelProviderAdapter 并行，不替代旧接口。
 */
public interface ModelTurnProviderAdapter {
    ModelProviderType providerType();

    ModelTurnResult turn(
            ModelConfiguration configuration,
            String apiKey,
            ModelTurnCommand command);
}
