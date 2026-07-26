package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelProperties;
import com.shitulelv.aicollab.infrastructure.ai.OpenAiCompatibleChatModelGateway;
import com.shitulelv.aicollab.planning.infrastructure.ai.PlanningModelProperties;
import org.springframework.stereotype.Component;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;

@Component
public class TaskPlanModelClient {
    private final ChatModelGateway gateway;
    public TaskPlanModelClient(PlanningModelProperties properties) {
        this.gateway = new OpenAiCompatibleChatModelGateway(new ChatModelProperties(
                properties.enabled(), properties.provider(), properties.baseUrl(), properties.path(),
                properties.apiKey(), properties.model(), properties.connectTimeout(), properties.readTimeout(),
                properties.temperature(), properties.maxOutputTokens()));
    }
    public String generate(String system, String user) {
        try {
            return gateway.complete(new ChatCompletionCommand(system, user)).content();
        } catch (BusinessException failure) {
            ErrorCode mapped = switch (failure.getErrorCode()) {
                case AI_PROVIDER_UNAVAILABLE -> ErrorCode.PLANNING_MODEL_UNAVAILABLE;
                case AI_MODEL_TIMEOUT -> ErrorCode.PLANNING_MODEL_TIMEOUT;
                case AI_PROVIDER_QUOTA_EXCEEDED -> ErrorCode.PLANNING_MODEL_RATE_LIMITED;
                case AI_PROVIDER_INVALID_RESPONSE -> ErrorCode.PLANNING_MODEL_INVALID_OUTPUT;
                default -> failure.getErrorCode();
            };
            throw new BusinessException(mapped);
        }
    }
}
