package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentCancellationService;
import com.shitulelv.aicollab.agent.application.runtime.AgentLoopGuard;
import com.shitulelv.aicollab.agent.application.runtime.AgentModelMessageComposer;
import com.shitulelv.aicollab.agent.application.runtime.AgentRuntimeCoordinator;
import com.shitulelv.aicollab.agent.application.runtime.AgentToolCallExecutor;
import com.shitulelv.aicollab.agent.application.runtime.AgentToolScheduler;
import com.shitulelv.aicollab.agent.application.runtime.RoutingAgentModelExecutor;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.AgentDecisionParser;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 生产装配入口：消息组装器与工具执行器在此注册为容器单例，
 * 与容器中的摘要器（{@code AgentContextSummarizer}，@Component）一起
 * 由 {@link AgentRuntimeCoordinator} 显式接收，保证生产只有一条装配路径。
 */
@Configuration
@EnableConfigurationProperties(com.shitulelv.aicollab.agent.application.runtime.AgentContextProperties.class)
public class AgentRuntimeConfiguration {
    @Bean
    AgentDecisionParser agentDecisionParser(ObjectMapper json) {
        return new AgentDecisionParser(json);
    }

    @Bean
    AgentModelMessageComposer agentModelMessageComposer(AgentRepository repository, AgentMemoryService memories, ObjectMapper json) {
        return new AgentModelMessageComposer(repository, memories, json);
    }

    @Bean
    AgentToolCallExecutor agentToolCallExecutor(
            AgentRepository repository,
            AgentToolRegistry tools,
            AgentCancellationService cancellation,
            AgentLoopGuard loopGuard,
            AgentApprovalService approvals,
            RoutingAgentModelExecutor modelExecutor,
            AgentToolResultSanitizer sanitizer,
            ObjectMapper json,
            AgentEventService events,
            AgentToolScheduler scheduler) {
        return new AgentToolCallExecutor(repository, tools, cancellation, loopGuard,
                approvals, modelExecutor, sanitizer, json, events, scheduler);
    }
}
