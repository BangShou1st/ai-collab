package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import org.springframework.stereotype.Component;

@Component
public class TaskPlanModelClient {
    private final ChatModelGateway gateway;
    public TaskPlanModelClient(ChatModelGateway gateway) { this.gateway = gateway; }
    public String generate(String system, String user) {
        return gateway.complete(new ChatCompletionCommand(system, user)).content();
    }
}
