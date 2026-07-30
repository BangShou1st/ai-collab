package com.shitulelv.aicollab.planning.infrastructure.ai;

import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelProperties;
import com.shitulelv.aicollab.infrastructure.ai.OpenAiCompatibleChatModelGateway;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PlanningFallbackConfiguration {

    @Bean("planningFallbackChatModelGateway")
    ChatModelGateway planningFallbackChatModelGateway(
            PlanningModelProperties planning,
            ChatModelProperties chat) {
        return new OpenAiCompatibleChatModelGateway(
                new ChatModelProperties(
                        planning.enabled() != null ? planning.enabled() : chat.enabled(),
                        text(planning.provider(), chat.provider()),
                        text(planning.baseUrl(), chat.baseUrl()),
                        text(planning.path(), chat.path()),
                        text(planning.apiKey(), chat.apiKey()),
                        text(planning.model(), chat.model()),
                        duration(planning.connectTimeout(), chat.connectTimeout()),
                        duration(planning.readTimeout(), chat.readTimeout()),
                        planning.temperature() != null ? planning.temperature() : chat.temperature(),
                        planning.maxOutputTokens() != null && planning.maxOutputTokens() > 0
                                ? planning.maxOutputTokens()
                                : chat.maxOutputTokens(),
                        true),
                false);
    }

    private static String text(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static Duration duration(Duration primary, Duration fallback) {
        return primary == null ? fallback : primary;
    }
}
