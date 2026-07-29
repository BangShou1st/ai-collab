package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentRuntimeConfiguration {
    @Bean
    AgentDecisionParser agentDecisionParser(ObjectMapper json) {
        return new AgentDecisionParser(json);
    }

    @Bean
    AgentPromptFactory agentPromptFactory() {
        return new AgentPromptFactory();
    }
}
