package com.shitulelv.aicollab.agent.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentRecoveryJob;
import com.shitulelv.aicollab.agent.application.AgentWorker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;

/**
 * Agent 链路 MQ 装配（M2 迁移 §3.1 第 3 行）。
 *
 * <p>总闸 {@code agent.mq.enabled}（默认取全局 {@code MQ_ENABLED}，可经
 * {@code AGENT_MQ_ENABLED} 单独覆盖）：</p>
 * <ul>
 *   <li>true —— 装配 run 入队通知发送器（生产侧）与消费者（消费侧触发 claim），
 *       run 入队通知双轨生效；</li>
 *   <li>false（默认）—— 两个 MQ Bean 均不创建，{@code AgentRunQueuedNotifier} 注入点
 *       为空，各落点静默跳过通知，链路完全回落轮询兜底路径（AgentRuntimeJob tick +
 *       P1 进程内事件唤醒）。</li>
 * </ul>
 * 消费者与 {@code AgentImmediateWakeListener} 同受 {@code agent.runtime-allowed} 开关
 * 约束：运行时被管理端禁用时 MQ 触发路径一并停用。
 * 测试 profile 在 application.properties 中显式关闭总闸，避免单元/集成测试依赖 broker。
 */
@Configuration
@EnableConfigurationProperties(AgentMqProperties.class)
public class AgentMqConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "agent.mq.enabled", havingValue = "true")
    public AgentRunQueuedMqNotifier agentRunQueuedMqNotifier(AgentMqProperties properties,
                                                             AgentMqMetrics metrics,
                                                             ObjectMapper objectMapper) {
        return new AgentRunQueuedMqNotifier(properties, metrics, objectMapper);
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(name = "agent.mq.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "agent.runtime-allowed", havingValue = "true", matchIfMissing = true)
    public AgentRunQueuedMqConsumer agentRunQueuedMqConsumer(AgentMqProperties properties,
                                                             AgentMqMetrics metrics,
                                                             ObjectMapper objectMapper,
                                                             AgentRecoveryJob recovery,
                                                             AgentWorker worker,
                                                             @Qualifier("agentRunTaskExecutor")
                                                             TaskExecutor agentRunTaskExecutor) {
        return new AgentRunQueuedMqConsumer(properties, metrics, objectMapper, recovery, worker,
                agentRunTaskExecutor);
    }
}
