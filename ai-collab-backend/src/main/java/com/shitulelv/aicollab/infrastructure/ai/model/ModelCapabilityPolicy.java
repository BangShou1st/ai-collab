package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;

public final class ModelCapabilityPolicy {
    private ModelCapabilityPolicy() {
    }

    public static void require(
            ModelConfiguration configuration, ChatCompletionCommand command, boolean streaming) {
        require(configuration, ModelCapability.CHAT);
        if (streaming) require(configuration, ModelCapability.STREAMING);
        if (command.outputFormat() == ChatCompletionCommand.OutputFormat.JSON_OBJECT) {
            require(configuration, ModelCapability.STRUCTURED_OUTPUT);
        }
        if (!command.tools().isEmpty()) require(configuration, ModelCapability.NATIVE_TOOLS);
    }

    private static void require(ModelConfiguration configuration, ModelCapability capability) {
        if (!configuration.capabilities().contains(capability)) {
            throw new BusinessException(
                    ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "当前模型配置不支持 " + capability.name());
        }
    }
}
