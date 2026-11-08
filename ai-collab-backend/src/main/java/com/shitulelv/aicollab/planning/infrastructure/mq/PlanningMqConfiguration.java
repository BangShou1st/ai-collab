package com.shitulelv.aicollab.planning.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.application.TaskPlanGenerationOrchestrator;
import com.shitulelv.aicollab.planning.application.service.PlanGenerationMessenger;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 规划链路 MQ 装配（M2 迁移 §3.1 第 2 行）。
 *
 * <p>总闸 {@code planning.mq.enabled}（默认取全局 {@code MQ_ENABLED}，可经
 * {@code PLANNING_MQ_ENABLED} 单独覆盖）：</p>
 * <ul>
 *   <li>true —— 装配 RocketMQ dispatch 发送门面与消费者，编排器 dispatch 走
 *       事务提交后发消息 → 消费触发执行入口；</li>
 *   <li>false（默认）—— 两个 MQ Bean 均不创建，仅注册
 *       {@link DisabledPlanGenerationMessenger}（enabled()=false），编排器
 *       dispatch 完全回落进程内 FutureTask 路径。</li>
 * </ul>
 * 测试 profile 在 application.properties 中显式关闭总闸，避免单元/集成测试依赖 broker。
 */
@Configuration
@EnableConfigurationProperties(PlanningMqProperties.class)
public class PlanningMqConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "planning.mq.enabled", havingValue = "true")
    public PlanGenerationMessenger rocketMqPlanGenerationMessenger(PlanningMqProperties properties,
                                                                   PlanningMqMetrics metrics,
                                                                   ObjectMapper objectMapper) {
        return new RocketMqPlanGenerationMessenger(properties, metrics, objectMapper);
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(name = "planning.mq.enabled", havingValue = "true")
    public PlanGenerationMqConsumer planGenerationMqConsumer(PlanningMqProperties properties,
                                                             PlanningMqMetrics metrics,
                                                             ObjectMapper objectMapper,
                                                             TaskPlanRepository repository,
                                                             TaskPlanGenerationOrchestrator orchestrator) {
        return new PlanGenerationMqConsumer(properties, metrics, objectMapper, repository, orchestrator);
    }

    /** 总闸关闭时的回落实现：enabled()=false，发送方法按约定不应被调用。 */
    @Bean
    @ConditionalOnMissingBean(PlanGenerationMessenger.class)
    public PlanGenerationMessenger disabledPlanGenerationMessenger() {
        return new DisabledPlanGenerationMessenger();
    }
}
