package com.shitulelv.aicollab.infrastructure.ai.turn;

/**
 * Agent 2.0 的模型调用网关接口。
 * 与旧 ChatModelGateway 并行，不替代旧接口。
 */
public interface ModelTurnGateway {
    ModelTurnResult turn(ModelTurnCommand command);
}
