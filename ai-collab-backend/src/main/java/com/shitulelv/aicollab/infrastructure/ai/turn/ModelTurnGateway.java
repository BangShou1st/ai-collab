package com.shitulelv.aicollab.infrastructure.ai.turn;

import com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata;

/**
 * Agent 2.0 的模型调用网关接口。
 * 与旧 ChatModelGateway 并行，不替代旧接口。
 */
public interface ModelTurnGateway {
    ModelTurnResult turn(ModelTurnCommand command);
    default ModelTurnResult turn(ModelTurnCommand command, AiRequestMetadata metadata) { return turn(command); }
}
