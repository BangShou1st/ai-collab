package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelToolDefinition;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnGateway;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 原生 Tool Calling 执行器。
 * 适用于具有 NATIVE_TOOLS 能力的模型。
 * 使用 ModelTurnGateway 进行结构化工具调用。
 * 不调用 AgentDecisionParser。
 */
@Component
public class NativeToolCallingExecutor {
    private final ModelTurnGateway modelTurn;

    public NativeToolCallingExecutor(ModelTurnGateway modelTurn) {
        this.modelTurn = modelTurn;
    }

    /**
     * 使用原生 Tool Calling 协议调用模型。
     *
     * @param messages 多轮消息历史
     * @param exposed  当前暴露的工具定义
     * @param configurationId 模型配置 ID，确保与能力判断一致
     * @return 模型返回结果
     */
    public ModelTurnResult callModel(
            List<ModelMessage> messages,
            List<AgentToolDefinition> exposed,
            UUID configurationId) {

        List<ModelToolDefinition> toolDefs = exposed.stream()
                .map(def -> new ModelToolDefinition(def.name(), def.description(), def.inputSchema()))
                .toList();

        ModelTurnCommand command = new ModelTurnCommand(
                ModelPurpose.AGENT,
                configurationId,
                messages,
                toolDefs,
                false);

        return modelTurn.turn(command);
    }
}
