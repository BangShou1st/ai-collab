package com.shitulelv.aicollab.infrastructure.ai.turn;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelToolDefinition;

import java.util.List;
import java.util.UUID;

/**
 * 统一模型轮次命令合同。
 * 包含多轮消息历史、工具定义、用途和运行配置。
 * configurationId 确保能力判断和实际调用使用相同的 ModelConfiguration。
 */
public record ModelTurnCommand(
        ModelPurpose purpose,
        UUID configurationId,
        List<ModelMessage> messages,
        List<ModelToolDefinition> tools,
        boolean toolsRequired) {
    public ModelTurnCommand {
        purpose = purpose == null ? ModelPurpose.AGENT : purpose;
        // 先检查 null 元素，再做防御性复制
        if (messages != null) {
            for (ModelMessage message : messages) {
                if (message == null) {
                    throw new IllegalArgumentException("messages 中不能包含 null");
                }
            }
            messages = List.copyOf(messages);
        } else {
            messages = List.of();
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        if (toolsRequired && tools.isEmpty()) {
            throw new IllegalArgumentException("toolsRequired 时 tools 不能为空");
        }
    }

    /**
     * 便捷构造器，不指定 configurationId（向后兼容）。
     */
    public ModelTurnCommand(ModelPurpose purpose, List<ModelMessage> messages,
                            List<ModelToolDefinition> tools, boolean toolsRequired) {
        this(purpose, null, messages, tools, toolsRequired);
    }
}
